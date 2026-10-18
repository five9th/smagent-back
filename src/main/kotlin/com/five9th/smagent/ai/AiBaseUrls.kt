package com.five9th.smagent.ai

import com.five9th.smagent.domain.AiProviderType

/**
 * Static catalog of well-known API roots per provider type, served to the SPA
 * as baseUrl suggestions (GET /api/ai-providers/baseurls/{type}). Doubles as
 * the machine-readable doc of the per-type convention: ANTHROPIC entries are
 * origin-only (the SDK appends /v1/...), OPENAI_COMPATIBLE entries are full
 * roots including the version path.
 */
object AiBaseUrls {

    private val byType: Map<AiProviderType, Map<String, String>> = mapOf(
        AiProviderType.ANTHROPIC to mapOf(
            "Anthropic" to "https://api.anthropic.com",
        ),
        AiProviderType.OPENAI_COMPATIBLE to mapOf(
            "OpenAI" to "https://api.openai.com/v1",
            "OpenRouter" to "https://openrouter.ai/api/v1",
            "Groq" to "https://api.groq.com/openai/v1",
            "Mistral" to "https://api.mistral.ai/v1",
            "DeepSeek" to "https://api.deepseek.com/v1",
            "Ollama (local)" to "http://localhost:11434/v1",
        ),
    )

    /** Unknown type strings are a client bug — plain 400. */
    fun forType(type: String): Map<String, String> {
        val parsed = AiProviderType.entries.firstOrNull { it.name.equals(type, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown AI provider type '$type' (${AiProviderType.entries.joinToString()})")
        return byType.getValue(parsed)
    }
}
