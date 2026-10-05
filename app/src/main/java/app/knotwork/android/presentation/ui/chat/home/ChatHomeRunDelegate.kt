package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.JournalExportDocument
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.RunTraceExportDocument
import app.knotwork.android.domain.repositories.GenerationSettings
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.verification.DescribeRunUseCase
import app.knotwork.android.domain.verification.ExportRunTraceUseCase
import app.knotwork.android.domain.verification.RunAgainAvailability
import app.knotwork.android.domain.verification.RunAgainRequest
import app.knotwork.android.domain.verification.RunDescription
import app.knotwork.android.domain.verification.VerificationPlan
import app.knotwork.android.domain.verification.VerifyAvailability
import app.knotwork.android.domain.verification.VerifyRunUseCase
import app.knotwork.android.presentation.common.DisplayFormat
import app.knotwork.android.presentation.ui.common.JournalExportDelegate
import app.knotwork.android.presentation.ui.common.journalExportFileName
import app.knotwork.android.presentation.ui.common.journalGeneratedAtLabel
import app.knotwork.design.components.console.ConsoleHashCopy
import app.knotwork.design.components.console.HashKind
import app.knotwork.design.components.console.RunAgainConfirmUi
import app.knotwork.design.components.console.RunExportUi
import app.knotwork.design.components.console.RunLineUi
import app.knotwork.design.components.console.RunSettingsTarget
import app.knotwork.design.components.console.VerificationUi
import app.knotwork.design.components.console.VerifyConfirmUi
import app.knotwork.design.screens.chat.ChatHomeRunState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The use cases behind the run strip, injected as one so the chat ViewModel's
 * constructor grows by one parameter, not three.
 *
 * @property describeRun What the strip shows about a run.
 * @property verifyRun The check itself.
 * @property exportRunTrace The trace export.
 */
class ChatHomeRunUseCases @Inject constructor(
    val describeRun: DescribeRunUseCase,
    val verifyRun: VerifyRunUseCase,
    val exportRunTrace: ExportRunTraceUseCase,
)

/**
 * Something the run strip asks the screen to do that the ViewModel cannot: put
 * text on the clipboard, show a snackbar, or open settings.
 */
sealed interface RunHeaderEvent {

    /**
     * Copy [text] and say what was copied.
     *
     * @property text What goes on the clipboard: digits or 64 hex characters, never spaced.
     * @property what What it was, for the snackbar.
     */
    data class Copy(val text: String, val what: Copied) : RunHeaderEvent

    /**
     * A run was started again with its seed.
     *
     * @property seed The seed, grouped for reading.
     */
    data class RunningAgain(val seed: String) : RunHeaderEvent

    /**
     * Open where a mismatch is fixed.
     *
     * @property target The Settings home or Settings → Generation.
     */
    data class OpenSettings(val target: RunSettingsTarget) : RunHeaderEvent
}

/** What a [RunHeaderEvent.Copy] copied. */
sealed interface Copied {
    /** The seed. */
    data object Seed : Copied

    /** A model file's SHA-256. */
    data object ModelSha : Copied

    /** The run digest. */
    data object Digest : Copied

    /** A recorded or replayed answer's hash from the check. */
    data object VerificationHash : Copied

    /**
     * A node's input or output hash.
     *
     * @property node The node's name as the row shows it.
     * @property kind Input or output.
     */
    data class NodeHash(val node: String, val kind: HashKind) : Copied
}

/** File-name stem of a run trace export. */
internal const val RUN_TRACE_EXPORT_STEM: String = "run-trace"

