package com.five9th.smagent.oauth.x

import com.fasterxml.jackson.annotation.JsonProperty
import com.five9th.smagent.account.SocialAccountService
import com.five9th.smagent.account.UpdateAccountRequest
import com.five9th.smagent.config.AppProperties
import com.five9th.smagent.domain.AuthMethod
import com.five9th.smagent.domain.Platform
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

// note: like LinkedInOAuthService, some code will probably be moved to a base
// class when OAuth for other platforms is added

/**
 * 3-legged OAuth2 with PKCE (X requires it even for confidential clients) —
 * same shape as the LinkedIn flow in oauth/linkedin:
 *   - when adding an account with the ACCESS_TOKEN login option:
 *       1) SPA calls `POST /api/social-accounts` with a placeholder
 *          accessToken (and no refreshToken) and keeps the returned `id`
 *       2) SPA sends the browser to `/api/oauth/x/authorize/{id}` (top-level
 *          navigation — the session cookie must ride along)
 *   - when refreshing an existing account's connection: straight to step 2.
 *
 * The flow always requests `offline.access`, so X issues a refresh token
 * (access tokens last ~2h); the adapter rotates it on every refresh and the
 * caller persists the new pair (see XAccessTokenAdapter).
 */
@Service
class XOAuthService(
    private val props: AppProperties,
    restClientBuilder: RestClient.Builder,
    private val accountService: SocialAccountService,
) {

    private val restClient = restClientBuilder
        .baseUrl(props.x.baseUrl)
        .build()

    private val mapper = JsonMapper.builder().build()

    private val callbackUrl: String = props.baseUrl + props.x.callbackEndpoint
    private val oauthFinishedRedirectUrl: String = props.baseUrl + "/accounts" // frontend page

    /** The SPA page the OAuth flow lands on — with [error] when it failed. */
    fun finishedRedirectUrl(error: String? = null): String =
        if (error == null) oauthFinishedRedirectUrl
        else oauthFinishedRedirectUrl + "?oauthError=" + urlEncoded(error)

    /**
     * First leg: the URL of X's consent screen. The target account, the state
     * and the PKCE verifier travel together in the session (one attribute
     * keeps them atomic), so the callback path stays constant — one redirect
     * URL to register in the X app console, and a tampered callback path can
     * no longer retarget the token.
     */
    fun createAuthorizationUrl(request: HttpServletRequest, accountId: Long): String {
        validateAccount(accountId)
        val state = randomToken()
        val verifier = randomToken()
        request.getSession(true).setAttribute(PENDING_OAUTH_SESSION_KEY, "$accountId:$state:$verifier")

        return props.x.authorizeUrl + "?" +
            "response_type=code&" +
            "client_id=${props.x.clientId}&" +
            "redirect_uri=${urlEncoded(callbackUrl)}&" +
            "scope=${urlEncoded(props.x.scope)}&" +
            "state=$state&" +
            "code_challenge=${codeChallenge(verifier)}&" +
            "code_challenge_method=S256"
    }

    /** Only the one X login option has an OAuth flow; anything else fails here (400/404). */
    private fun validateAccount(accountId: Long) {
        val account = accountService.get(accountId)
        if (account.platform != Platform.X) {
            throw IllegalArgumentException("Account $accountId is a ${account.platform} account, not X")
        }
        if (account.authMethod != AuthMethod.ACCESS_TOKEN) {
            throw IllegalArgumentException(
                "Login option ${account.authMethod} of account $accountId has no OAuth flow (token login options only)"
            )
        }
    }

    /**
     * Terminal leg: X redirects the browser to [callbackUrl] with `code` +
     * `state`. The account the token belongs to and the PKCE verifier come
     * back from the session (stored at [createAuthorizationUrl]), not from
     * the request.
     *
     * Returns the redirect URL that brings the user back to the SPA.
     */
    fun handleCallback(request: HttpServletRequest, code: String, state: String): String {
        val (accountId, verifier) = consumePendingOAuth(request, state)

        val tokenResponse = exchangeCodeForAccessToken(code, verifier)
        saveTokens(accountId, tokenResponse)

        return oauthFinishedRedirectUrl
    }

    /**
     * Pops the pending "accountId:state:verifier" from the session (single
     * use — a replayed callback finds nothing left) and constant-time
     * -compares the state. Anything off fails as an invalid state.
     */
    private fun consumePendingOAuth(request: HttpServletRequest, returnedState: String): Pair<Long, String> {
        val session = request.getSession(false)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "No active session — restart the authorization")

        val pending = session.getAttribute(PENDING_OAUTH_SESSION_KEY) as? String
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing OAuth state")

        session.removeAttribute(PENDING_OAUTH_SESSION_KEY)

        val parts = pending.split(':', limit = 3)
        val accountId = parts.getOrNull(0)?.toLongOrNull()
        val expectedState = parts.getOrNull(1)
        val verifier = parts.getOrNull(2)
        if (accountId == null || expectedState == null || verifier == null ||
            !MessageDigest.isEqual(expectedState.toByteArray(), returnedState.toByteArray())
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid OAuth state")
        }
        return accountId to verifier
    }

    /**
     * Authorization-code exchange. X is a confidential client here: the app
     * credentials go as HTTP Basic auth, the form carries only the grant
     * (LinkedIn puts clientId/secret in the form instead).
     */
    private fun exchangeCodeForAccessToken(code: String, codeVerifier: String): XTokenResponse {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("code", code)
            // must byte-match the authorize leg's redirect_uri — both are the
            // constant [callbackUrl]
            add("redirect_uri", callbackUrl)
            add("code_verifier", codeVerifier)
        }

        return try {
            restClient.post()
                .uri("/2/oauth2/token")
                .headers { it.setBasicAuth(props.x.clientId, props.x.clientSecret) }
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(XTokenResponse::class.java)
                ?: throw ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "X returned an empty token response"
                )
        } catch (e: RestClientResponseException) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "X token exchange failed (${e.statusCode.value()}): ${extractErrorMessage(e)}"
            )
        } catch (e: RestClientException) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "X token exchange could not be completed: ${e.message}"
            )
        }
    }

    /** X's token endpoint errors are JSON like {"error":"invalid_grant","error_description":"..."}. */
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

    private fun saveTokens(accountId: Long, tokenResponse: XTokenResponse) {
        // the refresh token is the point of the flow (2h access tokens);
        // keep whatever is stored if X didn't issue one this time
        val credentials = buildMap {
            put("accessToken", tokenResponse.accessToken)
            tokenResponse.refreshToken?.let { put("refreshToken", it) }
        }
        accountService.update(accountId, true, UpdateAccountRequest(credentials = credentials))
    }

    data class XTokenResponse(
        @JsonProperty("access_token")
        val accessToken: String,

        @JsonProperty("refresh_token")
        val refreshToken: String? = null,

        @JsonProperty("expires_in")
        val expiresIn: Long? = null,

        val scope: String? = null,
    )

    companion object {
        // session attribute holding "accountId:state:verifier"; one pending
        // OAuth per session — a second authorize overwrites the first
        private const val PENDING_OAUTH_SESSION_KEY = "x_oauth_pending"

        /** 32 random bytes, base64url without padding: 43 URL-safe, colon-free chars. */
        private fun randomToken(): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

        /** S256 PKCE challenge: base64url(SHA-256(verifier)) without padding. */
        private fun codeChallenge(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

        private fun urlEncoded(value: String): String {
            return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
        }
    }
}
