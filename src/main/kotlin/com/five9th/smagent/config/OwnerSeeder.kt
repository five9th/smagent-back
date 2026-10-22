package com.five9th.smagent.config

import com.five9th.smagent.domain.AppUser
import com.five9th.smagent.repo.AppUserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

/**
 * Owner seeding / sync at startup, from configuration
 * (env SMAGENT_ADMIN_USERNAME / SMAGENT_ADMIN_PASSWORD).
 *
 * - No owner yet: created from env; fails fast if no password is set.
 * - Owner exists and env password is set: env is the source of truth — the
 *   stored hash is updated when it differs (unset env never wipes the password).
 * - Env username doesn't match the existing owner: the existing owner wins and
 *   the env value is ignored with a warning (no second account is created).
 */
@Component
class OwnerSeeder(
    private val users: AppUserRepository,
    private val encoder: PasswordEncoder,
    @Value("\${smagent.admin.username}") private val adminUsername: String,
    @Value("\${smagent.admin.password:}") private val adminPassword: String,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) = seed()

    fun seed() {
        val username = adminUsername.trim().ifEmpty { "admin" }
        val existing = users.findByUsername(username)

        if (existing == null) {
            if (users.count() > 0) {
                log.warn(
                    "Owner already exists with a different username; SMAGENT_ADMIN_USERNAME='{}' ignored",
                    username,
                )
                return
            }
            if (adminPassword.isBlank()) {
                throw IllegalStateException(
                    "No owner account exists and SMAGENT_ADMIN_PASSWORD is not set. " +
                        "Export SMAGENT_ADMIN_PASSWORD (and optionally SMAGENT_ADMIN_USERNAME), then restart."
                )
            }
            val passwordHash = encoder.encode(adminPassword)
                ?: throw RuntimeException("Encoder error: returned hash is null.")
            users.save(AppUser(username = username, passwordHash = passwordHash))
            log.info("Seeded owner account '{}'", username)
            return
        }

        // Owner exists: keep the stored hash in sync with the env password while it is set
        if (adminPassword.isNotBlank() && !encoder.matches(adminPassword, existing.passwordHash)) {
            val passwordHash = encoder.encode(adminPassword)
                ?: throw RuntimeException("Encoder error: returned hash is null.")
            existing.passwordHash = passwordHash
            users.save(existing)
            log.warn("Owner password differs from SMAGENT_ADMIN_PASSWORD — stored hash updated to match env")
        }
    }
}
