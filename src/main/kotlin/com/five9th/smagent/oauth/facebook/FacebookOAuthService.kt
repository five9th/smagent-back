package com.five9th.smagent.oauth.facebook

import com.fasterxml.jackson.annotation.JsonProperty
import com.five9th.smagent.account.SocialAccountService
import com.five9th.smagent.account.UpdateAccountRequest
import com.five9th.smagent.config.AppProperties
import com.five9th.smagent.domain.AuthMethod
import com.five9th.smagent.domain.Platform
import jakarta.servlet.http.HttpServletRequest
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

// note: like the LinkedIn and X services, some code will probably be moved to
// a base class when OAuth for other platforms is added

/**
 * 3-legged OAuth for the Facebook Page login option — same shape as the
 * LinkedIn/X flows (see oauth/linkedin, oauth/x), but the Login dialog only
 * yields a short-lived USER token. To land the non-expiring Page token the
 * adapter needs, the callback chains (all server-to-server; the client
 * secret never reaches the SPA):
 *
 *   1) code           -> short-lived user token   GET /{v}/oauth/access_token (client_secret in the query — Meta's design)
 *   2) fb_exchange_token -> ~60d long-lived user token (same endpoint, grant_type=fb_exchange_token)
 *   3) GET /{v}/me/accounts with the long-lived user token -> the user's Pages,
 *      each entry carrying its own Page access token; a Page token derived from
 *      a long-lived user token has no expiry. The one matching the account's
 *      stored pageId is merged in as pageAccessToken.
 *
 * The intermediate user token is discarded — the Page token never expires, so
 * re-deriving it later is just a re-run of this flow.
 */
