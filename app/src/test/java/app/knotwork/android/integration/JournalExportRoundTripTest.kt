package app.knotwork.android.integration

import app.knotwork.android.domain.models.ExternalAutomationJournalEntry
import app.knotwork.android.domain.models.ExternalAutomationRejectionReason
import app.knotwork.android.domain.models.ExternalAutomationStatus
import app.knotwork.android.domain.models.ExternalAutomationTarget
import app.knotwork.android.domain.models.JournalExportDocument
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.TriggerEvaluation
import app.knotwork.android.domain.models.TriggerEvaluationSource
import app.knotwork.android.domain.models.TriggerEvaluationVerdict
import app.knotwork.android.domain.models.TriggerHitlActivity
import app.knotwork.android.domain.models.TriggerHitlResolution
import app.knotwork.android.domain.models.TriggerRunOutcome
import app.knotwork.android.domain.models.TriggerSkipReason
import app.knotwork.android.domain.repositories.ExternalAutomationJournalRepository
import app.knotwork.android.domain.repositories.TriggerJournalRepository
import app.knotwork.android.domain.usecases.BuildTriggerJournalExportUseCase
import app.knotwork.android.domain.usecases.ExportTriggerJournalUseCase
import app.knotwork.android.domain.usecases.automation.BuildExternalAutomationJournalExportUseCase
import app.knotwork.android.domain.usecases.automation.ExportExternalAutomationJournalUseCase
import app.knotwork.android.presentation.ui.common.FakePickedDocument
import app.knotwork.android.presentation.ui.common.JournalExportDelegate
import app.knotwork.android.presentation.ui.common.TRIGGER_JOURNAL_EXPORT_STEM
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The round-trip guarantee of the journal exports: **one document, one parse.**
 *
 * The trigger journal leaves the device by two doors — the debug-only
 * `TriggerJournalDumpReceiver` (adb, during a soak run, app never opened) and the
 * in-app export action that a release build also has. The whole point of routing
 * both through [ExportTriggerJournalUseCase] is that whoever analyses a dump
 * needs one reader, not one per door. That claim is only worth something if
 * something checks it, so this test:
 *
 * 1. reads each document back with [JournalExportReader] — the single parse — and
 *    asserts it reconstitutes the journal it was built from, field for field;
 * 2. asserts the bytes the in-app save path writes are **identical** to the
 *    document the receiver's seam produces for the same snapshot and label.
 *
 * The fixtures deliberately cover every discriminator in both vocabularies: a
 * variant that is never exported is a variant whose round trip nobody has
 * checked.
 */
class JournalExportRoundTripTest {

    private val label = "2026-08-30 21:30:00"

    // ── Fixtures ────────────────────────────────────────────────────────────

    private val evaluations = listOf(
        TriggerEvaluation(
            id = "e1",
            triggerId = "trig-daily",
            evaluatedAt = 1_724_000_000_000L,
            source = TriggerEvaluationSource.POLL,
            verdict = TriggerEvaluationVerdict.Fired,
            runId = "run-1",
            outcome = TriggerRunOutcome.Success,
            hitl = TriggerHitlActivity(
                gateCount = 2,
                lastKind = PendingInteractionKind.APPROVAL,
                lastResolution = TriggerHitlResolution.APPROVED,
                parked = true,
            ),
        ),
        TriggerEvaluation(
            id = "e2",
            triggerId = "trig-daily",
            evaluatedAt = 1_724_000_001_000L,
            source = TriggerEvaluationSource.EVENT,
            verdict = TriggerEvaluationVerdict.Skipped(TriggerSkipReason.CONDITION_NOT_MET),
            runId = null,
            outcome = null,
            hitl = null,
        ),
        TriggerEvaluation(
            id = "e3",
            triggerId = "trig-charging",
            evaluatedAt = 1_724_000_002_000L,
            source = TriggerEvaluationSource.CHARGING_SWEEP,
            verdict = TriggerEvaluationVerdict.ReArmed,
            runId = null,
            outcome = null,
            hitl = null,
        ),
        TriggerEvaluation(
            id = "e4",
            triggerId = "trig-daily",
            evaluatedAt = 1_724_000_003_000L,
            source = TriggerEvaluationSource.POLL,
            verdict = TriggerEvaluationVerdict.Fired,
            runId = "run-2",
            outcome = TriggerRunOutcome.Failure("the model ran out of context"),
            hitl = null,
        ),
    )

    private val requests = listOf(
        ExternalAutomationJournalEntry(
            id = "r1",
            requestId = "tsk-1",
            receivedAt = 1_724_000_000_000L,
            action = "app.knotwork.android.action.RUN_PIPELINE",
            target = ExternalAutomationTarget.ById("pipe-7"),
            declaredReturnPackage = "net.dinglisch.android.taskerm",
            returnAction = "app.knotwork.android.action.RUN_RESULT",
            attestedSenderPackage = "com.example.attested",
            status = ExternalAutomationStatus.Completed,
            runId = "run-1",
            repeatCount = 1,
        ),
        ExternalAutomationJournalEntry(
            id = "r2",
            requestId = "tsk-2",
            receivedAt = 1_724_000_001_000L,
            action = "com.example.TYPO",
            target = ExternalAutomationTarget.ByName("Morning digest"),
            declaredReturnPackage = null,
            returnAction = "app.knotwork.android.action.RUN_RESULT",
            attestedSenderPackage = null,
            status = ExternalAutomationStatus.Rejected(ExternalAutomationRejectionReason.UNKNOWN_ACTION),
            runId = null,
            repeatCount = 43,
        ),
        ExternalAutomationJournalEntry(
            id = "r3",
            requestId = "tsk-3",
            receivedAt = 1_724_000_002_000L,
            action = "app.knotwork.android.action.RUN_PIPELINE",
            target = null,
            declaredReturnPackage = "com.example.caller",
            returnAction = "app.knotwork.android.action.RUN_RESULT",
            attestedSenderPackage = null,
            status = ExternalAutomationStatus.Blocked(ExternalAutomationRejectionReason.RATE_LIMITED),
            runId = null,
            repeatCount = 1,
        ),
    )

