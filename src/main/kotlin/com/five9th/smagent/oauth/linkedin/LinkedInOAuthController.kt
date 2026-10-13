package com.five9th.smagent.oauth.linkedin

import com.five9th.smagent.common.NotFoundException
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

    /**
     * Terminal leg of the 3-legged OAuth, hit by a top-level browser
     * navigation from LinkedIn — so every failure mode must land the user
     * back in the SPA with an `oauthError` message, never a raw JSON error.
     */
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
        val redirectUrl = try {
            when {
                error != null -> throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "LinkedIn authorization failed: ${errorDescription ?: error}"
                )

                code.isNullOrBlank() || state.isNullOrBlank() -> throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Missing authorization code or state"
                )

                else -> linkedInOAuthService.handleCallback(servletRequest, code, state)
            }
        } catch (e: ResponseStatusException) {
            linkedInOAuthService.finishedRedirectUrl(e.reason ?: "Authorization failed")
        } catch (e: NotFoundException) {
            // the account vanished between authorize and callback
            linkedInOAuthService.finishedRedirectUrl(e.message ?: "Account not found")
        } catch (e: IllegalArgumentException) {
            // the account's login option changed shape mid-flight (merge validation)
            linkedInOAuthService.finishedRedirectUrl(e.message ?: "Account update rejected")
        }
        response.sendRedirect(redirectUrl)
    }
}
