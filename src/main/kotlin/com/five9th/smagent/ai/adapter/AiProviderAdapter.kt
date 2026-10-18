package com.five9th.smagent.ai.adapter

import com.five9th.smagent.domain.AiProviderType
import com.five9th.smagent.domain.GenerateEffort
import com.five9th.smagent.domain.GeneratePlatform
import com.five9th.smagent.domain.Platform
import com.five9th.smagent.domain.PostContent

/**
 * One implementation per [AiProviderType]; Spring injects all implementations
 * as a list (see AiAdapterRegistry for dispatch). Adapters receive the
 * decrypted apiKey and the optional API-root override per call — clients are
 * cheap to build and provider rows are few.
 */
interface AiProviderAdapter {
    val type: AiProviderType

    /**
     * Model ids the key can use, sorted — feeds both verify's message and
     * GET /api/ai-providers/{id}/models. Throws [AiProviderException] on
     * upstream failure.
     */
    fun listModels(apiKey: String, baseUrl: String?): List<String>

    /**
     * Cheap authenticated probe on top of [listModels]. Returns a
     * human-readable success message; throws [AiProviderException] on
     * upstream failure.
     */
    fun verify(apiKey: String, baseUrl: String?): String

    /** Draft post content for every requested variant in one call. */
    fun generate(
        apiKey: String,
        baseUrl: String?,
        model: String,
        effort: GenerateEffort,
        command: GenerateCommand,
    ): GenerateResult
}

/** What to draft: the user prompt, the requested variants, optional global constraints, optional current draft. */
data class GenerateCommand(
    val prompt: String,
    /** Which entries to produce; GENERIC becomes `content`, the rest `overrides`. */
    val platforms: Set<GeneratePlatform>,
    val constraints: GenerateConstraints? = null,
    /**
     * Current state of the post editor (GENERIC = the post's own content) —
     * lets the model edit the existing draft instead of starting from zero.
     * Blank-text entries are ignored when building the prompt.
     */
    val editorState: Map<GeneratePlatform, PostContent>? = null,
    /**
     * Effective per-platform style rules (GenerationSettings overrides merged
     * over the built-ins by the service — server context, not request data).
     * Null/blank entries fall back to [GenerationContract.DEFAULT_RULES].
     */
    val platformRules: Map<GeneratePlatform, String>? = null,
)

data class GenerateConstraints(
    /** Hard cap folded into the system prompt for every variant. */
    val maxLength: Int? = null,
    val tone: String? = null,
)

/** One drafted entry; title only ever appears on GENERIC and REDDIT. */
data class GeneratedContent(
    val title: String? = null,
    val text: String,
)

data class GenerateResult(
    /** GENERIC entry; null when it wasn't requested. */
    val content: GeneratedContent? = null,
    val overrides: Map<Platform, GeneratedContent> = emptyMap(),
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
)

/** Upstream AI API failure (bad key, rejected request, outage, malformed model output). */
class AiProviderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
