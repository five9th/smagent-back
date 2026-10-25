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

/** Well-known setting keys; [key] is the persisted identifier (app_settings.setting_key). */
enum class SettingKey(val key: String) {
    FACEBOOK_CLIENT_ID("facebook.client-id"),
    FACEBOOK_CLIENT_SECRET("facebook.client-secret"),
    LINKEDIN_CLIENT_ID("linkedin.client-id"),
    LINKEDIN_CLIENT_SECRET("linkedin.client-secret"),
    X_CLIENT_ID("x.client-id"),
    X_CLIENT_SECRET("x.client-secret"),
}

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
        clientId = resolve(SettingKey.FACEBOOK_CLIENT_ID, props.facebook.clientId),
        clientSecret = resolve(SettingKey.FACEBOOK_CLIENT_SECRET, props.facebook.clientSecret),
    )

    fun linkedin() = ClientCredentials(
        clientId = resolve(SettingKey.LINKEDIN_CLIENT_ID, props.linkedin.clientId),
        clientSecret = resolve(SettingKey.LINKEDIN_CLIENT_SECRET, props.linkedin.clientSecret),
    )

    fun x() = ClientCredentials(
        clientId = resolve(SettingKey.X_CLIENT_ID, props.x.clientId),
        clientSecret = resolve(SettingKey.X_CLIENT_SECRET, props.x.clientSecret),
    )

    /** True when a runtime override row exists for the key (config value may differ). */
    fun isOverridden(key: SettingKey): Boolean = settings.existsById(key.key)

    /**
     * PUT semantics: replaces the whole override set. Platform sections and
     * individual fields left out clear those overrides back to config; blank
     * values are rejected (400 by the caller's validation).
     */
    fun replaceOverrides(request: UpdateSettingsRequest) {
        val wanted: Map<SettingKey, String> = buildMap {
            request.facebook?.clientId?.let { put(SettingKey.FACEBOOK_CLIENT_ID, it) }
            request.facebook?.clientSecret?.let { put(SettingKey.FACEBOOK_CLIENT_SECRET, it) }
            request.linkedin?.clientId?.let { put(SettingKey.LINKEDIN_CLIENT_ID, it) }
            request.linkedin?.clientSecret?.let { put(SettingKey.LINKEDIN_CLIENT_SECRET, it) }
            request.x?.clientId?.let { put(SettingKey.X_CLIENT_ID, it) }
            request.x?.clientSecret?.let { put(SettingKey.X_CLIENT_SECRET, it) }
        }
        settings.deleteAll()
        wanted.forEach { (key, value) ->
            settings.save(AppSetting(key = key.key, valueEnc = crypto.encrypt(value)))
        }
    }

    private fun resolve(key: SettingKey, configDefault: String): String {
        val override = settings.findById(key.key).orElse(null) ?: return configDefault
        return runCatching { crypto.decrypt(override.valueEnc) }
            .getOrElse { configDefault } // e.g. key file regenerated; config still works
    }
}
