package com.five9th.smagent.account

import com.five9th.smagent.common.NotFoundException
import com.five9th.smagent.crypto.CryptoService
import com.five9th.smagent.domain.AccountStatus
import com.five9th.smagent.domain.CredentialFieldKind
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

    // mb should've used PATCH endpoint instead of mergeCredentials flag?
    fun update(id: Long, mergeCredentials: Boolean, request: UpdateAccountRequest): AccountDto {
        val account = get(id)
        request.displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { account.displayName = it }

        if (request.authMethod != null && request.credentials == null) {
            throw IllegalArgumentException("Changing authMethod requires submitting new credentials")
        }

        request.credentials?.let {
            val authMethod = request.authMethod ?: account.authMethod
            val adapter = registry.get(account.platform, authMethod)

            val credentials = if (mergeCredentials) {
                mergeCredentials(request, account, adapter)
            }
            else it

            validateCredentials(adapter, credentials)
            account.authMethod = authMethod
            account.credentialsEnc = crypto.encrypt(mapper.writeValueAsString(credentials))
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
        val missing = adapter.credentialFields.keys.filter {
            credentials[it].isNullOrBlank()
        }
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException(
                "Missing credential field(s) for ${adapter.platform}/${adapter.authMethod}: ${missing.joinToString(", ")}"
            )
        }
    }

    /** Overrides the values in `account.credentials` with the values from `request.credentials`
     * Values present in `account.credentials` but not in `request.credentials` are kept in the result as is. */
    private fun mergeCredentials(
        request: UpdateAccountRequest,
        account: SocialAccount,
        adapter: SocialPlatformAdapter
    ): Map<String, String> {

        // If request.authMethod is present and differs from account.authMethod
        request.authMethod?.let {
            if (it != account.authMethod) {
                throw IllegalArgumentException(
                    "Can't merge the credentials while also changing the `authMethod` from ${account.authMethod} to $it"
                )
            }
        }

        val requestCredentials = request.credentials ?: throw IllegalArgumentException(
            "`mergeCredentials` flag is set but no new credentials provided"
        )

        val credentialsJson = crypto.decrypt(account.credentialsEnc)
        val originalCredentials = CredentialParser.parse(
            credentialsJson,
            *adapter.credentialFields.keys.toTypedArray()
        ).toMutableMap()

        for ((key, value) in requestCredentials) {
            originalCredentials[key] = value
        }

        return originalCredentials.toMap()
    }

    private fun toDto(account: SocialAccount): AccountDto {
        val adapter = registry.find(account.platform, account.authMethod)
        return AccountDto(
            id = account.id,
            platform = account.platform,
            authMethod = account.authMethod,
            displayName = account.displayName,
            status = account.status,
            canPublish = adapter?.canPublish ?: false,
            canShare = adapter?.canShare ?: false,
            credentialsMasked = maskCredentials(account),
            createdAt = account.createdAt,
            deletedAt = account.deletedAt,
        )
    }

    /** Mask by field kind: IDs in full, tokens keep their tail, passwords fully hidden. */
    private fun maskCredentials(account: SocialAccount): Map<String, String> =
        if (account.credentialsEnc.isBlank()) {
            emptyMap() // soft-deleted: credentials dropped
        } else {
            val kinds = registry.find(account.platform, account.authMethod)
                ?.credentialFields ?: emptyMap() // unknown login option: mask everything
            CredentialParser.parseAll(crypto.decrypt(account.credentialsEnc))
                .mapValues { (field, value) -> maskByKind(kinds[field], value) }
        }

    private fun maskByKind(kind: CredentialFieldKind?, value: String): String = when (kind) {
        CredentialFieldKind.ID -> value
        CredentialFieldKind.TOKEN -> maskToken(value)
        null, CredentialFieldKind.PASSWORD -> "••••" // undeclared fields fail closed
    }

    private fun maskToken(value: String): String =
        if (value.length > 8) "••••" + value.takeLast(4) else "••••"
}
