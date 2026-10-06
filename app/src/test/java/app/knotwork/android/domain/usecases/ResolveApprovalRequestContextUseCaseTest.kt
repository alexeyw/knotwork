package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.models.ApprovalRequestContext
import app.knotwork.android.domain.models.ApprovalRequestSource
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.Trigger
import app.knotwork.android.domain.models.TriggerCondition
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.TriggerJournalRepository
import app.knotwork.android.domain.repositories.TriggerRepository
import app.knotwork.android.domain.text.ApprovalRequestText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ResolveApprovalRequestContextUseCase] — what an approval shows next to the
 * call: the ROOT run's recorded request, labelled by where it came from, made
 * display-safe, and never a reason for the approval itself to fail.
 */
class ResolveApprovalRequestContextUseCaseTest {

    private val runs: PipelineRunRepository = mockk()
    private val journal: TriggerJournalRepository = mockk()
    private val triggers: TriggerRepository = mockk()
    private val resolve = ResolveApprovalRequestContextUseCase(runs, journal, triggers)

    @Test
    fun `given a chat run when resolved then the request is its prompt from the user`() = runTest {
        givenRoot(ROOT_ID, RunOrigin.CHAT, prompt = "Email Anna the slides")

        val context = resolve(ROOT_ID)

        assertEquals(
            ApprovalRequestContext(
                ApprovalRequestSource.Chat,
                "Email Anna the slides",
                shortened = false,
                hadImage = false,
            ),
            context,
        )
    }

    @Test
    fun `given a gate inside a sub-pipeline when resolved then the request is the root's not the node input`() =
        runTest {
            // The child's recorded prompt is what its PIPELINE node passed down —
            // text the pipeline produced, which the user never wrote.
            givenRoot(ROOT_ID, RunOrigin.CHAT, prompt = "Plan my trip to Porto")
            val childId = "$ROOT_ID::sub::0"
            coEvery { runs.getRootRunId(childId) } returns ROOT_ID
            coEvery { runs.getRun(childId) } returns run(childId, RunOrigin.CHAT, prompt = "step 2: book the hotel")

            val context = resolve(childId)

            assertEquals("Plan my trip to Porto", context?.request)
        }

    @Test
    fun `given each origin when resolved then the source names it and only a trigger reads the journal`() = runTest {
        val expected = mapOf(
            RunOrigin.CHAT to ApprovalRequestSource.Chat,
            RunOrigin.SHARE to ApprovalRequestSource.Shared,
            RunOrigin.SCHEDULER to ApprovalRequestSource.ScheduledTask,
            RunOrigin.QUICK_TILE to ApprovalRequestSource.QuickTile,
            RunOrigin.EXTERNAL to ApprovalRequestSource.OtherApp,
            RunOrigin.TRIGGER to ApprovalRequestSource.Trigger(name = "Morning briefing"),
        )
        assertEquals("every origin has an expected source", RunOrigin.entries.toSet(), expected.keys)
        coEvery { journal.findTriggerIdForRun(any()) } returns TRIGGER_ID
        coEvery { triggers.getTriggerById(TRIGGER_ID) } returns trigger(name = "Morning briefing")

        expected.forEach { (origin, source) ->
            val runId = "run-$origin"
            givenRoot(runId, origin, prompt = "do it")

            assertEquals(origin.name, source, resolve(runId)?.source)
        }
        coVerify(exactly = 1) { journal.findTriggerIdForRun(any()) }
        coVerify(exactly = 1) { journal.findTriggerIdForRun("run-TRIGGER") }
    }

    @Test
    fun `given a trigger with a long multi-line name when resolved then the name is one line within its limit`() =
        runTest {
            givenRoot(ROOT_ID, RunOrigin.TRIGGER, prompt = "Check the weather")
            coEvery { journal.findTriggerIdForRun(ROOT_ID) } returns TRIGGER_ID
            coEvery { triggers.getTriggerById(TRIGGER_ID) } returns
                trigger(name = "Morning\nbriefing for the whole family before school and work")

            val name = (resolve(ROOT_ID)?.source as ApprovalRequestSource.Trigger).name

            assertEquals("Morning briefing for the whole family…", name)
            assertTrue(name!!.length <= ApprovalRequestText.MAX_TRIGGER_NAME_LENGTH)
        }

    @Test
    fun `given a trigger deleted since it fired when resolved then its prompt is kept without a name`() = runTest {
        givenRoot(ROOT_ID, RunOrigin.TRIGGER, prompt = "Submit the timesheet")
        coEvery { journal.findTriggerIdForRun(ROOT_ID) } returns TRIGGER_ID
        coEvery { triggers.getTriggerById(TRIGGER_ID) } returns null

        val context = resolve(ROOT_ID)

        assertEquals(ApprovalRequestSource.Trigger(name = null), context?.source)
        assertEquals("Submit the timesheet", context?.request)
    }

