package app.knotwork.android.domain.usecases.automation

import app.knotwork.android.domain.constants.ExternalAutomationContract
import app.knotwork.android.domain.models.ExternalAutomationInvocation
import app.knotwork.android.domain.models.ExternalAutomationJournalEntry
import app.knotwork.android.domain.models.ExternalAutomationRejectionReason
import app.knotwork.android.domain.models.ExternalAutomationStatus
import app.knotwork.android.domain.models.ExternalAutomationTarget
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.repositories.ExternalAutomationJournalRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.ScheduledTaskConstraints
import app.knotwork.android.domain.services.TaskScheduler
import app.knotwork.android.domain.usecases.RunRateCeiling
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the security guarantees of the external-automation entry point.
 *
 * Every guarantee the contract publishes is asserted here rather than in the
 * receiver, because the receiver deliberately decides nothing: it copies an intent
 * into a value object and calls this use case. A guarantee without a test is a
 * guarantee the app does not have, and these are the ones a third-party caller is
 * told about in `docs/external-automation.md`.
 */
class HandleExternalAutomationRequestUseCaseTest {

    private lateinit var settings: SettingsRepository
    private lateinit var pipelines: PipelineRepository
    private lateinit var journal: ExternalAutomationJournalRepository
    private lateinit var scheduler: TaskScheduler
    private lateinit var useCase: HandleExternalAutomationRequestUseCase

    @Before
    fun setUp() {
        settings = mockk(relaxed = true)
        pipelines = mockk()
        journal = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        // Admitted by default; the ceiling tests override it.
        coEvery { journal.admitAcceptedWithinCeiling(any(), any(), any()) } returns true
        every { settings.externalAutomationEnabled } returns flowOf(true)
        every { settings.externalAutomationPipelineId } returns flowOf(BOUND_ID)
        coEvery { pipelines.getPipelineById(BOUND_ID) } returns
            PipelineGraph(id = BOUND_ID, name = BOUND_NAME)
        useCase = HandleExternalAutomationRequestUseCase(
            parseRequest = ParseExternalAutomationRequestUseCase(),
            authorizeRequest = AuthorizeExternalAutomationRequestUseCase(),
            settingsRepository = settings,
            pipelineRepository = pipelines,
            journal = journal,
            taskScheduler = scheduler,
        )
    }

    private fun invocation(
        action: String = ExternalAutomationContract.ACTION_RUN_PIPELINE,
        pipelineId: String? = BOUND_ID,
        pipelineName: String? = null,
        prompt: String? = "Summarise my day",
        promptB64: String? = null,
        requestId: String? = "req-1",
        returnPackage: String? = "com.example.caller",
        returnAction: String? = null,
    ) = ExternalAutomationInvocation(
        action = action,
        extras = mapOf(
            ExternalAutomationContract.EXTRA_PIPELINE_ID to pipelineId,
            ExternalAutomationContract.EXTRA_PIPELINE_NAME to pipelineName,
            ExternalAutomationContract.EXTRA_PROMPT to prompt,
            ExternalAutomationContract.EXTRA_PROMPT_B64 to promptB64,
            ExternalAutomationContract.EXTRA_REQUEST_ID to requestId,
            ExternalAutomationContract.EXTRA_RETURN_PACKAGE to returnPackage,
            ExternalAutomationContract.EXTRA_RETURN_ACTION to returnAction,
        ),
    )

