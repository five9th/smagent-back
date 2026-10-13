package com.five9th.smagent.oauth.linkedin

import com.fasterxml.jackson.annotation.JsonProperty
import com.five9th.smagent.account.SocialAccountService
import com.five9th.smagent.account.UpdateAccountRequest
import com.five9th.smagent.config.AppProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.server.ResponseStatusException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*


// TODO: right now it is for personal profile only, need to add company page too

// note: Some code will probably be moved to a base class when OAuth for other platforms added

/**
 * (this should be moved to the docs)
 * 3-legged OAuth (user POV):
 *     - when adding an account with a supported *TOKEN AuthMethod:
 *         - user enters LinkedIn user / page ID (optional for user, required for company page)
 *         SPA has a field for a token and a button: 'get token with OAuth'. When clicked:
 *         1) calls `POST /api/social-accounts` to create an account with entered LinkedIn id and empty/placeholder token value and gets created account's `id` in response
 *         2) calls `/api/oauth/<platform>/authorize/{id}` to init the OAuth process. `id` - id of the account created with `/social-accounts`
 *     - when updating a token for an existing account:
 *         - same but go strait to step 2
 **/

@Service
class LinkedInOAuthService(
    private val props: AppProperties,
    restClientBuilder: RestClient.Builder,
    private val accountService: SocialAccountService // is it ok architecturally ??
) {

    private val restClient = restClientBuilder
        .baseUrl("https://www.linkedin.com")
        .build()

    private val callbackUrl: String = props.baseUrl + props.linkedin.callbackEndPoint
    private val oauthFinishedRedirectUrl: String = props.baseUrl + "/accounts" // frontend page

    /** First step of 3-legged OAuth: user gets redirected to
     * GET https://www.linkedin.com/oauth/v2/authorization?response_type=code&client_id={your_client_id}&redirect_uri={your_callback_url}&state=foobar&scope=liteprofile%20emailaddress%20w_member_social
     *  */
    fun createAuthorizationUrl(request: HttpServletRequest, accountId: Long): String {
        val state = createRandomState()
        saveStateToSession(request, state)

        return buildAuthorizationUrl(accountId, state)
    }

    private fun createRandomState(): String {
        return SecureRandom()
            .let { random ->
                ByteArray(32).also(random::nextBytes)
            }
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }
    private fun saveStateToSession(request: HttpServletRequest, state: String) {
        request.getSession(true).setAttribute(STATE_SESSION_KEY, state)
    }

    private fun buildAuthorizationUrl(accountId: Long, state: String): String {
        return "https://www.linkedin.com/oauth/v2/authorization?" +
                "response_type=code&" +
                "client_id=${props.linkedin.clientId}&" +
                "redirect_uri=${urlEncoded("$callbackUrl/$accountId")}&" +
                "state=$state&" +
                "scope=${urlEncoded(props.linkedin.scopePersonal)}"
    }

    /** Second step: User approves the app permissions and gets redirected to [callbackUrl] (special endpoint in
     * our smagent app) with params [code] and [state]
     *
     * Returns redirectUrl that should bring the user back to smagent app */
    fun handleCallback(request: HttpServletRequest, accountId: Long, code: String, state: String): String {
        validateState(request, state) // throws an exception if something's wrong

        val tokenResponse = exchangeCodeForAccessToken(code)
        saveToken(accountId, tokenResponse)

        return oauthFinishedRedirectUrl
    }

    private fun validateState(
        request: HttpServletRequest,
        returnedState: String
    ) {
        val session = request.getSession(false)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

        val expectedState = session.getAttribute(STATE_SESSION_KEY) as? String
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing OAuth state")

        session.removeAttribute(STATE_SESSION_KEY)

        if (!MessageDigest.isEqual(
                expectedState.toByteArray(),
                returnedState.toByteArray()
            )) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid OAuth state")
        }
    }

     private fun exchangeCodeForAccessToken(code: String): LinkedInTokenResponse {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("code", code)
            add("client_id", props.linkedin.clientId)
            add("client_secret", props.linkedin.clientSecret)
            add("redirect_uri", callbackUrl) // not sure (todo: test)
        }

        return restClient.post()
            .uri("/oauth/v2/accessToken")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(LinkedInTokenResponse::class.java)
            ?: error("LinkedIn returned an empty token response")
    }

    private fun saveToken(accountId: Long, tokenResponse: LinkedInTokenResponse) {
        // rn only save 'access_token', 'refresh_token' will be added later
        val request = UpdateAccountRequest(
            credentials = mapOf("accessToken" to tokenResponse.accessToken)
        )

        accountService.update(accountId, true, request)
    }

    data class LinkedInTokenResponse(
        @JsonProperty("access_token")
        val accessToken: String,

        @JsonProperty("expires_in")
        val expiresIn: Long,

        @JsonProperty("refresh_token")
        val refreshToken: String? = null,

        @JsonProperty("refresh_token_expires_in")
        val refreshTokenExpiresIn: Long? = null,

        val scope: String? = null,
    )


    companion object {
        private const val STATE_SESSION_KEY = "linkedin_oauth_state"

        private fun urlEncoded(value: String): String {
            return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
        }
    }
}