/**
 * The run strip's half of the chat ViewModel: which run the console shows, what
 * its strip says, and the three actions — check it, start it again with its seed,
 * export its trace.
 *
 * The console's run is the session's active root run, else its latest one, as the
 * console's own replay picks it. The strip is re-described whenever that run
 * changes status and whenever the backend or window the next load would use
 * changes, so a mismatch appears and clears as the user fixes it.
 *
 * Shares the ViewModel's [scope] and [state] like the other chat delegates; every
 * change lands in `console.runHeader` / `console.runHeaderExpanded` and `run`.
 *
 * @property scope The ViewModel's scope.
 * @property state The ViewModel's state flow.
 * @property pipelineRunRepository The session's runs.
 * @property useCases The use cases behind the strip.
 * @property generationSettings The backend and window the next load would use.
 * @property startRunAgain Starts a run again with a recorded run's seed — the
 *   ViewModel's send path, which owns the generating state.
 */
class ChatHomeRunDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatHomeScreenState>,
    private val pipelineRunRepository: PipelineRunRepository,
    private val useCases: ChatHomeRunUseCases,
    private val generationSettings: GenerationSettings,
    private val startRunAgain: (RunAgainRequest) -> Unit,
) {

    private val _events = MutableSharedFlow<RunHeaderEvent>(extraBufferCapacity = 1)

    /** One-shot requests for the screen. */
    val events: SharedFlow<RunHeaderEvent> = _events.asSharedFlow()

    private var sessionJob: Job? = null
    private var description: RunDescription? = null
    private var exportDocument: RunTraceExportDocument? = null
    private var exportFileName: String? = null

    /**
     * Shares and saves the rendered trace; the document and name are the ones the
     * export sheet showed.
     */
    val export: JournalExportDelegate = JournalExportDelegate(
        scope = scope,
        fileNameStem = RUN_TRACE_EXPORT_STEM,
        buildDocument = { _ ->
            val document = checkNotNull(exportDocument) { "no run trace was rendered" }
            JournalExportDocument(json = document.json, entryCount = document.recordCount)
        },
        fileNameFor = { at -> exportFileName ?: journalExportFileName(RUN_TRACE_EXPORT_STEM, at) },
    )

    /** The check of the console's run: its confirmation, its sheet and its events. */
    val verification: RunVerificationController = RunVerificationController(
        scope = scope,
        state = state,
        verifyRun = useCases.verifyRun,
        freshPlan = ::freshPlan,
    )

    /**
     * Follows the console's run in [sessionId], replacing whatever was followed.
     *
     * @param sessionId The session the chat shows.
     */
    fun observe(sessionId: String) {
        sessionJob?.cancel()
        verification.close()
        description = null
        state.update {
            it.copy(console = it.console.copy(runHeader = null, runHeaderExpanded = false), run = ChatHomeRunState())
        }
        sessionJob = scope.launch {
            combine(
                pipelineRunRepository.observeRunsForSession(sessionId).map(::consoleRunKey).distinctUntilChanged(),
                generationSettings.localModelBackend,
                generationSettings.maxContextLength,
            ) { key, _, _ -> key }
                .collectLatest { key -> describe(key?.id) }
        }
    }

    /** Opens or closes the strip; opening re-reads the run, so a checksum computed meanwhile shows. */
    fun toggleHeader() {
        val opening = !state.value.console.runHeaderExpanded
        state.update { it.copy(console = it.console.copy(runHeaderExpanded = opening)) }
        if (opening) scope.launch { describe(description?.run?.id) }
    }

    /** Copies the seed as digits. */
    fun copySeed() {
        val seed = description?.run?.header?.seed ?: return
        _events.tryEmit(RunHeaderEvent.Copy(seed.toString(), Copied.Seed))
    }

    /**
     * Copies a model file's full SHA-256.
     *
     * @param sha256 The hash.
     */
    fun copyModelSha(sha256: String) {
        _events.tryEmit(RunHeaderEvent.Copy(sha256, Copied.ModelSha))
    }

    /** Copies the run digest. */
    fun copyDigest() {
        val digest = description?.digest ?: return
        _events.tryEmit(RunHeaderEvent.Copy(digest, Copied.Digest))
    }

    /**
     * Copies a node's full input or output hash from the Vars or Traces tab.
     *
     * @param hash The chip the user tapped.
     */
    fun copyNodeHash(hash: ConsoleHashCopy) {
        _events.tryEmit(RunHeaderEvent.Copy(hash.sha256, Copied.NodeHash(hash.node, hash.kind)))
    }

    /**
     * Copies a recorded or replayed hash from the check's sheet.
     *
     * @param sha256 The hash.
     */
    fun copyVerificationHash(sha256: String) {
        _events.tryEmit(RunHeaderEvent.Copy(sha256, Copied.VerificationHash))
    }

    /**
     * Opens where a mismatch is fixed.
     *
     * @param target The Settings home or Settings → Generation.
     */
    fun openSettings(target: RunSettingsTarget) {
        _events.tryEmit(RunHeaderEvent.OpenSettings(target))
    }

    /** Asks before starting the run again with its seed. */
    fun runAgain() {
        val request = (description?.runAgain as? RunAgainAvailability.Available)?.request ?: return
        val sampler = request.sampling.sampler
        val confirm = RunAgainConfirmUi(
            seed = RunDisplay.grouped(request.sampling.seed),
            pipeline = request.pipelineName,
            temperature = RunDisplay.decimal(sampler.temperature),
            topK = sampler.topK,
            topP = RunDisplay.decimal(sampler.topP),
        )
        state.update { it.withRun { run -> run.copy(runAgainConfirm = confirm) } }
    }

    /** Starts the run again with its seed, closes the console so the answer shows as it arrives, and says so. */
    fun confirmRunAgain() {
        val request = (description?.runAgain as? RunAgainAvailability.Available)?.request
        state.update { it.withRun { run -> run.copy(runAgainConfirm = null) } }
        if (request == null) return
        startRunAgain(request)
        _events.tryEmit(RunHeaderEvent.RunningAgain(RunDisplay.grouped(request.sampling.seed)))
    }

    /** Closes the run-again confirmation. */
    fun dismissRunAgainConfirm() {
        state.update { it.withRun { run -> run.copy(runAgainConfirm = null) } }
    }

    /** Renders the trace and opens the export sheet with the file's name and size. */
    fun openExport() {
        val runId = description?.run?.id ?: return
        scope.launch {
            val document = useCases.exportRunTrace(runId, journalGeneratedAtLabel()) ?: return@launch
            val fileName = journalExportFileName(RUN_TRACE_EXPORT_STEM)
            exportDocument = document
            exportFileName = fileName
            val ui = RunExportUi(fileName = fileName, size = DisplayFormat.formatBytes(document.sizeBytes.toLong()))
            state.update { it.withRun { run -> run.copy(export = ui) } }
        }
    }

    /** Hands the rendered trace to the share sheet and closes the export sheet. */
    fun shareExport() {
        export.share()
        dismissExport()
    }

    /** Closes the export sheet; the rendered document stays for a save the picker is still finishing. */
    fun dismissExport() {
        state.update { it.withRun { run -> run.copy(export = null) } }
    }

    /** Re-reads the run [runId] and projects it onto the strip; `null` clears it. */
    private suspend fun describe(runId: String?) {
        val described = runId?.let { useCases.describeRun(it) }
        description = described
        state.update { it.copy(console = it.console.copy(runHeader = described?.toRunHeaderUi())) }
    }

    /** The run re-read now, and its check's plan when the check can start. */
    private suspend fun freshPlan(): VerificationPlan? {
        describe(description?.run?.id)
        return (description?.verify as? VerifyAvailability.Available)?.plan
    }

    /**
     * The run the console shows among [runs] (most recent first): the active root
     * run, else the latest one — keyed with its status so a status change
     * re-describes it.
     */
    private fun consoleRunKey(runs: List<PipelineRun>): ConsoleRunKey? {
        val roots = runs.filter { it.parentRunId == null }
        val run = roots.firstOrNull { !it.status.isTerminal } ?: roots.firstOrNull()
        return run?.let { ConsoleRunKey(it.id, it.status.name) }
    }

    /**
     * The console's run and its status.
     *
     * @property id The run.
     * @property status Its status's name.
     */
    private data class ConsoleRunKey(val id: String, val status: String)
}

