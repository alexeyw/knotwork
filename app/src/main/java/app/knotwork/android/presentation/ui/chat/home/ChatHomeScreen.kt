package app.knotwork.android.presentation.ui.chat.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.knotwork.android.BuildConfig
import app.knotwork.android.R
import app.knotwork.android.domain.report.ContentReport
import app.knotwork.android.domain.report.ContentReportComposer
import app.knotwork.android.domain.report.ContentReportReason
import app.knotwork.android.presentation.ui.chat.CHAT_EXPORT_MIME_JSON
import app.knotwork.android.presentation.ui.chat.toShareChooser
import app.knotwork.android.presentation.ui.common.RunTerminationAction
import app.knotwork.android.presentation.ui.common.RunTerminationCopyMapper
import app.knotwork.android.presentation.ui.common.resolve
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.chat.AudioSourceChooserSheet
import app.knotwork.design.components.chat.ChatContextAction
import app.knotwork.design.components.chat.ImageViewer
import app.knotwork.design.components.chat.SourceChooserSheet
import app.knotwork.design.components.controls.KnotworkField
import app.knotwork.design.components.controls.KnotworkTextField
import app.knotwork.design.components.dialogs.ConfirmDialog
import app.knotwork.design.components.dialogs.ConfirmDialogUi
import app.knotwork.design.components.knotworkMarkdownColor
import app.knotwork.design.components.knotworkMarkdownTypography
import app.knotwork.design.components.misc.KnotworkSnackbarHost
import app.knotwork.design.screens.chat.ChatHomeCallbacks
import app.knotwork.design.screens.chat.ChatHomeContent
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Redesigned Knotwork chat home — the user-facing surface that wires up:
 *  - agent orchestrator, sessions, pipeline binding, token meter;
 *  - HITL approval gate + clarification;
 *  - console pane;
 *  - secondary affordances: new-chat / rename /
 *    favorite / import / model picker / overflow (export, delete,
 *    clear-console) and the deep-link to Settings + Models.
 *
 * Inset wiring:
 *  - `safeDrawing.horizontal` clears the side system bars in landscape.
 *  - `AppShellScaffold` already applies `.imePadding()` so the body
 *    follows the keyboard.
 *
 * @param viewModel the screen-scoped Hilt [ChatHomeViewModel].
 * @param onOpenSettings deep-link callback into the Settings route.
 * @param onOpenModels deep-link callback into the Models management route.
 * @param onOpenArchive deep-link callback into the archived-chats route, fired
 *   from the drawer footer entry (which only appears once something is archived).
 * @param onOpenRunLimits deep-link callback into the run-limits screen, offered
 *   on a run that a ceiling stopped — the one action that can change the
 *   outcome, where retrying the same turn cannot.
 * @param modifier optional layout modifier applied to the screen root.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHomeScreen(
    viewModel: ChatHomeViewModel,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    onOpenArchive: () -> Unit = {},
    onOpenRunLimits: () -> Unit = {},
) {
    // Single subscription to the consolidated screen state — the immutable
    // sub-structures (composer, console, pending, thread, model, tokens)
    // are handed down the tree as-is, so child composables skip when their
    // slice did not change.
    val screenState by viewModel.state.collectAsStateWithLifecycle()
    val currentSessionId = screenState.thread.currentSessionId
    val chatListState = rememberLazyListState()

    // Auto-scroll for the message list, re-armed on every thread switch.
    //  1. Opening a thread jumps to the very end — but only once the list has
    //     actually laid out its items. The `snapshotFlow { … totalItemsCount }`
    //     wait is essential: the list is composed only after the messages
    //     arrive (Empty → Idle), so scrolling in the same frame would hit an
    //     unmeasured list and silently no-op (the bug in the first attempt).
    //  2. After that baseline, each newly appended message (user or agent) is
    //     revealed per the spec — top-aligned when taller than the viewport,
    //     otherwise bottom-aligned. Nothing moves when the conversation already
    //     fits (the scroll calls clamp to the current position).
    LaunchedEffect(currentSessionId) {
        if (currentSessionId.isBlank()) return@LaunchedEffect
        var knownMessages = -1
        var knownItems = -1
        // Observe BOTH the message count and the rendered item count:
        //  - `screenState.messages.size` grows by one for each appended
        //    user/agent row (and stays correct even when a generating-loader
        //    item is swapped for the agent's final message, which leaves the
        //    rendered count flat);
        //  - `layoutInfo.totalItemsCount` also captures the trailing service rows
        //    (the generating loader and the error tile) that are NOT part of
        //    the message list, so those scroll into view too.
        // Both reads MUST go through snapshot state inside the lambda:
        // `screenState` is a delegated Compose State, so reading it here keeps
        // snapshotFlow reactive across recompositions. A plain local copy of
        // the list would be frozen at effect-launch time (the effect only
        // restarts on a thread switch) and the loader-swap case would never
        // re-emit.
        // On either growth (or a freshly opened thread) we scroll to the list's
        // real last item. `scrollToItem` is reliable on its own: a short last
        // item clamps to the bottom (bottom-aligned), a tall one aligns its top
        // to the viewport (top-aligned), and a list that already fits is a no-op.
        snapshotFlow { screenState.messages.size to chatListState.layoutInfo.totalItemsCount }
            .collect { (messageCount, itemCount) ->
                if (messageCount <= 0 || itemCount <= 0) {
                    knownMessages = messageCount
                    knownItems = itemCount
                    return@collect
                }
                val opened = knownMessages < 0
                val grew = messageCount > knownMessages || itemCount > knownItems
                if (opened || grew) {
                    chatListState.scrollToItem(itemCount - 1)
                }
                knownMessages = messageCount
                knownItems = itemCount
            }
    }

    // Publish the open session as "active" while this chat is in the foreground so
    // the live HITL approval gate suppresses its duplicate system notification —
    // the user already sees the inline approval card. Cleared on pause / dispose /
    // background so a run that raises a gate while the app is away still notifies.
    // Keyed on currentSessionId so switching threads re-points the tracker.
    LifecycleResumeEffect(currentSessionId) {
        viewModel.onChatScreenVisible()
        onPauseOrDispose { viewModel.onChatScreenHidden() }
    }

    var debugPickerExpanded by remember { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }
    var renameTargetId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var newThreadSheetVisible by remember { mutableStateOf(false) }
    var deleteDialogVisible by remember { mutableStateOf(false) }
    var deleteThreadTargetId by remember { mutableStateOf<String?>(null) }
    // Report dialog state: the flagged row plus the category and note the user
    // is composing. Local to the screen because a report is never persisted —
    // it exists between opening the dialog and handing the text to the user.
    var reportTargetRowId by remember { mutableStateOf<String?>(null) }
    var reportReason by remember { mutableStateOf(ContentReportReason.HARMFUL_OR_UNSAFE) }
    var reportNote by remember { mutableStateOf("") }
    // The in-flight archive-undo snackbar, so a second archive supersedes it.
    var archiveSnackbarJob by remember { mutableStateOf<Job?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val pipelineFallbackMessage = stringResource(R.string.errors_chat_pipeline_removed)
    val consoleLineCopiedMessage = stringResource(R.string.chat_snackbar_console_line_copied)
    val consoleAllCopiedMessage = stringResource(R.string.chat_snackbar_console_copied)
    val exportChooserTitle = stringResource(R.string.chat_export_chooser_title)
    val importFailedTemplate = stringResource(R.string.chat_import_failed)
    val importUnreadableMessage = stringResource(R.string.chat_import_unreadable)
    val messageCopiedMessage = stringResource(R.string.chat_snackbar_copied)
    val reportCopiedMessage = stringResource(R.string.chat_report_snackbar_copied)
    val reportNoBrowserMessage = stringResource(R.string.chat_report_snackbar_no_browser)
    val savedToMemoryMessage = stringResource(R.string.chat_snackbar_saved_to_memory)
    val attachmentFailedMessage = stringResource(R.string.chat_snackbar_attachment_failed)
    val attachmentReplacedMessage = stringResource(R.string.chat_snackbar_attachment_replaced)
    val voiceFailedMessage = stringResource(R.string.chat_snackbar_voice_failed)
    val saveToMemoryFailedMessage = stringResource(R.string.chat_snackbar_save_to_memory_failed)
    val resumeGraphChangedMessage = stringResource(R.string.chat_snackbar_resume_graph_changed)
    val resumeExpiredMessage = stringResource(R.string.chat_snackbar_resume_expired)
    val resumeUnavailableMessage = stringResource(R.string.chat_snackbar_resume_unavailable)
    val archiveStrings = ArchiveSnackbarStrings(
        archived = stringResource(R.string.chat_snackbar_archived),
        undo = stringResource(R.string.chat_snackbar_archive_undo),
        restored = stringResource(R.string.chat_snackbar_restored),
    )

    LaunchedEffect(viewModel) {
        viewModel.pipelineBinding.pipelineFallbackEvents.collect {
            snackbarHostState.showSnackbar(message = pipelineFallbackMessage)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.console.consoleSnackbarEvents.collect { event ->
            val message = when (event) {
                ConsoleSnackbarEvent.LineCopied -> consoleLineCopiedMessage
                ConsoleSnackbarEvent.AllCopied -> consoleAllCopiedMessage
            }
            snackbarHostState.showSnackbar(message = message)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.transfer.exportEvents.collect { document ->
            context.startActivity(document.toShareChooser(exportChooserTitle))
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.transfer.importErrorEvents.collect { reason ->
            snackbarHostState.showSnackbar(message = importFailedTemplate.format(reason))
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.reattach.resumeFeedbackEvents.collect { event ->
            val message = when (event) {
                ResumeFeedbackEvent.GraphChanged -> resumeGraphChangedMessage
                ResumeFeedbackEvent.Expired -> resumeExpiredMessage
                ResumeFeedbackEvent.NotResumable -> resumeUnavailableMessage
            }
            snackbarHostState.showSnackbar(message = message)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.transfer.memorySaveEvents.collect { event ->
            val message = when (event) {
                MemorySaveEvent.Saved -> savedToMemoryMessage
                MemorySaveEvent.Failed -> saveToMemoryFailedMessage
            }
            snackbarHostState.showSnackbar(message = message)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.attachments.attachmentErrorEvents.collect {
            snackbarHostState.showSnackbar(message = attachmentFailedMessage)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.attachments.attachmentReplacedEvents.collect {
            snackbarHostState.showSnackbar(message = attachmentReplacedMessage)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.voice.voiceErrorEvents.collect {
            snackbarHostState.showSnackbar(message = voiceFailedMessage)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (json.isNullOrBlank()) {
                snackbarHostState.showSnackbar(message = importUnreadableMessage)
            } else {
                viewModel.transfer.importChatFromJson(json)
            }
        }
    }

    // Photo Picker (gallery / screenshots) — permission-free across all
    // supported API levels; on success the VM ingests the picked content URI.
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) viewModel.attachments.onImagePicked(uri.toString())
    }
    // Camera capture into a FileProvider URI the attachment delegate mints. The
    // URI is kept across recreation (the camera app is in front, so a rotation or
    // a process restore is likely): losing it lost the photo and left its
    // full-resolution original behind. The delegate ingests or discards it — the
    // original is deleted either way.
    var pendingCaptureUri by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCaptureUri
        pendingCaptureUri = null
        if (uri != null) viewModel.attachments.onCaptureResult(uri, success)
    }

    // Voice input: a RECORD_AUDIO permission gate before capture, and an
    // OpenDocument picker (audio/*) for an existing clip. Both feed the VM's
    // record→transcribe / pick→transcribe flow.
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.voice.startRecording() else viewModel.voice.onMicPermissionDenied()
    }
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.voice.onAudioFilePicked(uri.toString())
    }
    val requestRecordAudio: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.voice.startRecording()
        } else {
            recordAudioPermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO,
            )
        }
    }

    // Resolve every user-facing stub string up here so the mapping below
    // stays free of hardcoded strings — agent-status pills, drawer
    // sessions, and the empty-state suggestion cards all flow from
    // `strings_chat.xml`.
    val fixtures = rememberChatHomeFixtures()

    val viewState = screenState.toViewState(fixtures = fixtures) { context.resolve(it) }

    val callbacks = ChatHomeCallbacks(
        onComposerValueChange = viewModel::onComposerValueChange,
        onSend = viewModel::sendMessage,
        onStop = viewModel::stopGeneration,
        onAttach = viewModel.attachments::onAttachClicked,
        onRemoveAttachment = viewModel.attachments::removeAttachment,
        onMic = viewModel.voice::onMicClicked,
        onStopRecording = viewModel.voice::onStopRecording,
        onDiscardRecording = viewModel.voice::onDiscardRecording,
        onChangeModel = onOpenModels,
        onOpenAppSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
        onOpenDrawer = viewModel.threads::openDrawer,
        onCloseDrawer = viewModel.threads::closeDrawer,
        onSelectThread = viewModel::selectThread,
        onNewThread = { newThreadSheetVisible = true },
        onOverflow = { overflowExpanded = true },
        onSamplePrompt = viewModel::onComposerValueChange,
        onConsoleSnapChange = viewModel.console::setConsoleSnap,
        onConsoleTabChange = viewModel.console::onConsoleTabChange,
        onConsoleFilterChange = viewModel.console::onConsoleFilterChange,
        onConsoleSearch = viewModel.console::toggleConsoleSearch,
        onConsoleSearchQueryChange = viewModel.console::onConsoleSearchQueryChange,
        onConsoleCopyLine = { line ->
            clipboardManager.setText(AnnotatedString(ConsoleCopyPayloads.line(line)))
            viewModel.console.signalConsoleLineCopied()
        },
        onConsoleCopyVar = { row ->
            clipboardManager.setText(AnnotatedString(ConsoleCopyPayloads.variable(row)))
            viewModel.console.signalConsoleLineCopied()
        },
        onConsoleCopySpan = { span ->
            clipboardManager.setText(AnnotatedString(ConsoleCopyPayloads.span(span)))
            viewModel.console.signalConsoleLineCopied()
        },
        onConsoleFilterByLineSource = viewModel.console::filterConsoleByLineSource,
        // `Copy all` copies the tab the user is looking at. It used to copy log
        // lines whatever tab was open, so on Vars and Traces the button silently
        // put something else on the clipboard. The catalog applies
        // `console.filter` + `console.searchQuery` itself before rendering log
        // rows, so the screen reproduces the same pre-filter for that tab; the
        // choice of tab is made in the delegate, where a test can pin it.
        onConsoleCopyAll = {
            val console = screenState.console
            val payload = ConsoleCopyPayloads.forTab(
                tab = console.tab,
                visibleLogs = visibleConsoleLogs(console.logs, console.filter, console.searchQuery),
                vars = console.vars,
                traces = console.traces,
            )
            clipboardManager.setText(AnnotatedString(payload))
            viewModel.console.signalConsoleAllCopied()
        },
        onConsoleClear = viewModel.console::requestConsoleClear,
        onCloseConsole = viewModel.console::closeConsole,
        onHitlAllowOnce = viewModel.hitl::approveTool,
        onHitlReject = viewModel.hitl::rejectTool,
        onHitlTypedConfirmChange = viewModel.hitl::onTypedConfirmChange,
        onClarificationReply = viewModel.hitl::submitClarificationReply,
        onResumeRun = viewModel.reattach::resumeInterruptedRun,
        onDiscardRun = viewModel.reattach::discardInterruptedRun,
        onContinuePastCeiling = viewModel.hitl::continuePastCeiling,
        onStopAtCeiling = viewModel.hitl::stopAtCeiling,
        onErrorRetry = viewModel::retryAfterError,
        // One slot, three destinations. The tile carries a label, not a verb,
        // so what the action *does* is decided here from the typed reason the
        // label was derived from — keeping the design module free of any
        // opinion about navigation.
        onTerminationAction = {
            val reason = (screenState.visual as? ChatHomeUiState.Error)?.reason
            when (reason?.let { RunTerminationCopyMapper.terminationCopy(it).action }) {
                RunTerminationAction.ADJUST_LIMITS -> onOpenRunLimits()
                RunTerminationAction.OPEN_CONSOLE -> viewModel.console.openConsole()
                RunTerminationAction.RUN_AGAIN -> viewModel.retryAfterError()
                null -> Unit
            }
        },
        onTitleTripleTap = { debugPickerExpanded = true },
        onToggleFavorite = viewModel.threads::toggleFavoriteCurrent,
        onEditThread = { threadId ->
            val session = screenState.thread.rows.firstOrNull { it.id == threadId }
            renameDraft = session?.title.orEmpty()
            renameTargetId = threadId
        },
        onThreadMenuOpen = viewModel.threads::openThreadMenu,
        onThreadMenuDismiss = viewModel.threads::dismissThreadMenu,
        onArchiveThread = { threadId ->
            viewModel.threads.archiveThread(threadId)
            // Supersede any undo still on screen instead of queueing behind it:
            // the drawer stays open precisely so the user can archive two or
            // three in a row, and a queued snackbar would both delay the new
            // confirmation and leave a stale Undo pointing at the wrong chat.
            // Cancelling the previous coroutine dismisses its snackbar.
            archiveSnackbarJob?.cancel()
            archiveSnackbarJob = coroutineScope.launch {
                showArchiveUndoSnackbar(snackbarHostState, viewModel, threadId, archiveStrings)
            }
        },
        // Deleting from a drawer row is destructive, so it goes through the
        // same confirmation the top-bar Delete does — the menu item only
        // raises the dialog.
        onDeleteThread = { threadId -> deleteThreadTargetId = threadId },
        onOpenArchive = onOpenArchive,
        onRestoreArchivedThread = {
            val threadId = screenState.thread.currentSessionId
            viewModel.threads.unarchiveThread(threadId)
            coroutineScope.launch { snackbarHostState.showSnackbar(message = archiveStrings.restored) }
        },
        onImportChat = { importLauncher.launch(arrayOf(CHAT_EXPORT_MIME_JSON)) },
        onOpenSettings = onOpenSettings,
        onSamplePromptCard = { card -> viewModel.onComposerValueChange(card.title) },
        // Tapping the agent-status pill above the composer opens the
        // console pane at the Partial snap — a one-tap drill-in affordance.
        onAgentStatusClick = { viewModel.console.openConsole() },
        onMessageContextAction = { rowId, action ->
            when (action) {
                ChatContextAction.Copy -> {
                    val text = viewModel.transfer.textForRow(rowId)
                    if (!text.isNullOrEmpty()) {
                        clipboardManager.setText(AnnotatedString(text))
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(message = messageCopiedMessage)
                        }
                    }
                }
                ChatContextAction.Rerun -> {
                    viewModel.transfer.textForRow(rowId)?.let(viewModel::onComposerValueChange)
                }
                ChatContextAction.SaveToMemory -> {
                    viewModel.transfer.saveMessageToMemory(rowId)
                }
                ChatContextAction.Report -> {
                    reportReason = ContentReportReason.HARMFUL_OR_UNSAFE
                    reportNote = ""
                    reportTargetRowId = rowId
                }
            }
        },
    )

    // Inset wiring:
    //  - `AppShellScaffold` already wraps its Scaffold in `.imePadding()`,
    //    so the body + composer slide up with the keyboard in sync with
    //    the bottom-nav. Adding `.imePadding()` here would double-count.
    //  - `safeDrawing.horizontal` keeps the surface clear of the side
    //    system bars in landscape; the inner `Scaffold` inside
    //    `ChatHomeContent` already handles status-bar inset via its
    //    `TopAppBar` defaults.
    Box(
        contentAlignment = Alignment.TopStart,
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        // Pull the Knotwork-themed markdown bindings once per recomposition
        // so the renderer lambda below doesn't re-resolve composition locals
        // on every emit. `knotworkMarkdownTypography` / `…Color` ride
        // `KnotworkTextStyles` + `KnotworkTheme.extended.surface{1,2,3}`,
        // matching markdown headings, body, code surfaces, and tables to the
        // surrounding chat surface tokens.
        val markdownTypography = knotworkMarkdownTypography()
        val markdownColors = knotworkMarkdownColor()
        ChatHomeContent(
            state = viewState,
            callbacks = callbacks,
            // Catalog stays free of any markdown dependency on the screen
            // side; the app wires the `com.mikepenz.markdown.m3.Markdown`
            // renderer here so agent bubbles get the Knotwork-themed
            // typography + colors for headings, lists, and code fences.
            markdownRenderer = { source ->
                Markdown(
                    content = source,
                    typography = markdownTypography,
                    colors = markdownColors,
                )
            },
            messageListState = chatListState,
            // In the chat's own Scaffold, which lifts it above the bottom bar —
            // composer, console strip and run notices, whatever their height.
            snackbarHost = { KnotworkSnackbarHost(hostState = snackbarHostState) },
        )
        ChatHomeDebugStatePicker(
            expanded = debugPickerExpanded,
            onDismiss = { debugPickerExpanded = false },
            onPick = { id ->
                // Console entries open the overlay; every other entry
                // forces the underlying chat state.
                val snap = debugConsoleSnapForId(id)
                if (snap != null) {
                    viewModel.console.openConsole(snap)
                } else {
                    debugStateForId(id)?.let(viewModel::forceState)
                }
            },
        )
        // Overflow menu — anchored to the top-end so it visually drops out
        // of the TopAppBar `⋮` icon. The catalog `onOverflow` callback is
        // parameter-less, so the screen owns the anchor + items.
        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            DropdownMenu(
                expanded = overflowExpanded,
                onDismissRequest = { overflowExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_overflow_export)) },
                    onClick = {
                        overflowExpanded = false
                        viewModel.transfer.exportCurrentSession()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_overflow_delete)) },
                    onClick = {
                        overflowExpanded = false
                        deleteDialogVisible = true
                    },
                )
            }
        }
        if (screenState.consoleClearConfirmRequested) {
            ConfirmDialog(
                ui = ConfirmDialogUi(
                    title = stringResource(R.string.chat_console_clear_dialog_title),
                    body = stringResource(R.string.chat_console_clear_dialog_text),
                    confirmLabel = stringResource(R.string.chat_console_clear_dialog_confirm),
                    cancelLabel = stringResource(R.string.chat_console_clear_dialog_cancel),
                ),
                onConfirm = viewModel.console::confirmConsoleClear,
                onDismiss = viewModel.console::dismissConsoleClear,
            )
        }
        deleteThreadTargetId?.let { targetId ->
            ConfirmDialog(
                ui = ConfirmDialogUi(
                    title = stringResource(R.string.chat_delete_dialog_title),
                    body = stringResource(R.string.chat_delete_dialog_text),
                    confirmLabel = stringResource(R.string.chat_delete_dialog_confirm),
                    cancelLabel = stringResource(R.string.chat_delete_dialog_cancel),
                    destructive = true,
                ),
                onConfirm = {
                    deleteThreadTargetId = null
                    viewModel.threads.deleteThread(targetId)
                },
                onDismiss = { deleteThreadTargetId = null },
            )
        }
        if (deleteDialogVisible) {
            ConfirmDialog(
                ui = ConfirmDialogUi(
                    title = stringResource(R.string.chat_delete_dialog_title),
                    body = stringResource(R.string.chat_delete_dialog_text),
                    confirmLabel = stringResource(R.string.chat_delete_dialog_confirm),
                    cancelLabel = stringResource(R.string.chat_delete_dialog_cancel),
                    destructive = true,
                ),
                onConfirm = {
                    deleteDialogVisible = false
                    viewModel.threads.deleteCurrentSession()
                },
                onDismiss = { deleteDialogVisible = false },
            )
        }
        reportTargetRowId?.let { rowId ->
            // The report is rendered on demand from the row's current text: the
            // dialog holds the user's words, never a snapshot of the message.
            val renderReport = {
                ContentReport(
                    reason = reportReason,
                    note = reportNote,
                    messageText = viewModel.transfer.textForRow(rowId).orEmpty(),
                    appVersion = BuildConfig.VERSION_NAME,
                    buildIdentifier = BuildConfig.GIT_SHA,
                    device = "${Build.MANUFACTURER} ${Build.MODEL}",
                    androidVersion = Build.VERSION.RELEASE.orEmpty(),
                    // The model's display name, not its row id: a local
                    // database id means nothing to whoever reads the report.
                    modelIdentifier = screenState.model.installed
                        .firstOrNull { it.id == screenState.model.activeId }
                        ?.name,
                )
            }
            val copyReport = {
                val report = renderReport()
                clipboardManager.setText(AnnotatedString(ContentReportComposer.body(report)))
            }
            ReportResponseDialog(
                reason = reportReason,
                onReasonChange = { reportReason = it },
                note = reportNote,
                onNoteChange = { reportNote = it },
                onCopy = {
                    copyReport()
                    reportTargetRowId = null
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(message = reportCopiedMessage)
                    }
                },
                onOpenIssue = {
                    val report = renderReport()
                    val url = contentReportIssueUrl(
                        subject = ContentReportComposer.subject(report),
                        body = ContentReportComposer.body(report),
                    )
                    // No browser is a real state on a stripped device, and a
                    // silent no-op would look like the report was filed. Fall
                    // back to the clipboard and say so.
                    val opened = runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    }.isSuccess
                    if (!opened) copyReport()
                    reportTargetRowId = null
                    if (!opened) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(message = reportNoBrowserMessage)
                        }
                    }
                },
                onDismiss = { reportTargetRowId = null },
            )
        }
        renameTargetId?.let { targetId ->
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { renameTargetId = null },
                sheetState = sheetState,
            ) {
                RenameSessionSheetContent(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    onSave = {
                        viewModel.threads.renameSession(targetId, renameDraft)
                        renameTargetId = null
                    },
                    onCancel = { renameTargetId = null },
                )
            }
        }
        if (newThreadSheetVisible) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { newThreadSheetVisible = false },
                sheetState = sheetState,
            ) {
                NewThreadPipelinePickerSheetContent(
                    pipelines = screenState.availablePipelines,
                    initialPipelineId = viewModel.pipelineBinding.currentPipelineId(),
                    onCancel = { newThreadSheetVisible = false },
                    onCreate = { pipelineId ->
                        newThreadSheetVisible = false
                        viewModel.threads.createNewSessionWithPipeline(pipelineId)
                    },
                )
            }
        }
        if (screenState.sourceChooserVisible) {
            SourceChooserSheet(
                onDismiss = viewModel.attachments::dismissSourceChooser,
                onPickPhotoLibrary = {
                    viewModel.attachments.dismissSourceChooser()
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onPickCamera = {
                    viewModel.attachments.dismissSourceChooser()
                    val uri = viewModel.attachments.newCaptureUri()
                    pendingCaptureUri = uri
                    cameraLauncher.launch(uri.toUri())
                },
            )
        }
        if (screenState.composer.audioChooserVisible) {
            AudioSourceChooserSheet(
                maxDurationSec = screenState.composer.audioMaxDurationSec,
                onDismiss = viewModel.voice::dismissAudioChooser,
                onPickRecord = {
                    viewModel.voice.dismissAudioChooser()
                    requestRecordAudio()
                },
                onPickFile = {
                    viewModel.voice.dismissAudioChooser()
                    audioPickerLauncher.launch(arrayOf(AUDIO_MIME_FILTER))
                },
            )
        }
        screenState.imageViewer?.let { viewer ->
            ImageViewer(
                model = viewer.model,
                fileName = viewer.fileName,
                dimensionsLabel = viewer.dimensionsLabel,
                isMissing = viewer.isMissing,
                onDismiss = viewModel.attachments::dismissImageViewer,
            )
        }
    }
}

