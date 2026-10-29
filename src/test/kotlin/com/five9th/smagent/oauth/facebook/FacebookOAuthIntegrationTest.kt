package com.five9th.smagent.oauth.facebook

import com.five9th.smagent.oauth.linkedin.OAuthMockExchangeConfig
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * Facebook's 3-legged OAuth against MockMvc + mocked Graph endpoints (the
 * code exchange, the long-lived extension and /me/accounts are all GETs).
 * Reuses the LinkedIn test's mock-exchange configuration — same context cache
 * key, so the shared context (and its mock server) serve all OAuth test
 * classes. Every failure of the browser-facing callback must end in a
 * redirect to the SPA with an oauthError message, never a JSON error page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OAuthMockExchangeConfig::class)
class FacebookOAuthIntegrationTest(
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

    private val pageId = "111222333"

    private fun createAccount(body: String): Long {
        val result = mvc.post("/api/social-accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = body.trimIndent()
        }.andReturn()
        return Regex("\"id\":(\\d+)").find(result.response.contentAsString)!!.groupValues[1].toLong()
    }

    private fun createFacebookAccount(pageId: String = this.pageId): Long = createAccount(
        """{"platform":"FACEBOOK","authMethod":"PAGE_ACCESS_TOKEN",
           "credentials":{"pageId":"$pageId","pageAccessToken":"oldtoken1234"}}"""
    )

    private data class Authorized(val accountId: Long, val state: String, val session: MockHttpSession, val location: String)

    private fun authorize(accountId: Long): Authorized {
        val result = mvc.get("/api/oauth/facebook/authorize/{id}", accountId)
            .andExpect { status { is3xxRedirection() } }
            .andReturn()
        val location = result.response.getHeader("Location")!!
        val state = Regex("state=([^&]+)").find(location)!!.groupValues[1]
        // "accountId:state", one atomic session attribute
        val pending = result.request.getSession(false)
            ?.getAttribute("facebook_oauth_pending") as String?
            ?: error("authorize did not store the pending OAuth in the session")
        assertTrue(pending == "$accountId:$state", pending)
        return Authorized(accountId, state, result.request.getSession(false) as MockHttpSession, location)
    }

    /** The full happy-path chain: code exchange -> extension -> page lookup. */
    private fun expectTokenChain(code: String) {
        server.expect(
            requestTo(
                "https://graph.facebook.com/v26.0/oauth/access_token" +
                    "?client_id=test-client-id" +
                    "&redirect_uri=/api/oauth/facebook/callback" +
                    "&client_secret=test-client-secret" +
                    "&code=$code"
            )
        )
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess("""{"access_token":"SHORTTOKEN123","token_type":"bearer","expires_in":5180}""", MediaType.APPLICATION_JSON)
            )
        server.expect(
            requestTo(
                "https://graph.facebook.com/v26.0/oauth/access_token" +
                    "?grant_type=fb_exchange_token" +
                    "&client_id=test-client-id" +
                    "&client_secret=test-client-secret" +
                    "&fb_exchange_token=SHORTTOKEN123"
            )
        )
            .andRespond(
                withSuccess("""{"access_token":"LONGUSERTOKEN456","token_type":"bearer","expires_in":5184000}""", MediaType.APPLICATION_JSON)
            )
        server.expect(
            requestTo(
                "https://graph.facebook.com/v26.0/me/accounts" +
                    "?fields=id,name,access_token" +
                    "&access_token=LONGUSERTOKEN456"
            )
        )
            .andRespond(
                withSuccess(
                    """{"data":[{"id":"$pageId","name":"My Page","access_token":"PAGE12345678","category":"Local business"}]}""",
                    MediaType.APPLICATION_JSON,
                )
            )
    }

    @Test
    fun `authorize and callback require authentication`() {
        mvc.get("/api/oauth/facebook/authorize/1").andExpect { status { isUnauthorized() } }
        mvc.get("/api/oauth/facebook/callback") {
            param("code", "c")
            param("state", "s")
        }.andExpect { status { isUnauthorized() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `authorize redirects to the Login dialog with the account bound to the state`() {
        val accountId = createFacebookAccount()
        val authed = authorize(accountId)

        assertTrue(authed.location.startsWith("https://www.facebook.com/v26.0/dialog/oauth?"), authed.location)
        assertTrue(authed.location.contains("client_id=test-client-id"), authed.location)
        assertTrue(authed.location.contains("redirect_uri=%2Fapi%2Foauth%2Ffacebook%2Fcallback"), authed.location)
        assertTrue(
            authed.location.contains("scope=pages_show_list%2Cpages_manage_posts%2Cpages_read_engagement"),
            authed.location,
        )
        assertTrue(authed.location.contains("response_type=code"), authed.location)
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `callback chains the token exchanges and merges the page token`() {
        val accountId = createFacebookAccount()
        val authed = authorize(accountId)

        expectTokenChain("the-code")

        mvc.get("/api/oauth/facebook/callback") {
            session = authed.session
            param("code", "the-code")
            param("state", authed.state)
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/accounts")
        }

        // permanent page token merged in, pageId kept, status reset
        mvc.get("/api/social-accounts/{id}", accountId).andExpect {
            status { isOk() }
            jsonPath("$.credentialsMasked.pageAccessToken") { value("••••5678") }
            jsonPath("$.credentialsMasked.pageId") { value(pageId) }
            jsonPath("$.status") { value("UNVERIFIED") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `callback with a mismatched pageId lists the pages the user manages`() {
        val accountId = createFacebookAccount(pageId = "999888777")
        val authed = authorize(accountId)

        expectTokenChain("the-code")

        val result = mvc.get("/api/oauth/facebook/callback") {
            session = authed.session
            param("code", "the-code")
            param("state", authed.state)
        }.andExpect { status { is3xxRedirection() } }.andReturn()

        val location = result.response.getHeader("Location")!!
        assertTrue(location.startsWith("/accounts?oauthError="), location)
        assertTrue(location.contains("not%20among%20the%20pages"), location)
        assertTrue(location.contains("My%20Page"), location) // the found pages are listed
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `authorize rejects accounts that cannot use OAuth before leaving the app`() {
        // authorize failures are SPA bugs (JSON errors), unlike the
        // browser-facing callback failures (redirects)
        val manualAccount = createAccount(
            """{"platform":"FACEBOOK","authMethod":"PROFILE_URL",
               "credentials":{"profileUrl":"https://facebook.com/my_page"}}"""
        )
        mvc.get("/api/oauth/facebook/authorize/{id}", manualAccount).andExpect { status { isBadRequest() } }

        val xAccount = createAccount(
            """{"platform":"X","authMethod":"ACCESS_TOKEN",
               "credentials":{"accessToken":"tok","refreshToken":"rt"}}"""
        )
        mvc.get("/api/oauth/facebook/authorize/{id}", xAccount).andExpect { status { isBadRequest() } }

        mvc.get("/api/oauth/facebook/authorize/{id}", 999).andExpect { status { isNotFound() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `callback with a tampered state redirects with an error and never exchanges`() {
        val accountId = createFacebookAccount()
        val authed = authorize(accountId)

        // no token-exchange expectations: an unexpected exchange fails the test

        val result = mvc.get("/api/oauth/facebook/callback") {
            session = authed.session
            param("code", "the-code")
            param("state", "tampered")
        }.andExpect { status { is3xxRedirection() } }.andReturn()

        val location = result.response.getHeader("Location")!!
        assertTrue(location.startsWith("/accounts?oauthError="), location)
        assertTrue(location.contains("Invalid%20OAuth%20state"), location)

        // consumed even on failure — the flow must be restarted
        assertNull(authed.session.getAttribute("facebook_oauth_pending"))
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `callback is single-use - a replay redirects with an error`() {
        val accountId = createFacebookAccount()
        val authed = authorize(accountId)

        expectTokenChain("the-code")

        mvc.get("/api/oauth/facebook/callback") {
            session = authed.session
            param("code", "the-code")
            param("state", authed.state)
        }.andExpect { redirectedUrl("/accounts") }

        // same code+state again: the pending OAuth is gone
        val result = mvc.get("/api/oauth/facebook/callback") {
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
        val result = mvc.get("/api/oauth/facebook/callback") {
            param("code", "the-code")
            param("state", "orphan")
        }.andExpect { status { is3xxRedirection() } }.andReturn()

        val location = result.response.getHeader("Location")!!
        assertTrue(location.startsWith("/accounts?oauthError="), location)
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `Facebook-reported denial redirects with the reason`() {
        // Facebook sends error + error_reason (+ error_description) on cancel
        val result = mvc.get("/api/oauth/facebook/callback") {
            param("error", "access_denied")
            param("error_reason", "user_denied")
        }.andExpect { status { is3xxRedirection() } }.andReturn()

        val location = result.response.getHeader("Location")!!
        assertTrue(location.startsWith("/accounts?oauthError="), location)
        assertTrue(location.contains("user_denied"), location)
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `callback without a code redirects with an error`() {
        val result = mvc.get("/api/oauth/facebook/callback") {
            param("state", "some-state")
        }.andExpect { status { is3xxRedirection() } }.andReturn()

        val location = result.response.getHeader("Location")!!
        assertTrue(location.startsWith("/accounts?oauthError="), location)
        assertTrue(location.contains("Missing%20authorization%20code"), location)
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `a failed token exchange surfaces the Graph message`() {
        val authed = authorize(createFacebookAccount())

        server.expect(
            requestTo(
                "https://graph.facebook.com/v26.0/oauth/access_token" +
                    "?client_id=test-client-id" +
                    "&redirect_uri=/api/oauth/facebook/callback" +
                    "&client_secret=test-client-secret" +
                    "&code=stale-code"
            )
        )
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"message":"This authorization code has expired.","type":"OAuthException","code":100}}""")
            )

        val result = mvc.get("/api/oauth/facebook/callback") {
            session = authed.session
            param("code", "stale-code")
            param("state", authed.state)
        }.andExpect { status { is3xxRedirection() } }.andReturn()

        val location = result.response.getHeader("Location")!!
        assertTrue(location.startsWith("/accounts?oauthError="), location)
        assertTrue(location.contains("token%20exchange%20failed%20%28400%29"), location)
        assertTrue(location.contains("authorization%20code%20has%20expired"), location)
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `a failed long-lived extension surfaces the Graph message`() {
        val authed = authorize(createFacebookAccount())

        server.expect(
            requestTo(
                "https://graph.facebook.com/v26.0/oauth/access_token" +
                    "?client_id=test-client-id" +
                    "&redirect_uri=/api/oauth/facebook/callback" +
                    "&client_secret=test-client-secret" +
                    "&code=the-code"
            )
        )
            .andRespond(
                withSuccess("""{"access_token":"SHORTTOKEN123","token_type":"bearer","expires_in":5180}""", MediaType.APPLICATION_JSON)
            )
        server.expect(
            requestTo(
                "https://graph.facebook.com/v26.0/oauth/access_token" +
                    "?grant_type=fb_exchange_token" +
                    "&client_id=test-client-id" +
                    "&client_secret=test-client-secret" +
                    "&fb_exchange_token=SHORTTOKEN123"
            )
        )
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"message":"Invalid OAuth access token.","type":"OAuthException","code":190}}""")
            )

        val result = mvc.get("/api/oauth/facebook/callback") {
            session = authed.session
            param("code", "the-code")
            param("state", authed.state)
        }.andExpect { status { is3xxRedirection() } }.andReturn()

        val location = result.response.getHeader("Location")!!
        assertTrue(location.startsWith("/accounts?oauthError="), location)
        assertTrue(location.contains("token%20extension%20failed%20%28400%29"), location)
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `callback for an account deleted mid-flight redirects with an error`() {
        val accountId = createFacebookAccount()
        val authed = authorize(accountId)

        // authorize validates the account up front; the mid-flight
        // disappearance is the remaining failure path
        mvc.delete("/api/social-accounts/{id}", accountId).andExpect { status { isNoContent() } }

        expectTokenChain("the-code")

        val result = mvc.get("/api/oauth/facebook/callback") {
            session = authed.session
            param("code", "the-code")
            param("state", authed.state)
        }.andExpect { status { is3xxRedirection() } }.andReturn()

        val location = result.response.getHeader("Location")!!
        assertTrue(location.startsWith("/accounts?oauthError="), location)
        assertTrue(location.contains("Social%20account%20${accountId}%20not%20found"), location)
    }
}
