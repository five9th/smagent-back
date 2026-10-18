package com.five9th.smagent.ai

import com.five9th.smagent.ai.adapter.AnthropicAdapter
import com.five9th.smagent.ai.adapter.OpenAiCompatibleAdapter
import com.five9th.smagent.ai.adapter.AiProviderException
import com.five9th.smagent.ai.adapter.GeneratedContent
import com.five9th.smagent.ai.adapter.GenerateCommand
import com.five9th.smagent.ai.adapter.GenerateResult
import com.five9th.smagent.domain.AiProviderType
import com.five9th.smagent.domain.GenerateEffort
import com.five9th.smagent.domain.GeneratePlatform
import com.five9th.smagent.domain.Platform
import com.five9th.smagent.crypto.CryptoService
import com.five9th.smagent.repo.AiProviderRepository
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiGenerateIntegrationTest(@Autowired val mvc: MockMvc) {

    @MockitoBean
    lateinit var anthropicAdapter: AnthropicAdapter

    @MockitoBean
    lateinit var openAiAdapter: OpenAiCompatibleAdapter

    @Autowired
    lateinit var providers: AiProviderRepository

    @Autowired
    lateinit var crypto: CryptoService

    private val cannedResult = GenerateResult(
        content = GeneratedContent("v2 is out", "master text"),
        overrides = mapOf(Platform.REDDIT to GeneratedContent("v2", "community text")),
        inputTokens = 11,
        outputTokens = 7,
    )

    @BeforeEach
    fun stubAdapter() {
        providers.deleteAll() // the in-mem H2 persists across tests in this context
        whenever(anthropicAdapter.type).thenReturn(AiProviderType.ANTHROPIC)
        whenever(openAiAdapter.type).thenReturn(AiProviderType.OPENAI_COMPATIBLE)
        // baseUrl may be null (no override) — any() alone excludes nulls
        whenever(anthropicAdapter.generate(any(), anyOrNull(), any(), any(), any())).thenReturn(cannedResult)
        whenever(openAiAdapter.generate(any(), anyOrNull(), any(), any(), any())).thenReturn(cannedResult)
    }

    private fun createProvider(
        name: String = "Anthropic main",
        defaultModel: String = "claude-sonnet-5",
        baseUrl: String? = null,
        type: String = "ANTHROPIC",
    ): Long {
        val extras = buildString {
            baseUrl?.let { append(""","baseUrl":"$it"""") }
        }
        val result = mvc.post("/api/ai-providers") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name","type":"$type","apiKey":"sk-ant-decrypted-1234","defaultModel":"$defaultModel"$extras}"""
        }.andReturn()
        return Regex("\"id\":(\\d+)").find(result.response.contentAsString)!!.groupValues[1].toLong()
    }

    private fun generate(body: String) =
        mvc.post("/api/ai/generate") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    @Test
    fun `anonymous cannot generate`() {
        generate("""{"prompt":"Post about v2"}""").andExpect { status { isUnauthorized() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `generate returns content and overrides in PostDto shape`() {
        createProvider()

        generate("""{"prompt":"Post about releasing v2","platforms":["GENERIC","REDDIT"]}""").andExpect {
            status { isOk() }
            jsonPath("$.content.title") { value("v2 is out") }
            jsonPath("$.content.text") { value("master text") }
            jsonPath("$.overrides.REDDIT.title") { value("v2") }
            jsonPath("$.overrides.REDDIT.text") { value("community text") }
            jsonPath("$.provider") { value("Anthropic main") }
            jsonPath("$.model") { value("claude-sonnet-5") }
            jsonPath("$.usage.inputTokens") { value(11) }
            jsonPath("$.usage.outputTokens") { value(7) }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `generate defaults to GENERIC only and passes resolved values to the adapter`() {
        createProvider()

        generate("""{"prompt":"  Post about v2  ","effort":"HIGH"}""").andExpect {
            status { isOk() }
            jsonPath("$.content.text") { value("master text") }
        }

        val apiKey = argumentCaptor<String>()
        val baseUrl = argumentCaptor<String>()
        val model = argumentCaptor<String>()
        val effort = argumentCaptor<GenerateEffort>()
        val command = argumentCaptor<GenerateCommand>()
        verify(anthropicAdapter).generate(
            apiKey.capture(), baseUrl.capture(), model.capture(), effort.capture(), command.capture()
        )
        assertEquals("sk-ant-decrypted-1234", apiKey.firstValue) // decrypted, not the stored blob
        assertNull(baseUrl.firstValue)
        assertEquals("claude-sonnet-5", model.firstValue)
        assertEquals(GenerateEffort.HIGH, effort.firstValue)
        assertEquals("Post about v2", command.firstValue.prompt)
        assertEquals(setOf(GeneratePlatform.GENERIC), command.firstValue.platforms)
        assertNull(command.firstValue.constraints)
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `model resolution - request beats provider default`() {
        createProvider(defaultModel = "claude-opus-5")

        // provider defaultModel (request names none)
        generate("""{"prompt":"hi"}""").andExpect { jsonPath("$.model") { value("claude-opus-5") } }
        // request model wins over the provider default
        generate("""{"prompt":"hi","model":"claude-fable-5-1"}""").andExpect {
            jsonPath("$.model") { value("claude-fable-5-1") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `generate without any model resolution is a 400`() {
        // pre-0.7 rows can exist without a defaultModel (creation now requires
        // one) — insert one directly and confirm the guard fires
        val legacy = com.five9th.smagent.domain.AiProvider(
            name = "Legacy",
            apiKeyEnc = crypto.encrypt("sk-ant-legacy-1234"),
        )
        providers.save(legacy)

        generate("""{"prompt":"hi"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.message") {
                value("Provider 'Legacy' has no defaultModel — set one (PUT /api/ai-providers/{id}) or pass model in the request")
            }
        }

        // an explicit request model still works
        generate("""{"prompt":"hi","model":"claude-haiku-4-5"}""").andExpect {
            status { isOk() }
            jsonPath("$.model") { value("claude-haiku-4-5") }
        }
    }

    @Test
    fun `anonymous cannot list effort levels`() {
        mvc.get("/api/ai/effort-levels").andExpect { status { isUnauthorized() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `effort levels stub returns the full enum`() {
        mvc.get("/api/ai/effort-levels").andExpect {
            status { isOk() }
            jsonPath("$") {
                value(org.hamcrest.Matchers.contains("LOW", "MEDIUM", "HIGH", "XHIGH", "MAX"))
            }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `generate infers the only provider and forwards its baseUrl`() {
        createProvider(baseUrl = "http://localhost:5001")

        generate("""{"prompt":"hi"}""").andExpect { status { isOk() } }

        val baseUrl = argumentCaptor<String>()
        verify(anthropicAdapter).generate(any(), baseUrl.capture(), any(), any(), any())
        assertEquals("http://localhost:5001", baseUrl.firstValue)
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `generate dispatches to the OpenAI-compatible adapter with its defaultModel`() {
        val id = createProvider(
            name = "OpenRouter",
            defaultModel = "deepseek/deepseek-chat",
            baseUrl = "https://openrouter.ai/api/v1",
            type = "OPENAI_COMPATIBLE",
        )

        generate("""{"prompt":"hi","providerId":$id}""").andExpect {
            status { isOk() }
            jsonPath("$.provider") { value("OpenRouter") }
            jsonPath("$.model") { value("deepseek/deepseek-chat") }
        }

        val model = argumentCaptor<String>()
        verify(openAiAdapter).generate(any(), anyOrNull(), model.capture(), any(), any())
        assertEquals("deepseek/deepseek-chat", model.firstValue)
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `providerId picks a specific provider when several exist`() {
        val first = createProvider(name = "First")
        createProvider(name = "Second")

        generate("""{"prompt":"hi","providerId":$first}""").andExpect {
            status { isOk() }
            jsonPath("$.provider") { value("First") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `several providers without providerId is a 400`() {
        createProvider(name = "First")
        createProvider(name = "Second")

        generate("""{"prompt":"hi"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("Several AI providers exist — pass providerId to choose one") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `no providers at all is a 400`() {
        generate("""{"prompt":"hi"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("No AI providers configured — create one first (POST /api/ai-providers)") }
        }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `unknown providerId is a 404`() {
        createProvider()
        generate("""{"prompt":"hi","providerId":999}""").andExpect { status { isNotFound() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `empty platforms and blank prompt are 400s`() {
        createProvider()
        generate("""{"prompt":"hi","platforms":[]}""").andExpect { status { isBadRequest() } }
        generate("""{"prompt":"   "}""").andExpect { status { isBadRequest() } }
    }

    @WithMockUser(roles = ["OWNER"])
    @Test
    fun `upstream failure surfaces as 502`() {
        createProvider()
        whenever(anthropicAdapter.generate(any(), anyOrNull(), any(), any(), any())).thenThrow(
            AiProviderException("Anthropic API error (401): invalid x-api-key")
        )

        generate("""{"prompt":"hi"}""").andExpect {
            status { isBadGateway() }
            jsonPath("$.message") { value("Anthropic API error (401): invalid x-api-key") }
        }
    }
}