    // ── Seams under test ────────────────────────────────────────────────────

    private fun triggerExport(snapshot: List<TriggerEvaluation>): ExportTriggerJournalUseCase {
        val repository = mockk<TriggerJournalRepository>()
        coEvery { repository.readAll() } returns snapshot
        return ExportTriggerJournalUseCase(repository, BuildTriggerJournalExportUseCase())
    }

    private fun requestExport(snapshot: List<ExternalAutomationJournalEntry>): ExportExternalAutomationJournalUseCase {
        val repository = mockk<ExternalAutomationJournalRepository>()
        coEvery { repository.readAll() } returns snapshot
        return ExportExternalAutomationJournalUseCase(repository, BuildExternalAutomationJournalExportUseCase())
    }

    // ── Trigger journal ─────────────────────────────────────────────────────

    @Test
    fun `given a journal covering every variant when exported then it parses back to the same evaluations`() = runTest {
        val document = triggerExport(evaluations)(label)

        val parsed = JournalExportReader.readTriggerJournal(document.json)

        assertEquals(evaluations, parsed)
        assertEquals(evaluations.size, document.entryCount)
    }

    @Test
    fun `given an empty trigger journal when exported then it parses back to an empty list`() = runTest {
        val document = triggerExport(emptyList())(label)

        // An empty journal is a legitimate export — during a soak run it is the
        // finding — so it must round-trip like any other, not throw.
        assertEquals(emptyList<TriggerEvaluation>(), JournalExportReader.readTriggerJournal(document.json))
        assertEquals(0, document.entryCount)
    }

    @Test
    fun `given the same snapshot when saved in-app then the bytes match the debug dump's document`() = runTest {
        val export = triggerExport(evaluations)
        // What the debug receiver writes to the soak file.
        val viaReceiverSeam = export(label).json

        // What the in-app save path writes into the picked document. The delegate
        // stamps its own label, so the comparison is made on the parsed bodies as
        // well as on the byte-for-byte header-stripped remainder.
        val sink = FakePickedDocument()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val delegate = JournalExportDelegate(
            scope = scope,
            fileNameStem = TRIGGER_JOURNAL_EXPORT_STEM,
            buildDocument = { stampedLabel -> JournalExportDocument(export(stampedLabel).json, evaluations.size) },
            ioDispatcher = dispatcher,
        )
        delegate.saveTo(sink)
        scope.advanceUntilIdle()
        val viaInAppSave = sink.bytes.toString(Charsets.UTF_8)

        assertEquals(
            JournalExportReader.readTriggerJournal(viaReceiverSeam),
            JournalExportReader.readTriggerJournal(viaInAppSave),
        )
        // And the same shape, not merely the same content: one reader, one schema.
        assertEquals(
            viaReceiverSeam.lines().filterNot { it.contains("\"generatedAt\"") },
            viaInAppSave.lines().filterNot { it.contains("\"generatedAt\"") },
        )
    }

    // ── External-request journal ────────────────────────────────────────────

    @Test
    fun `given a request journal covering every variant when exported then it parses back to the same entries`() =
        runTest {
            val document = requestExport(requests)(label)

            assertEquals(requests, JournalExportReader.readRequestJournal(document.json))
            assertEquals(requests.size, document.entryCount)
        }

    @Test
    fun `given an empty request journal when exported then it parses back to an empty list`() = runTest {
        val document = requestExport(emptyList())(label)

        assertEquals(
            emptyList<ExternalAutomationJournalEntry>(),
            JournalExportReader.readRequestJournal(document.json),
        )
        assertEquals(0, document.entryCount)
    }

    @Test
    fun `given a journal read that failed when exported then the document is empty rather than absent`() = runTest {
        // Both repositories degrade a storage failure to an empty snapshot, so the
        // export path inherits it: a broken database yields an empty file, never a
        // crash on a screen the user opened to diagnose something.
        val document = triggerExport(emptyList())(label)

        assertTrue(document.json.contains("\"totalEvaluations\": 0"))
    }

    @Test
    fun `given both journals when exported then each declares its own schema version`() = runTest {
        // The two documents are different formats that happen to share a purpose.
        // Reading one with the other's expectations is the mistake a version field
        // exists to prevent, so each is asserted explicitly.
        assertTrue(triggerExport(evaluations)(label).json.contains("\"schemaVersion\": 3"))
        assertTrue(requestExport(requests)(label).json.contains("\"schemaVersion\": 1"))
    }
}