/** [this] with its run surfaces changed by [change]. */
private fun ChatHomeScreenState.withRun(change: (ChatHomeRunState) -> ChatHomeRunState): ChatHomeScreenState =
    copy(run = change(run))

/**
 * The check of the run the console shows: it re-reads the run before starting, so
 * it plans against the settings of now; a long check asks first; a run whose
 * on-device calls all ran on the NPU opens straight to its result; and the check's
 * events fill its sheet until it ends, is cancelled or is closed.
 *
 * @property scope The ViewModel's scope.
 * @property state The ViewModel's state flow.
 * @property verifyRun The check itself.
 * @property freshPlan Re-reads the console's run and returns its check's plan, or
 *   `null` when the check cannot start now — the strip then says why.
 */
class RunVerificationController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatHomeScreenState>,
    private val verifyRun: VerifyRunUseCase,
    private val freshPlan: suspend () -> VerificationPlan?,
) {

    private var job: Job? = null
    private var progress: VerificationProgress? = null

    /** Starts the check, or asks first when it is long. */
    fun verify() {
        scope.launch {
            val plan = freshPlan() ?: return@launch
            if (asksBeforeVerifying(plan)) {
                state.update { it.withRun { run -> run.copy(verifyConfirm = confirmFor(plan)) } }
            } else {
                start(plan)
            }
        }
    }

    /** Starts the check its confirmation asked about. */
    fun confirm() {
        state.update { it.withRun { run -> run.copy(verifyConfirm = null) } }
        scope.launch { freshPlan()?.let(::start) }
    }

    /** Closes the check's confirmation. */
    fun dismissConfirm() {
        state.update { it.withRun { run -> run.copy(verifyConfirm = null) } }
    }

    /** Stops the check; the visits checked so far keep their verdicts. */
    fun cancel() {
        job?.cancel()
        val tracker = progress ?: return
        state.update { screen ->
            screen.withRun { run -> run.copy(verification = run.verification?.let(tracker::cancelled)) }
        }
    }

    /** Starts the check again from a fresh plan; a mismatch since closes the sheet and shows in the strip. */
    fun again() {
        scope.launch {
            val plan = freshPlan()
            if (plan == null) close() else start(plan)
        }
    }

    /** Closes the check's sheet, stopping a check still running. */
    fun close() {
        job?.cancel()
        progress = null
        state.update { it.withRun { run -> run.copy(verification = null) } }
    }

    /** The confirmation for [plan]. */
    private fun confirmFor(plan: VerificationPlan) = VerifyConfirmUi(
        calls = plan.calls,
        seed = RunDisplay.grouped(plan.header.seed),
        cloudCalls = plan.cloudCalls,
        estimate = plan.recordedModelMs?.let(RunDisplay::duration),
    )

    /** Opens the check's sheet on [plan] and folds its events into it until it ends or is stopped. */
    private fun start(plan: VerificationPlan) {
        job?.cancel()
        val line = state.value.console.runHeader?.line as? RunLineUi.Seeded
        val tracker = VerificationProgress(
            plan = plan,
            seed = RunDisplay.grouped(plan.header.seed),
            backend = line?.backend.orEmpty(),
            model = line?.model.orEmpty(),
        )
        progress = tracker
        show(tracker.start())
        job = scope.launch {
            verifyRun(plan).collect { event ->
                val current = state.value.run.verification ?: return@collect
                show(tracker.after(current, event))
            }
        }
    }

    /** Shows [ui] in the check's sheet. */
    private fun show(ui: VerificationUi) {
        state.update { it.withRun { run -> run.copy(verification = ui) } }
    }
}
