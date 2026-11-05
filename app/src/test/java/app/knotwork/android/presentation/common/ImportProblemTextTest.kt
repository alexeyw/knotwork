package app.knotwork.android.presentation.common

import android.content.Context
import app.knotwork.android.R
import app.knotwork.android.domain.models.ChatImportReason
import app.knotwork.android.domain.models.ImportProblem
import app.knotwork.android.domain.models.InputProblem
import app.knotwork.android.domain.models.InvalidInputException
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineValidationError
import app.knotwork.android.domain.text.ImportedText
import app.knotwork.android.domain.usecases.BundleExportProblem
import app.knotwork.android.domain.usecases.PipelineBundleExportException
import app.knotwork.android.presentation.ui.chat.home.chatImportReasonText
import app.knotwork.android.presentation.ui.common.UiText
import app.knotwork.android.presentation.ui.common.resolve
import app.knotwork.android.presentation.ui.orchestrator.OrchestratorErrorText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The sentences a refused import, export or input becomes. The English reads as the
 * parsers' messages did before they became types; the Russian comes from the same
 * resources; a refusal nested in a bundle is worded in the same language; and a value
 * quoted from a file stays one bounded line even when it reaches the wording raw.
 */
@RunWith(RobolectricTestRunner::class)
class ImportProblemTextTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun words(text: UiText): String = context.resolve(text)

    @Test
    fun `given the parsers' refusals when worded in English then they read as the messages did`() {
        assertEquals("Missing required field: name", words(importProblemText(ImportProblem.MissingField("name"))))
        assertEquals("Duplicate node id \"n5\"", words(importProblemText(ImportProblem.DuplicateNodeId("n5"))))
        assertEquals(
            "Connection \"m\" from IF_CONDITION node \"if\" is labelled \"maybe\"; its branches are True, False",
            words(
                importProblemText(
                    ImportProblem.LabelNotABranch("m", "IF_CONDITION", "if", "maybe", listOf("True", "False")),
                ),
            ),
        )
        assertEquals(
            "File contains 9 memory chunks, more than the 5 the memory can hold",
            words(importProblemText(ImportProblem.TooManyMemories(count = 9, limit = 5))),
        )
    }

    @Test
    fun `given a refusal inside a bundle when worded then the inner reason is worded in the same sentence`() {
        val nested = ImportProblem.BundleEntryInvalid(2, ImportProblem.MissingField("nodes"))

        assertEquals("Bundle pipeline #2 is invalid: Missing required field: nodes", words(importProblemText(nested)))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `given the app in Russian when a refusal is worded then the sentence and the inner one are Russian`() {
        val nested = ImportProblem.BundleEntryInvalid(2, ImportProblem.MissingField("nodes"))

        assertEquals("Пайплайн №2 в пакете неверен: Нет обязательного поля: nodes", words(importProblemText(nested)))
        assertEquals(
            "Имя пайплайна не может быть пустым",
            words(inputProblemText(InputProblem.BlankName(InputProblem.Subject.PIPELINE))),
        )
    }

    @Test
    fun `given more ids or findings than a sentence lists when worded then the rest are counted in the language`() {
        val ids = ImportProblem.MissingPipelines(listOf("a", "b"), more = 3)
        val findings = ImportProblem.PipelineFailsChecks(
            PipelineGraph(id = "p", name = "Big"),
            List(8) { PipelineValidationError.MissingInput },
        )

        assertEquals(
            "Bundle references pipelines not contained in it: \"a\", \"b\" and 3 more",
            words(importProblemText(ids)),
        )
        assertTrue(words(importProblemText(findings)).endsWith(" and 3 more"))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `given the app in Russian when ids are listed then they take Russian quotes and count`() {
        val ids = ImportProblem.MissingPipelines(listOf("a", "b"), more = 3)

        assertEquals("Пакет вызывает пайплайны, которых в нём нет: «a», «b» и ещё 3", words(importProblemText(ids)))
    }

    @Test
    fun `given a pipeline in a bundle that fails its checks when worded then each finding is named`() {
        val graph = PipelineGraph(id = "p", name = "Daily digest")
        val problem = ImportProblem.PipelineFailsChecks(
            graph,
            listOf(PipelineValidationError.MissingInput, PipelineValidationError.MissingOutput),
        )

        val text = words(importProblemText(problem))

        assertTrue(text, text.startsWith("Pipeline \"Daily digest\" is invalid: "))
        assertTrue(text, text.contains(words(UiText(R.string.errors_orchestrator_validation_missing_input))))
    }

    @Test
    fun `given hostile values that slipped past the parser when worded then every sentence is one bounded line`() {
        val bound = ImportedText.MAX_QUOTED_VALUE_LENGTH

        hostileProblems().map { words(importProblemText(it)) }.forEach { sentence ->
            assertTrue("line or bidi break in: $sentence", sentence.none { it.isISOControl() || it in LAYOUT_BREAKING })
            assertFalse("a value longer than $bound in: $sentence", sentence.contains("A".repeat(bound + 1)))
        }
    }

    @Test
    fun `given the app's own refusals when thrown then the library words them, and other errors by their message`() {
        val refusal = InvalidInputException(InputProblem.NameTooLong(InputProblem.Subject.PRESET, 80))
        val export = PipelineBundleExportException(BundleExportProblem.TooManyPipelines(50))

        assertEquals("Preset name must be at most 80 characters", words(OrchestratorErrorText.forThrowable(refusal)))
        assertEquals(
            "Dependency closure exceeds the limit of 50 pipelines.",
            words(OrchestratorErrorText.forThrowable(export)),
        )
        assertEquals("disk full", words(OrchestratorErrorText.forThrowable(IllegalStateException("disk full"))))
    }

    @Test
    fun `given a chat file's reasons when worded then each completes the import sentence`() {
        assertEquals("the file is not valid JSON", words(chatImportReasonText(ChatImportReason.NotJson)))
        assertEquals(
            "message 3 of the file is not a message",
            words(chatImportReasonText(ChatImportReason.NotAMessage(3))),
        )
    }

    /** One problem per variant that quotes the file, each carrying a value written to break the line. */
    private fun hostileProblems(): List<ImportProblem> = listOf(
        ImportProblem.NotJson(HOSTILE),
        ImportProblem.Malformed(HOSTILE),
        ImportProblem.MissingField(HOSTILE),
        ImportProblem.DuplicateNodeId(HOSTILE),
        ImportProblem.DuplicateConnectionId(HOSTILE),
        ImportProblem.NodeWithoutType(HOSTILE),
        ImportProblem.UnknownNodeType(HOSTILE, HOSTILE),
        ImportProblem.ConnectionWithoutEnd(HOSTILE, HOSTILE),
        ImportProblem.ConnectionToUnknownNode(HOSTILE, HOSTILE),
        ImportProblem.LabelNotABranch(HOSTILE, HOSTILE, HOSTILE, HOSTILE, listOf(HOSTILE)),
        ImportProblem.ContextFlagNotBoolean(HOSTILE, HOSTILE),
        ImportProblem.UnknownCloudProvider(HOSTILE, HOSTILE),
        ImportProblem.DuplicatePipelineIds(listOf(HOSTILE, HOSTILE), more = 0),
        ImportProblem.MissingPipelines(listOf(HOSTILE), more = 3),
        ImportProblem.BundleEntryInvalid(0, ImportProblem.UnknownNodeType(HOSTILE, HOSTILE)),
        ImportProblem.PipelineFailsChecks(PipelineGraph(id = "p", name = HOSTILE), emptyList()),
    )

    private companion object {
        val HOSTILE = "X\n\nA second paragraph written by the file." + " ‮" + "A".repeat(500)

        val LAYOUT_BREAKING = setOf(' ', ' ') + ('‪'..'‮') + ('⁦'..'⁩')
    }
}
