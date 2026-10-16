package com.five9th.smagent.ai.adapter

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.five9th.smagent.domain.AiProviderType
import com.five9th.smagent.domain.GenerateEffort
import com.five9th.smagent.domain.GeneratePlatform
import com.five9th.smagent.domain.Platform
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * Anthropic Messages API — or any endpoint implementing it, via the provider's
 * baseUrl. One non-streaming call per generate: the system prompt fixes a JSON
 * response contract (keys = requested variants, per-platform rules), and the
 * output is parsed leniently (see [parseGenerated]).
 */
@Component
class AnthropicAdapter : AiProviderAdapter {

    override val type = AiProviderType.ANTHROPIC

    /** (apiKey, baseUrl) -> SDK client; replaceable in tests to stub the API. */
    internal var clientFactory: (apiKey: String, baseUrl: String?) -> AnthropicClient = { apiKey, baseUrl ->
        val builder = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        baseUrl?.takeIf { it.isNotBlank() }?.let { builder.baseUrl(it) }
        builder.build()
    }

    private val mapper = JsonMapper.builder().build()

    override fun verify(apiKey: String, baseUrl: String?): String {
        val models = call(clientFactory(apiKey, baseUrl)) { it.models().list().items() }
        return "Key valid — ${models.size} models available" + (baseUrl?.let { " via $it" } ?: "")
    }

    override fun generate(
        apiKey: String,
        baseUrl: String?,
        model: String,
        effort: GenerateEffort,
        command: GenerateCommand,
    ): GenerateResult {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .system(systemPrompt(command))
            .addUserMessage(command.prompt)
            .outputConfig(OutputConfig.builder().effort(effort.toSdk()).build())
            .build()
        val message = call(clientFactory(apiKey, baseUrl)) { it.messages().create(params) }
        val raw = message.content()
            .mapNotNull { it.text().orElse(null) }
            .joinToString("\n") { it.text() }
        val entries = parseGenerated(raw, command.platforms)
        return GenerateResult(
            content = entries[GeneratePlatform.GENERIC],
            overrides = entries.filterKeys { it != GeneratePlatform.GENERIC }
                .mapKeys { it.key.toPlatform() },
            inputTokens = message.usage().inputTokens(),
            outputTokens = message.usage().outputTokens(),
        )
    }

    /**
     * The whole response contract lives here: requested keys, entry shapes
     * (title only on GENERIC and REDDIT — Reddit requires one at publish
     * time), per-platform rules, and the global constraints.
     */
    internal fun systemPrompt(command: GenerateCommand): String = buildString {
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
    internal fun parseGenerated(
        raw: String,
        requested: Set<GeneratePlatform>,
    ): Map<GeneratePlatform, GeneratedContent> {
        val root = try {
            mapper.readTree(stripFences(raw))
        } catch (e: Exception) {
            throw AiProviderException("Anthropic response was not valid JSON: ${e.message}")
        }
        if (root.isNull || root.isMissingNode || !root.isObject) {
            throw AiProviderException("Anthropic response was not a JSON object")
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

    private fun <T> call(client: AnthropicClient, block: (AnthropicClient) -> T): T = try {
        block(client)
    } catch (e: AnthropicServiceException) {
        throw AiProviderException("Anthropic API error (${e.statusCode()}): ${e.message ?: "request rejected"}", e)
    } catch (e: AnthropicException) {
        throw AiProviderException("Anthropic API call failed: ${e.message}", e)
    } catch (e: Exception) {
        throw AiProviderException("Anthropic API call failed: ${e.message}", e)
    }

    private fun GenerateEffort.toSdk(): OutputConfig.Effort = when (this) {
        GenerateEffort.LOW -> OutputConfig.Effort.LOW
        GenerateEffort.MEDIUM -> OutputConfig.Effort.MEDIUM
        GenerateEffort.HIGH -> OutputConfig.Effort.HIGH
        GenerateEffort.XHIGH -> OutputConfig.Effort.XHIGH
        GenerateEffort.MAX -> OutputConfig.Effort.MAX
    }

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

    companion object {
        /** GENERIC + a few platform variants fit comfortably; truncation mid-JSON is the one unrecoverable parse failure. */
        private const val MAX_TOKENS = 4096L

        /** Well under the SDK's 10-minute default; fits a synchronous SPA call. */
        private const val TIMEOUT_SECONDS = 90L
    }
}
