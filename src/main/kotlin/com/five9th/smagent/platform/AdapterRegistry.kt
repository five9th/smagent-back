package com.five9th.smagent.platform

import com.five9th.smagent.domain.AuthMethod
import com.five9th.smagent.domain.Platform
import org.springframework.stereotype.Component

/**
 * Dispatch over all [SocialPlatformAdapter] beans by (platform, authMethod).
 * Lookups are per-call (not a pre-built map) so the adapter list stays the
 * single source of truth — a handful of beans makes the scan irrelevant.
 * Also builds the login-option catalog that GET /api/platforms serves.
 */
@Component
class AdapterRegistry(private val adapters: List<SocialPlatformAdapter>) {

    fun find(platform: Platform, authMethod: AuthMethod): SocialPlatformAdapter? =
        adapters.firstOrNull { it.platform == platform && it.authMethod == authMethod }

    fun get(platform: Platform, authMethod: AuthMethod): SocialPlatformAdapter =
        find(platform, authMethod)
            ?: throw IllegalArgumentException("Unsupported login option $authMethod for $platform")

    /** Login options per platform, in Platform enum order (platforms with no adapter are skipped). */
    fun platformInfos(): List<PlatformDto> =
        Platform.entries.mapNotNull { platform ->
            val options = adapters.filter { it.platform == platform }
                .sortedBy { it.authMethod.name } // stable output: bean order is not
                .map { it.toLoginOptionDto() }
            if (options.isEmpty()) null else PlatformDto(platform, options)
        }

    private fun SocialPlatformAdapter.toLoginOptionDto() = LoginOptionDto(
        authMethod = authMethod,
        canPublish = canPublish,
        canShare = canShare,
        credentialFields = credentialFields,
    )
}
