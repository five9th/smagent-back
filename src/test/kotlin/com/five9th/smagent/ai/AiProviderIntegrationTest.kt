package com.five9th.smagent.ai

import com.five9th.smagent.ai.adapter.AiProviderException
import com.five9th.smagent.ai.adapter.AnthropicAdapter
import com.five9th.smagent.domain.AiProviderType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
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
class AiProviderIntegrationTest(@Autowired val mvc: MockMvc) {

    @MockitoBean
    lateinit var anthropicAdapter: AnthropicAdapter

    @BeforeEach
    fun stubAdapter() {
        whenever(anthropicAdapter.type).thenReturn(AiProviderType.ANTHROPIC)
    }

    private fun createProvider(body: String): Long {
        val result = mvc.post("/api/ai-providers") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andReturn()
        return Regex("\"id\":(\\d+)").find(result.response.contentAsString)!!.groupValues[1].toLong()
    }

    @Test
    fun `anonymous cannot list providers`() {
        mvc.get("/api/ai-providers").andExpect { status { isUnauthorized() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `create stores the key encrypted and returns it masked`() {
        mvc.post("/api/ai-providers") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"name":"Anthropic main","type":"ANTHROPIC",
                 "baseUrl":"http://localhost:5001","apiKey":"sk-ant-api03-verylong-1234",
                 "defaultModel":"claude-sonnet-5"}
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.name") { value("Anthropic main") }
            jsonPath("$.type") { value("ANTHROPIC") }
            jsonPath("$.baseUrl") { value("http://localhost:5001") }
            jsonPath("$.apiKeyMasked") { value("••••1234") }
            jsonPath("$.defaultModel") { value("claude-sonnet-5") }
            jsonPath("$.createdAt") { exists() }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `create without baseUrl or defaultModel leaves them null`() {
        mvc.post("/api/ai-providers") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Plain","type":"ANTHROPIC","apiKey":"short"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.baseUrl") { doesNotExist() }
            jsonPath("$.defaultModel") { doesNotExist() }
            jsonPath("$.apiKeyMasked") { value("••••") } // too short for a tail
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `create with OPENAI_COMPATIBLE returns 501`() {
        mvc.post("/api/ai-providers") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"OpenRouter","type":"OPENAI_COMPATIBLE","apiKey":"sk-or-1"}"""
        }.andExpect {
            status { isNotImplemented() }
            jsonPath("$.message") { value("Creating OPENAI_COMPATIBLE providers is not implemented yet") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `create rejects malformed and non-http baseUrl`() {
        listOf("not a url", "ftp://example.com").forEach { url ->
            mvc.post("/api/ai-providers") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"X","type":"ANTHROPIC","apiKey":"k","baseUrl":"$url"}"""
            }.andExpect { status { isBadRequest() } }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `create with missing name or apiKey returns 400`() {
        mvc.post("/api/ai-providers") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"type":"ANTHROPIC","apiKey":"k"}"""
        }.andExpect { status { isBadRequest() } }

        mvc.post("/api/ai-providers") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"X","type":"ANTHROPIC"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `list returns created providers`() {
        createProvider("""{"name":"Anthropic main","type":"ANTHROPIC","apiKey":"sk-ant-verylongtoken-1234"}""")

        mvc.get("/api/ai-providers").andExpect {
            status { isOk() }
            jsonPath("$[0].name") { value("Anthropic main") }
            jsonPath("$[0].apiKeyMasked") { value("••••1234") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `unknown provider returns 404`() {
        mvc.get("/api/ai-providers/999").andExpect { status { isNotFound() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `update renames, replaces the key and keeps it when blank`() {
        val id = createProvider("""{"name":"Old name","type":"ANTHROPIC","apiKey":"sk-ant-verylongtoken-1234"}""")

        mvc.put("/api/ai-providers/{id}", id) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"New name","apiKey":"sk-ant-other-token-5678"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.name") { value("New name") }
            jsonPath("$.apiKeyMasked") { value("••••5678") }
        }

        // blank apiKey = keep the stored one
        mvc.put("/api/ai-providers/{id}", id) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"apiKey":"  "}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.apiKeyMasked") { value("••••5678") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `update clears baseUrl and defaultModel with empty strings`() {
        val id = createProvider(
            """{"name":"X","type":"ANTHROPIC","apiKey":"k",
               "baseUrl":"http://localhost:5001","defaultModel":"claude-sonnet-5"}"""
        )

        mvc.put("/api/ai-providers/{id}", id) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"baseUrl":"","defaultModel":""}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.baseUrl") { doesNotExist() }
            jsonPath("$.defaultModel") { doesNotExist() }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `verify reports the adapter message`() {
        val id = createProvider("""{"name":"Anthropic main","type":"ANTHROPIC","apiKey":"sk-ant-token-1234"}""")
        whenever(anthropicAdapter.verify(any(), any())).thenReturn("Key valid — 12 models available")

        mvc.post("/api/ai-providers/{id}/verify", id).andExpect {
            status { isOk() }
            jsonPath("$.ok") { value(true) }
            jsonPath("$.message") { value("Key valid — 12 models available") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `verify failure surfaces the upstream error`() {
        val id = createProvider("""{"name":"Anthropic main","type":"ANTHROPIC","apiKey":"sk-ant-token-1234"}""")
        whenever(anthropicAdapter.verify(any(), any())).thenThrow(
            AiProviderException("Anthropic API error (401): invalid x-api-key")
        )

        mvc.post("/api/ai-providers/{id}/verify", id).andExpect {
            status { isOk() }
            jsonPath("$.ok") { value(false) }
            jsonPath("$.message") { value("Anthropic API error (401): invalid x-api-key") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `delete removes the provider for real`() {
        val id = createProvider("""{"name":"X","type":"ANTHROPIC","apiKey":"k"}""")
        mvc.delete("/api/ai-providers/{id}", id).andExpect { status { isNoContent() } }
        mvc.get("/api/ai-providers/{id}", id).andExpect { status { isNotFound() } }
    }
}
