package com.five9th.smagent.platform

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformIntegrationTest(@Autowired val mvc: MockMvc) {

    @Test
    fun `anonymous cannot list platforms`() {
        mvc.get("/api/platforms").andExpect { status { isUnauthorized() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `lists every platform with its login options`() {
        mvc.get("/api/platforms").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(4) }
            // Platform enum order; options sorted by authMethod name
            jsonPath("$[0].platform") { value("FACEBOOK") }
            jsonPath("$[0].loginOptions.length()") { value(2) }
            jsonPath("$[0].loginOptions[0].authMethod") { value("PAGE_ACCESS_TOKEN") }
            jsonPath("$[0].loginOptions[0].canPublish") { value(true) }
            jsonPath("$[0].loginOptions[0].canShare") { value(false) }
            jsonPath("$[0].loginOptions[0].credentialFields.pageId") { value("ID") }
            jsonPath("$[0].loginOptions[0].credentialFields.pageAccessToken") { value("TOKEN") }
            jsonPath("$[0].loginOptions[1].authMethod") { value("PROFILE_URL") }
            jsonPath("$[0].loginOptions[1].canPublish") { value(false) }
            jsonPath("$[0].loginOptions[1].canShare") { value(true) }
            jsonPath("$[1].platform") { value("REDDIT") }
            jsonPath("$[1].loginOptions.length()") { value(2) }
            jsonPath("$[1].loginOptions[1].credentialFields.subreddit") { value("ID") }
            jsonPath("$[2].platform") { value("X") }
            jsonPath("$[2].loginOptions.length()") { value(2) }
            jsonPath("$[2].loginOptions[0].authMethod") { value("ACCESS_TOKEN") }
            jsonPath("$[2].loginOptions[0].canPublish") { value(true) }
            jsonPath("$[2].loginOptions[0].canShare") { value(false) }
            jsonPath("$[2].loginOptions[0].credentialFields.accessToken") { value("TOKEN") }
            jsonPath("$[2].loginOptions[0].credentialFields.refreshToken") { value("TOKEN") }
            jsonPath("$[2].loginOptions[1].authMethod") { value("PROFILE_URL") }
            jsonPath("$[3].platform") { value("LINKEDIN") }
            jsonPath("$[3].loginOptions.length()") { value(3) }
            jsonPath("$[3].loginOptions[0].authMethod") { value("ACCESS_TOKEN") }
            jsonPath("$[3].loginOptions[0].canPublish") { value(true) }
            jsonPath("$[3].loginOptions[0].credentialFields.accessToken") { value("TOKEN") }
            jsonPath("$[3].loginOptions[0].credentialFields.userId") { value("ID") }
            jsonPath("$[3].loginOptions[1].authMethod") { value("ORGANIZATION_ACCESS_TOKEN") }
            jsonPath("$[3].loginOptions[1].canPublish") { value(true) }
            jsonPath("$[3].loginOptions[1].credentialFields.organizationId") { value("ID") }
            jsonPath("$[3].loginOptions[1].credentialFields.accessToken") { value("TOKEN") }
            jsonPath("$[3].loginOptions[2].authMethod") { value("PROFILE_URL") }
        }
    }
}
