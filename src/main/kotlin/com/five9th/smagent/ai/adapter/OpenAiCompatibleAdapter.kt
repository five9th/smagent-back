package com.five9th.smagent.ai.adapter

import com.five9th.smagent.domain.AiProviderType
import com.five9th.smagent.domain.GenerateEffort
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.errors.OpenAIException
import com.openai.errors.OpenAIServiceException
import com.openai.models.chat.completions.ChatCompletionCreateParams
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Chat-completions against OpenAI or any compatible endpoint (OpenRouter,
 * Groq, Mistral, DeepSeek, local Ollama…) via the provider's baseUrl. Note the
 * convention differs from ANTHROPIC: baseUrl is the FULL API root including
 * the version path (e.g. `https://openrouter.ai/api/v1`,
 * `http://localhost:11434/v1`); absent -> `https://api.openai.com/v1`.
 */
@Component
class OpenAiCompatibleAdapter : AiProviderAdapter {

    override val type = AiProviderType.OPENAI_COMPATIBLE

    /** (apiKey, baseUrl) -> SDK client; replaceable in tests to stub the API. */
    internal var clientFactory: (apiKey: String, baseUrl: String?) -> OpenAIClient = { apiKey, baseUrl ->
        val builder = OpenAIOkHttpClient.builder()
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
        val params = ChatCompletionCreateParams.builder()
            .model(model)
            .maxCompletionTokens(MAX_TOKENS)
            .addSystemMessage(GenerationContract.systemPrompt(command))
            .addUserMessage(command.prompt)
            // TODO effort: map to reasoningEffort. Only reasoning models accept it and
            //  heterogeneous compatible endpoints reject unknown params, so it is never
            //  sent today — decide a mapping (send only above LOW, clamp XHIGH/MAX?) later.
            .build()
        val completion = call(clientFactory(apiKey, baseUrl)) { it.chat().completions().create(params) }
        val raw = completion.choices().joinToString("\n") { it.message().content().orElse(null) ?: "" }
        val usage = completion.usage().orElse(null)
        return GenerationContract.assembleResult(
            entries = GenerationContract.parseGenerated(raw, command.platforms),
            inputTokens = usage?.promptTokens() ?: 0L,
            outputTokens = usage?.completionTokens() ?: 0L,
        )
    }

    private fun <T> call(client: OpenAIClient, block: (OpenAIClient) -> T): T = try {
        block(client)
    } catch (e: OpenAIServiceException) {
        throw AiProviderException("OpenAI-compatible API error (${e.statusCode()}): ${e.message ?: "request rejected"}", e)
    } catch (e: OpenAIException) {
        throw AiProviderException("OpenAI-compatible API call failed: ${e.message}", e)
    } catch (e: Exception) {
        throw AiProviderException("OpenAI-compatible API call failed: ${e.message}", e)
    }

    companion object {
        /** Same rationale as AnthropicAdapter: truncation mid-JSON is the one unrecoverable parse failure. */
        private const val MAX_TOKENS = 4096L

        /** Well under the SDK's 10-minute default; fits a synchronous SPA call. */
        private const val TIMEOUT_SECONDS = 90L
    }
}
