package com.five9th.smagent.oauth.linkedin

import com.fasterxml.jackson.annotation.JsonProperty
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


@Service
class LinkedInOAuthService(
    private val props: AppProperties,
    private val restClientBuilder: RestClient.Builder,
) {

    /** First step of 3-legged OAuth: user gets redirected to
     * GET https://www.linkedin.com/oauth/v2/authorization?response_type=code&client_id={your_client_id}&redirect_uri={your_callback_url}&state=foobar&scope=liteprofile%20emailaddress%20w_member_social
     *  */
    fun createAuthorizationUrl(request: HttpServletRequest): String {
        val state = createRandomState()
        saveStateToSession(request, state)

        return buildAuthorizationUrl(state)
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

    private fun buildAuthorizationUrl(state: String): String {
        return "https://www.linkedin.com/oauth/v2/authorization?" +
                "response_type=code&" +
                "client_id=${props.linkedin.clientId}&" +
                "redirect_uri=${urlEncoded(callbackUrl)}&" +
                "state=$state&" +
                "scope=${urlEncoded(props.linkedin.scopePersonal)}"
    }

    private val callbackUrl: String = props.baseUrl + props.linkedin.callbackEndPoint


    /** Second step: User approves the app permissions and gets redirected to [callbackUrl] (our smagent app)
     * with params [code] and [state]
     *
     * Returns redirectUrl that should bring the user back to smagent app ??? */
    fun handleCallback(request: HttpServletRequest, code: String, state: String): String {
        validateState(request, state) // throws an ex if something's wrong

        val resp = exchangeCodeForAccessToken(code)
        //todo: we have the token

        return callbackUrl
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

    private val restClient = restClientBuilder
        .baseUrl("https://www.linkedin.com")
        .build()

    fun exchangeCodeForAccessToken(code: String): LinkedInTokenResponse {
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