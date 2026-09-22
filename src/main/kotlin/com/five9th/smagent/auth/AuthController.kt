package com.five9th.smagent.auth

import com.five9th.smagent.repo.AppUserRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authenticationManager: AuthenticationManager,
    private val users: AppUserRepository,
    private val contextRepository: SecurityContextRepository,
) {
    private val holderStrategy = SecurityContextHolder.getContextHolderStrategy()

    data class LoginRequest(val username: String, val password: String)
    data class UserDto(val id: Long?, val username: String, val role: String)

    @PostMapping("/login")
    fun login(
        @RequestBody request: LoginRequest,
        servletRequest: HttpServletRequest,
        servletResponse: HttpServletResponse,
    ): UserDto {
        val token = UsernamePasswordAuthenticationToken.unauthenticated(request.username, request.password)
        val authentication = authenticationManager.authenticate(token) // throws on bad credentials

        // A custom login endpoint bypasses the session-fixation protection that
        // authentication filters apply, so create (if absent) and rotate the
        // session id ourselves before storing the context.
        servletRequest.getSession(true)
        servletRequest.changeSessionId()

        val context = holderStrategy.createEmptyContext()
        context.authentication = authentication
        holderStrategy.context = context
        contextRepository.saveContext(context, servletRequest, servletResponse)
        return toDto(authentication)
    }

    @GetMapping("/me")
    fun me(authentication: Authentication): UserDto = toDto(authentication)

    @ExceptionHandler(AuthenticationException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun onAuthenticationFailure(e: AuthenticationException): Map<String, String> =
        mapOf("message" to (e.message ?: "Authentication failed"))

    private fun toDto(authentication: Authentication): UserDto {
        val user = users.findByUsername(authentication.name)
        return UserDto(
            id = user?.id,
            username = authentication.name,
            role = user?.role?.name ?: "OWNER",
        )
    }
}