/**
 * The three strings the archive snackbars need, resolved once in the composable
 * so the suspending helper below stays free of resource lookups.
 *
 * @property archived confirmation raised right after archiving.
 * @property undo action label on that confirmation.
 * @property restored confirmation raised after a restore (no action slot).
 */
internal data class ArchiveSnackbarStrings(val archived: String, val undo: String, val restored: String)

/**
 * Dwell of the archive-undo snackbar.
 *
 * Longer than the Material `Short` default (~4 s) on purpose: Undo is the only
 * way back for a user who has not yet found the archive screen, and 4 s is not
 * enough to read the message, decide, and reach the action. Material3 offers no
 * 8 s duration, so the snackbar is shown as `Indefinite` and dismissed on this
 * timer instead.
 */
private const val ARCHIVE_UNDO_DWELL_MS = 8_000L

/**
 * Shows "Chat archived · Undo" and restores the chat if the user takes the
 * action. The drawer deliberately stays open — the user is usually mid-triage
 * and archiving two or three in a row, so closing it would punish them for
 * using the feature.
 */
private suspend fun showArchiveUndoSnackbar(
    snackbarHostState: SnackbarHostState,
    viewModel: ChatHomeViewModel,
    threadId: String,
    strings: ArchiveSnackbarStrings,
) {
    // On timeout `withTimeoutOrNull` cancels the `showSnackbar` call, and a
    // cancelled `showSnackbar` dismisses its own snackbar — so the dwell needs
    // no explicit dismiss. Calling `currentSnackbarData.dismiss()` here would
    // be actively wrong: by then the current snackbar may belong to a *later*
    // archive, and we would cut that one short instead.
    val result = withTimeoutOrNull(ARCHIVE_UNDO_DWELL_MS) {
        snackbarHostState.showSnackbar(
            message = strings.archived,
            actionLabel = strings.undo,
            duration = SnackbarDuration.Indefinite,
        )
    }
    if (result == SnackbarResult.ActionPerformed) {
        viewModel.threads.unarchiveThread(threadId)
    }
}

