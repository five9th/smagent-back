package com.five9th.smagent.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Prints the effective runtime settings once at startup, so a running instance
 * can be identified from its console/log without digging through config.
 *
 * NOTE: the admin password is printed in clear text on purpose — this is a
 * single-owner tool and the log lives on the owner's machine. Remove that line
 * before ever exposing logs elsewhere.
 */
@Component
class StartupSettingsLogger(
    private val props: AppProperties,
    private val environment: Environment,
    @Value("\${smagent.admin.username}") private val adminUsername: String,
    @Value("\${smagent.admin.password:}") private val adminPassword: String,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val port = environment.getProperty("server.port", Int::class.java, 8080)
        val baseUrl = props.baseUrl.ifBlank { "http://localhost:$port" }

        val h2Enabled = environment.getProperty("spring.h2.console.enabled", Boolean::class.java, false)
        val h2Path = environment.getProperty("spring.h2.console.path", "/h2-console")
        val h2Console = if (h2Enabled) "$baseUrl$h2Path" else "(disabled)"

        //todo: mb not log this in prod
        val password = adminPassword.ifBlank { "(not set — owner already seeded)" }

        val cors = props.cors.allowedOrigins.joinToString()

        log.info(
            "SMAgent settings:\n" +
                "  base url:       {}\n" +
                "  H2 console:     {}\n" +
                "  admin username: {}\n" +
                "  admin password: {}\n\n" +
                "  allowed origins: {}",
            baseUrl, h2Console, adminUsername, password, cors
        )
    }
}
