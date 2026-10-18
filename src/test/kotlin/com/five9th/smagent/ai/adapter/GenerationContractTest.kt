package com.five9th.smagent.ai.adapter

import com.five9th.smagent.domain.GeneratePlatform
import com.five9th.smagent.domain.Platform
import com.five9th.smagent.domain.PostContent
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** The shared prompt/parse contract — provider-agnostic, no SDK types involved. */
class GenerationContractTest {

    private fun command(
        platforms: Set<GeneratePlatform>,
        constraints: GenerateConstraints? = null,
        editorState: Map<GeneratePlatform, PostContent>? = null,
    ) = GenerateCommand("Post about releasing v2 of our CLI tool", platforms, constraints, editorState)

    @Test
    fun `system prompt lists the requested keys and their rules`() {
        val prompt = GenerationContract.systemPrompt(
            command(setOf(GeneratePlatform.GENERIC, GeneratePlatform.X, GeneratePlatform.REDDIT))
        )

        assertTrue(prompt.contains("Requested keys: GENERIC, X, REDDIT"))
        assertTrue(prompt.contains("\"GENERIC\": {\"text\"")) // REDDIT requested -> generic carries no title
        assertTrue(prompt.contains("\"X\": {\"text\""))
        assertTrue(prompt.contains("\"REDDIT\": {\"title\""))
    }

    @Test
    fun `system prompt requires a GENERIC title only when REDDIT is not requested`() {
        val standalone = GenerationContract.systemPrompt(command(setOf(GeneratePlatform.GENERIC, GeneratePlatform.LINKEDIN)))
        assertTrue(standalone.contains("\"GENERIC\": {\"title\": <string>, \"text\": <string>}"))
        assertTrue(standalone.contains("The title is REQUIRED: this text may later be published to Reddit"))

        val withReddit = GenerationContract.systemPrompt(command(setOf(GeneratePlatform.GENERIC, GeneratePlatform.REDDIT)))
        assertTrue(withReddit.contains("\"GENERIC\": {\"text\": <string>}"))
        assertTrue(withReddit.contains("No title: the requested REDDIT entry carries its own"))
    }

    @Test
    fun `system prompt tells entries to stand alone without a title`() {
        val prompt = GenerationContract.systemPrompt(command(setOf(GeneratePlatform.GENERIC, GeneratePlatform.X)))

        assertTrue(prompt.contains("only Reddit displays one"))
        assertTrue(prompt.contains("must stand on its own"))
    }

    @Test
    fun `system prompt omits rules for platforms that were not requested`() {
        val prompt = GenerationContract.systemPrompt(command(setOf(GeneratePlatform.GENERIC)))

        assertTrue(prompt.contains("\"GENERIC\": {\"title\"")) // no REDDIT -> generic needs the title
        assertFalse(prompt.contains("280 characters")) // X rule not offered
        assertFalse(prompt.contains("- \"REDDIT\": {")) // no REDDIT entry rule (the global titles note still mentions Reddit)
    }

    @Test
    fun `system prompt folds in constraints and skips them when absent`() {
        val constrained = GenerationContract.systemPrompt(
            command(setOf(GeneratePlatform.GENERIC), GenerateConstraints(maxLength = 500, tone = "casual"))
        )
        assertTrue(constrained.contains("Keep every text under 500 characters."))
        assertTrue(constrained.contains("Tone: casual."))

        val plain = GenerationContract.systemPrompt(command(setOf(GeneratePlatform.GENERIC)))
        assertFalse(plain.contains("Constraints:"))
    }

    @Test
    fun `system prompt adds the edit contract and link guard only when a draft is provided`() {
        val draft = mapOf(
            GeneratePlatform.GENERIC to PostContent(title = "v2", text = "master", link = "https://example.com/v2"),
        )

        val editing = GenerationContract.systemPrompt(command(setOf(GeneratePlatform.GENERIC), editorState = draft))
        assertTrue(editing.contains("edit of that draft"))
        assertTrue(editing.contains("full replacements, never diffs"))
        assertTrue(editing.contains("Do not repeat that URL in any text"))

        val fresh = GenerationContract.systemPrompt(command(setOf(GeneratePlatform.GENERIC)))
        assertFalse(fresh.contains("edit of that draft"))
        assertFalse(fresh.contains("Do not repeat that URL"))
    }

