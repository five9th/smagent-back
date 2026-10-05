package com.five9th.smagent.account

import com.five9th.smagent.common.NotFoundException
import com.five9th.smagent.crypto.CryptoService
import com.five9th.smagent.domain.AccountStatus
import com.five9th.smagent.domain.SocialAccount
import com.five9th.smagent.platform.AdapterRegistry
import com.five9th.smagent.platform.CredentialParser
import com.five9th.smagent.platform.PlatformException
import com.five9th.smagent.platform.SocialPlatformAdapter
import com.five9th.smagent.repo.SocialAccountRepository
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

@Service
class SocialAccountService(
    private val accounts: SocialAccountRepository,
    private val crypto: CryptoService,
    private val registry: AdapterRegistry,
) {
    private val mapper = JsonMapper.builder().build()

    fun list(showDeleted: Boolean = false): List<AccountDto> {
        val all = if (showDeleted) {
            accounts.findAllByOrderByCreatedAtDesc()
        } else {
            accounts.findAllByDeletedAtIsNullOrderByCreatedAtDesc()
        }
        return all.map { toDto(it) }
    }

    /** Soft-deleted accounts behave as missing (404) everywhere. */
    fun get(id: Long): SocialAccount =
        accounts.findByIdAndDeletedAtIsNull(id)
            ?: throw NotFoundException("Social account $id not found")

    fun getDto(id: Long): AccountDto = toDto(get(id))

    fun create(request: CreateAccountRequest): AccountDto {
        val adapter = registry.get(request.platform, request.authMethod)
        validateCredentials(adapter, request.credentials)
        val account = SocialAccount(
            platform = request.platform,
            authMethod = request.authMethod,
            displayName = request.displayName?.trim()?.takeIf { it.isNotEmpty() }
                ?: "${request.platform} account",
            credentialsEnc = crypto.encrypt(mapper.writeValueAsString(request.credentials)),
        )
        return toDto(accounts.save(account))
    }

    fun update(id: Long, request: UpdateAccountRequest): AccountDto {
        val account = get(id)
        request.displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { account.displayName = it }
        if (request.authMethod != null && request.credentials == null) {
            throw IllegalArgumentException("Changing authMethod requires submitting new credentials")
        }
        request.credentials?.let {
            val authMethod = request.authMethod ?: account.authMethod
            val adapter = registry.get(account.platform, authMethod)
            validateCredentials(adapter, it)
            account.authMethod = authMethod
            account.credentialsEnc = crypto.encrypt(mapper.writeValueAsString(it))
            account.status = AccountStatus.UNVERIFIED
        }
        return toDto(accounts.save(account))
    }

    /** Soft delete: keeps the row (publication history references it) but drops the credentials. */
    fun delete(id: Long) {
        val account = get(id)
        account.deletedAt = Instant.now()
        account.credentialsEnc = "" // secrets don't outlive the account
        accounts.save(account)
    }

    /** Cheap "who am I" call against the platform; updates status and displayName. */
    fun verify(id: Long): VerifyResponse {
        val account = get(id)
        val adapter = registry.get(account.platform, account.authMethod)
        return try {
            val result = adapter.verify(crypto.decrypt(account.credentialsEnc))
            result.displayName?.let { account.displayName = it }
            account.status = AccountStatus.ACTIVE
            accounts.save(account)
            VerifyResponse(toDto(account), ok = true, message = "Credentials verified")
        } catch (e: PlatformException) {
            account.status = AccountStatus.INVALID
            accounts.save(account)
            VerifyResponse(toDto(account), ok = false, message = e.message ?: "Verification failed")
        }
    }

    private fun validateCredentials(adapter: SocialPlatformAdapter, credentials: Map<String, String>) {
        val missing = adapter.requiredCredentialFields.filter {
            credentials[it].isNullOrBlank()
        }
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException(
                "Missing credential field(s) for ${adapter.platform}/${adapter.authMethod}: ${missing.joinToString(", ")}"
            )
        }
    }

    private fun toDto(account: SocialAccount): AccountDto = AccountDto(
        id = account.id,
        platform = account.platform,
        authMethod = account.authMethod,
        displayName = account.displayName,
        status = account.status,
        canPublish = registry.find(account.platform, account.authMethod)?.canPublish ?: false,
        credentialsMasked = maskCredentials(account),
        createdAt = account.createdAt,
        deletedAt = account.deletedAt,
    )

    /** Mask secrets, not IDs: adapter-declared non-secret fields are shown in full. */
    private fun maskCredentials(account: SocialAccount): Map<String, String> =
        if (account.credentialsEnc.isBlank()) {
            emptyMap() // soft-deleted: credentials dropped
        } else {
            val nonSecret = registry.find(account.platform, account.authMethod)
                ?.nonSecretCredentialFields ?: emptySet() // unknown login option: mask everything
            CredentialParser.parseAll(crypto.decrypt(account.credentialsEnc))
                .mapValues { (field, value) -> if (field in nonSecret) value else mask(value) }
        }

    private fun mask(value: String): String =
        if (value.length > 8) "••••" + value.takeLast(4) else "••••"
}
