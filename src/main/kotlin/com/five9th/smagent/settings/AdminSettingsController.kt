package com.five9th.smagent.settings

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Owner-facing runtime settings. Values are write-only: PUT updates them
 * (encrypted), GET reports only whether each is overridden at runtime —
 * never values.
 */
@RestController
@RequestMapping("/api/admin/settings")
class AdminSettingsController(private val settings: SettingsService) {

    data class PlatformConfigured(val clientIdOverridden: Boolean, val clientSecretOverridden: Boolean)
    data class SettingsStatus(
        val facebook: PlatformConfigured,
        val linkedin: PlatformConfigured,
        val x: PlatformConfigured,
    )

    /**
     * Per-field tri-state: non-empty = set override, "" = clear back to config,
     * omitted/null = untouched. Each of the six fields is independent.
     */
    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun update(@RequestBody request: UpdateSettingsRequest) = settings.applyOverrides(request)

    @GetMapping
    fun status(): SettingsStatus = SettingsStatus(
        facebook = configured(SettingKey.FACEBOOK_CLIENT_ID, SettingKey.FACEBOOK_CLIENT_SECRET),
        linkedin = configured(SettingKey.LINKEDIN_CLIENT_ID, SettingKey.LINKEDIN_CLIENT_SECRET),
        x = configured(SettingKey.X_CLIENT_ID, SettingKey.X_CLIENT_SECRET),
    )

    private fun configured(clientId: SettingKey, clientSecret: SettingKey) =
        PlatformConfigured(settings.isOverridden(clientId), settings.isOverridden(clientSecret))
}
