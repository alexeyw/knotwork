package app.knotwork.android.domain.text

import app.knotwork.android.domain.memoryio.MemoryJsonSerializer
import app.knotwork.android.domain.models.MemoryImportOutcome
import app.knotwork.android.domain.models.PipelineBundleImportOutcome
import app.knotwork.android.domain.models.PipelineImportOutcome
import app.knotwork.android.domain.models.PipelinePresetImportOutcome
import app.knotwork.android.domain.models.PromptPresetImportOutcome
import app.knotwork.android.domain.models.SkillImportOutcome
import app.knotwork.android.domain.pipelineio.PipelineBundleJsonSerializer
import app.knotwork.android.domain.pipelineio.PipelineJsonSerializer
import app.knotwork.android.domain.pipelineio.PipelinePresetJsonSerializer
import app.knotwork.android.domain.promptio.PromptPresetJsonSerializer
import app.knotwork.android.domain.skillio.SkillJsonSerializer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every import parser answers a deeply nested document with a failure instead of
 * throwing.
 *
 * Robolectric, because the platform matters here: under it the Android `org.json`
 * runs, which recurses once per nesting level and throws `StackOverflowError` — an
 * `Error` no `catch (JSONException)` sees — at a depth of 10 000 (about 20 KB, far
 * inside the 8 MB import limit). The standalone `org.json` the plain JVM tests use
 * turns the overflow into a `JSONException` itself, so a JVM-only test of this would
 * pass without the guard.
 */
@RunWith(RobolectricTestRunner::class)
class DeeplyNestedImportTest {

    private val deep = "{\"a\":" + "[".repeat(DEPTH) + "]".repeat(DEPTH) + "}"

    @Test
    fun `given a deeply nested pipeline document when parsed then it fails instead of throwing`() {
        assertTrue(PipelineJsonSerializer.parse(deep) is PipelineImportOutcome.Failure)
    }

    @Test
    fun `given a deeply nested bundle when checked and parsed then neither throws`() {
        assertFalse(PipelineBundleJsonSerializer.looksLikeBundle(deep))
        assertTrue(PipelineBundleJsonSerializer.parse(deep) is PipelineBundleImportOutcome.Failure)
    }

    @Test
    fun `given a deeply nested memory export when parsed then it fails instead of throwing`() {
        assertTrue(MemoryJsonSerializer.parse(deep) is MemoryImportOutcome.Failure)
    }

    @Test
    fun `given deeply nested presets and skills when parsed then each fails instead of throwing`() {
        assertTrue(PipelinePresetJsonSerializer.parse(deep, isBundled = false) is PipelinePresetImportOutcome.Failure)
        assertTrue(PromptPresetJsonSerializer.parse(deep, isBundled = false) is PromptPresetImportOutcome.Failure)
        assertTrue(SkillJsonSerializer.parse(deep, isBundled = false) is SkillImportOutcome.Failure)
    }

    private companion object {
        /** The first depth measured to overflow the Android parser under Robolectric (1 000 still parses). */
        const val DEPTH = 10_000
    }
}
