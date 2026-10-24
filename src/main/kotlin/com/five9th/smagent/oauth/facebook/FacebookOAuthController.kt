package com.five9th.smagent.oauth.facebook

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
@RequestMapping("/api/oauth/facebook")
class FacebookOAuthController(
    private val facebookOAuthService: FacebookOAuthService
) {

    @GetMapping("/authorize/{id}")
    fun authorize(
        @PathVariable id: Long,
        servletRequest: HttpServletRequest,
        response: HttpServletResponse
    ) {
        val authorizationUrl = facebookOAuthService.createAuthorizationUrl(servletRequest, id)
        response.sendRedirect(authorizationUrl)
    }

    /**
     * Terminal leg of the 3-legged OAuth, hit by a top-level browser
     * navigation from Facebook — so every failure mode must land the user
     * back in the SPA with an `oauthError` message, never a raw JSON error.
     */
    @GetMapping("/callback")
    fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
        @RequestParam(name = "error_description", required = false)
        errorDescription: String?,
        @RequestParam(name = "error_reason", required = false)
        errorReason: String?,
        servletRequest: HttpServletRequest,
        response: HttpServletResponse
    ) {
        val redirectUrl = try {
            when {
                error != null -> throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Facebook authorization failed: ${errorDescription ?: errorReason ?: error}"
                )

                code.isNullOrBlank() || state.isNullOrBlank() -> throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Missing authorization code or state"
                )

                else -> facebookOAuthService.handleCallback(servletRequest, code, state)
            }
        } catch (e: ResponseStatusException) {
            facebookOAuthService.finishedRedirectUrl(e.reason ?: "Authorization failed")
        } catch (e: NotFoundException) {
            // the account vanished between authorize and callback
            facebookOAuthService.finishedRedirectUrl(e.message ?: "Account not found")
        } catch (e: IllegalArgumentException) {
            // the stored pageId doesn't match the user's pages (or the account
            // changed shape mid-flight) — fixable from the SPA, so still a redirect
            facebookOAuthService.finishedRedirectUrl(e.message ?: "Account update rejected")
        }
        response.sendRedirect(redirectUrl)
    }
}
