package com.five9th.smagent.feedback

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FeedbackIntegrationTest(@Autowired val mvc: MockMvc) {

    private fun create(text: String): Long {
        val result = mvc.post("/api/feedback") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"text":"$text"}"""
        }.andReturn()
        return Regex("\"id\":(\\d+)").find(result.response.contentAsString)!!.groupValues[1].toLong()
    }

    @Test
    fun `anonymous access is rejected`() {
        mvc.get("/api/feedback").andExpect { status { isUnauthorized() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `add check delete flow`() {
        val id = create("try the facebook adapter")

        mvc.patch("/api/feedback/{id}", id) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"checked":true}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.checked") { value(true) }
            jsonPath("$.text") { value("try the facebook adapter") }
        }

        mvc.get("/api/feedback").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].checked") { value(true) }
        }

        mvc.delete("/api/feedback/{id}", id).andExpect { status { isNoContent() } }
        mvc.get("/api/feedback").andExpect { jsonPath("$.length()") { value(0) } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `blank text is rejected and unknown id is 404`() {
        mvc.post("/api/feedback") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"text":"   "}"""
        }.andExpect { status { isBadRequest() } }

        mvc.patch("/api/feedback/999") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"checked":true}"""
        }.andExpect { status { isNotFound() } }
    }
}
