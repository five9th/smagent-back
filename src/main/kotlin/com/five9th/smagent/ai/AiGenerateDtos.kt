package com.five9th.smagent.ai

import com.five9th.smagent.ai.adapter.GenerateConstraints
import com.five9th.smagent.domain.GenerateEffort
import com.five9th.smagent.domain.GeneratePlatform
import com.five9th.smagent.domain.Platform
import jakarta.validation.constraints.NotBlank

data class GenerateRequest(
    @field:NotBlank val prompt: String?,
    /** Variants to produce; null = ["GENERIC"]; an explicit empty set is rejected. */
    val platforms: Set<GeneratePlatform>? = null,
    val constraints: GenerateConstraints? = null,
    /** Defaults to the only provider; required when several exist. */
    val providerId: Long? = null,
    /** Defaults to the provider's defaultModel, then the built-in constant. */
    val model: String? = null,
    val effort: GenerateEffort? = null,
)

data class GenerateResponse(
    /** GENERIC entry; null when it wasn't requested. */
    val content: GeneratedTextDto? = null,
    val overrides: Map<Platform, GeneratedTextDto> = emptyMap(),
    val provider: String,
    val model: String,
    val usage: TokenUsageDto,
)

data class GeneratedTextDto(
    /** Present only on GENERIC (optional) and REDDIT (required at publish time). */
    val title: String? = null,
    val text: String,
)

data class TokenUsageDto(
    val inputTokens: Long,
    val outputTokens: Long,
)
