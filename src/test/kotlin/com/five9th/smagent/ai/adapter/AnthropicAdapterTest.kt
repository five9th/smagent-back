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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** generate/verify against a stubbed AnthropicClient: params, JSON contract parse, error mapping. */
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
        whenever(message.content()).thenReturn(listOf(ContentBlock.ofText(TextBlock.builder().text(raw).build())))
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
                constraints = GenerateConstraints(maxLength = 500, tone = "casual"),
            ),
        )

    @Test
    fun `generate parses the JSON contract into content and overrides`() {
        respondWith(
            """
            {"GENERIC":{"title":"v2 is out","text":"master text"},
             "REDDIT":{"title":"v2","text":"community text"},
             "X":{"title":"would be ignored","text":"short one"}}
            """.trimIndent()
        )

        val result = generate(GeneratePlatform.GENERIC, GeneratePlatform.REDDIT, GeneratePlatform.X)

        assertEquals(GeneratedContent("v2 is out", "master text"), result.content)
        assertEquals(GeneratedContent("v2", "community text"), result.overrides[Platform.REDDIT])
        // title is only part of the contract for GENERIC and REDDIT
        assertEquals(GeneratedContent(null, "short one"), result.overrides[Platform.X])
        assertEquals(11L, result.inputTokens)
        assertEquals(7L, result.outputTokens)
    }

    @Test
    fun `generate strips markdown fences the model added anyway`() {
        respondWith("```json\n{\"GENERIC\":{\"text\":\"fenced\"}}\n```")

        assertEquals(GeneratedContent(null, "fenced"), generate(GeneratePlatform.GENERIC).content)
    }

    @Test
    fun `generate tolerates missing entries and drops title-only ones`() {
        respondWith("""{"GENERIC":{"text":"master"},"X":{"title":"no text here"}}""")

        val result = generate(GeneratePlatform.GENERIC, GeneratePlatform.X)

        assertEquals(GeneratedContent(null, "master"), result.content)
        assertTrue(result.overrides.isEmpty(), "X has no text -> dropped, not an error")
    }

    @Test
    fun `generate fails as AiProviderException on non-JSON output`() {
        respondWith("Sure! Here's your post: ...")

        val e = assertFailsWith<AiProviderException> { generate(GeneratePlatform.GENERIC) }
        assertTrue(e.message!!.contains("not valid JSON"))
    }

    @Test
    fun `generate maps upstream failures to AiProviderException`() {
        whenever(messagesClient.create(any<MessageCreateParams>())).thenThrow(RuntimeException("connection reset"))

        val e = assertFailsWith<AiProviderException> { generate(GeneratePlatform.GENERIC) }
        assertTrue(e.message!!.contains("connection reset"))
    }

    @Test
    fun `generate sends model, cap, effort, prompt and the response contract`() {
        respondWith("""{"GENERIC":{"text":"ok"}}""")
        generate(GeneratePlatform.GENERIC, GeneratePlatform.REDDIT, effort = GenerateEffort.HIGH)

        val captor = argumentCaptor<MessageCreateParams>()
        verify(messagesClient).create(captor.capture())
        val params = captor.firstValue

        assertEquals("claude-sonnet-5", params.model().value())
        assertEquals(4096L, params.maxTokens())
        assertEquals(OutputConfig.Effort.HIGH, params.outputConfig().effort())
        val system = params.system().joinToString("\n") { it.text() }
        assertTrue(system.contains("Requested keys: GENERIC, REDDIT"))
        assertTrue(!system.contains("280 characters")) // X not requested -> its rule is not offered
        assertTrue(system.contains("\"REDDIT\": {\"title\""))
        assertTrue(system.contains("Keep every text under 500 characters."))
        assertTrue(system.contains("Tone: casual."))
        assertTrue(params.messages().first().content().toString().contains("releasing v2"))
    }

    @Test
    fun `generate offers the X rule only when X is requested`() {
        respondWith("""{"GENERIC":{"text":"ok"}}""")
        generate(GeneratePlatform.GENERIC)

        val system = argumentCaptor<MessageCreateParams>().apply {
            verify(messagesClient).create(capture())
        }.firstValue.system().joinToString("\n") { it.text() }
        assertTrue(!system.contains("280 characters"))
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
    fun `verify counts the models the key can use`() {
        val page = mock<ModelListPage>()
        whenever(modelsClient.list()).thenReturn(page)
        whenever(page.items()).thenReturn(listOf(mock<ModelInfo>(), mock<ModelInfo>()))

        assertEquals("Key valid — 2 models available", adapter.verify("key-1", null))
    }

    @Test
    fun `verify mentions the custom baseUrl when one is set`() {
        val page = mock<ModelListPage>()
        whenever(modelsClient.list()).thenReturn(page)
        whenever(page.items()).thenReturn(listOf(mock<ModelInfo>()))

        assertEquals(
            "Key valid — 1 models available via http://localhost:5001",
            adapter.verify("key-1", "http://localhost:5001"),
        )
    }
}