/**
 * Body of the rename-session `ModalBottomSheet`. Captured separately so
 * the screen file stays scannable and so the input field can be hoisted
 * for testing.
 */
@Composable
private fun RenameSessionSheetContent(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = KnotworkTheme.spacing.sp6,
                vertical = KnotworkTheme.spacing.sp4,
            )
            .navigationBarsPadding(),
    ) {
        Text(
            text = stringResource(R.string.chat_rename_sheet_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(KnotworkTheme.spacing.sp3))
        KnotworkField(
            label = stringResource(R.string.chat_rename_sheet_label),
        ) {
            KnotworkTextField(
                value = value,
                onValueChange = onValueChange,
            )
        }
        Spacer(modifier = Modifier.height(KnotworkTheme.spacing.sp3))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Row {
                KnotworkTextButton(
                    text = stringResource(R.string.chat_rename_sheet_cancel),
                    onClick = onCancel,
                )
                Spacer(modifier = Modifier.padding(horizontal = KnotworkTheme.spacing.sp1))
                KnotworkPrimaryButton(
                    text = stringResource(R.string.chat_rename_sheet_save),
                    onClick = onSave,
                    enabled = value.trim().isNotEmpty(),
                )
            }
        }
    }
}

/**
 * Body of the new-thread pipeline picker `ModalBottomSheet`. Pre-selects
 * the user's current pipeline binding so a "create" tap creates a chat
 * bound to the same pipeline by default.
 *
 * @param pipelines pipelines the user can pick from.
 * @param initialPipelineId pipeline id to pre-select (`null` = inherit
 *   default).
 * @param onCancel callback for the trailing Cancel button.
 * @param onCreate callback fired with the picked pipeline id (or `null`
 *   for the default pipeline).
 */
