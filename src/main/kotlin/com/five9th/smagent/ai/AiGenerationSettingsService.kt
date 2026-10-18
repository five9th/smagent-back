package com.five9th.smagent.ai

import com.five9th.smagent.ai.adapter.GenerationContract
import com.five9th.smagent.domain.GeneratePlatform
import com.five9th.smagent.domain.GenerationSettings
import com.five9th.smagent.repo.GenerationSettingsRepository
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper

/**
 * Owner-editable per-platform style rules for the generation system prompt —
 * the editorial half only; shapes and hard requirements stay templated (see
 * GenerationContract). Stored as one JSON-blob row, same pattern as
 * credential blobs.
 */
@Service
class AiGenerationSettingsService(
    private val settings: GenerationSettingsRepository,
) {

    private val mapper = JsonMapper.builder().build()

    /** Defaults with stored overrides applied — what generate sends and GET returns. */
    fun effectiveRules(): Map<GeneratePlatform, String> =
        GenerationContract.rulesWith(loadOverrides())

    fun get(): GenerationSettingsDto = GenerationSettingsDto(
        platformRules = effectiveRules(),
        defaults = GenerationContract.DEFAULT_RULES,
    )

    fun update(request: UpdateGenerationSettingsRequest): GenerationSettingsDto {
        val requested = request.platformRules ?: emptyMap()
        requested.forEach { (platform, text) ->
            require(text.isNullOrBlank() || text.length <= MAX_RULE_LENGTH) {
                "platformRules[$platform] is too long (max $MAX_RULE_LENGTH characters)"
            }
        }
        val overrides = requested
            .filterValues { !it.isNullOrBlank() }
            .mapValues { it.value!!.trim() }
        val row = settings.findFirstByOrderByIdAsc() ?: GenerationSettings()
        row.rulesJson = mapper.writeValueAsString(overrides)
        settings.save(row)
        return get()
    }

    /** Lenient: a corrupted row degrades to built-in defaults instead of breaking generation. */
    private fun loadOverrides(): Map<GeneratePlatform, String> {
        val json = settings.findFirstByOrderByIdAsc()?.rulesJson?.takeIf { it.isNotBlank() }
            ?: return emptyMap()
        return try {
            mapper.readValue(json, object : TypeReference<Map<GeneratePlatform, String>>() {})
        } catch (_: Exception) {
            emptyMap()
        }
    }

    companion object {
        private const val MAX_RULE_LENGTH = 1000
    }
}
