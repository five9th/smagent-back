package com.five9th.smagent.ai.adapter

import com.five9th.smagent.domain.GeneratePlatform
import com.five9th.smagent.domain.Platform
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
    ) = GenerateCommand("Post about releasing v2 of our CLI tool", platforms, constraints)

    @Test
    fun `system prompt lists the requested keys and their rules`() {
        val prompt = GenerationContract.systemPrompt(
            command(setOf(GeneratePlatform.GENERIC, GeneratePlatform.X, GeneratePlatform.REDDIT))
        )

        assertTrue(prompt.contains("Requested keys: GENERIC, X, REDDIT"))
        assertTrue(prompt.contains("\"GENERIC\": {\"title\""))
        assertTrue(prompt.contains("\"X\": {\"text\""))
        assertTrue(prompt.contains("\"REDDIT\": {\"title\""))
    }

    @Test
    fun `system prompt omits rules for platforms that were not requested`() {
        val prompt = GenerationContract.systemPrompt(command(setOf(GeneratePlatform.GENERIC)))

        assertTrue(prompt.contains("\"GENERIC\""))
        assertFalse(prompt.contains("280 characters")) // X rule not offered
        assertFalse(prompt.contains("\"REDDIT\""))
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
    fun `parse reads titles only on GENERIC and REDDIT`() {
        val entries = GenerationContract.parseGenerated(
            """
            {"GENERIC":{"title":"v2 is out","text":"master"},
             "REDDIT":{"title":"v2","text":"community"},
             "X":{"title":"would be ignored","text":"short one"}}
            """.trimIndent(),
            setOf(GeneratePlatform.GENERIC, GeneratePlatform.REDDIT, GeneratePlatform.X),
        )

        assertEquals(GeneratedContent("v2 is out", "master"), entries[GeneratePlatform.GENERIC])
        assertEquals(GeneratedContent("v2", "community"), entries[GeneratePlatform.REDDIT])
        assertEquals(GeneratedContent(null, "short one"), entries[GeneratePlatform.X])
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
