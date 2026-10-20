package com.five9th.smagent.ai.adapter

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.five9th.smagent.domain.AiProviderType
import com.five9th.smagent.domain.GenerateEffort
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Anthropic Messages API — or any endpoint implementing it, via the provider's
 * baseUrl (origin only: the SDK appends /v1/...). One non-streaming call per
 * generate; the prompt/parse contract lives in [GenerationContract].
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

    override fun listModels(apiKey: String, baseUrl: String?): List<String> =
        call(clientFactory(apiKey, baseUrl)) { it.models().list().items() }
            .mapNotNull { it.id() }
            .sorted()

    override fun verify(apiKey: String, baseUrl: String?): String =
        "Key valid — ${listModels(apiKey, baseUrl).size} models available" +
            (baseUrl?.let { " via $it" } ?: "")

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
            .system(GenerationContract.systemPrompt(command))
            .addUserMessage(GenerationContract.userMessage(command))
            .outputConfig(OutputConfig.builder().effort(effort.toSdk()).build())
            .build()
        val message = call(clientFactory(apiKey, baseUrl)) { it.messages().create(params) }
        val raw = message.content()
            .mapNotNull { it.text().orElse(null) }
            .joinToString("\n") { it.text() }
        return GenerationContract.assembleResult(
            parsed = GenerationContract.parseGenerated(raw, command.platforms),
            inputTokens = message.usage().inputTokens(),
            outputTokens = message.usage().outputTokens(),
        )
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

    companion object {
        /** GENERIC + a few platform variants fit comfortably; truncation mid-JSON is the one unrecoverable parse failure. */
        private const val MAX_TOKENS = 4096L

        /** Well under the SDK's 10-minute default; fits a synchronous SPA call. */
        private const val TIMEOUT_SECONDS = 90L
    }
}
