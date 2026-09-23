package app.knotwork.android.domain.pipelineio

import app.knotwork.android.domain.models.PipelineSamplePrompt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PipelineSamplePromptJson] — the wire shape of a pipeline's sample prompts,
 * including the ceilings that keep a file from filling the new-chat screen.
 */
class PipelineSamplePromptJsonTest {

    private fun array(vararg prompts: Pair<String, String?>): JSONArray = JSONArray(
        prompts.map { (title, hint) -> JSONObject().put("title", title).apply { hint?.let { put("toolsHint", it) } } },
    )

    @Test
    fun `given prompts with and without a hint when round-tripped then both survive`() {
        val prompts = listOf(PipelineSamplePrompt("Find news", "search_tool"), PipelineSamplePrompt("Say hi"))

        assertEquals(
            prompts,
            PipelineSamplePromptJson.decodeFromString(PipelineSamplePromptJson.encodeToString(prompts)),
        )
    }

    @Test
    fun `given entries without a title or with a blank hint when decoded then they are skipped or nulled`() {
        val decoded = PipelineSamplePromptJson.decodeFromArray(array("" to "x", "Ok" to "  "))

        assertEquals(listOf(PipelineSamplePrompt("Ok", null)), decoded)
    }

    @Test
    fun `given malformed or blank storage when decoded then the list is empty`() {
        assertEquals(emptyList<PipelineSamplePrompt>(), PipelineSamplePromptJson.decodeFromString("not json"))
        assertEquals(emptyList<PipelineSamplePrompt>(), PipelineSamplePromptJson.decodeFromString(" "))
        assertEquals(emptyList<PipelineSamplePrompt>(), PipelineSamplePromptJson.decodeFromArray(null))
    }

    @Test
    fun `given more prompts than the empty state shows when decoded then the list is capped`() {
        val many = array(*Array(5_000) { "Prompt $it" to null })

        val decoded = PipelineSamplePromptJson.decodeFromArray(many)

        assertEquals(PipelineSamplePromptJson.MAX_SAMPLE_PROMPTS, decoded.size)
        assertEquals("Prompt 0", decoded.first().title)
    }

    @Test
    fun `given an overlong title and hint when decoded then each is one line within its ceiling`() {
        val decoded = PipelineSamplePromptJson.decodeFromArray(
            array("T".repeat(10_000) + "\n\nsecond line" to "h".repeat(10_000)),
        ).single()

        assertEquals("T".repeat(PipelineSamplePromptJson.MAX_TITLE_LENGTH), decoded.title)
        assertEquals("h".repeat(PipelineSamplePromptJson.MAX_TOOLS_HINT_LENGTH), decoded.toolsHint)
    }

    @Test
    fun `given a title that flattens to nothing when decoded then the entry is skipped`() {
        assertEquals(
            emptyList<PipelineSamplePrompt>(),
            PipelineSamplePromptJson.decodeFromArray(
                array(
                    "\u2028 \n" to "x",
                ),
            ),
        )
    }

    @Test
    fun `given a hint that flattens to nothing when decoded then it is null`() {
        assertNull(PipelineSamplePromptJson.decodeFromArray(array("Ok" to "\n\t")).single().toolsHint)
    }
}
