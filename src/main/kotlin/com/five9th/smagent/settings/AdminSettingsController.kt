package com.five9th.smagent.settings

import com.five9th.smagent.settings.SettingsService.SettingKey
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Owner-facing runtime settings. Values are write-only: PUT stores them
 * (encrypted), GET reports only whether each is configured — never values.
 */
@RestController
@RequestMapping("/api/admin/settings")
class AdminSettingsController(private val settings: SettingsService) {

    data class PlatformConfigured(val clientIdSet: Boolean, val clientSecretSet: Boolean)
    data class SettingsStatus(
        val facebook: PlatformConfigured,
        val linkedin: PlatformConfigured,
        val x: PlatformConfigured,
    )

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun update(@Valid @RequestBody request: UpdateSettingsRequest) = settings.replaceOverrides(request)

    @GetMapping
    fun status(): SettingsStatus = SettingsStatus(
        facebook = PlatformConfigured(
            settings.isConfigured(SettingKey.FACEBOOK_CLIENT_ID),
            settings.isConfigured(SettingKey.FACEBOOK_CLIENT_SECRET),
        ),
        linkedin = PlatformConfigured(
            settings.isConfigured(SettingKey.LINKEDIN_CLIENT_ID),
            settings.isConfigured(SettingKey.LINKEDIN_CLIENT_SECRET),
        ),
        x = PlatformConfigured(
            settings.isConfigured(SettingKey.X_CLIENT_ID),
            settings.isConfigured(SettingKey.X_CLIENT_SECRET),
        ),
    )
}
