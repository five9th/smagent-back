package com.five9th.smagent.account

import com.five9th.smagent.domain.AccountStatus
import com.five9th.smagent.domain.AuthMethod
import com.five9th.smagent.domain.Platform
import jakarta.validation.constraints.NotNull
import java.time.Instant

data class CreateAccountRequest(
    @field:NotNull val platform: Platform,
    @field:NotNull val authMethod: AuthMethod,
    val displayName: String? = null,
    @field:NotNull val credentials: Map<String, String>,
)

data class UpdateAccountRequest(
    val displayName: String? = null,
    /** May only change together with [credentials] — switching login options replaces the whole blob. */
    val authMethod: AuthMethod? = null,
    val credentials: Map<String, String>? = null,
)

data class AccountDto(
    val id: Long?,
    val platform: Platform,
    val authMethod: AuthMethod,
    val displayName: String,
    val status: AccountStatus,
    val canPublish: Boolean,
    val credentialsMasked: Map<String, String>,
    val createdAt: Instant,
    val deletedAt: Instant? = null,
)

data class VerifyResponse(
    val account: AccountDto,
    val ok: Boolean,
    val message: String,
)
