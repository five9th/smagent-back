package com.five9th.smagent.ai.adapter

import com.five9th.smagent.domain.GeneratePlatform
import com.five9th.smagent.domain.Platform
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The provider-agnostic response contract shared by all [AiProviderAdapter]s:
 * the system prompt (platform rules + the exact JSON shape + constraints) and
 * the lenient parse of the model's answer. Adapters keep only SDK plumbing —
 * model/param mapping and error wrapping.
 */
internal object GenerationContract {

    private val mapper = JsonMapper.builder().build()

    /**
     * The whole response contract lives here: requested keys, entry shapes
     * (title only on GENERIC and REDDIT — Reddit requires one at publish
     * time), per-platform rules, and the global constraints.
     */
    fun systemPrompt(command: GenerateCommand): String = buildString {
        appendLine("You draft social media posts for a single owner publishing to their own accounts.")
        appendLine()
        appendLine("Respond with ONLY a valid JSON object — no markdown fences, no commentary, no keys beyond the ones requested.")
        appendLine("Requested keys: ${command.platforms.joinToString(", ") { it.name }}.")
        appendLine()
        appendLine("Entry shapes and rules:")
        if (GeneratePlatform.GENERIC in command.platforms) {
            appendLine("- \"GENERIC\": {\"title\": <string or null>, \"text\": <string>} — the master version other platforms fall back to. Set \"title\" only when it meaningfully helps (it becomes the post title on Reddit); otherwise null.")
        }
        command.platforms.forEach { platform ->
            when (platform) {
                GeneratePlatform.REDDIT ->
                    appendLine("- \"REDDIT\": {\"title\": <string>, \"text\": <string>} — community tone, honest, no corporate speak. The title is REQUIRED (Reddit posts need one) and at most 300 characters.")
                GeneratePlatform.X ->
                    appendLine("- \"X\": {\"text\": <string>} — at most 280 characters total, punchy. No title.")
                GeneratePlatform.FACEBOOK ->
                    appendLine("- \"FACEBOOK\": {\"text\": <string>} — conversational, may run a bit longer. No title.")
                GeneratePlatform.LINKEDIN ->
                    appendLine("- \"LINKEDIN\": {\"text\": <string>} — professional and clear, no hashtag spam. No title.")
                GeneratePlatform.GENERIC -> {} // described above
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
     * Lenient parse of the model's JSON: markdown fences are stripped, entries
     * for unrequested platforms are ignored, and an entry with missing/blank
     * text is dropped (a title alone is meaningless — publish-time validation
     * is the backstop for a title-less REDDIT entry). Anything that isn't a
     * JSON object fails the call.
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
        val entries = linkedMapOf<GeneratePlatform, GeneratedContent>()
        for (platform in requested) {
            val entry = root.path(platform.name)
            if (entry.isMissingNode || entry.isNull) continue // tolerated: entry simply absent
            val text = entry.path("text").nonBlankOrNull() ?: continue
            // title is part of the contract only where it can be used
            val title = if (platform == GeneratePlatform.GENERIC || platform == GeneratePlatform.REDDIT) {
                entry.path("title").nonBlankOrNull()
            } else {
                null
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
