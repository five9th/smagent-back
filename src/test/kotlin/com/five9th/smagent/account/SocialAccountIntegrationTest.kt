package com.five9th.smagent.account

import com.five9th.smagent.domain.Platform
import com.five9th.smagent.platform.FacebookAdapter
import com.five9th.smagent.platform.VerifyResult
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SocialAccountIntegrationTest(@Autowired val mvc: MockMvc) {

    @MockitoBean
    lateinit var facebookAdapter: FacebookAdapter

    @BeforeEach
    fun stubAdapter() {
        `when`(facebookAdapter.platform).thenReturn(Platform.FACEBOOK)
        `when`(facebookAdapter.requiredCredentialFields).thenReturn(setOf("pageId", "pageAccessToken"))
        `when`(facebookAdapter.verify(anyString())).thenReturn(VerifyResult("Fake Page"))
    }

    private fun createFacebookAccount(): Long {
        val result = mvc.post("/api/social-accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"platform":"FACEBOOK","credentials":{"pageId":"123","pageAccessToken":"token-value"}}"""
        }.andReturn()
        return Regex("\"id\":(\\d+)").find(result.response.contentAsString)!!.groupValues[1].toLong()
    }

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
                {"platform":"FACEBOOK",
                 "credentials":{"pageId":"123456789","pageAccessToken":"EAABverylongtoken1234"}}
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.platform") { value("FACEBOOK") }
            jsonPath("$.status") { value("UNVERIFIED") }
            jsonPath("$.displayName") { value("FACEBOOK account") }
            jsonPath("$.credentialsMasked.pageId") { value("••••6789") }
            jsonPath("$.credentialsMasked.pageAccessToken") { value("••••1234") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `create with missing credential field returns 400`() {
        mvc.post("/api/social-accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"platform":"FACEBOOK","credentials":{"pageId":"123"}}"""
        }.andExpect { status { isBadRequest() } }
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
