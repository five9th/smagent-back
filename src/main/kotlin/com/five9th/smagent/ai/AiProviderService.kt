package com.five9th.smagent.ai

import com.five9th.smagent.ai.adapter.AiAdapterRegistry
import com.five9th.smagent.ai.adapter.AiProviderException
import com.five9th.smagent.common.NotFoundException
import com.five9th.smagent.crypto.CryptoService
import com.five9th.smagent.domain.AiProvider
import com.five9th.smagent.domain.AiProviderType
import com.five9th.smagent.repo.AiProviderRepository
import org.springframework.stereotype.Service
import java.net.URI

@Service
class AiProviderService(
    private val providers: AiProviderRepository,
    private val crypto: CryptoService,
    private val registry: AiAdapterRegistry,
) {

    fun list(): List<AiProviderDto> =
        providers.findAllByOrderByCreatedAtDesc().map { toDto(it) }

    fun get(id: Long): AiProvider =
        providers.findById(id).orElse(null)
            ?: throw NotFoundException("AI provider $id not found")

    fun getDto(id: Long): AiProviderDto = toDto(get(id))

    fun create(request: CreateAiProviderRequest): AiProviderDto {
        val baseUrl = request.baseUrl?.trim()?.takeIf { it.isNotEmpty() }?.also(::requireHttpUrl)
        val defaultModel = request.defaultModel?.trim()?.takeIf { it.isNotEmpty() }
        // model names are provider-specific (gpt-5.2 / llama3.1 / deepseek/...) — no sensible built-in fallback
        if (request.type == AiProviderType.OPENAI_COMPATIBLE && defaultModel == null) {
            throw IllegalArgumentException("defaultModel is required for OPENAI_COMPATIBLE providers")
        }
        val provider = AiProvider(
            name = request.name!!.trim(),
            type = request.type,
            baseUrl = baseUrl,
            apiKeyEnc = crypto.encrypt(request.apiKey!!),
            defaultModel = defaultModel,
        )
        return toDto(providers.save(provider))
    }

    fun update(id: Long, request: UpdateAiProviderRequest): AiProviderDto {
        val provider = get(id)
        request.name?.trim()?.takeIf { it.isNotEmpty() }?.let { provider.name = it }
        request.baseUrl?.let {
            provider.baseUrl = it.trim().takeIf { u -> u.isNotEmpty() }?.also(::requireHttpUrl)
        }
        request.apiKey?.takeIf { it.isNotBlank() }?.let { provider.apiKeyEnc = crypto.encrypt(it) }
        request.defaultModel?.let {
            val newModel = it.trim().takeIf { m -> m.isNotEmpty() }
            if (provider.type == AiProviderType.OPENAI_COMPATIBLE && newModel == null) {
                throw IllegalArgumentException(
                    "defaultModel is required for OPENAI_COMPATIBLE providers — pass a new model instead of clearing it"
                )
            }
            provider.defaultModel = newModel
        }
        return toDto(providers.save(provider))
    }

    /** Hard delete: nothing references a provider yet (no usage rows, no FKs). */
    fun delete(id: Long) {
        providers.delete(get(id))
    }

    /**
     * Cheap authenticated probe; nothing is persisted (there is no status
     * machine — nothing gates on it, generate works regardless).
     */
    fun verify(id: Long): VerifyAiProviderResponse {
        val provider = get(id)
        val adapter = registry.get(provider.type)
        return try {
            VerifyAiProviderResponse(
                ok = true,
                message = adapter.verify(crypto.decrypt(provider.apiKeyEnc), provider.baseUrl),
            )
        } catch (e: AiProviderException) {
            VerifyAiProviderResponse(ok = false, message = e.message ?: "Verification failed")
        }
    }

    private fun requireHttpUrl(value: String) {
        val uri = try {
            URI.create(value)
        } catch (_: Exception) {
            null
        }
        require(uri?.scheme == "http" || uri?.scheme == "https") {
            "baseUrl must be a well-formed http(s) URL, got '$value'"
        }
    }

    private fun toDto(provider: AiProvider) = AiProviderDto(
        id = provider.id,
        name = provider.name,
        type = provider.type,
        baseUrl = provider.baseUrl,
        apiKeyMasked = maskToken(crypto.decrypt(provider.apiKeyEnc)),
        defaultModel = provider.defaultModel,
        createdAt = provider.createdAt,
    )

    private fun maskToken(value: String): String =
        if (value.length > 8) "••••" + value.takeLast(4) else "••••"
}
