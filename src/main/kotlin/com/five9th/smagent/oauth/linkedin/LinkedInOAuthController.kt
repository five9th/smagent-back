package com.five9th.smagent.oauth.linkedin

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/oauth/linkedin")
class LinkedInOAuthController(
    private val linkedInOAuthService: LinkedInOAuthService
) {

    @GetMapping("/authorize/{id}")
    fun authorize(
        @PathVariable id: Long,
        servletRequest: HttpServletRequest,
        response: HttpServletResponse
    ) {
        val authorizationUrl = linkedInOAuthService.createAuthorizationUrl(servletRequest, id)
        response.sendRedirect(authorizationUrl)
    }

    @GetMapping("/callback")
    fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
        @RequestParam(name = "error_description", required = false)
        errorDescription: String?,
        servletRequest: HttpServletRequest,
        response: HttpServletResponse
    ) {
        if (error != null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "LinkedIn authorization failed: ${errorDescription ?: error}"
            )
        }

        if (code.isNullOrBlank() || state.isNullOrBlank()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Missing authorization code or state"
            )
        }

        // the target account comes from the session (bound to the state at
        // authorize time), not from the request
        val redirectUrl = linkedInOAuthService.handleCallback(servletRequest, code, state)
        response.sendRedirect(redirectUrl)
    }
}