package com.five9th.smagent.ai.adapter

import com.five9th.smagent.domain.GeneratePlatform
import com.five9th.smagent.domain.Platform
import com.five9th.smagent.domain.PostContent
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The provider-agnostic response contract shared by all [AiProviderAdapter]s:
 * the system prompt (platform rules + the exact JSON shape + constraints),
 * the user message (optional current draft + the request), and the lenient
 * parse of the model's answer. Adapters keep only SDK plumbing — model/param
 * mapping and error wrapping.
 */
internal object GenerationContract {

    private val mapper = JsonMapper.builder().build()

    /**
     * Built-in per-platform style fragments for the system prompt (the
     * editorial half of each rule line — the structural half, shapes and hard
     * requirements, is templated and never overridable). GENERIC starts empty:
     * its line is almost entirely structural. Owner overrides live in
     * GenerationSettings and are merged over these (see [rulesWith]).
     */
    val DEFAULT_RULES: Map<GeneratePlatform, String> = linkedMapOf(
        GeneratePlatform.GENERIC to "",
        GeneratePlatform.FACEBOOK to "conversational, may run a bit longer.",
        GeneratePlatform.REDDIT to "community tone, honest, no corporate speak.",
        GeneratePlatform.X to "at most 280 characters total, punchy.",
        GeneratePlatform.LINKEDIN to "professional and clear, no hashtag spam.",
    )

    /** Defaults with the (already sanitized) overrides applied; blank overrides fall back. */
    fun rulesWith(overrides: Map<GeneratePlatform, String>?): Map<GeneratePlatform, String> =
        DEFAULT_RULES + (overrides ?: emptyMap()).filterValues { it.isNotBlank() }

    /**
     * The whole response contract lives here: requested keys, entry shapes
     * (title only where it can be used — see [genericNeedsTitle]), per-platform
     * rules (structural template + owner style), the edit contract when a draft
     * is provided, and the global constraints.
     */
    fun systemPrompt(command: GenerateCommand): String = buildString {
        val rules = rulesWith(command.platformRules)
        appendLine("You draft social media posts for a single owner publishing to their own accounts.")
        appendLine()
        appendLine("Respond with ONLY a valid JSON object — no markdown fences, no commentary, no keys beyond the ones requested.")
        appendLine("Requested keys: ${command.platforms.joinToString(", ") { it.name }}.")
        appendLine()
        appendLine("Entry shapes and rules:")
        if (GeneratePlatform.GENERIC in command.platforms) {
            val style = styleSentence(rules.getValue(GeneratePlatform.GENERIC))
            if (genericNeedsTitle(command.platforms)) {
                appendLine("- \"GENERIC\": {\"title\": <string>, \"text\": <string>} — the master version other platforms fall back to.$style The title is REQUIRED: this text may later be published to Reddit, which displays one.")
            } else {
                appendLine("- \"GENERIC\": {\"text\": <string>} — the master version other platforms fall back to.$style No title: the requested REDDIT entry carries its own.")
            }
        }
        command.platforms.forEach { platform ->
            val style = styleSentence(rules.getValue(platform))
            when (platform) {
                GeneratePlatform.REDDIT ->
                    appendLine("- \"REDDIT\": {\"title\": <string>, \"text\": <string>} —$style The title is REQUIRED (Reddit posts need one) and at most 300 characters.")
                GeneratePlatform.X ->
                    appendLine("- \"X\": {\"text\": <string>} —$style No title.")
                GeneratePlatform.FACEBOOK ->
                    appendLine("- \"FACEBOOK\": {\"text\": <string>} —$style No title.")
                GeneratePlatform.LINKEDIN ->
                    appendLine("- \"LINKEDIN\": {\"text\": <string>} —$style No title.")
                GeneratePlatform.GENERIC -> {} // described above
            }
        }
        appendLine()
        appendLine("Titles: only Reddit displays one — every other platform shows the text alone. The text of any entry except REDDIT must stand on its own, never relying on a title to carry meaning.")
        draftEntries(command)?.let { draft ->
            appendLine()
            appendLine("A current draft is provided with the request. Treat the request as an edit of that draft and return the complete updated entries for the requested keys — full replacements, never diffs.")
            if (draft.values.any { !it.link.isNullOrBlank() }) {
                appendLine("Draft entries may carry a \"link\" — context for what the post is about. Do not repeat that URL in any text; publishing appends it automatically.")
            }
        }
        command.constraints?.let { c ->
            val rules = buildList {
                c.maxLength?.let { add("Keep every text under $it characters.") }
                c.tone?.takeIf { it.isNotBlank() }?.let { add("Tone: $it.") }
            }
            if (rules.isNotEmpty()) {
                appendLine()
                appendLine("Constraints:")
                rules.forEach { appendLine("- $it") }
            }
        }
    }

