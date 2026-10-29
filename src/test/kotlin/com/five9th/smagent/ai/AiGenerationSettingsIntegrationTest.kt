package com.five9th.smagent.ai

import com.five9th.smagent.ai.adapter.AnthropicAdapter
import com.five9th.smagent.ai.adapter.GeneratedContent
import com.five9th.smagent.ai.adapter.GenerateResult
import com.five9th.smagent.domain.AiProviderType
import com.five9th.smagent.repo.AiProviderRepository
import com.five9th.smagent.repo.GenerationSettingsRepository
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.post

/** Owner-editable platform style rules: GET/PUT semantics and their effect on generate. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiGenerationSettingsIntegrationTest(@Autowired val mvc: MockMvc) {

    // only Anthropic is mocked -> a context separate from AiGenerateIntegrationTest (own H2)
    @MockitoBean
    lateinit var anthropicAdapter: AnthropicAdapter

    @Autowired
    lateinit var providers: AiProviderRepository

    @Autowired
    lateinit var settings: GenerationSettingsRepository

    @BeforeEach
    fun resetState() {
        providers.deleteAll()
        settings.deleteAll() // the in-mem H2 persists across tests in this context
        whenever(anthropicAdapter.type).thenReturn(AiProviderType.ANTHROPIC)
        whenever(anthropicAdapter.generate(any(), anyOrNull(), any(), any(), any())).thenReturn(
            GenerateResult(content = GeneratedContent(null, "text"))
        )
    }

    @Test
    fun `anonymous cannot read or write settings`() {
        mvc.get("/api/ai/generation-settings").andExpect { status { isUnauthorized() } }
        mvc.put("/api/ai/generation-settings") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"platformRules":{}}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `GET returns the built-ins until overridden`() {
        mvc.get("/api/ai/generation-settings").andExpect {
            status { isOk() }
            // effective == defaults while nothing is overridden
            jsonPath("$.platformRules.X") { value("at most 280 characters total, punchy.") }
            jsonPath("$.defaults.X") { value("at most 280 characters total, punchy.") }
            jsonPath("$.platformRules.GENERIC") { value("") }
            jsonPath("$.defaults.REDDIT") { value("community tone, honest, no corporate speak.") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `PUT replaces, resets via null or blank, and round-trips through GET`() {
        mvc.put("/api/ai/generation-settings") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"platformRules":{"X":"dry humor","FACEBOOK":null,"LINKEDIN":"  "}}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.platformRules.X") { value("dry humor") }
            jsonPath("$.platformRules.FACEBOOK") { value("conversational, may run a bit longer.") } // reset
            jsonPath("$.platformRules.LINKEDIN") { value("professional and clear, no hashtag spam.") } // blank = reset
            jsonPath("$.platformRules.REDDIT") { value("community tone, honest, no corporate speak.") } // untouched
        }

        mvc.get("/api/ai/generation-settings").andExpect {
            jsonPath("$.platformRules.X") { value("dry humor") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `PUT rejects overly long rules`() {
        mvc.put("/api/ai/generation-settings") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"platformRules":{"X":"${"x".repeat(1001)}"}}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("platformRules[X] is too long (max 1000 characters)") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `generate carries the effective rules into the command`() {
        mvc.put("/api/ai/generation-settings") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"platformRules":{"X":"dry humor"}}"""
        }.andExpect { status { isOk() } }

        mvc.post("/api/ai-providers") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Anthropic main","type":"ANTHROPIC","apiKey":"sk-ant-key-1234","defaultModel":"claude-sonnet-5"}"""
        }.andExpect { status { isCreated() } }

        mvc.post("/api/ai/generate") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"prompt":"hi","platforms":["X"]}"""
        }.andExpect { status { isOk() } }

        val command = argumentCaptor<com.five9th.smagent.ai.adapter.GenerateCommand>()
        verify(anthropicAdapter).generate(any(), anyOrNull(), any(), any(), command.capture())
        assertEquals("dry humor", command.firstValue.platformRules?.get(com.five9th.smagent.domain.GeneratePlatform.X))
        // untouched platforms ride along at their defaults
        assertEquals(
            "community tone, honest, no corporate speak.",
            command.firstValue.platformRules?.get(com.five9th.smagent.domain.GeneratePlatform.REDDIT),
        )
    }
}
