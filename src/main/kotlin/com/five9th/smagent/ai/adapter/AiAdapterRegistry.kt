package com.five9th.smagent.ai.adapter

import com.five9th.smagent.domain.AiProviderType
import org.springframework.stereotype.Component

/**
 * Dispatch over all [AiProviderAdapter] beans by provider type — same shape as
 * the platform AdapterRegistry: per-call lookup, the bean list stays the
 * single source of truth.
 */
@Component
class AiAdapterRegistry(private val adapters: List<AiProviderAdapter>) {

    fun find(type: AiProviderType): AiProviderAdapter? =
        adapters.firstOrNull { it.type == type }

    fun get(type: AiProviderType): AiProviderAdapter =
        find(type)
            ?: throw IllegalArgumentException("Unsupported AI provider type $type")
}
