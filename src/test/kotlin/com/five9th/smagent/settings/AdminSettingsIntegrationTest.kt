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
        val xIdConfigDefault = settings.x().clientId // set by the test profile yaml
        val fbSecretConfigDefault = settings.facebook().clientSecret

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

        // omitted platform/fields are untouched; a single field updates alone
        mvc.put("/api/admin/settings") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"facebook": {"clientId": "fb-id"}, "x": {"clientId": "x-id-2"}}"""
        }.andExpect { status { isNoContent() } }

        assertEquals("x-id-2", settings.x().clientId)
        assertEquals("runtime-x-secret", settings.x().clientSecret, "untouched field keeps its override")
        assertEquals("fb-id", settings.facebook().clientId)
        assertEquals(fbSecretConfigDefault, settings.facebook().clientSecret, "field never overridden falls back to config")

        // "" clears one field's override without touching its sibling
        mvc.put("/api/admin/settings") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"x": {"clientId": ""}}"""
        }.andExpect { status { isNoContent() } }

        assertEquals(xIdConfigDefault, settings.x().clientId, "cleared field falls back to config")
        assertEquals("runtime-x-secret", settings.x().clientSecret)

        // overridden flags track the override rows, not the resolved values
        mvc.get("/api/admin/settings").andExpect {
            jsonPath("$.x.clientIdOverridden") { value(false) }
            jsonPath("$.x.clientSecretOverridden") { value(true) }
            jsonPath("$.facebook.clientIdOverridden") { value(true) }
            jsonPath("$.facebook.clientSecretOverridden") { value(false) }
        }
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
