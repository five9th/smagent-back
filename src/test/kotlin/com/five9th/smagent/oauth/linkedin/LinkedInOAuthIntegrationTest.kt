package com.five9th.smagent.oauth.linkedin

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Binds a MockRestServiceServer into the shared RestClient.Builder bean
 * BEFORE the OAuth service builds its client from it, so the token exchange
 * is mockable in a full Spring context (bind-to-builder only affects clients
 * constructed afterwards).
 */
@TestConfiguration
class OAuthMockExchangeConfig {
    val builder = RestClient.builder()
    val server = MockRestServiceServer.bindTo(builder).build()

    @Bean
    @Primary
    fun oauthTestRestClientBuilder(): RestClient.Builder = builder

    @Bean
    fun oauthTestMockServer(): MockRestServiceServer = server
}

/**
 * 3-legged OAuth against MockMvc + a mocked LinkedIn token endpoint. Every
 * failure of the browser-facing callback must end in a redirect to the SPA
 * with an oauthError message, never a JSON error page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OAuthMockExchangeConfig::class)
class LinkedInOAuthIntegrationTest(
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

    private fun createAccount(): Long = createAccount(
        """{"platform":"LINKEDIN","authMethod":"ACCESS_TOKEN",
           "credentials":{"accessToken":"AQVJoldtoken1234","userId":"mem8"}}"""
    )

    private fun createOrgAccount(): Long = createAccount(
        """{"platform":"LINKEDIN","authMethod":"ORGANIZATION_ACCESS_TOKEN",
           "credentials":{"organizationId":"5515715","accessToken":"AQVJoldtoken1234"}}"""
    )

    private fun createAccount(body: String): Long {
        val result = mvc.post("/api/social-accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = body.trimIndent()
        }.andReturn()
        return Regex("\"id\":(\\d+)").find(result.response.contentAsString)!!.groupValues[1].toLong()
    }

    private data class Authorized(val accountId: Long, val state: String, val session: MockHttpSession, val location: String)

    private fun authorize(accountId: Long): Authorized {
        val result = mvc.get("/api/oauth/linkedin/authorize/{id}", accountId)
            .andExpect { status { is3xxRedirection() } }
            .andReturn()
        val location = result.response.getHeader("Location")!!
        val state = Regex("state=([^&]+)").find(location)!!.groupValues[1]
        return Authorized(accountId, state, result.request.getSession(false) as MockHttpSession, location)
    }

    /** The form the token exchange must send (pinned test creds, constant redirect_uri). */
    private fun expectedExchangeForm(code: String): LinkedMultiValueMap<String, String> =
        LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("code", code)
            add("client_id", "test-client-id")
            add("client_secret", "test-client-secret")
            add("redirect_uri", "/api/oauth/linkedin/callback")
        }

    private fun expectTokenExchange(
        code: String,
        response: () -> org.springframework.test.web.client.response.DefaultResponseCreator,
    ) {
        server.expect(requestTo("https://www.linkedin.com/oauth/v2/accessToken"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().formData(expectedExchangeForm(code)))
            .andRespond(response())
    }

    @Test
    fun `authorize and callback require authentication`() {
        mvc.get("/api/oauth/linkedin/authorize/1").andExpect { status { isUnauthorized() } }
        mvc.get("/api/oauth/linkedin/callback") {
            param("code", "c")
            param("state", "s")
        }.andExpect { status { isUnauthorized() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `authorize redirects to LinkedIn with the account bound to the state`() {
        val accountId = createAccount()
        val authed = authorize(accountId)

        assertTrue(authed.location.startsWith("https://www.linkedin.com/oauth/v2/authorization?"), authed.location)
        assertTrue(authed.location.contains("response_type=code"), authed.location)
        assertTrue(authed.location.contains("client_id=test-client-id"), authed.location)
        assertTrue(authed.location.contains("redirect_uri=%2Fapi%2Foauth%2Flinkedin%2Fcallback"), authed.location)
        assertTrue(authed.location.contains("scope=openid%20profile%20w_member_social"), authed.location)

        // the session carries the target account atomically with the state
        assertEquals("$accountId:${authed.state}", authed.session.getAttribute("linkedin_oauth_state"))
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `callback exchanges the code and merges the token into the account`() {
        val accountId = createAccount()
        val authed = authorize(accountId)

        expectTokenExchange("the-code") {
            withSuccess("""{"access_token":"AQVJnewtoken5678","expires_in":5183999}""", MediaType.APPLICATION_JSON)
        }

        mvc.get("/api/oauth/linkedin/callback") {
            session = authed.session
            param("code", "the-code")
            param("state", authed.state)
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/accounts")
        }

        // fresh token merged in, userId survived, status reset
        mvc.get("/api/social-accounts/{id}", accountId).andExpect {
            status { isOk() }
            jsonPath("$.credentialsMasked.accessToken") { value("••••5678") }
            jsonPath("$.credentialsMasked.userId") { value("mem8") }
            jsonPath("$.status") { value("UNVERIFIED") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `authorize picks the company scopes for a company page account`() {
        val authed = authorize(createOrgAccount())

        assertTrue(
            authed.location.contains("scope=rw_organization_admin%20w_organization_social"),
            authed.location,
        )
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `company page callback merges the token and keeps the organizationId`() {
        val accountId = createOrgAccount()
        val authed = authorize(accountId)

        expectTokenExchange("the-code") {
            withSuccess("""{"access_token":"AQVJnewtoken5678","expires_in":5183999}""", MediaType.APPLICATION_JSON)
        }

        mvc.get("/api/oauth/linkedin/callback") {
            session = authed.session
            param("code", "the-code")
            param("state", authed.state)
        }.andExpect { redirectedUrl("/accounts") }

        mvc.get("/api/social-accounts/{id}", accountId).andExpect {
            status { isOk() }
            jsonPath("$.credentialsMasked.accessToken") { value("••••5678") }
            jsonPath("$.credentialsMasked.organizationId") { value("5515715") }
            jsonPath("$.status") { value("UNVERIFIED") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `authorize rejects accounts that cannot use OAuth before leaving the app`() {
        // authorize failures are SPA bugs (JSON errors), unlike the
        // browser-facing callback failures (redirects)
        val manualAccount = createAccount(
            """{"platform":"LINKEDIN","authMethod":"PROFILE_URL",
               "credentials":{"profileUrl":"https://linkedin.com/company/acme"}}"""
        )
        mvc.get("/api/oauth/linkedin/authorize/{id}", manualAccount).andExpect { status { isBadRequest() } }

        val facebookAccount = createAccount(
            """{"platform":"FACEBOOK","authMethod":"PROFILE_URL",
               "credentials":{"profileUrl":"https://facebook.com/my_page"}}"""
        )
        mvc.get("/api/oauth/linkedin/authorize/{id}", facebookAccount).andExpect { status { isBadRequest() } }

        mvc.get("/api/oauth/linkedin/authorize/{id}", 999).andExpect { status { isNotFound() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `callback with a tampered state redirects with an error and never exchanges`() {
        val accountId = createAccount()
        val authed = authorize(accountId)

        // no token-exchange expectation: an unexpected exchange fails the test

        val result = mvc.get("/api/oauth/linkedin/callback") {
            session = authed.session
            param("code", "the-code")
            param("state", "tampered")
        }.andExpect { status { is3xxRedirection() } }.andReturn()

        val location = result.response.getHeader("Location")!!
        assertTrue(location.startsWith("/accounts?oauthError="), location)
        assertTrue(location.contains("Invalid%20OAuth%20state"), location)

        // consumed even on failure — the flow must be restarted
        assertNull(authed.session.getAttribute("linkedin_oauth_state"))
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `callback is single-use - a replay redirects with an error`() {
        val accountId = createAccount()
        val authed = authorize(accountId)

        expectTokenExchange("the-code") {
            withSuccess("""{"access_token":"AQVJnewtoken5678","expires_in":5183999}""", MediaType.APPLICATION_JSON)
        }

        mvc.get("/api/oauth/linkedin/callback") {
            session = authed.session
            param("code", "the-code")
            param("state", authed.state)
        }.andExpect { redirectedUrl("/accounts") }

        // same code+state again: the pending OAuth is gone
        val result = mvc.get("/api/oauth/linkedin/callback") {
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
        val result = mvc.get("/api/oauth/linkedin/callback") {
            param("code", "the-code")
            param("state", "orphan")
        }.andExpect { status { is3xxRedirection() } }.andReturn()

        val location = result.response.getHeader("Location")!!
        assertTrue(location.startsWith("/accounts?oauthError="), location)
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `LinkedIn-reported denial redirects with the description`() {
        val result = mvc.get("/api/oauth/linkedin/callback") {
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
        val result = mvc.get("/api/oauth/linkedin/callback") {
            param("state", "some-state")
        }.andExpect { status { is3xxRedirection() } }.andReturn()

        val location = result.response.getHeader("Location")!!
        assertTrue(location.startsWith("/accounts?oauthError="), location)
        assertTrue(location.contains("Missing%20authorization%20code"), location)
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `a failed token exchange surfaces the LinkedIn message`() {
        val authed = authorize(createAccount())

        expectTokenExchange("stale-code") {
            withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""{"error":"invalid_grant","error_description":"authorization code expired"}""")
        }

        val result = mvc.get("/api/oauth/linkedin/callback") {
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
        val accountId = createAccount()
        val authed = authorize(accountId)

        // authorize validates the account up front; the mid-flight
        // disappearance is the remaining failure path
        mvc.delete("/api/social-accounts/{id}", accountId).andExpect { status { isNoContent() } }

        expectTokenExchange("the-code") {
            withSuccess("""{"access_token":"AQVJnewtoken5678","expires_in":5183999}""", MediaType.APPLICATION_JSON)
        }

        val result = mvc.get("/api/oauth/linkedin/callback") {
            session = authed.session
            param("code", "the-code")
            param("state", authed.state)
        }.andExpect { status { is3xxRedirection() } }.andReturn()

        val location = result.response.getHeader("Location")!!
        assertTrue(location.startsWith("/accounts?oauthError="), location)
        assertTrue(location.contains("Social%20account%20${accountId}%20not%20found"), location)
    }
}
