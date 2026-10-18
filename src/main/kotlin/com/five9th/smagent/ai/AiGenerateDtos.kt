package com.five9th.smagent.ai

import com.five9th.smagent.ai.adapter.GenerateConstraints
import com.five9th.smagent.domain.GenerateEffort
import com.five9th.smagent.domain.GeneratePlatform
import com.five9th.smagent.domain.Platform
import com.five9th.smagent.domain.PostContent
import jakarta.validation.constraints.NotBlank

data class GenerateRequest(
    @field:NotBlank val prompt: String?,
    /** Variants to produce; null = ["GENERIC"]; an explicit empty set is rejected. */
    val platforms: Set<GeneratePlatform>? = null,
    val constraints: GenerateConstraints? = null,
    /**
     * Current post editor state (GENERIC = the post's own content) — when set,
     * the model edits this draft per the prompt instead of drafting from
     * scratch. Entries with blank text are ignored; keys outside [platforms]
     * are context only. The response is still full replacements.
     */
    val editorState: Map<GeneratePlatform, PostContent>? = null,
    /** Defaults to the only provider; required when several exist. */
    val providerId: Long? = null,
    /** Defaults to the provider's defaultModel. */
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

/** Effective per-platform style rules (overrides merged over the built-ins) + the built-ins for reset UIs. */
data class GenerationSettingsDto(
    val platformRules: Map<GeneratePlatform, String>,
    val defaults: Map<GeneratePlatform, String>,
)

/** Full replace: an absent, null or blank value resets that platform to its built-in default. */
data class UpdateGenerationSettingsRequest(
    val platformRules: Map<GeneratePlatform, String?>? = null,
)
