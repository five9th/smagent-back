package com.five9th.smagent.settings

import com.five9th.smagent.config.AppProperties
import com.five9th.smagent.crypto.CryptoService
import com.five9th.smagent.domain.AppSetting
import com.five9th.smagent.repo.AppSettingRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.stereotype.Service

/** App credentials for one platform; blank values are rejected, null = clear the override. */
data class PlatformCredentials(
    @field:NotBlank val clientId: String? = null,
    @field:NotBlank val clientSecret: String? = null,
)

/** PUT semantics: replaces all overrides; omitted platforms/fields fall back to config. */
data class UpdateSettingsRequest(
    @field:Valid val facebook: PlatformCredentials? = null,
    @field:Valid val linkedin: PlatformCredentials? = null,
    @field:Valid val x: PlatformCredentials? = null,
)

/**
 * Runtime app settings. Persistence model: a stored row OVERRIDES the yaml/env
 * default (AppProperties) for its key; no row = use the config value. Overrides
 * survive restarts; a PUT replaces the whole override set (fields/platforms left
 * out fall back to config again).
 *
 * Consumers ask this service for the OAuth app credentials — never AppProperties.
 */
@Service
class SettingsService(
    private val settings: AppSettingRepository,
    private val crypto: CryptoService,
    private val props: AppProperties,
) {
    data class ClientCredentials(val clientId: String, val clientSecret: String)

    fun facebook() = ClientCredentials(
        clientId = resolve(FACEBOOK_CLIENT_ID, props.facebook.clientId),
        clientSecret = resolve(FACEBOOK_CLIENT_SECRET, props.facebook.clientSecret),
    )

    fun linkedin() = ClientCredentials(
        clientId = resolve(LINKEDIN_CLIENT_ID, props.linkedin.clientId),
        clientSecret = resolve(LINKEDIN_CLIENT_SECRET, props.linkedin.clientSecret),
    )

    fun x() = ClientCredentials(
        clientId = resolve(X_CLIENT_ID, props.x.clientId),
        clientSecret = resolve(X_CLIENT_SECRET, props.x.clientSecret),
    )

    /** True when the key resolves to a non-blank value (override or config). */
    fun isConfigured(key: SettingKey): Boolean = when (key) {
        SettingKey.FACEBOOK_CLIENT_ID -> facebook().clientId.isNotBlank()
        SettingKey.FACEBOOK_CLIENT_SECRET -> facebook().clientSecret.isNotBlank()
        SettingKey.LINKEDIN_CLIENT_ID -> linkedin().clientId.isNotBlank()
        SettingKey.LINKEDIN_CLIENT_SECRET -> linkedin().clientSecret.isNotBlank()
        SettingKey.X_CLIENT_ID -> x().clientId.isNotBlank()
        SettingKey.X_CLIENT_SECRET -> x().clientSecret.isNotBlank()
    }

    /**
     * PUT semantics: replaces the whole override set. Platform sections and
     * individual fields left out clear those overrides back to config; blank
     * values are rejected (400 by the caller's validation).
     */
    fun replaceOverrides(request: UpdateSettingsRequest) {
        val wanted: Map<String, String> = buildMap {
            request.facebook?.clientId?.let { put(FACEBOOK_CLIENT_ID, it) }
            request.facebook?.clientSecret?.let { put(FACEBOOK_CLIENT_SECRET, it) }
            request.linkedin?.clientId?.let { put(LINKEDIN_CLIENT_ID, it) }
            request.linkedin?.clientSecret?.let { put(LINKEDIN_CLIENT_SECRET, it) }
            request.x?.clientId?.let { put(X_CLIENT_ID, it) }
            request.x?.clientSecret?.let { put(X_CLIENT_SECRET, it) }
        }
        settings.deleteAll()
        wanted.forEach { (key, value) ->
            settings.save(AppSetting(key = key, valueEnc = crypto.encrypt(value)))
        }
    }

    private fun resolve(key: String, configDefault: String): String {
        val override = settings.findById(key).orElse(null) ?: return configDefault
        return runCatching { crypto.decrypt(override.valueEnc) }
            .getOrElse { configDefault } // e.g. key file regenerated; config still works
    }

    enum class SettingKey { FACEBOOK_CLIENT_ID, FACEBOOK_CLIENT_SECRET, LINKEDIN_CLIENT_ID, LINKEDIN_CLIENT_SECRET, X_CLIENT_ID, X_CLIENT_SECRET }

    companion object {
        const val FACEBOOK_CLIENT_ID = "facebook.client-id"
        const val FACEBOOK_CLIENT_SECRET = "facebook.client-secret"
        const val LINKEDIN_CLIENT_ID = "linkedin.client-id"
        const val LINKEDIN_CLIENT_SECRET = "linkedin.client-secret"
        const val X_CLIENT_ID = "x.client-id"
        const val X_CLIENT_SECRET = "x.client-secret"
    }
}
