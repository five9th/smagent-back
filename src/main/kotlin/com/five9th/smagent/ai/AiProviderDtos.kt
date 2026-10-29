package com.five9th.smagent.ai

import com.five9th.smagent.domain.AiProviderType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.Instant

data class CreateAiProviderRequest(
    @field:NotBlank val name: String?,
    @field:NotNull val type: AiProviderType,
    /** Optional API root override (origin only; must be a well-formed http(s) URL). */
    val baseUrl: String? = null,
    @field:NotBlank val apiKey: String?,
    val defaultModel: String? = null,
)

data class UpdateAiProviderRequest(
    val name: String? = null,
    /** null = keep the stored value; "" clears it back to null. */
    val baseUrl: String? = null,
    /** null or blank = keep the stored key. */
    val apiKey: String? = null,
    /** null = keep the stored value; "" clears it back to the built-in default. */
    val defaultModel: String? = null,
)

data class AiProviderDto(
    val id: Long?,
    val name: String,
    val type: AiProviderType,
    val baseUrl: String?,
    val apiKeyMasked: String,
    val defaultModel: String?,
    val createdAt: Instant,
)

data class VerifyAiProviderResponse(
    val ok: Boolean,
    val message: String,
)
