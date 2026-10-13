package com.five9th.smagent.oauth.linkedin

import com.fasterxml.jackson.annotation.JsonProperty
import com.five9th.smagent.account.SocialAccountService
import com.five9th.smagent.account.UpdateAccountRequest
import com.five9th.smagent.config.AppProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
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

    private val mapper = JsonMapper.builder().build()

    private val callbackUrl: String = props.baseUrl + props.linkedin.callbackEndPoint
    private val oauthFinishedRedirectUrl: String = props.baseUrl + "/accounts" // frontend page

    /** The SPA page the OAuth flow lands on — with [error] when it failed. */
    fun finishedRedirectUrl(error: String? = null): String =
        if (error == null) oauthFinishedRedirectUrl
        else oauthFinishedRedirectUrl + "?oauthError=" + urlEncoded(error)

    /** First step of 3-legged OAuth: user gets redirected to
     * GET https://www.linkedin.com/oauth/v2/authorization?response_type=code&client_id={your_client_id}&redirect_uri={your_callback_url}&state=foobar&scope=liteprofile%20emailaddress%20w_member_social
     *
     * The target account travels with the state in the session (one attribute
     * keeps them atomic), so the callback path stays constant — one redirect
     * URL to register in the LinkedIn app console, and a tampered callback
     * path can no longer retarget the token.
     *  */
    fun createAuthorizationUrl(request: HttpServletRequest, accountId: Long): String {
        val state = createRandomState()
        request.getSession(true).setAttribute(PENDING_OAUTH_SESSION_KEY, "$accountId:$state")

        return buildAuthorizationUrl(state)
    }

    private fun createRandomState(): String {
        return SecureRandom()
            .let { random ->
                ByteArray(32).also(random::nextBytes)
            }
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }

    private fun buildAuthorizationUrl(state: String): String {
        return "https://www.linkedin.com/oauth/v2/authorization?" +
                "response_type=code&" +
                "client_id=${props.linkedin.clientId}&" +
                "redirect_uri=${urlEncoded(callbackUrl)}&" +
                "state=$state&" +
                "scope=${urlEncoded(props.linkedin.scopePersonal)}"
    }

    /** Second step: User approves the app permissions and gets redirected to [callbackUrl] (special endpoint in
     * our smagent app) with params [code] and [state]. The account the token belongs to comes back
     * from the session (stored at [createAuthorizationUrl]), not from the request.
     *
     * Returns redirectUrl that should bring the user back to smagent app */
    fun handleCallback(request: HttpServletRequest, code: String, state: String): String {
        val accountId = consumePendingOAuth(request, state)

        val tokenResponse = exchangeCodeForAccessToken(code)
        saveToken(accountId, tokenResponse)

        return oauthFinishedRedirectUrl
    }

    /** Pops the pending "accountId:state" from the session (single use — a
     * replayed callback finds nothing left) and constant-time-compares the
     * state. Anything off fails as an invalid state. */
    private fun consumePendingOAuth(request: HttpServletRequest, returnedState: String): Long {
        val session = request.getSession(false)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "No active session — restart the authorization")

        val pending = session.getAttribute(PENDING_OAUTH_SESSION_KEY) as? String
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing OAuth state")

        session.removeAttribute(PENDING_OAUTH_SESSION_KEY)

        val parts = pending.split(':', limit = 2)
        val accountId = parts.getOrNull(0)?.toLongOrNull()
        val expectedState = parts.getOrNull(1)
        if (accountId == null || expectedState == null ||
            !MessageDigest.isEqual(expectedState.toByteArray(), returnedState.toByteArray())
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid OAuth state")
        }
        return accountId
    }

     private fun exchangeCodeForAccessToken(code: String): LinkedInTokenResponse {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("code", code)
            add("client_id", props.linkedin.clientId)
            add("client_secret", props.linkedin.clientSecret)
            // must byte-match the authorize leg's redirect_uri — both are the
            // constant [callbackUrl]
            add("redirect_uri", callbackUrl)
        }

        return try {
            restClient.post()
                .uri("/oauth/v2/accessToken")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(LinkedInTokenResponse::class.java)
                ?: throw ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "LinkedIn returned an empty token response"
                )
        } catch (e: RestClientResponseException) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "LinkedIn token exchange failed (${e.statusCode.value()}): ${extractErrorMessage(e)}"
            )
        } catch (e: RestClientException) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "LinkedIn token exchange could not be completed: ${e.message}"
            )
        }
    }

    /** LinkedIn's OAuth errors are JSON like {"error":"invalid_grant","error_description":"..."}. */
    private fun extractErrorMessage(e: RestClientResponseException): String = try {
        val root: JsonNode = mapper.readTree(e.responseBodyAsString)
        root.path("error_description").asStringOrNull()
            ?: root.path("error").asStringOrNull()
            ?: e.statusText
    } catch (_: Exception) {
        e.statusText
    }

    private fun JsonNode.asStringOrNull(): String? =
        if (isNull || isMissingNode) null else asString()

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
        // session attribute holding "accountId:state"; one pending OAuth per
        // session — a second authorize overwrites the first
        private const val PENDING_OAUTH_SESSION_KEY = "linkedin_oauth_state"

        private fun urlEncoded(value: String): String {
            return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
        }
    }
}