@Composable
private fun NewThreadPipelinePickerSheetContent(
    pipelines: List<PipelineSummary>,
    initialPipelineId: String?,
    onCancel: () -> Unit,
    onCreate: (String?) -> Unit,
) {
    // `null` represents the "Use default pipeline" option, mirroring the
    // ChatSession.pipelineId semantics: null means "inherit the
    // application-wide default". The picker always surfaces this option
    // regardless of whether the library has pipelines, so the user can
    // intentionally leave the binding unset.
    var selectedId by remember(initialPipelineId, pipelines) {
        mutableStateOf(initialPipelineId)
    }
    val useDefaultLabel = stringResource(R.string.chat_new_thread_sheet_use_default)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = KnotworkTheme.spacing.sp6,
                vertical = KnotworkTheme.spacing.sp4,
            )
            .navigationBarsPadding(),
    ) {
        Text(
            text = stringResource(R.string.chat_new_thread_sheet_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(KnotworkTheme.spacing.sp3))
        // The option list scrolls within the bounded sheet height (weight caps it
        // to the remaining space) so a long pipeline library never pushes the
        // Cancel/Create row off-screen — the action row below stays pinned.
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            PipelinePickerRow(
                label = useDefaultLabel,
                selected = selectedId == null,
                onClick = { selectedId = null },
            )
            pipelines.forEach { pipeline ->
                PipelinePickerRow(
                    label = pipeline.name,
                    selected = pipeline.id == selectedId,
                    onClick = { selectedId = pipeline.id },
                )
            }
        }
        Spacer(modifier = Modifier.height(KnotworkTheme.spacing.sp3))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Row {
                KnotworkTextButton(
                    text = stringResource(R.string.chat_new_thread_sheet_cancel),
                    onClick = onCancel,
                )
                Spacer(modifier = Modifier.padding(horizontal = KnotworkTheme.spacing.sp1))
                KnotworkPrimaryButton(
                    text = stringResource(R.string.chat_new_thread_sheet_create),
                    onClick = { onCreate(selectedId) },
                )
            }
        }
    }
}

