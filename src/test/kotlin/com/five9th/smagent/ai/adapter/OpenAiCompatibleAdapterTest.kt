package com.five9th.smagent.ai.adapter

import com.five9th.smagent.domain.GenerateEffort
import com.five9th.smagent.domain.GeneratePlatform
import com.five9th.smagent.domain.Platform
import com.openai.client.OpenAIClient
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessage
import com.openai.models.completions.CompletionUsage
import com.openai.models.models.Model
import com.openai.models.models.ModelListPage
import com.openai.services.blocking.ChatService
import com.openai.services.blocking.ModelService
import com.openai.services.blocking.chat.ChatCompletionService
import java.util.Optional
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

/** generate/verify against a stubbed OpenAIClient: params, result mapping, error handling. */
class OpenAiCompatibleAdapterTest {

    private val openAiClient = mock<OpenAIClient>()
    private val chatService = mock<ChatService>()
    private val completionsService = mock<ChatCompletionService>()
    private val modelsService = mock<ModelService>()
    private val completion = mock<ChatCompletion>()
    private val choice = mock<ChatCompletion.Choice>()
    private val message = mock<ChatCompletionMessage>()
    private val usage = mock<CompletionUsage>()

    private val adapter = OpenAiCompatibleAdapter().apply { clientFactory = { _, _ -> openAiClient } }

    @BeforeEach
    fun stubClient() {
        whenever(openAiClient.chat()).thenReturn(chatService)
        whenever(openAiClient.models()).thenReturn(modelsService)
        whenever(chatService.completions()).thenReturn(completionsService)
        whenever(completionsService.create(any<ChatCompletionCreateParams>())).thenReturn(completion)
        whenever(completion.choices()).thenReturn(listOf(choice))
        whenever(choice.message()).thenReturn(message)
        whenever(message.content()).thenReturn(Optional.empty())
        whenever(completion.usage()).thenReturn(Optional.empty())
    }

    private fun respondWith(raw: String, promptTokens: Long = 11, completionTokens: Long = 7) {
        whenever(message.content()).thenReturn(Optional.of(raw))
        whenever(completion.usage()).thenReturn(Optional.of(usage))
        whenever(usage.promptTokens()).thenReturn(promptTokens)
        whenever(usage.completionTokens()).thenReturn(completionTokens)
    }

    private fun generate(vararg platforms: GeneratePlatform) =
        adapter.generate(
            apiKey = "sk-or-key",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "deepseek/deepseek-chat",
            effort = GenerateEffort.HIGH, // must be accepted but never sent (see adapter TODO)
            command = GenerateCommand(
                prompt = "Post about releasing v2 of our CLI tool",
                platforms = platforms.toSet(),
            ),
        )

    @Test
    fun `generate maps a model answer into content, overrides and usage`() {
        respondWith(
            """
            {"GENERIC":{"title":"v2 is out","text":"master text"},
             "REDDIT":{"title":"v2","text":"community text"},
             "X":{"title":"ignored","text":"short one"}}
            """.trimIndent()
        )

        val result = generate(GeneratePlatform.GENERIC, GeneratePlatform.REDDIT, GeneratePlatform.X)

        assertEquals(GeneratedContent("v2 is out", "master text"), result.content)
        assertEquals(GeneratedContent("v2", "community text"), result.overrides[Platform.REDDIT])
        assertEquals(GeneratedContent(null, "short one"), result.overrides[Platform.X])
        assertEquals(11L, result.inputTokens)
        assertEquals(7L, result.outputTokens)
    }

    @Test
    fun `generate sends model, cap, contract and prompt - and no reasoning effort`() {
        respondWith("""{"GENERIC":{"text":"ok"}}""")
        generate(GeneratePlatform.GENERIC)

        val captor = argumentCaptor<ChatCompletionCreateParams>()
        verify(completionsService).create(captor.capture())
        val params = captor.firstValue

        assertEquals("deepseek/deepseek-chat", params.model().asString())
        assertEquals(4096L, params.maxCompletionTokens().orElse(0L))
        // deliberately never sent: heterogeneous endpoints reject unknown params
        assertTrue(params.reasoningEffort().isEmpty)
        // system contract first, user prompt second
        val messages = params.messages()
        assertEquals(2, messages.size)
        assertTrue(messages[0].isSystem())
        assertTrue(messages[0].asSystem().toString().contains("Requested keys: GENERIC"))
        assertTrue(messages[1].isUser())
        assertTrue(messages[1].asUser().toString().contains("releasing v2"))
    }

    @Test
    fun `generate maps upstream failures to AiProviderException`() {
        whenever(completionsService.create(any<ChatCompletionCreateParams>()))
            .thenThrow(RuntimeException("connection reset"))

        val e = assertFailsWith<AiProviderException> { generate(GeneratePlatform.GENERIC) }
        assertTrue(e.message!!.contains("connection reset"))
    }

    @Test
    fun `generate passes apiKey and baseUrl to the client factory`() {
        respondWith("""{"GENERIC":{"text":"ok"}}""")
        var seen: Pair<String, String?>? = null
        adapter.clientFactory = { apiKey, baseUrl ->
            seen = apiKey to baseUrl
            openAiClient
        }

        adapter.generate(
            "key-1", "http://localhost:11434/v1", "llama3.1", GenerateEffort.LOW,
            GenerateCommand("hi", setOf(GeneratePlatform.GENERIC)),
        )

        assertEquals("key-1" to "http://localhost:11434/v1", seen)
    }

    @Test
    fun `listModels returns sorted model ids`() {
        val page = mock<ModelListPage>()
        whenever(modelsService.list()).thenReturn(page)
        val llama = mock<Model>()
        whenever(llama.id()).thenReturn("meta-llama/llama-3.1-405b-instruct")
        val deepseek = mock<Model>()
        whenever(deepseek.id()).thenReturn("deepseek/deepseek-chat")
        whenever(page.items()).thenReturn(listOf(llama, deepseek))

        assertEquals(
            listOf("deepseek/deepseek-chat", "meta-llama/llama-3.1-405b-instruct"),
            adapter.listModels("key-1", null),
        )
    }

    @Test
    fun `verify counts the models the key can use`() {
        val page = mock<ModelListPage>()
        whenever(modelsService.list()).thenReturn(page)
        val m1 = mock<Model>()
        whenever(m1.id()).thenReturn("deepseek/deepseek-chat")
        val m2 = mock<Model>()
        whenever(m2.id()).thenReturn("openai/gpt-5.2")
        whenever(page.items()).thenReturn(listOf(m1, m2))

        assertEquals(
            "Key valid — 2 models available via https://openrouter.ai/api/v1",
            adapter.verify("key-1", "https://openrouter.ai/api/v1"),
        )
    }
}
