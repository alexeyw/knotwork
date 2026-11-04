package app.knotwork.android.presentation.ui.pipeline.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** What the SKILL node sheet's read-only instruction field is given ([asInstructionPreview]). */
class SkillInstructionPreviewTest {

    @Test
    fun `given an instruction within the limit when previewed then it is shown as is`() {
        val instruction = "Summarise \$MEMORY_SUMMARY in three bullet points."

        assertSame(instruction, instruction.asInstructionPreview())
    }

    @Test
    fun `given an instruction exactly at the limit when previewed then it is not cut`() {
        val instruction = "a".repeat(SKILL_INSTRUCTION_PREVIEW_MAX_CHARS)

        assertEquals(instruction, instruction.asInstructionPreview())
    }

    @Test
    fun `given a longer instruction when previewed then it is cut at the limit and marked`() {
        val instruction = "x".repeat(200_000)

        val preview = instruction.asInstructionPreview()

        assertEquals("x".repeat(SKILL_INSTRUCTION_PREVIEW_MAX_CHARS) + "…", preview)
    }

    @Test
    fun `given a surrogate pair at the limit when previewed then the pair is not split`() {
        val instruction = "a".repeat(SKILL_INSTRUCTION_PREVIEW_MAX_CHARS - 1) + "😀" + "tail"

        val preview = instruction.asInstructionPreview()

        assertEquals("a".repeat(SKILL_INSTRUCTION_PREVIEW_MAX_CHARS - 1) + "…", preview)
    }
}