    /** Asserts that no work of any kind was started. */
    private fun assertNothingStarted() {
        coVerify(exactly = 0) {
            scheduler.scheduleOneTime(any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { journal.admitAcceptedWithinCeiling(any(), any(), any()) }
    }

    /** Captures the single refusal written to the journal. */
    private fun capturedRefusal(): ExternalAutomationJournalEntry {
        val slot = slot<ExternalAutomationJournalEntry>()
        coVerify(exactly = 1) { journal.recordRefusal(capture(slot)) }
        return slot.captured
    }

    // --- The switch --------------------------------------------------------

    @Test
    fun `given the contract is switched off when a request arrives then it is refused and nothing runs`() = runTest {
        every { settings.externalAutomationEnabled } returns flowOf(false)

        val status = useCase(invocation())

        assertEquals(ExternalAutomationStatus.Rejected(ExternalAutomationRejectionReason.CONTRACT_DISABLED), status)
        assertNothingStarted()
    }

    @Test
    fun `given the contract is switched off when a request arrives then the refusal is still journalled`() = runTest {
        every { settings.externalAutomationEnabled } returns flowOf(false)

        useCase(invocation())

        // The whole point of journalling a refusal: without this row, "the user
        // never switched it on" and "the broadcast never arrived" look identical.
        val entry = capturedRefusal()
        assertEquals(
            ExternalAutomationStatus.Rejected(ExternalAutomationRejectionReason.CONTRACT_DISABLED),
            entry.status,
        )
        assertNull(entry.runId)
    }

    // --- The binding is an allowlist ---------------------------------------

    @Test
    fun `given no pipeline is bound when a request arrives then it is refused rather than run by default`() = runTest {
        every { settings.externalAutomationPipelineId } returns flowOf(null)

        val status = useCase(invocation())

        assertEquals(ExternalAutomationStatus.Rejected(ExternalAutomationRejectionReason.SURFACE_NOT_BOUND), status)
        assertNothingStarted()
    }

    @Test
    fun `given a request naming another pipeline when it arrives then it is refused not redirected`() = runTest {
        val status = useCase(invocation(pipelineId = "some-other-pipeline"))

        // Redirecting to the bound pipeline would make the binding a default rather
        // than an allowlist, and hand any installed app the whole library by name.
        assertEquals(ExternalAutomationStatus.Rejected(ExternalAutomationRejectionReason.TARGET_NOT_ALLOWED), status)
        assertNothingStarted()
    }

    @Test
    fun `given the bound pipeline was deleted when a request arrives then the surface reads as unbound`() = runTest {
        coEvery { pipelines.getPipelineById(BOUND_ID) } returns null

        val status = useCase(invocation())

        assertEquals(ExternalAutomationStatus.Rejected(ExternalAutomationRejectionReason.SURFACE_NOT_BOUND), status)
        assertNothingStarted()
    }

    @Test
    fun `given a request naming the bound pipeline by name when it arrives then the bound pipeline is what runs`() =
        runTest {
            val pipelineSlot = slot<String>()
            coEvery {
                scheduler.scheduleOneTime(any(), any(), any(), any(), capture(pipelineSlot), any(), any())
            } returns Unit

            val status = useCase(invocation(pipelineId = null, pipelineName = BOUND_NAME))

            assertEquals(ExternalAutomationStatus.Accepted, status)
            // Asserting the status alone would pass even if the by-name path resolved
            // to nothing and the run fell through to the app's default routing.
            assertEquals(BOUND_ID, pipelineSlot.captured)
        }

    @Test
    fun `given the surface is unbound between authorisation and enqueue then the authorised pipeline still runs`() =
        runTest {
            // The binding is read once and carried. Were it re-read to find the
            // pipeline, this sequence would enqueue an already-authorised request
            // with a null pipeline id — i.e. run whatever the app's default routing
            // picks, which is precisely the pipeline nobody allowed.
            //
            // `flowOf(BOUND_ID, null)` would NOT reproduce this: `first()` returns
            // the head every time, so both reads would see the binding and the test
            // would pass against the bug it exists to catch. The setting has to
            // actually change between the two reads, so the pipeline lookup — which
            // happens between them — is the seam that unbinds it.
            val bound = MutableStateFlow<String?>(BOUND_ID)
            every { settings.externalAutomationPipelineId } returns bound
            coEvery { pipelines.getPipelineById(BOUND_ID) } answers {
                bound.value = null
                PipelineGraph(id = BOUND_ID, name = BOUND_NAME)
            }
            val pipelineSlot = slot<String>()
            coEvery {
                scheduler.scheduleOneTime(any(), any(), any(), any(), capture(pipelineSlot), any(), any())
            } returns Unit

            useCase(invocation(pipelineId = null, pipelineName = BOUND_NAME))

            assertEquals(BOUND_ID, pipelineSlot.captured)
        }

    @Test
    fun `given a request naming a different pipeline by name when it arrives then it is refused`() = runTest {
        val status = useCase(invocation(pipelineId = null, pipelineName = "Something else"))

        assertEquals(ExternalAutomationStatus.Rejected(ExternalAutomationRejectionReason.TARGET_NOT_ALLOWED), status)
        assertNothingStarted()
    }

    // --- Admission ---------------------------------------------------------

    @Test
    fun `given a permitted request when it arrives then the run is enqueued as an external-origin run`() = runTest {
        val originSlot = slot<RunOrigin>()
        val runIdSlot = slot<String>()
        val pipelineSlot = slot<String>()
        coEvery {
            scheduler.scheduleOneTime(
                any(),
                any(),
                any(),
                any(),
                capture(pipelineSlot),
                capture(originSlot),
                capture(runIdSlot),
            )
        } returns Unit

        val status = useCase(invocation())

        assertEquals(ExternalAutomationStatus.Accepted, status)
        // The origin is load-bearing: it is what routes the terminal callback and
        // what the rate ceiling is attributed to.
        assertEquals(RunOrigin.EXTERNAL, originSlot.captured)
        assertEquals(BOUND_ID, pipelineSlot.captured)
        assertTrue(runIdSlot.captured.isNotBlank())
    }

    @Test
    fun `given permitted requests when they arrive then all their runs land in one accumulating chat`() = runTest {
        val sessions = mutableListOf<String>()
        coEvery {
            scheduler.scheduleOneTime(any(), any(), capture(sessions), any(), any(), any(), any())
        } returns Unit

        useCase(invocation(requestId = "req-1"))
        useCase(invocation(requestId = "req-2"))

        // A null session would mint a fresh chat per request — up to 288 a day at
        // the external ceiling. The results of one automation belong together.
        assertEquals(
            listOf(
                HandleExternalAutomationRequestUseCase.EXTERNAL_AUTOMATION_SESSION_ID,
                HandleExternalAutomationRequestUseCase.EXTERNAL_AUTOMATION_SESSION_ID,
            ),
            sessions,
        )
    }

    @Test
    fun `given a permitted request when it arrives then the journal row carries the run id that was scheduled`() =
        runTest {
            val entrySlot = slot<ExternalAutomationJournalEntry>()
            coEvery { journal.admitAcceptedWithinCeiling(capture(entrySlot), any(), any()) } returns true
            val runIdSlot = slot<String>()
            coEvery {
                scheduler.scheduleOneTime(any(), any(), any(), any(), any(), any(), capture(runIdSlot))
            } returns Unit

            useCase(invocation())

            // The correlation exists before the run does; without it the terminal
            // callback has nothing to match on.
            assertEquals(entrySlot.captured.runId, runIdSlot.captured)
        }

    @Test
    fun `given a permitted request when it arrives then the prompt and target reach the scheduler unchanged`() =
        runTest {
            val promptSlot = slot<String>()
            val constraintsSlot = slot<ScheduledTaskConstraints>()
            coEvery {
                scheduler.scheduleOneTime(
                    capture(promptSlot),
                    any(),
                    any(),
                    capture(constraintsSlot),
                    any(),
                    any(),
                    any(),
                )
            } returns Unit

            useCase(invocation(prompt = "  Summarise my day  "))

            assertEquals("Summarise my day", promptSlot.captured)
            assertTrue(constraintsSlot.captured.requiresBatteryNotLow)
        }

    // --- The rate ceiling ---------------------------------------------------

    @Test
    fun `given the rate ceiling refuses when a request arrives then it is blocked and never queued`() = runTest {
        coEvery { journal.admitAcceptedWithinCeiling(any(), any(), any()) } returns false

        val status = useCase(invocation())

        // Blocked, deliberately not a silent enqueue: a caller whose request was
        // dropped on the floor cannot otherwise tell that from one that ran.
        assertEquals(ExternalAutomationStatus.Blocked(ExternalAutomationRejectionReason.RATE_LIMITED), status)
        coVerify(exactly = 0) {
            scheduler.scheduleOneTime(any(), any(), any(), any(), any(), any(), any())
        }
        assertEquals(
            ExternalAutomationStatus.Blocked(ExternalAutomationRejectionReason.RATE_LIMITED),
            capturedRefusal().status,
        )
    }

    @Test
    fun `given a request when it is admitted then the ceiling window comes from the external rate ceiling`() = runTest {
        val windowSlot = slot<Long>()
        val limitSlot = slot<Int>()
        coEvery {
            journal.admitAcceptedWithinCeiling(any(), capture(windowSlot), capture(limitSlot))
        } returns true

        useCase(invocation(), nowMillis = 10_000_000L)

        assertEquals(RunRateCeiling.EXTERNAL.limitPerWindow, limitSlot.captured)
        assertEquals(RunRateCeiling.EXTERNAL.windowStart(10_000_000L), windowSlot.captured)
    }

    // --- Enqueue failure ----------------------------------------------------

    @Test
    fun `given the enqueue fails when a request was admitted then the row is settled as failed`() = runTest {
        coEvery {
            scheduler.scheduleOneTime(any(), any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("work manager unavailable")

        val status = useCase(invocation())

        assertEquals(ExternalAutomationStatus.Failed, status)
        // The admission is on the ledger and cannot be un-counted, but the row must
        // not keep claiming an accepted run that never started.
        coVerify(exactly = 1) { journal.recordOutcome(any(), ExternalAutomationStatus.Failed) }
    }

    // --- Malformed calls ----------------------------------------------------

    @Test
    fun `given an action this contract does not define when it arrives then it is refused and journalled`() = runTest {
        val status = useCase(invocation(action = "com.example.SOMETHING_ELSE"))

        assertEquals(ExternalAutomationStatus.Rejected(ExternalAutomationRejectionReason.UNKNOWN_ACTION), status)
        assertNothingStarted()
        // Recorded verbatim: a caller with a typo in its profile can only find it
        // if the journal shows what actually arrived.
        assertEquals("com.example.SOMETHING_ELSE", capturedRefusal().action)
    }

    @Test
    fun `given a request with no prompt when it arrives then it is refused rather than run empty`() = runTest {
        val status = useCase(invocation(prompt = "   "))

        assertEquals(ExternalAutomationStatus.Rejected(ExternalAutomationRejectionReason.PROMPT_MISSING), status)
        assertNothingStarted()
    }

    @Test
    fun `given an unparseable request when it arrives then the journal still records what it said`() = runTest {
        useCase(invocation(requestId = null, pipelineId = "pipe-x"))

        val entry = capturedRefusal()
        assertEquals(
            ExternalAutomationStatus.Rejected(ExternalAutomationRejectionReason.REQUEST_ID_MISSING),
            entry.status,
        )
        assertEquals(ExternalAutomationTarget.ById("pipe-x"), entry.target)
        assertEquals("com.example.caller", entry.declaredReturnPackage)
    }

    // --- The opportunistic sender check --------------------------------------

    @Test
    fun `given an attested sender naming someone else for the callback when it arrives then it is refused`() = runTest {
        val status =
            useCase(
                invocation(returnPackage = "com.example.victim"),
                attestedSenderPackage = "com.example.attacker",
            )

        // On the rare call that shares its identity, a mismatch is an app trying
        // to make this one broadcast at a third party. Its own reason, not
        // TARGET_NOT_ALLOWED: the caller named a permitted pipeline, so a row
        // saying "wrong pipeline" would send the reader to the wrong setting.
        assertEquals(
            ExternalAutomationStatus.Rejected(ExternalAutomationRejectionReason.RETURN_PACKAGE_MISMATCH),
            status,
        )
        assertNothingStarted()
    }

    @Test
    fun `given an attested sender naming itself for the callback when it arrives then it is admitted`() = runTest {
        val status =
            useCase(invocation(returnPackage = "com.example.caller"), attestedSenderPackage = "com.example.caller")

        assertEquals(ExternalAutomationStatus.Accepted, status)
    }

    @Test
    fun `given no attested sender when a request arrives then the declared package is accepted as-is`() = runTest {
        // The ordinary case: neither automation apps nor adb share their identity,
        // so an absent attestation must not refuse every real caller.
        val status = useCase(invocation(returnPackage = "com.example.caller"), attestedSenderPackage = null)

        assertEquals(ExternalAutomationStatus.Accepted, status)
    }

    @Test
    fun `given a fire-and-forget request when it arrives then it is admitted without a callback address`() = runTest {
        val status = useCase(invocation(returnPackage = null))

        assertEquals(ExternalAutomationStatus.Accepted, status)
    }

    // --- One record per decision ---------------------------------------------

    @Test
    fun `given any refused request when handled then exactly one journal record is written`() = runTest {
        every { settings.externalAutomationEnabled } returns flowOf(false)

        useCase(invocation())

        coVerify(exactly = 1) { journal.recordRefusal(any()) }
        coVerify(exactly = 0) { journal.admitAcceptedWithinCeiling(any(), any(), any()) }
    }

    @Test
    fun `given an admitted request when handled then exactly one journal record is written`() = runTest {
        useCase(invocation())

        coVerify(exactly = 1) { journal.admitAcceptedWithinCeiling(any(), any(), any()) }
        coVerify(exactly = 0) { journal.recordRefusal(any()) }
    }

    // --- What the journal keeps of a caller's text ---------------------------

    @Test
    fun `given a refused call carrying huge values when journalled then every caller-chosen field is clamped`() =
        runTest {
            // Refusals are journalled while the contract is off, and each one is a
            // row: without a bound per value, any app on the device could fill the
            // encrypted database a megabyte at a time. The action is refused first,
            // so every other value reaches the row raw, before any parse check.
            val huge = "x".repeat(100_000)
            useCase(
                invocation(
                    action = huge,
                    pipelineId = null,
                    pipelineName = huge,
                    requestId = huge,
                    returnPackage = huge,
                    returnAction = huge,
                ),
            )

            val row = capturedRefusal()
            val max = HandleExternalAutomationRequestUseCase.MAX_JOURNALED_VALUE_LENGTH
            val target = row.target as ExternalAutomationTarget.ByName
            listOf(row.action, row.requestId, target.pipelineName, row.declaredReturnPackage!!, row.returnAction)
                .forEach { value ->
                    assertEquals(max, value.length)
                    // Marked as cut, so the row does not pass for what was sent.
                    assertTrue(value.endsWith("…"))
                    assertTrue(value.startsWith("xxxx"))
                }
        }

    @Test
    fun `given an admitted request at every ceiling when journalled then its values are recorded unchanged`() =
        runTest {
            // The final callback reads its address and id back from this row, so an
            // admitted request must never be cut on the way in.
            val id = "r".repeat(ExternalAutomationContract.MAX_REQUEST_ID_LENGTH)
            val address = "p".repeat(ExternalAutomationContract.MAX_RETURN_ADDRESS_LENGTH)
            val slot = slot<ExternalAutomationJournalEntry>()
            coEvery { journal.admitAcceptedWithinCeiling(capture(slot), any(), any()) } returns true

            useCase(invocation(requestId = id, returnPackage = address, returnAction = address))

            assertEquals(id, slot.captured.requestId)
            assertEquals(address, slot.captured.declaredReturnPackage)
            assertEquals(address, slot.captured.returnAction)
        }

    @Test
    fun `given the contract ceilings then the journal ceiling is not below any of them`() {
        val max = HandleExternalAutomationRequestUseCase.MAX_JOURNALED_VALUE_LENGTH
        assertTrue(max >= ExternalAutomationContract.MAX_REQUEST_ID_LENGTH)
        assertTrue(max >= ExternalAutomationContract.MAX_RETURN_ADDRESS_LENGTH)
    }

    private companion object {
        const val BOUND_ID = "pipe-bound"
        const val BOUND_NAME = "Daily digest"
    }
}
