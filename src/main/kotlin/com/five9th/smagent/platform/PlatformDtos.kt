package com.five9th.smagent.platform

import com.five9th.smagent.domain.AuthMethod
import com.five9th.smagent.domain.Platform

/** GET /api/platforms payload — the menu the SPA builds the account form from. */
data class PlatformDto(
    val platform: Platform,
    val loginOptions: List<LoginOptionDto>,
)

data class LoginOptionDto(
    val authMethod: AuthMethod,
    val canPublish: Boolean,
    val requiredCredentialFields: Set<String>,
)
