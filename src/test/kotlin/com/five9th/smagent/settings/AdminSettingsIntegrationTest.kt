package com.five9th.smagent.settings

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminSettingsIntegrationTest(
    @Autowired val mvc: MockMvc,
    @Autowired val settings: SettingsService,
    @Autowired val settingRepo: com.five9th.smagent.repo.AppSettingRepository,
    @Autowired val crypto: com.five9th.smagent.crypto.CryptoService,
) {

    @Test
    fun `settings endpoint requires authentication`() {
        mvc.get("/api/admin/settings").andExpect { status { isUnauthorized() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `status reports booleans only, never values`() {
        val body = mvc.get("/api/admin/settings").andReturn().response.contentAsString
        assertTrue("clientIdOverridden" in body)
        assertTrue("clientSecret" !in body.replace("clientSecretOverridden", ""), "no value-bearing field may appear")
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `put stores overrides that win over config and survive replace`() {
        val configDefault = settings.x().clientId // set by the test profile yaml

        mvc.put("/api/admin/settings") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"x": {"clientId": "runtime-x-id", "clientSecret": "runtime-x-secret"}}"""
        }.andExpect { status { isNoContent() } }

        assertEquals("runtime-x-id", settings.x().clientId, "override must win over config")
        assertEquals("runtime-x-secret", settings.x().clientSecret)
        assertTrue(settings.isOverridden(SettingKey.X_CLIENT_ID))

        // GET reflects override state
        mvc.get("/api/admin/settings").andExpect {
            jsonPath("$.x.clientIdOverridden") { value(true) }
            jsonPath("$.x.clientSecretOverridden") { value(true) }
        }

        // PUT replaces the whole set: omitted platform falls back to config
        mvc.put("/api/admin/settings") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"facebook": {"clientId": "fb-id", "clientSecret": "fb-secret"}}"""
        }.andExpect { status { isNoContent() } }

        assertEquals(configDefault, settings.x().clientId, "omitted platform must clear its override")
        assertEquals("fb-id", settings.facebook().clientId)

        // overridden flags track the override rows, not the resolved values
        mvc.get("/api/admin/settings").andExpect {
            jsonPath("$.x.clientIdOverridden") { value(false) }
            jsonPath("$.facebook.clientIdOverridden") { value(true) }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `blank values are rejected`() {
        mvc.put("/api/admin/settings") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"x": {"clientId": "  "}}"""
        }.andExpect { status { isBadRequest() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `values are encrypted at rest`() {
        mvc.put("/api/admin/settings") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"linkedin": {"clientId": "plain-li-id", "clientSecret": "plain-li-secret"}}"""
        }.andExpect { status { isNoContent() } }

        val row = settingRepo.findById(SettingKey.LINKEDIN_CLIENT_ID.key).orElseThrow()
        assertTrue("plain-li-id" !in row.valueEnc, "plaintext must not be stored")
        assertEquals("plain-li-id", crypto.decrypt(row.valueEnc))
    }
}