    /**
     * The user turn: the sanitized current draft (when provided) as context,
     * then the request. Volatile per-request data stays out of the system
     * prompt on purpose.
     */
    fun userMessage(command: GenerateCommand): String {
        val draft = draftEntries(command) ?: return command.prompt
        return buildString {
            appendLine("Current draft:")
            appendLine(renderDraft(draft))
            appendLine()
            append("Request: ${command.prompt}")
        }
    }

    /**
     * Whether the GENERIC entry carries a title: only when no REDDIT variant
     * is requested (that variant brings its own title, and without it the
     * generic text is the fallback for a possible later Reddit publish).
     */
    private fun genericNeedsTitle(platforms: Set<GeneratePlatform>): Boolean =
        GeneratePlatform.GENERIC in platforms && GeneratePlatform.REDDIT !in platforms

    /** Draft entries with actual text; blank-text entries are context noise and dropped. */
    private fun draftEntries(command: GenerateCommand): Map<GeneratePlatform, PostContent>? =
        command.editorState
            ?.takeIf { it.isNotEmpty() }
            ?.filterValues { it.text.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }

    /** A style fragment as a lead-in sentence: " dry humor." — empty stays empty. */
    private fun styleSentence(style: String): String {
        val s = style.trim()
        if (s.isEmpty()) return ""
        return " " + if (s.last() in ".!?;:") s else "$s."
    }

    private fun renderDraft(draft: Map<GeneratePlatform, PostContent>): String =
        mapper.writeValueAsString(
            draft.mapKeys { it.key.name }.mapValues { (_, c) ->
                buildMap<String, String> {
                    c.title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                    put("text", c.text)
                    c.link?.takeIf { it.isNotBlank() }?.let { put("link", it) }
                }
            }
        )

    /**
     * Lenient parse of the model's JSON: markdown fences are stripped, entries
     * for unrequested platforms are ignored, and an entry with missing/blank
     * text is dropped (a title alone is meaningless — publish-time validation
     * is the backstop for a title-less entry). Anything that isn't a JSON
     * object fails the call.
     */
    fun parseGenerated(
        raw: String,
        requested: Set<GeneratePlatform>,
    ): Map<GeneratePlatform, GeneratedContent> {
        val root = try {
            mapper.readTree(stripFences(raw))
        } catch (e: Exception) {
            throw AiProviderException("AI response was not valid JSON: ${e.message}")
        }
        if (root.isNull || root.isMissingNode || !root.isObject) {
            throw AiProviderException("AI response was not a JSON object")
        }
        val needsGenericTitle = genericNeedsTitle(requested)
        val entries = linkedMapOf<GeneratePlatform, GeneratedContent>()
        for (platform in requested) {
            val entry = root.path(platform.name)
            if (entry.isMissingNode || entry.isNull) continue // tolerated: entry simply absent
            val text = entry.path("text").nonBlankOrNull() ?: continue
            // title is part of the contract only where it can be used
            val title = when (platform) {
                GeneratePlatform.REDDIT -> entry.path("title").nonBlankOrNull()
                GeneratePlatform.GENERIC -> if (needsGenericTitle) entry.path("title").nonBlankOrNull() else null
                else -> null
            }
            entries[platform] = GeneratedContent(title, text)
        }
        return entries
    }

    /** Handles the common ``` and ```json wrappers some models add anyway. */
    private fun stripFences(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```").removePrefix("json").trim()
            if (s.endsWith("```")) s = s.removeSuffix("```").trim()
        }
        return s
    }

    /** Map parsed entries (+ the call's usage) into the transport result. */
    fun assembleResult(
        entries: Map<GeneratePlatform, GeneratedContent>,
        inputTokens: Long,
        outputTokens: Long,
    ): GenerateResult = GenerateResult(
        content = entries[GeneratePlatform.GENERIC],
        overrides = entries.filterKeys { it != GeneratePlatform.GENERIC }.mapKeys { it.key.toPlatform() },
        inputTokens = inputTokens,
        outputTokens = outputTokens,
    )

    private fun GeneratePlatform.toPlatform(): Platform = when (this) {
        GeneratePlatform.GENERIC -> error("GENERIC maps to the post's own content, not a Platform")
        GeneratePlatform.FACEBOOK -> Platform.FACEBOOK
        GeneratePlatform.REDDIT -> Platform.REDDIT
        GeneratePlatform.X -> Platform.X
        GeneratePlatform.LINKEDIN -> Platform.LINKEDIN
    }

    private fun JsonNode.asStringOrNull(): String? =
        if (isNull || isMissingNode) null else asString()

    private fun JsonNode.nonBlankOrNull(): String? =
        asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
