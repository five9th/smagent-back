package com.five9th.smagent.account

import com.five9th.smagent.common.NotFoundException
import com.five9th.smagent.crypto.CryptoService
import com.five9th.smagent.domain.AccountStatus
import com.five9th.smagent.domain.Platform
import com.five9th.smagent.domain.SocialAccount
import com.five9th.smagent.platform.CredentialParser
import com.five9th.smagent.platform.PlatformException
import com.five9th.smagent.platform.SocialPlatformAdapter
import com.five9th.smagent.repo.SocialAccountRepository
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper

@Service
class SocialAccountService(
    private val accounts: SocialAccountRepository,
    private val crypto: CryptoService,
    private val adapters: List<SocialPlatformAdapter>,
) {
    private val mapper = JsonMapper.builder().build()

    fun list(): List<AccountDto> =
        accounts.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).map { toDto(it) }

    fun get(id: Long): SocialAccount =
        accounts.findById(id).orElseThrow { NotFoundException("Social account $id not found") }

    fun getDto(id: Long): AccountDto = toDto(get(id))

    fun create(request: CreateAccountRequest): AccountDto {
        val adapter = adapterFor(request.platform)
        validateCredentials(adapter, request.credentials)
        val account = SocialAccount(
            platform = request.platform,
            displayName = request.displayName?.trim()?.takeIf { it.isNotEmpty() }
                ?: "${request.platform} account",
            credentialsEnc = crypto.encrypt(mapper.writeValueAsString(request.credentials)),
        )
        return toDto(accounts.save(account))
    }

    fun update(id: Long, request: UpdateAccountRequest): AccountDto {
        val account = get(id)
        request.displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { account.displayName = it }
        request.credentials?.let {
            validateCredentials(adapterFor(account.platform), it)
            account.credentialsEnc = crypto.encrypt(mapper.writeValueAsString(it))
            account.status = AccountStatus.UNVERIFIED
        }
        return toDto(accounts.save(account))
    }

    fun delete(id: Long) {
        accounts.delete(get(id))
    }

    /** Cheap "who am I" call against the platform; updates status and displayName. */
    fun verify(id: Long): VerifyResponse {
        val account = get(id)
        val adapter = adapterFor(account.platform)
        return try {
            val result = adapter.verify(crypto.decrypt(account.credentialsEnc))
            account.displayName = result.displayName
            account.status = AccountStatus.ACTIVE
            accounts.save(account)
            VerifyResponse(toDto(account), ok = true, message = "Credentials verified")
        } catch (e: PlatformException) {
            account.status = AccountStatus.INVALID
            accounts.save(account)
            VerifyResponse(toDto(account), ok = false, message = e.message ?: "Verification failed")
        }
    }

    private fun adapterFor(platform: Platform): SocialPlatformAdapter =
        adapters.firstOrNull { it.platform == platform }
            ?: throw IllegalArgumentException("Unsupported platform: $platform")

    private fun validateCredentials(adapter: SocialPlatformAdapter, credentials: Map<String, String>) {
        val missing = adapter.requiredCredentialFields.filter {
            credentials[it].isNullOrBlank()
        }
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException(
                "Missing credential field(s) for ${adapter.platform}: ${missing.joinToString(", ")}"
            )
        }
    }

    private fun toDto(account: SocialAccount): AccountDto = AccountDto(
        id = account.id,
        platform = account.platform,
        displayName = account.displayName,
        status = account.status,
        credentialsMasked = maskCredentials(account),
        createdAt = account.createdAt,
    )

    private fun maskCredentials(account: SocialAccount): Map<String, String> =
        CredentialParser.parseAll(crypto.decrypt(account.credentialsEnc))
            .mapValues { (_, value) -> mask(value) }

    private fun mask(value: String): String =
        if (value.length > 8) "••••" + value.takeLast(4) else "••••"
}
