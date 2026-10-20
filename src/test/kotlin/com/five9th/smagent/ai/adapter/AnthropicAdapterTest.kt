package com.five9th.smagent.ai.adapter

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.ContentBlock
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlock
import com.anthropic.models.messages.Usage
import com.anthropic.models.models.ModelInfo
import com.anthropic.models.models.ModelListPage
import com.anthropic.services.blocking.MessageService
import com.anthropic.services.blocking.ModelService
import com.five9th.smagent.domain.GenerateEffort
import com.five9th.smagent.domain.GeneratePlatform
import com.five9th.smagent.domain.Platform
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * generate/verify against a stubbed AnthropicClient: params, result mapping,
 * error handling. The prompt/parse contract itself is covered by
 * GenerationContractTest.
 */
class AnthropicAdapterTest {

    private val anthropicClient = mock<AnthropicClient>()
    private val messagesClient = mock<MessageService>()
    private val modelsClient = mock<ModelService>()
    private val message = mock<Message>()
    private val usage = mock<Usage>()

    private val adapter = AnthropicAdapter().apply { clientFactory = { _, _ -> anthropicClient } }

    @BeforeEach
    fun stubClient() {
        whenever(anthropicClient.messages()).thenReturn(messagesClient)
        whenever(anthropicClient.models()).thenReturn(modelsClient)
        whenever(messagesClient.create(any<MessageCreateParams>())).thenReturn(message)
        whenever(message.content()).thenReturn(listOf<ContentBlock>())
        whenever(message.usage()).thenReturn(usage)
    }

    private fun respondWith(raw: String, inputTokens: Long = 11, outputTokens: Long = 7) {
        whenever(message.content()).thenReturn(listOf(
            ContentBlock.ofText(
                TextBlock
                    .builder()
                    .text(raw)
                    .citations(null)
                    .build())
        ))
        whenever(usage.inputTokens()).thenReturn(inputTokens)
        whenever(usage.outputTokens()).thenReturn(outputTokens)
    }

    private fun generate(vararg platforms: GeneratePlatform, effort: GenerateEffort = GenerateEffort.LOW) =
        adapter.generate(
            apiKey = "sk-ant-key",
            baseUrl = null,
            model = "claude-sonnet-5",
            effort = effort,
            command = GenerateCommand(
                prompt = "Post about releasing v2 of our CLI tool",
                platforms = platforms.toSet(),
            ),
        )

    @Test
    fun `generate maps a model answer into content, overrides and usage`() {
        respondWith(
            """
            {"GENERIC":{"title":"dropped: REDDIT is requested","text":"master text"},
             "REDDIT":{"title":"v2","text":"community text"},
             "X":{"title":"ignored","text":"short one"},
             "note":"tone contradicts X's punchy rule; kept the rule"}
            """.trimIndent()
        )

        val result = generate(GeneratePlatform.GENERIC, GeneratePlatform.REDDIT, GeneratePlatform.X)

        assertEquals(GeneratedContent(null, "master text"), result.content)
        assertEquals(GeneratedContent("v2", "community text"), result.overrides[Platform.REDDIT])
        assertEquals(GeneratedContent(null, "short one"), result.overrides[Platform.X])
        assertEquals(11L, result.inputTokens)
        assertEquals(7L, result.outputTokens)
        assertEquals("tone contradicts X's punchy rule; kept the rule", result.note)
    }

    @Test
    fun `generate sends model, cap, effort, prompt and the response contract`() {
        respondWith("""{"GENERIC":{"text":"ok"}}""")
        generate(GeneratePlatform.GENERIC, GeneratePlatform.REDDIT, effort = GenerateEffort.HIGH)

        val captor = argumentCaptor<MessageCreateParams>()
        verify(messagesClient).create(captor.capture())
        val params = captor.firstValue

        assertEquals("claude-sonnet-5", params.model().asString())
        assertEquals(4096L, params.maxTokens())
        assertEquals(OutputConfig.Effort.HIGH, params.outputConfig().get().effort().get())
        // wiring check only — the contract's content is covered by GenerationContractTest
        val system = params.system().get().asString()
        assertTrue(system.contains("Requested keys: GENERIC, REDDIT"))
        assertTrue(params.messages().first().content().toString().contains("releasing v2"))
    }

    @Test
    fun `generate maps upstream failures to AiProviderException`() {
        whenever(messagesClient.create(any<MessageCreateParams>())).thenThrow(RuntimeException("connection reset"))

        val e = assertFailsWith<AiProviderException> { generate(GeneratePlatform.GENERIC) }
        assertTrue(e.message!!.contains("connection reset"))
    }

    @Test
    fun `generate passes apiKey and baseUrl to the client factory`() {
        respondWith("""{"GENERIC":{"text":"ok"}}""")
        var seen: Pair<String, String?>? = null
        adapter.clientFactory = { apiKey, baseUrl ->
            seen = apiKey to baseUrl
            anthropicClient
        }

        adapter.generate("key-1", "http://localhost:5001", "claude-sonnet-5", GenerateEffort.LOW, GenerateCommand("hi", setOf(GeneratePlatform.GENERIC)))

        assertEquals("key-1" to "http://localhost:5001", seen)
    }

    @Test
    fun `listModels returns sorted model ids`() {
        val page = mock<ModelListPage>()
        whenever(modelsClient.list()).thenReturn(page)
        val sonnet = mock<ModelInfo>()
        whenever(sonnet.id()).thenReturn("claude-sonnet-5")
        val opus = mock<ModelInfo>()
        whenever(opus.id()).thenReturn("claude-opus-5")
        whenever(page.items()).thenReturn(listOf(sonnet, opus))

        assertEquals(listOf("claude-opus-5", "claude-sonnet-5"), adapter.listModels("key-1", null))
    }

    private fun modelInfo(id: String): ModelInfo =
        mock<ModelInfo>().also { whenever(it.id()).thenReturn(id) }

    @Test
    fun `verify counts the models the key can use`() {
        val page = mock<ModelListPage>()
        whenever(modelsClient.list()).thenReturn(page)
        // build the list first: stubbing inside thenReturn's argument nests stubbings
        val items = listOf(modelInfo("claude-opus-5"), modelInfo("claude-sonnet-5"))
        whenever(page.items()).thenReturn(items)

        assertEquals("Key valid — 2 models available", adapter.verify("key-1", null))
    }

    @Test
    fun `verify mentions the custom baseUrl when one is set`() {
        val page = mock<ModelListPage>()
        whenever(modelsClient.list()).thenReturn(page)
        val items = listOf(modelInfo("claude-opus-5"))
        whenever(page.items()).thenReturn(items)

        assertEquals(
            "Key valid — 1 models available via http://localhost:5001",
            adapter.verify("key-1", "http://localhost:5001"),
        )
    }
}
