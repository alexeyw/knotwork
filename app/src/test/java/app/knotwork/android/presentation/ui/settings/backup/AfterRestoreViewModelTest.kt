package app.knotwork.android.presentation.ui.settings.backup

import android.content.Context
import app.knotwork.android.R
import app.knotwork.android.data.network.AndroidModelDownloadManager.DownloadError
import app.knotwork.android.domain.backup.BackupModelEntry
import app.knotwork.android.domain.backup.BackupRestorer
import app.knotwork.android.domain.backup.RestoreReport
import app.knotwork.android.domain.models.AppError
import app.knotwork.android.domain.models.DownloadState
import app.knotwork.android.domain.models.ModelDownloadFailure
import app.knotwork.android.domain.repositories.GenerationSettings
import app.knotwork.android.domain.repositories.InterruptedDownload
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.ModelDownloadManager
import app.knotwork.android.presentation.state.TransientMessageRelay
import app.knotwork.android.presentation.ui.common.UiText
import app.knotwork.design.screens.settings.backup.AfterRestoreDownloadFailure
import app.knotwork.design.screens.settings.backup.AfterRestoreModel
import app.knotwork.design.screens.settings.backup.AfterRestoreState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * [AfterRestoreViewModel]: the sheet from the report, the snackbar when nothing is left to do, the downloads it
 * starts — one at a time — and *Done*; the sheet only while the chat is resumed, without the models installed
 * meanwhile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AfterRestoreViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val restorer = mockk<BackupRestorer>(relaxed = true)
    private val download = MutableSharedFlow<DownloadState>(extraBufferCapacity = 8)
    private val recordedStops = MutableStateFlow<List<InterruptedDownload>>(emptyList())
    private val downloads = mockk<ModelDownloadManager>(relaxed = true) {
        every { downloadModel(KNOWN.sourceUrl!!, KNOWN.name, any()) } returns download
        every { observeInterruptedDownloads() } returns recordedStops
    }
    private val generationSettings = mockk<GenerationSettings> {
        every { huggingFaceAuthToken } returns flowOf(null)
    }
    private val localModels = mockk<LocalModelRepository> {
        coEvery { isInstalled(any()) } returns false
    }
    private val relay = mockk<TransientMessageRelay>(relaxed = true)
    private val context = mockk<Context> {
        every { getString(R.string.backup_snack_restored) } returns "Restored"
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `given a report with things left to do when the chat opens then the sheet lists them`() = runTest(dispatcher) {
        coEvery { restorer.report() } returns REPORT

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(
            AfterRestoreState(
                madeOnLabel = "on 5000",
                models = listOf(
                    AfterRestoreModel(KNOWN.name, KNOWN.sizeBytes, sourceKnown = true),
                    AfterRestoreModel(UNKNOWN.name, UNKNOWN.sizeBytes, sourceKnown = false),
                ),
                enterKeysAgain = true,
                tasksLeftBehind = true,
            ),
            viewModel.state.value,
        )
        verify(exactly = 0) { relay.post(any()) }
    }

    @Test
    fun `given a report with nothing left to do when the chat opens then it says Restored and forgets it`() =
        runTest(dispatcher) {
            coEvery { restorer.report() } returns
                RestoreReport(emptyList(), scheduledTasksLeftBehind = 0, secretsApplied = true)

            val viewModel = viewModel()
            advanceUntilIdle()

            assertNull(viewModel.state.value)
            verify { relay.post(UiText.Dynamic("Restored")) }
            coVerify { restorer.dismissReport() }
        }

    @Test
    fun `given a report whose only task is the keys when the chat opens then the sheet still shows`() =
        runTest(dispatcher) {
            // A backup without keys, onto a phone with nothing else left: the keys are still the user's to enter.
            coEvery { restorer.report() } returns
                RestoreReport(emptyList(), scheduledTasksLeftBehind = 0, secretsApplied = false, secretsOnPhone = false)

            val viewModel = viewModel()
            advanceUntilIdle()

            assertEquals(true, viewModel.state.value?.enterKeysAgain)
            verify(exactly = 0) { relay.post(any()) }
        }

    @Test
    fun `given a backup without keys onto a phone that keeps its own when the chat opens then nothing is asked`() =
        runTest(dispatcher) {
            // The phone's own keys stay through a restore: telling the user to enter them again would be false.
            coEvery { restorer.report() } returns
                RestoreReport(emptyList(), scheduledTasksLeftBehind = 0, secretsApplied = false, secretsOnPhone = true)

            val viewModel = viewModel()
            advanceUntilIdle()

            assertNull(viewModel.state.value)
            verify { relay.post(UiText.Dynamic("Restored")) }
        }

    @Test
    fun `given a report that cannot be read when the chat opens then nothing shows and nothing breaks`() =
        runTest(dispatcher) {
            coEvery { restorer.report() } throws IOException("disk")

            val viewModel = viewModel()
            advanceUntilIdle()

            assertNull(viewModel.state.value)
        }

    @Test
    fun `given a download cancelled from its notification when its flow ends then the row is free again`() =
        runTest(dispatcher) {
            // The download's flow ends with no last state when its notification's Cancel stops it.
            every { downloads.downloadModel(KNOWN.sourceUrl!!, KNOWN.name, any()) } returns
                flowOf(DownloadState.Downloading(30))
            coEvery { restorer.report() } returns REPORT
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onDownload(KNOWN.name)
            advanceUntilIdle()

            val rows = viewModel.state.value!!.models
            assertNull(rows.first { it.name == KNOWN.name }.progress)
            assertEquals(listOf(true, true), rows.map { it.canDownload })
        }

    @Test
    fun `given a running download when Done is tapped then the download goes on`() = runTest(dispatcher) {
        coEvery { restorer.report() } returns REPORT
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onDownload(KNOWN.name)
        advanceUntilIdle()

        viewModel.onDone()
        download.emit(DownloadState.Downloading(60))
        advanceUntilIdle()

        verify(exactly = 0) { downloads.cancelDownload(any()) }
        assertNull(viewModel.state.value)
    }

    @Test
    fun `given the chat not resumed when the report is read then the sheet waits, and hides again on pause`() =
        runTest(dispatcher) {
            // A back gesture from Discover draws the chat started, not resumed: a sheet then would take the gesture.
            coEvery { restorer.report() } returns REPORT
            val viewModel = hiddenViewModel()
            advanceUntilIdle()
            assertNull(viewModel.state.value)

            viewModel.onResumed()
            advanceUntilIdle()
            assertEquals(listOf(KNOWN.name, UNKNOWN.name), viewModel.state.value?.models?.map { it.name })

            viewModel.onPaused()
            assertNull(viewModel.state.value)
            coVerify(exactly = 0) { restorer.dismissReport() }
        }

    @Test
    fun `given a model installed meanwhile when the chat resumes then its row leaves`() = runTest(dispatcher) {
        // Given — the sheet showing both models, then Discover installs the one with no recorded source.
        coEvery { restorer.report() } returns REPORT
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onPaused()
        val checked = CompletableDeferred<Boolean>()
        coEvery { localModels.isInstalled(UNKNOWN.name) } coAnswers { checked.await() }

        // When — back on the chat, the check under way.
        viewModel.onResumed()
        advanceUntilIdle()

        // Then — no sheet while it runs, and none listing the model just installed after it.
        assertNull(viewModel.state.value)
        checked.complete(true)
        advanceUntilIdle()
        assertEquals(listOf(KNOWN.name), viewModel.state.value?.models?.map { it.name })
    }

    @Test
    fun `given a model installed and then deleted again when the chat resumes then its row comes back`() =
        runTest(dispatcher) {
            coEvery { restorer.report() } returns REPORT
            coEvery { localModels.isInstalled(UNKNOWN.name) } returns true
            val viewModel = viewModel()
            advanceUntilIdle()
            assertEquals(listOf(KNOWN.name), viewModel.state.value?.models?.map { it.name })

            // Deleted on the Models screen while the chat was away.
            viewModel.onPaused()
            coEvery { localModels.isInstalled(UNKNOWN.name) } returns false
            viewModel.onResumed()
            advanceUntilIdle()

            assertEquals(listOf(KNOWN.name, UNKNOWN.name), viewModel.state.value?.models?.map { it.name })
        }

    @Test
    fun `given every model already on the phone and nothing else left when the report is read then it says Restored`() =
        runTest(dispatcher) {
            // The models were installed in an earlier process, before Done: nothing is left but an empty sheet.
            coEvery { restorer.report() } returns
                RestoreReport(listOf(UNKNOWN), scheduledTasksLeftBehind = 0, secretsApplied = true)
            coEvery { localModels.isInstalled(UNKNOWN.name) } returns true

            val viewModel = viewModel()
            advanceUntilIdle()

            assertNull(viewModel.state.value)
            verify { relay.post(UiText.Dynamic("Restored")) }
            coVerify { restorer.dismissReport() }
        }

    @Test
    fun `given the chat paused while its resume check runs when the check ends then no sheet shows`() =
        runTest(dispatcher) {
            // A back gesture that starts right after a resume pauses the chat before the check is back.
            coEvery { restorer.report() } returns REPORT
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onPaused()
            val checked = CompletableDeferred<Boolean>()
            coEvery { localModels.isInstalled(UNKNOWN.name) } coAnswers { checked.await() }
            viewModel.onResumed()
            advanceUntilIdle()

            viewModel.onPaused()
            checked.complete(false)
            advanceUntilIdle()

            assertNull(viewModel.state.value)
        }

    @Test
    fun `given a download that ends while the chat resume check runs then its model stays off the list`() =
        runTest(dispatcher) {
            // Given — the sheet's download running, the chat paused, the next check slow.
            coEvery { restorer.report() } returns REPORT
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onDownload(KNOWN.name)
            advanceUntilIdle()
            viewModel.onPaused()
            val checked = CompletableDeferred<Boolean>()
            coEvery { localModels.isInstalled(UNKNOWN.name) } coAnswers { checked.await() }
            viewModel.onResumed()
            advanceUntilIdle()

            // When — the download succeeds while the check waits.
            download.emit(DownloadState.Success("file:///models/gemma.litertlm"))
            advanceUntilIdle()
            checked.complete(false)
            advanceUntilIdle()

            // Then — the check does not write its stale view over the finished download.
            assertEquals(listOf(UNKNOWN.name), viewModel.state.value?.models?.map { it.name })
        }

    @Test
    fun `given download progress while the chat resume check runs then no sheet shows before it`() =
        runTest(dispatcher) {
            // Given — the sheet's download running; Discover installed the other model; the check slow.
            coEvery { restorer.report() } returns REPORT
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onDownload(KNOWN.name)
            advanceUntilIdle()
            viewModel.onPaused()
            val checked = CompletableDeferred<Boolean>()
            coEvery { localModels.isInstalled(UNKNOWN.name) } coAnswers { checked.await() }
            viewModel.onResumed()
            advanceUntilIdle()

            // When — progress arrives while the check waits.
            download.emit(DownloadState.Downloading(60))
            advanceUntilIdle()

            // Then — nothing shown with the installed model still listed; then the sheet without it.
            assertNull(viewModel.state.value)
            checked.complete(true)
            advanceUntilIdle()
            assertEquals(listOf(KNOWN.name), viewModel.state.value?.models?.map { it.name })
            assertEquals(60, viewModel.state.value?.models?.single()?.progress)
        }

    @Test
    fun `given a model installed before the report is read when the chat opens then it is not listed`() =
        runTest(dispatcher) {
            // The report waits for Done across restarts; a model installed in an earlier process stays installed.
            // The chat is resumed while the report is still being read, so the resume's own check finds no report.
            val reportRead = CompletableDeferred<Unit>()
            coEvery { restorer.report() } coAnswers {
                reportRead.await()
                REPORT
            }
            coEvery { localModels.isInstalled(UNKNOWN.name) } returns true
            val viewModel = viewModel()
            advanceUntilIdle()

            reportRead.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(KNOWN.name), viewModel.state.value?.models?.map { it.name })
        }

    @Test
    fun `given a download running here when its file is reported on the phone then its row stays until it ends`() =
        runTest(dispatcher) {
            // Given — the row's download under way when the chat is resumed again.
            coEvery { restorer.report() } returns REPORT
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onDownload(KNOWN.name)
            advanceUntilIdle()
            download.emit(DownloadState.Downloading(40))
            advanceUntilIdle()
            viewModel.onPaused()
            coEvery { localModels.isInstalled(KNOWN.name) } returns true

            // When
            viewModel.onResumed()
            advanceUntilIdle()

            // Then — the row and its Cancel stay while the download runs.
            assertEquals(40, viewModel.state.value?.models?.first { it.name == KNOWN.name }?.progress)
        }

    @Test
    fun `given the registry that cannot be read when the chat resumes then the rows stay`() = runTest(dispatcher) {
        coEvery { restorer.report() } returns REPORT
        coEvery { localModels.isInstalled(any()) } throws IllegalStateException("database unavailable")

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(listOf(KNOWN.name, UNKNOWN.name), viewModel.state.value?.models?.map { it.name })
    }

    @Test
    fun `given no report when the chat opens then nothing shows`() = runTest(dispatcher) {
        coEvery { restorer.report() } returns null

        val viewModel = viewModel()
        advanceUntilIdle()

        assertNull(viewModel.state.value)
        verify(exactly = 0) { relay.post(any()) }
    }

    @Test
    fun `given a model with a known source when downloaded then its row shows progress and leaves once done`() =
        runTest(dispatcher) {
            // Given
            coEvery { restorer.report() } returns REPORT
            val viewModel = viewModel()
            advanceUntilIdle()

            // When
            viewModel.onDownload(KNOWN.name)
            advanceUntilIdle()
            download.emit(DownloadState.Downloading(40))
            advanceUntilIdle()

            // Then — progress on its row, and no second download while it runs.
            val rows = viewModel.state.value!!.models
            assertEquals(40, rows.first { it.name == KNOWN.name }.progress)
            assertEquals(listOf(false, false), rows.map { it.canDownload })
            viewModel.onDownload(KNOWN.name)
            verify(exactly = 1) { downloads.downloadModel(any(), any(), any()) }
            download.emit(DownloadState.Success("file:///models/gemma.litertlm"))
            advanceUntilIdle()
            assertEquals(listOf(UNKNOWN.name), viewModel.state.value!!.models.map { it.name })
        }

    @Test
    fun `given a download that fails when it ends then the row says so and offers Download again`() =
        runTest(dispatcher) {
            coEvery { restorer.report() } returns REPORT
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onDownload(KNOWN.name)
            advanceUntilIdle()
            download.emit(DownloadState.Error(mockk<AppError.Network>(relaxed = true)))
            advanceUntilIdle()

            val row = viewModel.state.value!!.models.first { it.name == KNOWN.name }
            assertNull(row.progress)
            assertEquals(true, row.canDownload)
            assertEquals(AfterRestoreDownloadFailure.Failed, row.failure)
        }

    @Test
    fun `given a Hugging Face token saved on this phone when Download is tapped then the token goes with it`() =
        runTest(dispatcher) {
            // Given — the case the restore leaves when its backup carried the token, or it was entered since.
            every { generationSettings.huggingFaceAuthToken } returns flowOf("hf_saved")
            coEvery { restorer.report() } returns REPORT
            val viewModel = viewModel()
            advanceUntilIdle()

            // When
            viewModel.onDownload(KNOWN.name)
            advanceUntilIdle()

            // Then — only whether: the downloader reads the token itself.
            verify(exactly = 1) { downloads.downloadModel(KNOWN.sourceUrl!!, KNOWN.name, true) }
        }

    @Test
    fun `given no Hugging Face token or a blank one when Download is tapped then the download goes without one`() =
        runTest(dispatcher) {
            coEvery { restorer.report() } returns REPORT
            listOf(null, "  ").forEach { token ->
                every { generationSettings.huggingFaceAuthToken } returns flowOf(token)
                val viewModel = viewModel()
                advanceUntilIdle()

                viewModel.onDownload(KNOWN.name)
                advanceUntilIdle()
            }

            verify(exactly = 2) { downloads.downloadModel(KNOWN.sourceUrl!!, KNOWN.name, false) }
        }

    @Test
    fun `given a token that cannot be read when Download is tapped then the download still starts without it`() =
        runTest(dispatcher) {
            every { generationSettings.huggingFaceAuthToken } returns flow { throw IOException("keystore") }
            coEvery { restorer.report() } returns REPORT
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onDownload(KNOWN.name)
            advanceUntilIdle()

            verify(exactly = 1) { downloads.downloadModel(KNOWN.sourceUrl!!, KNOWN.name, false) }
            assertEquals(0, viewModel.state.value!!.models.first { it.name == KNOWN.name }.progress)
        }

    @Test
    fun `given the token still being read when the download is cancelled then no download starts`() =
        runTest(dispatcher) {
            // The download is enqueued only once the token is read: a cancel in between must leave nothing behind.
            val read = CompletableDeferred<String?>()
            every { generationSettings.huggingFaceAuthToken } returns flow { emit(read.await()) }
            coEvery { restorer.report() } returns REPORT
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onDownload(KNOWN.name)
            advanceUntilIdle()

            viewModel.onCancelDownload(KNOWN.name)
            read.complete("hf_saved")
            advanceUntilIdle()

            verify(exactly = 0) { downloads.downloadModel(any(), any(), any()) }
            assertNull(viewModel.state.value!!.models.first { it.name == KNOWN.name }.progress)
        }

    @Test
    fun `given the token still being read when Download is tapped again then one download starts`() =
        runTest(dispatcher) {
            val read = CompletableDeferred<String?>()
            every { generationSettings.huggingFaceAuthToken } returns flow { emit(read.await()) }
            coEvery { restorer.report() } returns REPORT
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onDownload(KNOWN.name)
            viewModel.onDownload(KNOWN.name)
            read.complete(null)
            advanceUntilIdle()

            verify(exactly = 1) { downloads.downloadModel(any(), any(), any()) }
        }

    @Test
    fun `given Hugging Face refusing with 401 or 403 when the download ends then the row asks for a token`() =
        runTest(dispatcher) {
            // A download's flow completes once it fails, so each tap starts a download of its own.
            every { downloads.downloadModel(GATED.sourceUrl!!, GATED.name, any()) } returnsMany
                listOf(401, 403).map { flowOf(DownloadState.Error(DownloadError("HTTP $it", code = it))) }
            coEvery { restorer.report() } returns REPORT.copy(modelsToDownload = listOf(GATED))
            val viewModel = viewModel()
            advanceUntilIdle()

            listOf(401, 403).forEach { status ->
                viewModel.onDownload(GATED.name)
                advanceUntilIdle()

                val row = viewModel.state.value!!.models.single()
                assertEquals("HTTP $status", AfterRestoreDownloadFailure.NeedsAccess, row.failure)
                assertEquals(true, row.canDownload)
            }
            verify(exactly = 2) { downloads.downloadModel(any(), any(), any()) }
        }

    @Test
    fun `given another address refusing with 403 when the download ends then the row does not ask for a token`() =
        runTest(dispatcher) {
            // No token is ever sent there — a signed CDN link, say — so asking for one would send the user in circles.
            every { downloads.downloadModel(KNOWN.sourceUrl!!, KNOWN.name, any()) } returns
                flowOf(DownloadState.Error(DownloadError("HTTP 403", code = 403)))
            coEvery { restorer.report() } returns REPORT
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onDownload(KNOWN.name)
            advanceUntilIdle()

            assertEquals(
                AfterRestoreDownloadFailure.Failed,
                viewModel.state.value!!.models.first { it.name == KNOWN.name }.failure,
            )
        }

    @Test
    fun `given Hugging Face answering another HTTP error when the download ends then the row says it did not finish`() =
        runTest(dispatcher) {
            // A missing file, a rate limit, a server error: no token answers them, and some a later try does.
            val statuses = listOf(404, 429, 503)
            every { downloads.downloadModel(GATED.sourceUrl!!, GATED.name, any()) } returnsMany
                statuses.map { flowOf(DownloadState.Error(DownloadError("HTTP $it", code = it))) }
            coEvery { restorer.report() } returns REPORT.copy(modelsToDownload = listOf(GATED))
            val viewModel = viewModel()
            advanceUntilIdle()

            statuses.forEach { status ->
                viewModel.onDownload(GATED.name)
                advanceUntilIdle()

                assertEquals(
                    "HTTP $status",
                    AfterRestoreDownloadFailure.Failed,
                    viewModel.state.value!!.models.single().failure,
                )
            }
            verify(exactly = 3) { downloads.downloadModel(any(), any(), any()) }
        }

    @Test
    fun `given a download that ends with no answer when it fails then the row says the download failed`() =
        runTest(dispatcher) {
            every { downloads.downloadModel(GATED.sourceUrl!!, GATED.name, any()) } returns
                flowOf(DownloadState.Error(DownloadError("timeout", code = null)))
            coEvery { restorer.report() } returns REPORT.copy(modelsToDownload = listOf(GATED))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onDownload(GATED.name)
            advanceUntilIdle()

            assertEquals(AfterRestoreDownloadFailure.Failed, viewModel.state.value!!.models.single().failure)
        }

    @Test
    fun `given a paused or mismatched download when its stream ends then the stream alone adds nothing`() =
        runTest(dispatcher) {
            // The download recorded these two before failing; the row takes them from that record, not twice.
            val stops = listOf(ModelDownloadFailure.PAUSED_TIME_LIMIT, ModelDownloadFailure.CHECKSUM_MISMATCH)
            every { downloads.downloadModel(KNOWN.sourceUrl!!, KNOWN.name, any()) } returnsMany
                stops.map { flowOf(DownloadState.Error(DownloadError("stopped", code = 403, failure = it))) }
            coEvery { restorer.report() } returns REPORT
            val viewModel = viewModel()
            advanceUntilIdle()

            stops.forEach { failure ->
                viewModel.onDownload(KNOWN.name)
                advanceUntilIdle()

                val row = viewModel.state.value!!.models.first { it.name == KNOWN.name }
                assertNull(failure.name, row.failure)
                assertNull(failure.name, row.progress)
            }
            verify(exactly = 2) { downloads.downloadModel(any(), any(), any()) }
        }

    @Test
    fun `given a recorded stop of a listed model when the sheet shows then its row says so and the record stays`() =
        runTest(dispatcher) {
            coEvery { restorer.report() } returns REPORT.copy(modelsToDownload = listOf(KNOWN, GATED))
            val viewModel = viewModel()
            advanceUntilIdle()

            recordedStops.value = listOf(
                stop(KNOWN, ModelDownloadFailure.PAUSED_TIME_LIMIT),
                stop(GATED, ModelDownloadFailure.CHECKSUM_MISMATCH),
            )
            advanceUntilIdle()

            assertEquals(
                listOf(AfterRestoreDownloadFailure.Paused, AfterRestoreDownloadFailure.ChecksumMismatch),
                viewModel.state.value!!.models.map { it.failure },
            )
            // Read only: the notice and the Models screen still tell it, once.
            verify(exactly = 0) { downloads.acknowledgeInterruption(any()) }
        }

    @Test
    fun `given a stop recorded while the sheet is hidden when the chat resumes then the row says so`() =
        runTest(dispatcher) {
            coEvery { restorer.report() } returns REPORT
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onPaused()

            recordedStops.value = listOf(stop(KNOWN, ModelDownloadFailure.PAUSED_TIME_LIMIT))
            advanceUntilIdle()
            assertNull(viewModel.state.value)

            viewModel.onResumed()
            advanceUntilIdle()

            assertEquals(
                AfterRestoreDownloadFailure.Paused,
                viewModel.state.value!!.models.first { it.name == KNOWN.name }.failure,
            )
        }

    @Test
    fun `given a recorded stop when its record goes then the row's reason goes with it`() = runTest(dispatcher) {
        // The download started again from another screen, or another screen showed the stop.
        coEvery { restorer.report() } returns REPORT
        val viewModel = viewModel()
        advanceUntilIdle()
        recordedStops.value = listOf(stop(KNOWN, ModelDownloadFailure.CHECKSUM_MISMATCH))
        advanceUntilIdle()
        assertEquals(
            AfterRestoreDownloadFailure.ChecksumMismatch,
            viewModel.state.value!!.models.first { it.name == KNOWN.name }.failure,
        )

        recordedStops.value = emptyList()
        advanceUntilIdle()

        assertNull(viewModel.state.value!!.models.first { it.name == KNOWN.name }.failure)
    }

    @Test
    fun `given a stream failure, then a record of a later stop, when the record goes then the failure stays gone`() =
        runTest(dispatcher) {
            // Failed here, then started again from the Models screen, where it paused and was shown.
            every { downloads.downloadModel(KNOWN.sourceUrl!!, KNOWN.name, any()) } returns
                flowOf(DownloadState.Error(DownloadError("timeout")))
            coEvery { restorer.report() } returns REPORT
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onDownload(KNOWN.name)
            advanceUntilIdle()
            recordedStops.value = listOf(stop(KNOWN, ModelDownloadFailure.PAUSED_TIME_LIMIT))
            advanceUntilIdle()
            assertEquals(
                AfterRestoreDownloadFailure.Paused,
                viewModel.state.value!!.models.first { it.name == KNOWN.name }.failure,
            )

            recordedStops.value = emptyList()
            advanceUntilIdle()

            assertNull(viewModel.state.value!!.models.first { it.name == KNOWN.name }.failure)
        }

    @Test
    fun `given a stop recorded while its download runs here when the download ends then the reason shows only then`() =
        runTest(dispatcher) {
            coEvery { restorer.report() } returns REPORT
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onDownload(KNOWN.name)
            advanceUntilIdle()

            // The worker records the pause just before the download reports its failure.
            recordedStops.value = listOf(stop(KNOWN, ModelDownloadFailure.PAUSED_TIME_LIMIT))
            advanceUntilIdle()
            val running = viewModel.state.value!!.models.first { it.name == KNOWN.name }
            assertEquals(0, running.progress)
            assertNull(running.failure)

            download.emit(
                DownloadState.Error(DownloadError("paused", failure = ModelDownloadFailure.PAUSED_TIME_LIMIT)),
            )
            advanceUntilIdle()

            assertEquals(
                AfterRestoreDownloadFailure.Paused,
                viewModel.state.value!!.models.first { it.name == KNOWN.name }.failure,
            )
        }

    @Test
    fun `given a stop recorded for a model not listed when the sheet shows then no row says anything`() =
        runTest(dispatcher) {
            coEvery { restorer.report() } returns REPORT
            val viewModel = viewModel()
            advanceUntilIdle()

            recordedStops.value = listOf(stop(GATED, ModelDownloadFailure.PAUSED_TIME_LIMIT))
            advanceUntilIdle()

            assertEquals(listOf(null, null), viewModel.state.value!!.models.map { it.failure })
        }

    @Test
    fun `given no report when the chat opens then the downloads' records are never read`() = runTest(dispatcher) {
        coEvery { restorer.report() } returns null

        viewModel()
        advanceUntilIdle()

        verify(exactly = 0) { downloads.observeInterruptedDownloads() }
    }

    @Test
    fun `given the sheet when Done is tapped then the downloads' records are no longer read`() = runTest(dispatcher) {
        coEvery { restorer.report() } returns REPORT
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(1, recordedStops.subscriptionCount.value)

        viewModel.onDone()
        advanceUntilIdle()

        assertEquals(0, recordedStops.subscriptionCount.value)
    }

    @Test
    fun `given a failed download when Download is tapped again then the reason leaves while it runs`() =
        runTest(dispatcher) {
            every { downloads.downloadModel(GATED.sourceUrl!!, GATED.name, any()) } returnsMany
                listOf(flowOf(DownloadState.Error(DownloadError("HTTP 401", code = 401))), download)
            coEvery { restorer.report() } returns REPORT.copy(modelsToDownload = listOf(GATED))
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onDownload(GATED.name)
            advanceUntilIdle()
            assertEquals(AfterRestoreDownloadFailure.NeedsAccess, viewModel.state.value!!.models.single().failure)

            viewModel.onDownload(GATED.name)

            val row = viewModel.state.value!!.models.single()
            assertNull(row.failure)
            assertEquals(0, row.progress)
        }

    @Test
    fun `given a failed model installed elsewhere and deleted when the chat resumes then its row is clean`() =
        runTest(dispatcher) {
            every { downloads.downloadModel(KNOWN.sourceUrl!!, KNOWN.name, any()) } returns
                flowOf(DownloadState.Error(DownloadError("timeout")))
            coEvery { restorer.report() } returns REPORT
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onDownload(KNOWN.name)
            advanceUntilIdle()

            // Installed from Discover, then deleted on the Models screen, while the chat was away.
            viewModel.onPaused()
            coEvery { localModels.isInstalled(KNOWN.name) } returns true
            viewModel.onResumed()
            advanceUntilIdle()
            viewModel.onPaused()
            coEvery { localModels.isInstalled(KNOWN.name) } returns false
            viewModel.onResumed()
            advanceUntilIdle()

            assertNull(viewModel.state.value!!.models.first { it.name == KNOWN.name }.failure)
        }

    @Test
    fun `given a running download when cancelled then the worker is told and the row resets`() = runTest(dispatcher) {
        coEvery { restorer.report() } returns REPORT
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onDownload(KNOWN.name)
        advanceUntilIdle()

        viewModel.onCancelDownload(KNOWN.name)

        verify { downloads.cancelDownload(KNOWN.name) }
        assertNull(viewModel.state.value!!.models.first { it.name == KNOWN.name }.progress)
    }

    @Test
    fun `given a model whose source is unknown when Download is asked for then nothing starts`() = runTest(dispatcher) {
        coEvery { restorer.report() } returns REPORT
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onDownload(UNKNOWN.name)

        verify(exactly = 0) { downloads.downloadModel(any(), any(), any()) }
    }

    @Test
    fun `given the sheet when Done is tapped then it closes and the report is forgotten`() = runTest(dispatcher) {
        coEvery { restorer.report() } returns REPORT
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onDone()
        advanceUntilIdle()

        assertNull(viewModel.state.value)
        coVerify { restorer.dismissReport() }
    }

    /** A stop the download recorded for [model], as the download manager lists it. */
    private fun stop(model: BackupModelEntry, failure: ModelDownloadFailure) =
        InterruptedDownload(model.name, model.sourceUrl!!, failure, message = "stopped")

    /** The view model with the chat on screen, as the host reports it once the destination is resumed. */
    private fun viewModel() = hiddenViewModel().also { it.onResumed() }

    private fun hiddenViewModel() =
        AfterRestoreViewModel(restorer, downloads, generationSettings, localModels, relay, context) { "on $it" }

    private companion object {
        val KNOWN = BackupModelEntry("gemma.litertlm", 600, null, "https://example/gemma")
        val UNKNOWN = BackupModelEntry("custom.litertlm", 900, null, null)
        val GATED = BackupModelEntry(
            "gated.litertlm",
            700,
            null,
            "https://huggingface.co/org/gated/resolve/main/gated.litertlm",
        )
        val REPORT = RestoreReport(
            modelsToDownload = listOf(KNOWN, UNKNOWN),
            scheduledTasksLeftBehind = 2,
            secretsApplied = false,
            backupCreatedAtEpochMillis = 5_000,
            secretsOnPhone = false,
        )
    }
}
