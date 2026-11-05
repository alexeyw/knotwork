package app.knotwork.android.presentation.run

import app.knotwork.android.R
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.models.RunTerminationKind
import app.knotwork.android.domain.models.RunTerminationReason
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.presentation.ui.common.RunTerminationCopyMapper
import app.knotwork.android.presentation.ui.common.resolve
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Coverage for [RunOutcomeAnnouncerImpl] — the line a stopped run leaves in the
 * chat it ran in.
 *
 * The defect it exists to end: a run that failed while the app was in the
 * background reached the user only as ViewModel state, so returning to the chat
 * found the user's message and no reply at all. Two properties therefore matter
 * more than the wording — the line is **visible** (`isFinal = true`, which the
 * display query filters on), and it says the **same thing** every other surface
 * says about the same event.
 */
@RunWith(RobolectricTestRunner::class)
class RunOutcomeAnnouncerImplTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var chatRepository: ChatRepository
    private lateinit var announcer: RunOutcomeAnnouncerImpl
    private val saved = slot<ChatMessage>()

    @Before
    fun setup() {
        chatRepository = mockk()
        coEvery { chatRepository.saveMessage(capture(saved)) } returns Unit
        announcer = RunOutcomeAnnouncerImpl(context, chatRepository)
    }

    @Test
    fun `given a classified stop when announced then it reuses the shared vocabulary`() = runTest {
        // Not "some sentence about a ceiling": byte-identical to what the chat
        // tile and the background notification say, because they resolve the
        // same mapper. A second wording here is the fork this whole vocabulary
        // exists to prevent.
        val reason = RunTerminationReason.StepCeiling(spent = 40, limit = 40)

        announcer.announce("s-1", PipelineRunStatus.FAILED, reason, "step-ceiling")

        val expected = context.resolve(RunTerminationCopyMapper.terminationCopy(reason).body)
        assertEquals(expected, saved.captured.content)
    }

    @Test
    fun `given every termination kind when announced then none of them falls back to the diagnostic`() = runTest {
        // Walks every kind rather than the interesting ones, for the reason
        // `RunTerminationCopyMapperTest` gives: a mapper covering only the
        // ceilings would leave the enum constant on screen for the rest.
        RunTerminationKind.entries.forEach { kind ->
            val reason = reasonFor(kind)
            announcer.announce("s-1", PipelineRunStatus.FAILED, reason, DIAGNOSTIC)

            assertEquals(
                "$kind fell through to the diagnostic instead of its own sentence.",
                context.resolve(RunTerminationCopyMapper.terminationCopy(reason).body),
                saved.captured.content,
            )
        }
    }

    @Test
    fun `given a failure the engine did not classify when announced then the diagnostic is carried`() = runTest {
        announcer.announce("s-1", PipelineRunStatus.FAILED, reason = null, diagnostic = DIAGNOSTIC)

        assertEquals(
            context.getString(R.string.run_outcome_chat_failed_with_diagnostic, DIAGNOSTIC),
            saved.captured.content,
        )
    }

    @Test
    fun `given a failure with nothing to report when announced then the bare sentence is used`() = runTest {
        announcer.announce("s-1", PipelineRunStatus.FAILED, reason = null, diagnostic = "   ")

        assertEquals(context.getString(R.string.run_outcome_chat_failed), saved.captured.content)
    }

    @Test
    fun `given a cancelled run when announced then it says so`() = runTest {
        announcer.announce("s-1", PipelineRunStatus.CANCELLED, reason = null, diagnostic = null)

        assertEquals(context.getString(R.string.run_outcome_chat_cancelled), saved.captured.content)
    }

    @Test
    fun `given an interrupted run when announced then it says so`() = runTest {
        announcer.announce("s-1", PipelineRunStatus.INTERRUPTED, reason = null, diagnostic = null)

        assertEquals(context.getString(R.string.run_outcome_chat_interrupted), saved.captured.content)
    }

    @Test
    fun `given an announced outcome when saved then it is a visible system message`() = runTest {
        // `isFinal = false` is what the other SYSTEM writers use, and the
        // display query filters those out — a line nobody can see would leave
        // the reported defect exactly where it was.
        announcer.announce("s-1", PipelineRunStatus.CANCELLED, reason = null, diagnostic = null)

        assertEquals(Role.SYSTEM, saved.captured.role)
        assertEquals("s-1", saved.captured.sessionId)
        assertTrue("The line must survive the display filter.", saved.captured.isFinal)
    }

    @Test
    fun `given a completed run when announced then nothing is written`() = runTest {
        announcer.announce("s-1", PipelineRunStatus.COMPLETED, reason = null, diagnostic = null)

        coVerify(exactly = 0) { chatRepository.saveMessage(any()) }
    }

    @Test
    fun `given a non-terminal status when announced then nothing is written`() = runTest {
        listOf(
            PipelineRunStatus.QUEUED,
            PipelineRunStatus.RUNNING,
            PipelineRunStatus.WAITING_APPROVAL,
            PipelineRunStatus.WAITING_CLARIFICATION,
        ).forEach { announcer.announce("s-1", it, reason = null, diagnostic = null) }

        coVerify(exactly = 0) { chatRepository.saveMessage(any()) }
    }

    @Test
    fun `given a blank session when announced then nothing is written`() = runTest {
        announcer.announce("", PipelineRunStatus.FAILED, reason = null, diagnostic = DIAGNOSTIC)

        coVerify(exactly = 0) { chatRepository.saveMessage(any()) }
    }

    @Test
    fun `given the write fails when announcing then the caller is not taken down`() = runTest {
        // The caller is the queue worker settling a run that has already
        // failed. A second failure while recording the first must not kill it.
        coEvery { chatRepository.saveMessage(any()) } throws IOException("disk fault")

        announcer.announce("s-1", PipelineRunStatus.FAILED, reason = null, diagnostic = DIAGNOSTIC)
    }

    @Test
    @Config(qualifiers = "ru")
    fun `given the app in Russian when an outcome is announced then the row is written in Russian`() = runTest {
        // The line is history a model reads; it is written in the interface language
        // at the moment it is saved, so a Russian chat's model reads Russian.
        announcer.announce("s-1", PipelineRunStatus.CANCELLED, reason = null, diagnostic = null)
        assertEquals("Этот прогон остановили, прежде чем он успел ответить.", saved.captured.content)

        announcer.announce("s-1", PipelineRunStatus.FAILED, RunTerminationReason.NoProgress, DIAGNOSTIC)
        assertEquals(
            "Одна и та же работа повторялась и не продвигала задачу, поэтому прогон завершён.",
            saved.captured.content,
        )
    }

    /** Builds a representative [RunTerminationReason] for [kind], with numbers where the kind carries them. */
    private fun reasonFor(kind: RunTerminationKind): RunTerminationReason = when (kind) {
        RunTerminationKind.STEP_CEILING -> RunTerminationReason.StepCeiling(spent = 40, limit = 40)
        RunTerminationKind.TOKEN_CEILING -> RunTerminationReason.TokenCeiling(spent = 8_000, limit = 8_000)
        RunTerminationKind.NO_PROGRESS -> RunTerminationReason.NoProgress
        RunTerminationKind.RUN_STALLED -> RunTerminationReason.RunStalled
        RunTerminationKind.HITL_WINDOW_EXPIRED -> RunTerminationReason.HitlWindowExpired
        RunTerminationKind.GRAPH_CHANGED -> RunTerminationReason.GraphChanged
        RunTerminationKind.PROCESS_DIED -> RunTerminationReason.ProcessDied
        RunTerminationKind.DISCARDED_BY_USER -> RunTerminationReason.DiscardedByUser
        RunTerminationKind.NOT_RESUMABLE -> RunTerminationReason.NotResumable
    }

    private companion object {
        const val DIAGNOSTIC = "engine-diagnostic"
    }
}
