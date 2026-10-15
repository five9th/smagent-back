package com.five9th.smagent.oauth.x

import com.five9th.smagent.oauth.linkedin.OAuthMockExchangeConfig
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.util.LinkedMultiValueMap
import kotlin.test.assertNull

/**
 * X's 3-legged OAuth (PKCE) against MockMvc + a mocked token endpoint. Reuses
 * the LinkedIn test's mock-exchange configuration — same context cache key,
 * so the shared context (and its mock server) serve both test classes. Every
 * failure of the browser-facing callback must end in a redirect to the SPA
 * with an oauthError message, never a JSON error page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OAuthMockExchangeConfig::class)
class XOAuthIntegrationTest(
    @Autowired val mvc: MockMvc,
    @Autowired val server: MockRestServiceServer,
) {

    @BeforeEach
    fun freshExpectations() {
        // the server is baked into the context-singleton builder — without a
        // reset, expectations can't be added after any test made real requests
        server.reset()
    }

    @AfterEach
    fun allExpectedExchangesHappened() = server.verify()

    private fun createXAccount(): Long = createAccount(
        """{"platform":"X","authMethod":"ACCESS_TOKEN",
           "credentials":{"accessToken":"oldtoken1234","refreshToken":"oldrtok9999"}}"""
    )

    private fun createAccount(body: String): Long {
        val result = mvc.post("/api/social-accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = body.trimIndent()
        }.andReturn()
        return Regex("\"id\":(\\d+)").find(result.response.contentAsString)!!.groupValues[1].toLong()
    }

    private data class Authorized(val accountId: Long, val state: String, val verifier: String, val session: MockHttpSession, val location: String)

    private fun authorize(accountId: Long): Authorized {
        val result = mvc.get("/api/oauth/x/authorize/{id}", accountId)
            .andExpect { status { is3xxRedirection() } }
            .andReturn()
        val location = result.response.getHeader("Location")!!
        val state = Regex("state=([^&]+)").find(location)!!.groupValues[1]
        // "accountId:state:verifier", one atomic session attribute
        val pending = result.request.getSession(false)
            ?.getAttribute("x_oauth_pending") as String?
            ?: error("authorize did not store the pending OAuth in the session")
        val parts = pending.split(':', limit = 3)
        assertTrue(parts[0] == accountId.toString(), pending)
        assertTrue(parts[1] == state, pending)
        return Authorized(accountId, state, parts[2], result.request.getSession(false) as MockHttpSession, location)
    }

    private fun basicAuth(): String =
        "Basic " + Base64.getEncoder().encodeToString("test-client-id:test-client-secret".toByteArray())

    private fun s256(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    /** The form the code exchange must send (Basic auth carries the app credentials). */
    private fun expectedExchangeForm(code: String, verifier: String): LinkedMultiValueMap<String, String> =
        LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("code", code)
            add("redirect_uri", "/api/oauth/x/callback")
            add("code_verifier", verifier)
        }

    private fun expectTokenExchange(
        code: String,
        verifier: String,
        response: () -> org.springframework.test.web.client.response.DefaultResponseCreator,
    ) {
        server.expect(requestTo("https://api.x.com/2/oauth2/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, basicAuth()))
            .andExpect(content().formData(expectedExchangeForm(code, verifier)))
            .andRespond(response())
    }

    @Test
    fun `authorize and callback require authentication`() {
        mvc.get("/api/oauth/x/authorize/1").andExpect { status { isUnauthorized() } }
        mvc.get("/api/oauth/x/callback") {
            param("code", "c")
            param("state", "s")
        }.andExpect { status { isUnauthorized() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `authorize redirects to X with the PKCE challenge bound to the session`() {
        val accountId = createXAccount()
        val authed = authorize(accountId)

        assertTrue(authed.location.startsWith("https://x.com/i/oauth2/authorize?"), authed.location)
        assertTrue(authed.location.contains("response_type=code"), authed.location)
        assertTrue(authed.location.contains("client_id=test-client-id"), authed.location)
        assertTrue(authed.location.contains("redirect_uri=%2Fapi%2Foauth%2Fx%2Fcallback"), authed.location)
        assertTrue(authed.location.contains("scope=tweet.read%20tweet.write%20users.read%20offline.access"), authed.location)
        assertTrue(authed.location.contains("code_challenge_method=S256"), authed.location)

        // the challenge is S256 of the session-held verifier — not the verifier itself
        assertTrue(authed.location.contains("code_challenge=${s256(authed.verifier)}"), authed.location)
        assertTrue(!authed.location.contains("code_challenge=${authed.verifier}"), authed.location)
        assertTrue(authed.verifier.length >= 43, authed.verifier)
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `callback exchanges the code with the verifier and merges both tokens`() {
        val accountId = createXAccount()
        val authed = authorize(accountId)

        expectTokenExchange("the-code", authed.verifier) {
            withSuccess(
                """{"token_type":"bearer","expires_in":7200,"access_token":"newtok5678",
                    "scope":"tweet.read tweet.write users.read offline.access","refresh_token":"newrtok1234"}""",
                MediaType.APPLICATION_JSON,
            )
        }

        mvc.get("/api/oauth/x/callback") {
            session = authed.session
            param("code", "the-code")
            param("state", authed.state)
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/accounts")
        }

        // fresh pair merged in, status reset
        mvc.get("/api/social-accounts/{id}", accountId).andExpect {
            status { isOk() }
            jsonPath("$.credentialsMasked.accessToken") { value("••••5678") }
            jsonPath("$.credentialsMasked.refreshToken") { value("••••1234") }
            jsonPath("$.status") { value("UNVERIFIED") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `callback keeps the stored refresh token when X issues none`() {
        val accountId = createXAccount()
        val authed = authorize(accountId)

        expectTokenExchange("the-code", authed.verifier) {
            withSuccess("""{"token_type":"bearer","expires_in":7200,"access_token":"newtok5678"}""", MediaType.APPLICATION_JSON)
        }

        mvc.get("/api/oauth/x/callback") {
            session = authed.session
            param("code", "the-code")
            param("state", authed.state)
        }.andExpect { redirectedUrl("/accounts") }

        mvc.get("/api/social-accounts/{id}", accountId).andExpect {
            status { isOk() }
            jsonPath("$.credentialsMasked.accessToken") { value("••••5678") }
            jsonPath("$.credentialsMasked.refreshToken") { value("••••9999") } // the pre-flow one
            jsonPath("$.status") { value("UNVERIFIED") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `authorize rejects accounts that cannot use OAuth before leaving the app`() {
        // authorize failures are SPA bugs (JSON errors), unlike the
        // browser-facing callback failures (redirects)
        val manualAccount = createAccount(
            """{"platform":"X","authMethod":"PROFILE_URL",
               "credentials":{"profileUrl":"https://x.com/my_page"}}"""
        )
        mvc.get("/api/oauth/x/authorize/{id}", manualAccount).andExpect { status { isBadRequest() } }

        val facebookAccount = createAccount(
            """{"platform":"FACEBOOK","authMethod":"PROFILE_URL",
               "credentials":{"profileUrl":"https://facebook.com/my_page"}}"""
        )
        mvc.get("/api/oauth/x/authorize/{id}", facebookAccount).andExpect { status { isBadRequest() } }

        mvc.get("/api/oauth/x/authorize/{id}", 999).andExpect { status { isNotFound() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `callback with a tampered state redirects with an error and never exchanges`() {
        val accountId = createXAccount()
        val authed = authorize(accountId)

        // no token-exchange expectation: an unexpected exchange fails the test

        val result = mvc.get("/api/oauth/x/callback") {
            session = authed.session
            param("code", "the-code")
            param("state", "tampered")
        }.andExpect { status { is3xxRedirection() } }.andReturn()

        val location = result.response.getHeader("Location")!!
        assertTrue(location.startsWith("/accounts?oauthError="), location)
        assertTrue(location.contains("Invalid%20OAuth%20state"), location)

        // consumed even on failure — the flow must be restarted
        assertNull(authed.session.getAttribute("x_oauth_pending"))
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `callback is single-use - a replay redirects with an error`() {
        val accountId = createXAccount()
        val authed = authorize(accountId)

        expectTokenExchange("the-code", authed.verifier) {
            withSuccess("""{"token_type":"bearer","expires_in":7200,"access_token":"newtok5678"}""", MediaType.APPLICATION_JSON)
        }

        mvc.get("/api/oauth/x/callback") {
            session = authed.session
            param("code", "the-code")
            param("state", authed.state)
        }.andExpect { redirectedUrl("/accounts") }

        // same code+state again: the pending OAuth is gone
        val result = mvc.get("/api/oauth/x/callback") {
            session = authed.session
            param("code", "the-code")
            param("state", authed.state)
        }.andExpect { status { is3xxRedirection() } }.andReturn()

        val location = result.response.getHeader("Location")!!
        assertTrue(location.startsWith("/accounts?oauthError="), location)
        assertTrue(location.contains("Missing%20OAuth%20state"), location)
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `callback without a prior authorize redirects with an error`() {
        // whether a session exists depends on security-filter session handling;
        // either branch must end in the same SPA redirect shape
        val result = mvc.get("/api/oauth/x/callback") {
            param("code", "the-code")
            param("state", "orphan")
        }.andExpect { status { is3xxRedirection() } }.andReturn()

        val location = result.response.getHeader("Location")!!
        assertTrue(location.startsWith("/accounts?oauthError="), location)
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `X-reported denial redirects with the description`() {
        val result = mvc.get("/api/oauth/x/callback") {
            param("error", "access_denied")
            param("error_description", "the user denied the request")
        }.andExpect { status { is3xxRedirection() } }.andReturn()

        val location = result.response.getHeader("Location")!!
        assertTrue(location.startsWith("/accounts?oauthError="), location)
        assertTrue(location.contains("user%20denied"), location)
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `callback without a code redirects with an error`() {
        val result = mvc.get("/api/oauth/x/callback") {
            param("state", "some-state")
        }.andExpect { status { is3xxRedirection() } }.andReturn()

        val location = result.response.getHeader("Location")!!
        assertTrue(location.startsWith("/accounts?oauthError="), location)
        assertTrue(location.contains("Missing%20authorization%20code"), location)
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `a failed token exchange surfaces the X message`() {
        val authed = authorize(createXAccount())

        expectTokenExchange("stale-code", authed.verifier) {
            withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""{"error":"invalid_grant","error_description":"authorization code expired"}""")
        }

        val result = mvc.get("/api/oauth/x/callback") {
            session = authed.session
            param("code", "stale-code")
            param("state", authed.state)
        }.andExpect { status { is3xxRedirection() } }.andReturn()

        val location = result.response.getHeader("Location")!!
        assertTrue(location.startsWith("/accounts?oauthError="), location)
        assertTrue(location.contains("token%20exchange%20failed%20%28400%29"), location)
        assertTrue(location.contains("authorization%20code%20expired"), location)
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `callback for an account deleted mid-flight redirects with an error`() {
        val accountId = createXAccount()
        val authed = authorize(accountId)

        // authorize validates the account up front; the mid-flight
        // disappearance is the remaining failure path
        mvc.delete("/api/social-accounts/{id}", accountId).andExpect { status { isNoContent() } }

        expectTokenExchange("the-code", authed.verifier) {
            withSuccess("""{"token_type":"bearer","expires_in":7200,"access_token":"newtok5678"}""", MediaType.APPLICATION_JSON)
        }

        val result = mvc.get("/api/oauth/x/callback") {
            session = authed.session
            param("code", "the-code")
            param("state", authed.state)
        }.andExpect { status { is3xxRedirection() } }.andReturn()

        val location = result.response.getHeader("Location")!!
        assertTrue(location.startsWith("/accounts?oauthError="), location)
        assertTrue(location.contains("Social%20account%20${accountId}%20not%20found"), location)
    }
}
