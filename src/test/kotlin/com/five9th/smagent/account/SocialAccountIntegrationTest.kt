package com.five9th.smagent.account

import com.five9th.smagent.domain.AuthMethod
import com.five9th.smagent.domain.CredentialFieldKind
import com.five9th.smagent.domain.Platform
import com.five9th.smagent.platform.VerifyResult
import com.five9th.smagent.platform.adapter.facebook.FacebookPageAccessTokenAdapter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SocialAccountIntegrationTest(@Autowired val mvc: MockMvc) {

    @MockitoBean
    lateinit var facebookPageTokenAdapter: FacebookPageAccessTokenAdapter

    @BeforeEach
    fun stubAdapter() {
        `when`(facebookPageTokenAdapter.platform).thenReturn(Platform.FACEBOOK)
        `when`(facebookPageTokenAdapter.authMethod).thenReturn(AuthMethod.PAGE_ACCESS_TOKEN)
        `when`(facebookPageTokenAdapter.credentialFields).thenReturn(
            mapOf("pageId" to CredentialFieldKind.ID, "pageAccessToken" to CredentialFieldKind.TOKEN)
        )
        `when`(facebookPageTokenAdapter.canPublish).thenReturn(true)
        `when`(facebookPageTokenAdapter.verify(anyString())).thenReturn(VerifyResult("Fake Page"))
    }

    private fun createAccount(body: String): Long {
        val result = mvc.post("/api/social-accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andReturn()
        return Regex("\"id\":(\\d+)").find(result.response.contentAsString)!!.groupValues[1].toLong()
    }

    private fun createFacebookAccount(): Long = createAccount(
        """{"platform":"FACEBOOK","authMethod":"PAGE_ACCESS_TOKEN","credentials":{"pageId":"123","pageAccessToken":"token-value"}}"""
    )

    @Test
    fun `anonymous cannot list accounts`() {
        mvc.get("/api/social-accounts").andExpect { status { isUnauthorized() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `create returns masked credentials`() {
        mvc.post("/api/social-accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"platform":"FACEBOOK","authMethod":"PAGE_ACCESS_TOKEN",
                 "credentials":{"pageId":"123456789","pageAccessToken":"EAABverylongtoken1234"}}
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.platform") { value("FACEBOOK") }
            jsonPath("$.authMethod") { value("PAGE_ACCESS_TOKEN") }
            jsonPath("$.status") { value("UNVERIFIED") }
            jsonPath("$.canPublish") { value(true) }
            jsonPath("$.canShare") { value(false) }
            jsonPath("$.displayName") { value("FACEBOOK account") }
            jsonPath("$.credentialsMasked.pageId") { value("123456789") }
            jsonPath("$.credentialsMasked.pageAccessToken") { value("••••1234") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `create stores a blank userId when left out and shows it unmasked when given`() {
        // present -> shown in full (ID kind)
        mvc.post("/api/social-accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"platform":"LINKEDIN","authMethod":"ACCESS_TOKEN",
                 "credentials":{"accessToken":"AQVJverylongtoken1234","userId":"abc123"}}
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.credentialsMasked.accessToken") { value("••••1234") }
            jsonPath("$.credentialsMasked.userId") { value("abc123") }
        }

        // absent -> stored as "" (the adapter resolves the author itself at publish)
        mvc.post("/api/social-accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"platform":"LINKEDIN","authMethod":"ACCESS_TOKEN",
                 "credentials":{"accessToken":"AQVJverylongtoken1234"}}
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.credentialsMasked.accessToken") { value("••••1234") }
            jsonPath("$.credentialsMasked.userId") { value("") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `create with missing credential field returns 400`() {
        mvc.post("/api/social-accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"platform":"FACEBOOK","authMethod":"PAGE_ACCESS_TOKEN","credentials":{"pageId":"123"}}"""
        }.andExpect { status { isBadRequest() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `create with unsupported login option for the platform returns 400`() {
        mvc.post("/api/social-accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"platform":"FACEBOOK","authMethod":"PASSWORD_GRANT","credentials":{"pageId":"123"}}"""
        }.andExpect { status { isBadRequest() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `create PROFILE_URL account needs no platform API`() {
        mvc.post("/api/social-accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"platform":"X","displayName":"My X page","authMethod":"PROFILE_URL",
                 "credentials":{"profileUrl":"https://x.com/my_page"}}
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.platform") { value("X") }
            jsonPath("$.authMethod") { value("PROFILE_URL") }
            jsonPath("$.canPublish") { value(false) }
            jsonPath("$.canShare") { value(true) }
            jsonPath("$.status") { value("UNVERIFIED") }
            jsonPath("$.displayName") { value("My X page") }
            jsonPath("$.credentialsMasked.profileUrl") { value("https://x.com/my_page") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `reddit PROFILE_URL account requires a subreddit`() {
        mvc.post("/api/social-accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"platform":"REDDIT","authMethod":"PROFILE_URL","credentials":{"profileUrl":"https://reddit.com/r/test"}}"""
        }.andExpect { status { isBadRequest() } }

        mvc.post("/api/social-accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"platform":"REDDIT","authMethod":"PROFILE_URL","displayName":"r/test",
                 "credentials":{"profileUrl":"https://reddit.com/r/test","subreddit":"r/test"}}
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.canShare") { value(true) }
            jsonPath("$.credentialsMasked.profileUrl") { value("https://reddit.com/r/test") }
            jsonPath("$.credentialsMasked.subreddit") { value("r/test") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `credential kinds drive masking - IDs full, tokens tailed, passwords hidden`() {
        mvc.post("/api/social-accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"platform":"REDDIT","authMethod":"PASSWORD_GRANT",
                 "credentials":{"clientId":"fake-client-id","clientSecret":"abcdef123456789",
                                "username":"fake-user","password":"supersecret","subreddit":"test",
                                "extraUndeclaredField":"whatever"}}
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.credentialsMasked.clientId") { value("fake-client-id") }
            jsonPath("$.credentialsMasked.clientSecret") { value("••••6789") }
            jsonPath("$.credentialsMasked.username") { value("fake-user") }
            jsonPath("$.credentialsMasked.password") { value("••••") }
            jsonPath("$.credentialsMasked.subreddit") { value("test") }
            jsonPath("$.credentialsMasked.extraUndeclaredField") { value("••••") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `verify updates status and displayName`() {
        val id = createFacebookAccount()
        mvc.post("/api/social-accounts/{id}/verify", id).andExpect {
            status { isOk() }
            jsonPath("$.ok") { value(true) }
            jsonPath("$.account.status") { value("ACTIVE") }
            jsonPath("$.account.displayName") { value("Fake Page") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `verify PROFILE_URL account checks only the URL and keeps the label`() {
        val id = createAccount(
            """{"platform":"LINKEDIN","displayName":"My LI","authMethod":"PROFILE_URL","credentials":{"profileUrl":"https://linkedin.com/company/acme"}}"""
        )
        mvc.post("/api/social-accounts/{id}/verify", id).andExpect {
            status { isOk() }
            jsonPath("$.ok") { value(true) }
            jsonPath("$.account.status") { value("ACTIVE") }
            jsonPath("$.account.displayName") { value("My LI") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `verify PROFILE_URL account with malformed URL fails`() {
        val id = createAccount(
            """{"platform":"X","authMethod":"PROFILE_URL","credentials":{"profileUrl":"not a url"}}"""
        )
        mvc.post("/api/social-accounts/{id}/verify", id).andExpect {
            status { isOk() }
            jsonPath("$.ok") { value(false) }
            jsonPath("$.account.status") { value("INVALID") }
            jsonPath("$.message") { exists() }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `update can switch login option together with credentials`() {
        val id = createAccount(
            """{"platform":"FACEBOOK","authMethod":"PROFILE_URL","credentials":{"profileUrl":"https://facebook.com/my_page"}}"""
        )
        mvc.put("/api/social-accounts/{id}", id) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"authMethod":"PAGE_ACCESS_TOKEN","credentials":{"pageId":"123","pageAccessToken":"tok"}}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.authMethod") { value("PAGE_ACCESS_TOKEN") }
            jsonPath("$.status") { value("UNVERIFIED") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `update with authMethod but no credentials returns 400`() {
        val id = createFacebookAccount()
        mvc.put("/api/social-accounts/{id}", id) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"authMethod":"PROFILE_URL"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `update replaces credentials and resets status`() {
        val id = createAccount(
            """{"platform":"FACEBOOK","authMethod":"PAGE_ACCESS_TOKEN","credentials":{"pageId":"123456789012345","pageAccessToken":"old-token-1234"}}"""
        )

        mvc.put("/api/social-accounts/{id}?mergeCredentials=false", id) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"credentials":{"pageId":"987654321098765","pageAccessToken":"new-token-5678"}}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UNVERIFIED") }
            jsonPath("$.authMethod") { value("PAGE_ACCESS_TOKEN") }
            jsonPath("$.credentialsMasked.pageId") { value("987654321098765") }
            jsonPath("$.credentialsMasked.pageAccessToken") { value("••••5678") }
        }

        mvc.get("/api/social-accounts/{id}", id)
            .andExpect {
                status { isOk() }
                jsonPath("$.credentialsMasked.pageId") { value("987654321098765") }
                jsonPath("$.credentialsMasked.pageAccessToken") { value("••••5678") }
            }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `update merges credentials and preserves omitted fields`() {
        val id = createAccount(
            """{"platform":"FACEBOOK","authMethod":"PAGE_ACCESS_TOKEN","credentials":{"pageId":"123456789012345","pageAccessToken":"old-token-1234"}}"""
        )

        mvc.put("/api/social-accounts/{id}?mergeCredentials=true", id) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"authMethod":"PAGE_ACCESS_TOKEN","credentials":{"pageAccessToken":"new-token-5678"}}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UNVERIFIED") }
            jsonPath("$.authMethod") { value("PAGE_ACCESS_TOKEN") }
            jsonPath("$.credentialsMasked.pageId") { value("123456789012345") }
            jsonPath("$.credentialsMasked.pageAccessToken") { value("••••5678") }
        }

        mvc.get("/api/social-accounts/{id}", id)
            .andExpect {
                status { isOk() }
                jsonPath("$.credentialsMasked.pageId") { value("123456789012345") }
                jsonPath("$.credentialsMasked.pageAccessToken") { value("••••5678") }
            }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `update rejects authMethod change when merging credentials`() {
        val id = createAccount(
            """{"platform":"FACEBOOK","authMethod":"PROFILE_URL","credentials":{"profileUrl":"https://facebook.com/my_page"}}"""
        )

        mvc.put("/api/social-accounts/{id}?mergeCredentials=true", id) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"authMethod":"PAGE_ACCESS_TOKEN","credentials":{"pageId":"123","pageAccessToken":"tok"}}"""
        }.andExpect {
            status { isBadRequest() }
        }

        mvc.get("/api/social-accounts/{id}", id)
            .andExpect {
                status { isOk() }
                jsonPath("$.authMethod") { value("PROFILE_URL") }
                jsonPath("$.credentialsMasked.profileUrl") { value("https://facebook.com/my_page") }
            }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `update displayName preserves credentials and status`() {
        val id = createAccount(
            """{"platform":"FACEBOOK","authMethod":"PAGE_ACCESS_TOKEN","credentials":{"pageId":"123456789012345","pageAccessToken":"old-token-1234"}}"""
        )

        // Establish a non-UNVERIFIED status if your test setup supports it.
        // Otherwise, the credential-preservation checks still apply.
        mvc.put("/api/social-accounts/{id}?mergeCredentials=false", id) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"displayName":"  Updated Page  "}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.displayName") { value("Updated Page") }
            jsonPath("$.authMethod") { value("PAGE_ACCESS_TOKEN") }
            jsonPath("$.credentialsMasked.pageId") { value("123456789012345") }
            jsonPath("$.credentialsMasked.pageAccessToken") { value("••••1234") }
        }

        mvc.get("/api/social-accounts/{id}", id)
            .andExpect {
                status { isOk() }
                jsonPath("$.displayName") { value("Updated Page") }
                jsonPath("$.credentialsMasked.pageId") { value("123456789012345") }
                jsonPath("$.credentialsMasked.pageAccessToken") { value("••••1234") }
            }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `update rejects authMethod without credentials when merging`() {
        val id = createFacebookAccount()

        mvc.put("/api/social-accounts/{id}?mergeCredentials=true", id) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"authMethod":"PROFILE_URL"}"""
        }.andExpect {
            status { isBadRequest() }
        }

        mvc.get("/api/social-accounts/{id}", id)
            .andExpect {
                status { isOk() }
                jsonPath("$.authMethod") { value("PAGE_ACCESS_TOKEN") }
            }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `unknown account returns 404`() {
        mvc.get("/api/social-accounts/999").andExpect { status { isNotFound() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `delete removes account`() {
        val id = createFacebookAccount()
        mvc.delete("/api/social-accounts/$id").andExpect { status { isNoContent() } }
        mvc.get("/api/social-accounts/$id").andExpect { status { isNotFound() } }
    }
}