    @Test
    fun `user message is just the prompt without a draft`() {
        assertEquals(
            "Post about releasing v2 of our CLI tool",
            GenerationContract.userMessage(command(setOf(GeneratePlatform.GENERIC))),
        )
    }

    @Test
    fun `user message renders the sanitized draft before the request`() {
        val message = GenerationContract.userMessage(
            command(
                setOf(GeneratePlatform.GENERIC, GeneratePlatform.X),
                editorState = mapOf(
                    GeneratePlatform.GENERIC to PostContent("v2", "master text", "https://example.com/v2"),
                    GeneratePlatform.X to PostContent(text = "short one"), // no link field rendered
                    GeneratePlatform.LINKEDIN to PostContent(text = "  "), // blank text -> dropped
                ),
            )
        )

        assertTrue(message.startsWith("Current draft:"))
        assertTrue(message.contains("\"GENERIC\":{\"title\":\"v2\",\"text\":\"master text\",\"link\":\"https://example.com/v2\"}"))
        assertTrue(message.contains("\"X\":{\"text\":\"short one\"}"))
        assertFalse(message.contains("LINKEDIN"))
        assertTrue(message.contains("Request: Post about releasing v2 of our CLI tool"))
    }

    @Test
    fun `parse reads titles only where the contract allows`() {
        val raw = """
            {"GENERIC":{"title":"generic title","text":"master"},
             "REDDIT":{"title":"v2","text":"community"},
             "X":{"title":"ignored","text":"short one"}}
        """.trimIndent()

        // no REDDIT requested -> the generic title is part of the contract
        val standalone = GenerationContract.parseGenerated(raw, setOf(GeneratePlatform.GENERIC, GeneratePlatform.X))
        assertEquals(GeneratedContent("generic title", "master"), standalone[GeneratePlatform.GENERIC])
        assertEquals(GeneratedContent(null, "short one"), standalone[GeneratePlatform.X])

        // REDDIT requested -> the generic title is dropped, REDDIT keeps its own
        val withReddit = GenerationContract.parseGenerated(
            raw, setOf(GeneratePlatform.GENERIC, GeneratePlatform.REDDIT, GeneratePlatform.X)
        )
        assertEquals(GeneratedContent(null, "master"), withReddit[GeneratePlatform.GENERIC])
        assertEquals(GeneratedContent("v2", "community"), withReddit[GeneratePlatform.REDDIT])
    }

    @Test
    fun `parse strips markdown fences the model added anyway`() {
        val entries = GenerationContract.parseGenerated(
            "```json\n{\"GENERIC\":{\"text\":\"fenced\"}}\n```",
            setOf(GeneratePlatform.GENERIC),
        )

        assertEquals(GeneratedContent(null, "fenced"), entries[GeneratePlatform.GENERIC])
    }

    @Test
    fun `parse tolerates missing entries and drops title-only ones`() {
        val entries = GenerationContract.parseGenerated(
            """{"GENERIC":{"text":"master"},"X":{"title":"no text here"}}""",
            setOf(GeneratePlatform.GENERIC, GeneratePlatform.X),
        )

        assertEquals(GeneratedContent(null, "master"), entries[GeneratePlatform.GENERIC])
        assertFalse(GeneratePlatform.X in entries)
    }

    @Test
    fun `parse fails on non-JSON output`() {
        val e = assertFailsWith<AiProviderException> {
            GenerationContract.parseGenerated("Sure! Here's your post: ...", setOf(GeneratePlatform.GENERIC))
        }
        assertTrue(e.message!!.contains("not valid JSON"))
    }

    @Test
    fun `assembleResult splits content and overrides`() {
        val entries = mapOf(
            GeneratePlatform.GENERIC to GeneratedContent("t", "master"),
            GeneratePlatform.X to GeneratedContent(null, "short"),
        )

        val result = GenerationContract.assembleResult(entries, inputTokens = 11, outputTokens = 7)

        assertEquals(GeneratedContent("t", "master"), result.content)
        assertEquals(mapOf(Platform.X to GeneratedContent(null, "short")), result.overrides)
        assertEquals(11L, result.inputTokens)
        assertEquals(7L, result.outputTokens)
    }
}
