package com.five9th.smagent.ai

import com.five9th.smagent.ai.adapter.AiAdapterRegistry
import com.five9th.smagent.ai.adapter.GenerateCommand
import com.five9th.smagent.crypto.CryptoService
import com.five9th.smagent.domain.AiProvider
import com.five9th.smagent.domain.GenerateEffort
import com.five9th.smagent.domain.GeneratePlatform
import com.five9th.smagent.repo.AiProviderRepository
import org.springframework.stereotype.Service

/**
 * One-shot post-text generation: resolves provider and model, decrypts the
 * key, and maps the adapter result into the PostDto-shaped content/overrides
 * response (the SPA drops it straight into the post editor). The result is
 * never persisted here — saving is the SPA's POST /api/posts as usual.
 */
@Service
class AiGenerateService(
    private val providers: AiProviderRepository,
    private val providerService: AiProviderService,
    private val crypto: CryptoService,
    private val registry: AiAdapterRegistry,
) {

    fun generate(request: GenerateRequest): GenerateResponse {
        val platforms = request.platforms?.also { require(it.isNotEmpty()) { "platforms must not be empty" } }
            ?: setOf(GeneratePlatform.GENERIC)
        val provider = request.providerId?.let { providerService.get(it) } ?: inferProvider()
        val model = request.model?.trim()?.takeIf { it.isNotEmpty() }
            ?: provider.defaultModel?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(
                "Provider '${provider.name}' has no defaultModel — set one (PUT /api/ai-providers/{id}) or pass model in the request"
            )

        val result = registry.get(provider.type).generate(
            apiKey = crypto.decrypt(provider.apiKeyEnc),
            baseUrl = provider.baseUrl,
            model = model,
            effort = request.effort ?: GenerateEffort.LOW,
            command = GenerateCommand(
                prompt = request.prompt!!.trim(),
                platforms = platforms,
                constraints = request.constraints,
                editorState = request.editorState,
            ),
        )
        return GenerateResponse(
            content = result.content?.let { GeneratedTextDto(it.title, it.text) },
            overrides = result.overrides.mapValues { GeneratedTextDto(it.value.title, it.value.text) },
            provider = provider.name,
            model = model,
            usage = TokenUsageDto(result.inputTokens, result.outputTokens),
        )
    }

    /**
     * No stored default provider (deliberately — the SPA passes its choice per
     * request): with exactly one provider there is nothing to choose.
     */
    private fun inferProvider(): AiProvider {
        val all = providers.findAllByOrderByCreatedAtDesc()
        return when {
            all.isEmpty() -> throw IllegalArgumentException("No AI providers configured — create one first (POST /api/ai-providers)")
            all.size == 1 -> all[0]
            else -> throw IllegalArgumentException("Several AI providers exist — pass providerId to choose one")
        }
    }
}
