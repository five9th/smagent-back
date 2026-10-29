package com.five9th.smagent.auth

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.mock.web.MockHttpSession

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest(@Autowired val mvc: MockMvc) {

    @Test
    fun `anonymous request is rejected with 401`() {
        mvc.get("/api/auth/me").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `login with bad credentials returns 401`() {
        mvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"test-owner","password":"wrong"}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `login establishes session usable on subsequent requests`() {
        val result = mvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"test-owner","password":"test-password"}"""
        }.andExpect {
            status { isOk() }
            content { json("""{"id":1,"username":"test-owner","role":"OWNER"}""", JsonCompareMode.LENIENT) }
        }.andReturn()

        val session = result.request.getSession(false) as MockHttpSession

        // same session -> authenticated
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk)

        // fresh request without session stays anonymous
        mvc.get("/api/auth/me").andExpect { status { isUnauthorized() } }
    }
}