@Service
class FacebookOAuthService(
    private val props: AppProperties,
    restClientBuilder: RestClient.Builder,
    private val accountService: SocialAccountService,
) {

    private val restClient = restClientBuilder
        .baseUrl(props.facebook.baseUrl)
        .build()

    private val mapper = JsonMapper.builder().build()

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass) // TODO temp debug logging, remove

    /**
     * TODO temp debug logging — remove. Logs one Graph GET (full URL incl.
     * secrets) and its raw body, or the error body when it fails, then
     * rethrows so the caller's translation logic is unchanged.
     */
    private fun loggedGet(step: String, url: String, call: () -> String): String {
        log.info("FB OAuth {} request: GET {}", step, url)
        return try {
            val body = call()
            log.info("FB OAuth {} response: {}", step, body)
            body
        } catch (e: RestClientResponseException) {
            log.warn("FB OAuth {} error response: {} {} body={}", step, e.statusCode.value(), e.statusText, e.responseBodyAsString)
            throw e
        }
    }

    private val callbackUrl: String = props.baseUrl + props.facebook.callbackEndpoint
    private val oauthFinishedRedirectUrl: String = props.baseUrl + "/accounts" // frontend page

    /** The SPA page the OAuth flow lands on — with [error] when it failed. */
    fun finishedRedirectUrl(error: String? = null): String =
        if (error == null) oauthFinishedRedirectUrl
        else oauthFinishedRedirectUrl + "?oauthError=" + urlEncoded(error)

    /**
     * First leg: the URL of the Login dialog. The target account travels with
     * the state in the session (one attribute keeps them atomic), so the
     * callback path stays constant — one redirect URL to register in the app
     * console, and a tampered callback path can no longer retarget the token.
     */
    fun createAuthorizationUrl(request: HttpServletRequest, accountId: Long): String {
        validateAccount(accountId)
        val state = randomState()
        request.getSession(true).setAttribute(PENDING_OAUTH_SESSION_KEY, "$accountId:$state")

        // TODO temp debug logging, remove
        val dialogUrl = "https://www.facebook.com/${props.facebook.graphApiVersion}/dialog/oauth?" +
            "client_id=${props.facebook.clientId}&" +
            "redirect_uri=${urlEncoded(callbackUrl)}&" +
            "state=$state&" +
            "scope=${urlEncoded(props.facebook.scope)}&" +
            "response_type=code"
        log.info("FB OAuth authorize request: redirect to {}", dialogUrl) // TODO temp debug logging, remove
        return dialogUrl
    }

    /** Only the Page token login option has an OAuth flow; anything else fails here (400/404). */
    private fun validateAccount(accountId: Long) {
        val account = accountService.get(accountId)
        if (account.platform != Platform.FACEBOOK) {
            throw IllegalArgumentException("Account $accountId is a ${account.platform} account, not Facebook")
        }
        if (account.authMethod != AuthMethod.PAGE_ACCESS_TOKEN) {
            throw IllegalArgumentException(
                "Login option ${account.authMethod} of account $accountId has no OAuth flow (token login options only)"
            )
        }
        // fails as an IllegalArgumentException (JSON 400) before leaving the app —
        // the flow targets the account's stored page, so it must exist
        storedPageId(accountId)
    }

    /**
     * Terminal leg: Facebook redirects the browser to [callbackUrl] with
     * `code` + `state`. The account the token belongs to comes back from the
     * session (stored at [createAuthorizationUrl]), not from the request.
     *
     * Returns the redirect URL that brings the user back to the SPA.
     */
    fun handleCallback(request: HttpServletRequest, code: String, state: String): String {
        log.info("FB OAuth callback: code={} state={}", code, state) // TODO temp debug logging, remove
        val accountId = consumePendingOAuth(request, state)

        val shortLived = exchangeCodeForAccessToken(code)
        val longLived = extendToLongLived(shortLived.accessToken)
        val pageToken = pageTokenForStoredPage(accountId, longLived.accessToken)

        accountService.update(
            accountId,
            true,
            UpdateAccountRequest(credentials = mapOf("pageAccessToken" to pageToken)),
        )
        return oauthFinishedRedirectUrl
    }

    /**
     * Pops the pending "accountId:state" from the session (single use — a
     * replayed callback finds nothing left) and constant-time-compares the
     * state. Anything off fails as an invalid state.
     */
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

    /** Authorization-code exchange: GET with the app secret in the query (Meta's documented form). */
    private fun exchangeCodeForAccessToken(code: String): FacebookTokenResponse = try {
        parseTokenResponse(
            loggedGet(
                "code exchange",
                "${props.facebook.baseUrl}/${props.facebook.graphApiVersion}/oauth/access_token" +
                    "?client_id=${props.facebook.clientId}&redirect_uri=$callbackUrl" +
                    "&client_secret=${props.facebook.clientSecret}&code=$code",
            ) {
                restClient.get()
                    .uri { b ->
                        b.path("/{v}/oauth/access_token")
                            .queryParam("client_id", props.facebook.clientId)
                            .queryParam("redirect_uri", callbackUrl)
                            .queryParam("client_secret", props.facebook.clientSecret)
                            .queryParam("code", code)
                            .build(props.facebook.graphApiVersion)
                    }
                    .retrieve()
                    .body(String::class.java) ?: ""
            }
        )
    } catch (e: RestClientResponseException) {
        throw ResponseStatusException(
            HttpStatus.BAD_GATEWAY,
            "Facebook token exchange failed (${e.statusCode.value()}): ${extractErrorMessage(e)}"
        )
    } catch (e: RestClientException) {
        throw ResponseStatusException(
            HttpStatus.BAD_GATEWAY,
            "Facebook token exchange could not be completed: ${e.message}"
        )
    }

    /** Short-lived user token -> ~60d long-lived user token; Page tokens derived from it don't expire. */
    private fun extendToLongLived(shortLivedToken: String): FacebookTokenResponse = try {
        parseTokenResponse(
            loggedGet(
                "token extension",
                "${props.facebook.baseUrl}/${props.facebook.graphApiVersion}/oauth/access_token" +
                    "?grant_type=fb_exchange_token&client_id=${props.facebook.clientId}" +
                    "&client_secret=${props.facebook.clientSecret}&fb_exchange_token=$shortLivedToken",
            ) {
                restClient.get()
                    .uri { b ->
                        b.path("/{v}/oauth/access_token")
                            .queryParam("grant_type", "fb_exchange_token")
                            .queryParam("client_id", props.facebook.clientId)
                            .queryParam("client_secret", props.facebook.clientSecret)
                            .queryParam("fb_exchange_token", shortLivedToken)
                            .build(props.facebook.graphApiVersion)
                    }
                    .retrieve()
                    .body(String::class.java) ?: ""
            }
        )
    } catch (e: RestClientResponseException) {
        throw ResponseStatusException(
            HttpStatus.BAD_GATEWAY,
            "Facebook token extension failed (${e.statusCode.value()}): ${extractErrorMessage(e)}"
        )
    } catch (e: RestClientException) {
        throw ResponseStatusException(
            HttpStatus.BAD_GATEWAY,
            "Facebook token extension could not be completed: ${e.message}"
        )
    }

    /**
     * The Pages the user administers (`GET /me/accounts`), each with its own
     * Page access token. The stored `pageId` picks the one whose token we
     * keep; a mismatch fails with the pages that were found, so a wrong id is
     * obvious at a glance.
     */
    private fun pageTokenForStoredPage(accountId: Long, longLivedToken: String): String {
        val accounts: JsonNode = try {
            mapper.readTree(
                loggedGet(
                    "page list",
                    "${props.facebook.baseUrl}/${props.facebook.graphApiVersion}/me/accounts" +
                        "?fields=id,name,access_token&access_token=$longLivedToken",
                ) {
                    restClient.get()
                        .uri { b ->
                            b.path("/{v}/me/accounts")
                                .queryParam("fields", "id,name,access_token")
                                .queryParam("access_token", longLivedToken)
                                .build(props.facebook.graphApiVersion)
                        }
                        .retrieve()
                        .body(String::class.java) ?: ""
                }
            )
        } catch (e: RestClientResponseException) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Facebook page list failed (${e.statusCode.value()}): ${extractErrorMessage(e)}"
            )
        } catch (e: RestClientException) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Facebook page list could not be completed: ${e.message}"
            )
        }

        val storedId = storedPageId(accountId)
        val data = accounts.path("data")
        val pages: List<JsonNode> = if (data.isArray) data.toList() else emptyList()
        for (page in pages) {
            if (page.path("id").asString() == storedId) {
                return page.path("access_token").asStringOrNull()
                    ?: throw ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Facebook returned no access token for page $storedId (check the pages_manage_posts permission)"
                    )
            }
        }
        val found = pages.joinToString(", ") { "${it.path("id").asString()} \"${it.path("name").asString()}\"" }
            .ifEmpty { "none" }
        throw IllegalArgumentException(
            "Facebook page $storedId (stored on account $accountId) is not among the pages this user manages: $found"
        )
    }

    /**
     * The pageId the account already carries — the flow targets an existing
     * account, like LinkedIn's. Read through `credentialsMasked`: pageId is an
     * ID-kind field, so it is returned in full (no decrypt path is exposed).
     */
    private fun storedPageId(accountId: Long): String {
        val credentials = accountService.getDto(accountId).credentialsMasked
        return credentials["pageId"]?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw IllegalArgumentException(
                "Account $accountId has no pageId stored — set it before starting the OAuth flow"
            )
    }

    /** Graph errors are JSON like {"error":{"message":"...","type":..,"code":..}}. */
    private fun extractErrorMessage(e: RestClientResponseException): String = try {
        val root: JsonNode = mapper.readTree(e.responseBodyAsString)
        root.path("error").path("message").asStringOrNull()
            ?: root.path("error").path("type").asStringOrNull()
            ?: e.statusText
    } catch (_: Exception) {
        e.statusText
    }

    private fun parseTokenResponse(body: String): FacebookTokenResponse {
        if (body.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Facebook returned an empty token response")
        }
        return mapper.readValue(body, FacebookTokenResponse::class.java)
    }

    private fun JsonNode.asStringOrNull(): String? =
        if (isNull || isMissingNode) null else asString()

    private data class FacebookTokenResponse(
        @JsonProperty("access_token")
        val accessToken: String,

        @JsonProperty("token_type")
        val tokenType: String? = null,

        @JsonProperty("expires_in")
        val expiresIn: Long? = null,
    )

    companion object {
        // session attribute holding "accountId:state"; one pending OAuth per
        // session — a second authorize overwrites the first
        private const val PENDING_OAUTH_SESSION_KEY = "facebook_oauth_pending"

        private fun randomState(): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

        private fun urlEncoded(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
