package com.five9th.smagent

import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorIntegrationTest(
    @Autowired val mvc: MockMvc,
    @Autowired val buildProperties: BuildProperties,
) {

    @Test
    fun `health is public and reports UP`() {
        mvc.get("/actuator/health").andExpect {
            status { isOk() }
            content { json("""{"status":"UP"}""", JsonCompareMode.LENIENT) }
        }
    }

    @Test
    fun `info is public and serves the build version`() {
        mvc.perform(get("/actuator/info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.build.version", `is`(buildProperties.version)))
    }

    @Test
    fun `other actuator paths stay behind auth`() {
        mvc.get("/actuator/env").andExpect { status { isUnauthorized() } }
        mvc.get("/actuator").andExpect { status { isUnauthorized() } }
    }
}
