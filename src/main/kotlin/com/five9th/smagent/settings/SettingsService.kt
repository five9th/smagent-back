package com.five9th.smagent.settings

import com.five9th.smagent.config.AppProperties
import com.five9th.smagent.crypto.CryptoService
import com.five9th.smagent.domain.AppSetting
import com.five9th.smagent.repo.AppSettingRepository
import org.springframework.stereotype.Service

/**
 * App credentials for one platform. Per-field tri-state:
 * non-empty -> set the override; `""` -> clear it (fall back to config);
 * null/omitted -> untouched. `clientId` and `clientSecret` are independent.
 */
data class PlatformCredentials(
    val clientId: String? = null,
    val clientSecret: String? = null,
)

/** Any subset of platforms/fields; omitted ones are left untouched. */
data class UpdateSettingsRequest(
    val facebook: PlatformCredentials? = null,
    val linkedin: PlatformCredentials? = null,
    val x: PlatformCredentials? = null,
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
 * survive restarts; each field is set/cleared independently (see
 * [applyOverrides]).
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
     * Per-field tri-state update (null/omitted = untouched, "" = clear back to
     * config, non-empty = set). Each of the six fields is independent.
     */
    fun applyOverrides(request: UpdateSettingsRequest) {
        fun set(key: SettingKey, value: String?) {
            if (value == null) return // untouched
            val trimmed = value.trim()
            if (trimmed.isEmpty()) {
                settings.deleteById(key.key)
            } else {
                settings.save(AppSetting(key = key.key, valueEnc = crypto.encrypt(trimmed)))
            }
        }

        set(SettingKey.FACEBOOK_CLIENT_ID, request.facebook?.clientId)
        set(SettingKey.FACEBOOK_CLIENT_SECRET, request.facebook?.clientSecret)
        set(SettingKey.LINKEDIN_CLIENT_ID, request.linkedin?.clientId)
        set(SettingKey.LINKEDIN_CLIENT_SECRET, request.linkedin?.clientSecret)
        set(SettingKey.X_CLIENT_ID, request.x?.clientId)
        set(SettingKey.X_CLIENT_SECRET, request.x?.clientSecret)
    }

    private fun resolve(key: SettingKey, configDefault: String): String {
        val override = settings.findById(key.key).orElse(null) ?: return configDefault
        return runCatching { crypto.decrypt(override.valueEnc) }
            .getOrElse { configDefault } // e.g. key file regenerated; config still works
    }
}