/**
 * Single row in the new-thread pipeline picker. The whole row is
 * clickable so the touch target spans the full sheet width — relying on
 * the RadioButton alone leaves a thin strip that fails the 48dp
 * accessibility guideline.
 */
@Composable
private fun PipelinePickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    // A compact radio row (not an M3 `ListItem`, whose ~56dp min-height and
    // `bodyLarge` headline read as oversized in this picker). Mirrors the
    // catalog `PromptPresetPickerSheet` row: a tight `Row` with a `BodyBase`
    // (15sp) label, so a long pipeline list stays scannable.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = KnotworkTheme.spacing.sp1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(KnotworkTheme.spacing.sp2))
        Text(
            text = label,
            style = KnotworkTextStyles.BodyBase,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Reproduces the catalog's filter + substring-search pass over [logs] so
 * the `Copy all` clipboard payload mirrors exactly what the user sees in
 * the Logs tab. The catalog `ConsoleLogsBody` performs the same two-stage
 * filter; duplicating it here is the cheapest cut while the catalog's
 * filter helpers stay internal.
 *
 * @param logs Raw aggregated log lines.
 * @param filter Active source-set filter.
 * @param searchQuery Inline-search query (`null` means hidden — no
 *   substring filter is applied).
 * @return Lines currently visible to the user, in the same order as
 *   rendered.
 */
internal fun visibleConsoleLogs(
    logs: List<app.knotwork.design.components.console.ConsoleLine>,
    filter: app.knotwork.design.components.console.ConsoleFilter,
    searchQuery: String?,
): List<app.knotwork.design.components.console.ConsoleLine> {
    val sourceFiltered = logs.filter(filter::matches)
    if (searchQuery.isNullOrEmpty()) return sourceFiltered
    return sourceFiltered.filter { it.text.contains(searchQuery, ignoreCase = true) }
}

/** MIME filter for the voice-input audio file picker (OpenDocument). */
private const val AUDIO_MIME_FILTER: String = "audio/*"
