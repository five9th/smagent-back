package com.five9th.smagent.config

import com.five9th.smagent.repo.AppUserRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun corsConfigurationSource(props: AppProperties): UrlBasedCorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = props.cors.allowedOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Content-Type")
            allowCredentials = true // session cookie
            maxAge = 3600L
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", config) }
    }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        corsSource: UrlBasedCorsConfigurationSource,
    ): SecurityFilterChain {
        http {
            cors { configurationSource = corsSource }
            // Compensating controls for disabled CSRF: SameSite=Lax session cookie
            // (server.servlet.session.cookie.same-site), JSON-only request bodies,
            // and never mutating on GET. See docs.spring.io CSRF guidance.
            csrf { disable() }
            authorizeHttpRequests {
                authorize("/api/auth/login", permitAll)
                authorize("/h2-console/**", permitAll)
                authorize(anyRequest, authenticated)
            }
            sessionManagement {
                sessionCreationPolicy = SessionCreationPolicy.IF_REQUIRED
            }
            exceptionHandling {
                // 401 without redirect for anonymous API calls
                authenticationEntryPoint = HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)
            }
            headers {
                frameOptions { sameOrigin = true } // H2 console renders in a frame
            }
            logout {
                logoutUrl = "/api/auth/logout"
                logoutSuccessHandler = { _, response, _ ->
                    response.status = HttpStatus.NO_CONTENT.value()
                }
            }
        }
        return http.build()
    }

    @Bean
    fun userDetailsService(users: AppUserRepository): UserDetailsService =
        UserDetailsService { username ->
            users.findByUsername(username)
                ?.let {
                    User.withUsername(it.username)
                        .password(it.passwordHash)
                        .roles(it.role.name)
                        .build()
                }
                ?: throw UsernameNotFoundException("Unknown user: $username")
        }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun authenticationManager(config: AuthenticationConfiguration): AuthenticationManager =
        config.authenticationManager

    /** Used by AuthController to persist the SecurityContext after a JSON login. */
    @Bean
    fun securityContextRepository(): SecurityContextRepository = HttpSessionSecurityContextRepository()
}
