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
 * Seeds the single Owner account at startup from configuration
 * (env SMAGENT_ADMIN_USERNAME / SMAGENT_ADMIN_PASSWORD).
 * Fails fast when no owner exists and no password is configured.
 */
@Component
class OwnerSeeder(
    private val users: AppUserRepository,
    private val encoder: PasswordEncoder,
    @Value("\${smagent.admin.username}") private val adminUsername: String,
    @Value("\${smagent.admin.password:}") private val adminPassword: String,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val username = adminUsername.trim().ifEmpty { "admin" }
        if (users.findByUsername(username) != null) {
            log.info("Owner account '{}' already present", username)
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
    }
}