    @Test
    fun `given a trigger run without a journal row when resolved then it is shown without a name`() = runTest {
        givenRoot(ROOT_ID, RunOrigin.TRIGGER, prompt = "Submit the timesheet")
        coEvery { journal.findTriggerIdForRun(ROOT_ID) } returns null

        assertEquals(ApprovalRequestSource.Trigger(name = null), resolve(ROOT_ID)?.source)
        coVerify(exactly = 0) { triggers.getTriggerById(any()) }
    }

    @Test
    fun `given an image sent without text when resolved then the app's default instruction is not shown`() = runTest {
        // The run received the app's own instruction; showing it under
        // "You asked" would put words in the user's mouth.
        givenRoot(ROOT_ID, RunOrigin.CHAT, prompt = DefaultPrompts.IMAGE_ONLY_DEFAULT_INSTRUCTION, hadImage = true)

        val context = resolve(ROOT_ID)

        assertEquals(
            ApprovalRequestContext(ApprovalRequestSource.Chat, request = null, shortened = false, hadImage = true),
            context,
        )
    }

    @Test
    fun `given an image sent with text when resolved then the text is the request`() = runTest {
        givenRoot(ROOT_ID, RunOrigin.CHAT, prompt = "Add this to my reminders", hadImage = true)

        val context = resolve(ROOT_ID)

        assertEquals("Add this to my reminders", context?.request)
        assertTrue(context!!.hadImage)
    }

    @Test
    fun `given the default instruction typed without an image when resolved then it is shown as typed`() = runTest {
        givenRoot(ROOT_ID, RunOrigin.CHAT, prompt = DefaultPrompts.IMAGE_ONLY_DEFAULT_INSTRUCTION)

        assertEquals(DefaultPrompts.IMAGE_ONLY_DEFAULT_INSTRUCTION, resolve(ROOT_ID)?.request)
    }

    @Test
    fun `given a request longer than the card shows when resolved then it is clamped and marked shortened`() = runTest {
        givenRoot(ROOT_ID, RunOrigin.EXTERNAL, prompt = "word ".repeat(300))

        val context = resolve(ROOT_ID)!!

        assertTrue(context.shortened)
        assertTrue(context.request!!.length <= ApprovalRequestText.MAX_CARD_LENGTH)
        assertTrue(context.request!!.endsWith("word…"))
    }

    @Test
    fun `given a multi-line request within the limit when resolved then it is one line and not shortened`() = runTest {
        givenRoot(ROOT_ID, RunOrigin.SHARE, prompt = "Team offsite moved\n\nto Thursday 14:00")

        val context = resolve(ROOT_ID)!!

        assertEquals("Team offsite moved to Thursday 14:00", context.request)
        assertEquals(false, context.shortened)
    }

    @Test
    fun `given nothing to show when resolved then there is no context`() = runTest {
        // Not persisted (an editor test run): nothing is even looked up.
        assertNull(resolve(null))
        coVerify(exactly = 0) { runs.getRootRunId(any()) }

        coEvery { runs.getRootRunId("unknown") } returns null
        assertNull(resolve("unknown"))

        coEvery { runs.getRootRunId("orphan") } returns "gone"
        coEvery { runs.getRun("gone") } returns null
        assertNull(resolve("orphan"))

        givenRoot("no-prompt", RunOrigin.CHAT, prompt = null)
        assertNull(resolve("no-prompt"))

        givenRoot("blank", RunOrigin.CHAT, prompt = " \n ")
        assertNull(resolve("blank"))
    }

    @Test
    fun `given a store that fails when resolved then the approval goes on without a context`() = runTest {
        coEvery { runs.getRootRunId(ROOT_ID) } throws IllegalStateException("database is locked")

        assertNull(resolve(ROOT_ID))
    }

    @Test(expected = CancellationException::class)
    fun `given the lookup is cancelled when resolved then the cancellation propagates`() = runTest {
        coEvery { runs.getRootRunId(ROOT_ID) } throws CancellationException("run stopped")

        resolve(ROOT_ID)
    }

    private fun givenRoot(id: String, origin: RunOrigin, prompt: String?, hadImage: Boolean = false) {
        coEvery { runs.getRootRunId(id) } returns id
        coEvery { runs.getRun(id) } returns run(id, origin, prompt, hadImage)
    }

    private fun run(id: String, origin: RunOrigin, prompt: String?, hadImage: Boolean = false) = PipelineRun(
        id = id,
        sessionId = "session-1",
        pipelineId = "pipeline-1",
        origin = origin,
        status = PipelineRunStatus.WAITING_APPROVAL,
        currentNodeId = null,
        startedAt = 0L,
        finishedAt = null,
        errorMessage = null,
        graphContentHash = null,
        userPrompt = prompt,
        hadImage = hadImage,
    )

    private fun trigger(name: String) = Trigger(
        id = TRIGGER_ID,
        name = name,
        condition = TriggerCondition.Charging,
        pipelineId = "pipeline-1",
        prompt = "the trigger's current prompt",
        enabled = true,
        createdAt = 0L,
    )

    private companion object {
        const val ROOT_ID = "run-root"
        const val TRIGGER_ID = "trigger-1"
    }
}
