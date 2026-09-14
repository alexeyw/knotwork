package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.ClarificationRequest
import app.knotwork.android.domain.models.RunNoticeCause
import app.knotwork.android.presentation.ui.common.RunTerminationCopy
import app.knotwork.android.presentation.ui.common.RunTerminationCopyMapper
import app.knotwork.android.presentation.ui.common.RunTerminationTone
import app.knotwork.android.presentation.ui.common.UiText
import app.knotwork.design.components.chat.ChatContent
import app.knotwork.design.components.chat.ChatMessageStatus
import app.knotwork.design.components.chat.ChatMetadata
import app.knotwork.design.components.chat.ChatRole
import app.knotwork.design.components.chat.ClarificationCardModel
import app.knotwork.design.components.chat.ComposerAttachment
import app.knotwork.design.components.chat.ComposerState
import app.knotwork.design.components.chat.HitlConfirmationModel
import app.knotwork.design.components.chat.InterruptedRunCardModel
import app.knotwork.design.components.chat.RunCeilingPauseCardModel
import app.knotwork.design.components.chips.Risk
import app.knotwork.design.components.console.ConsoleSnap
import app.knotwork.design.screens.chat.ChatHomeMessageRow
import app.knotwork.design.screens.chat.ChatHomeSamplePromptCard
import app.knotwork.design.screens.chat.ChatHomeViewState
import app.knotwork.design.screens.chat.ChatHomeVisualState
import app.knotwork.design.screens.chat.ChatRunNoticeUi
import app.knotwork.design.screens.chat.ChatTerminationUi
import app.knotwork.design.screens.chat.RunTerminationToneUi
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Status line of a `Generating` surface. While the model loads before an auto-send
 * (`preparingModel`), or while the run waits behind another one (`waitingInQueue`),
 * it says so rather than telling the user the assistant is producing tokens.
 * Otherwise it carries the running token count, so the pill reads
 * "generating · 42 tok" — visible progress on long generations.
 *
 * @param visual the generating visual.
 * @param fixtures resolved status strings.
 * @return the line for the console entry strip.
 */
private fun ChatHomeScreenState.generatingStatusLine(
    visual: ChatHomeUiState.Generating,
    fixtures: ChatHomeFixtures,
): String = when {
    visual.preparingModel -> fixtures.statusPreparingModel
    visual.waitingInQueue -> fixtures.statusWaitingInQueue
    else -> formatGeneratingStatus(fixtures.statusGenerating, tokens.streaming, tokens.backend)
}

/**
 * Label of the loader bubble at the end of the thread: the same claim as
 * [generatingStatusLine], or `null` for the design system's default "Generating…".
 *
 * @param visual the generating visual.
 * @param fixtures resolved loader labels.
 * @return the label, or `null` while tokens are actually being generated.
 */
private fun generatingLoaderLabel(visual: ChatHomeUiState.Generating, fixtures: ChatHomeFixtures): String? = when {
    visual.preparingModel -> fixtures.loaderPreparingModel
    visual.waitingInQueue -> fixtures.loaderWaitingInQueue
    else -> null
}

/**
 * Pure-Kotlin projection of the aggregated [ChatHomeScreenState] onto the
 * catalog [ChatHomeViewState] consumed by `ChatHomeContent`. Lives in `:app`
 * because the catalog cannot reach `app.knotwork.android.*` (Clean
 * Architecture keeps `:catalog` free of `:app` types).
 *
 * The mapping is intentionally chunky — every visual state owns a complete
 * fixture block. The stub-VM stage holds no real conversation history, so a
 * deterministic story unique to each variant is the cheapest way to make
 * the state picker meaningful while the real backend wiring is still
 * pending (post-v0.1).
 *
 * The console slice ([ChatHomeScreenState.console]) is already the catalog
 * `ChatHomeConsoleState`, so it passes through without re-projection.
 *
 * @param fixtures locale-resolved stub strings (status pills, drawer
 *   fallbacks, empty-state suggestion cards).
 * @return the immutable view-state passed directly to `ChatHomeContent`.
 */
// Reason: single switch over 8 visual variants, each branch a flat
// constructor call; splitting would just shuffle the fixtures.
@Suppress("LongMethod")
fun ChatHomeScreenState.toViewState(
    fixtures: ChatHomeFixtures = ChatHomeFixtures.forTesting(),
    resolveText: (UiText) -> String = ::stubResolveText,
): ChatHomeViewState {
    val threadTitle = thread.title
    val modelName = model.name
    val composerValue = composer.value
    val resolvedPipelineName = pipelineName ?: PIPELINE_NAME_PLACEHOLDER
    val composerAttachment = composer.attachment?.toCatalogComposerAttachment()
    val baseViewState = when (val visual = visual) {
        is ChatHomeUiState.Loading -> ChatHomeViewState(
            visualState = ChatHomeVisualState.Loading,
            threadTitle = threadTitle,
            modelName = modelName,
            composerValue = composerValue,
            pipelineName = resolvedPipelineName,
            tokensUsed = tokens.used,
            tokensMax = tokens.max,
            favorite = thread.favorite,
            console = console,
        )

        is ChatHomeUiState.Empty -> ChatHomeViewState(
            visualState = ChatHomeVisualState.Empty,
            threadTitle = threadTitle,
            modelName = modelName,
            composerValue = composerValue,
            samplePromptCards = samplePromptCards(fixtures),
            pipelineName = resolvedPipelineName,
            tokensUsed = tokens.used,
            tokensMax = tokens.max,
            favorite = thread.favorite,
            agentStatusLine = fixtures.statusIdle,
            console = console,
        )

        is ChatHomeUiState.Idle -> ChatHomeViewState(
            visualState = ChatHomeVisualState.Idle,
            threadTitle = threadTitle,
            modelName = modelName,
            messages = visibleMessages,
            composerValue = composerValue,
            pipelineName = resolvedPipelineName,
            tokensUsed = tokens.used,
            tokensMax = tokens.max,
            favorite = thread.favorite,
            agentStatusLine = fixtures.statusIdle,
            console = console,
        )

        is ChatHomeUiState.Generating -> ChatHomeViewState(
            visualState = ChatHomeVisualState.Generating,
            threadTitle = threadTitle,
            modelName = modelName,
            messages = visibleMessages,
            composerValue = composerValue,
            composerState = ComposerState.Generating,
            pipelineName = resolvedPipelineName,
            tokensUsed = tokens.used,
            tokensMax = tokens.max,
            favorite = thread.favorite,
            agentStatusLine = generatingStatusLine(visual, fixtures),
            loaderLabel = generatingLoaderLabel(visual, fixtures),
            console = console,
        )

        is ChatHomeUiState.HitlConfirm -> ChatHomeViewState(
            visualState = ChatHomeVisualState.HitlConfirm,
            threadTitle = threadTitle,
            modelName = modelName,
            messages =
            visibleMessages + (pending.tool?.let { liveHitlRow(modelName, it) } ?: hitlRow(modelName, visual.risk)),
            composerValue = composerValue,
            pendingTypedConfirm = composer.typedConfirm,
            pipelineName = resolvedPipelineName,
            tokensUsed = tokens.used,
            tokensMax = tokens.max,
            favorite = thread.favorite,
            agentStatusLine = fixtures.statusHitl,
            console = console,
        )

        is ChatHomeUiState.Clarification -> ChatHomeViewState(
            visualState = ChatHomeVisualState.Clarification,
            threadTitle = threadTitle,
            modelName = modelName,
            messages = visibleMessages + (
                pending.clarification?.let { liveClarificationRow(modelName, it) }
                    ?: clarificationRow(modelName)
                ),
            composerValue = composerValue,
            pipelineName = resolvedPipelineName,
            tokensUsed = tokens.used,
            tokensMax = tokens.max,
            favorite = thread.favorite,
            agentStatusLine = fixtures.statusClarification,
            console = console,
        )

        is ChatHomeUiState.Interrupted -> ChatHomeViewState(
            visualState = ChatHomeVisualState.Interrupted,
            threadTitle = threadTitle,
            modelName = modelName,
            messages = visibleMessages + (
                pending.interrupted?.let { liveInterruptedRow(modelName, it) }
                    ?: interruptedRow(modelName)
                ),
            composerValue = composerValue,
            pipelineName = resolvedPipelineName,
            tokensUsed = tokens.used,
            tokensMax = tokens.max,
            favorite = thread.favorite,
            agentStatusLine = fixtures.statusIdle,
            console = console,
        )

        is ChatHomeUiState.CeilingPause -> ChatHomeViewState(
            visualState = ChatHomeVisualState.CeilingPause,
            threadTitle = threadTitle,
            modelName = modelName,
            // No debug-picker fallback row, unlike the interrupted state above.
            // That fallback exists so the state picker can show a card with no
            // pending snapshot behind it; a pause card without one would offer
            // Continue and Stop buttons wired to a run that does not exist.
            messages = visibleMessages + listOfNotNull(
                pending.ceiling?.let { ceilingPauseRow(modelName, it, resolveText) },
            ),
            composerValue = composerValue,
            pipelineName = resolvedPipelineName,
            tokensUsed = tokens.used,
            tokensMax = tokens.max,
            favorite = thread.favorite,
            agentStatusLine = fixtures.statusIdle,
            console = console,
        )

        is ChatHomeUiState.Error -> {
            // A typed termination is explained in its own words; an untyped
            // failure keeps the destructive tile and its Retry. Exactly one of
            // the two is ever populated — the catalog view state requires it.
            val termination = visual.reason?.let { RunTerminationCopyMapper.terminationCopy(it) }
            ChatHomeViewState(
                visualState = ChatHomeVisualState.Error,
                threadTitle = threadTitle,
                modelName = modelName,
                messages = visibleMessages,
                composerValue = composerValue,
                // Only an untyped failure puts the composer into its error
                // state. A typed stop explains itself in its own tone above the
                // composer instead — the error banner is destructive-red, which
                // would have contradicted the tile two inches above it.
                composerState = untypedComposerState(visual.message, termination),
                errorMessage = untypedErrorMessage(visual.message, termination, visual.announcedInThread),
                termination = termination?.toCatalog(resolveText),
                explainedInThread = visual.announcedInThread,
                pipelineName = resolvedPipelineName,
                tokensUsed = tokens.used,
                tokensMax = tokens.max,
                favorite = thread.favorite,
                agentStatusLine = fixtures.statusError,
                console = console,
            )
        }

        is ChatHomeUiState.DrawerOpen -> ChatHomeViewState(
            visualState = ChatHomeVisualState.DrawerOpen,
            threadTitle = threadTitle,
            modelName = modelName,
            messages = visibleMessages,
            composerValue = composerValue,
            // Live VM-projected threads. Falls back to fixtures only when
            // the debug picker forces DrawerOpen on an empty session list
            // (e.g. before the first session is persisted) — production
            // flows always have at least the active session present.
            threads = thread.rows.ifEmpty { fixtures.sessionRows },
            pipelineName = resolvedPipelineName,
            tokensUsed = tokens.used,
            tokensMax = tokens.max,
            favorite = thread.favorite,
            agentStatusLine = fixtures.statusIdle,
            console = console,
        )
    }
    // The voice capture/transcription phase overrides the visual-derived composer
    // state: recording replaces the input row, transcribing shows the spinner.
    val voiceComposerState = when (val voice = composer.voice) {
        is VoiceInputState.Recording -> ComposerState.Recording(elapsedSec = voice.elapsedSec, maxSec = voice.maxSec)
        VoiceInputState.Transcribing -> ComposerState.Transcribing
        VoiceInputState.Idle -> null
    }
    return baseViewState.copy(
        composerAttachment = composerAttachment,
        composerState = voiceComposerState ?: baseViewState.composerState,
        composerVoiceNotice = composer.voiceNotice,
        // Drawer chrome and the archived read-only flag are orthogonal to the
        // visual branch, so they ride the same post-pass rather than being
        // repeated across all nine constructor blocks.
        openThreadMenuId = thread.openMenuId,
        archivedCount = thread.archivedCount,
        archivedReadOnly = thread.archived,
        // Orthogonal for the same reason: the notice is about the run in
        // flight, so it can coexist with any visual — generating, or a HITL
        // gate held open — rather than belonging to one of them.
        runNotice = runNotice?.toCatalog(resolveText),
    )
}

/**
 * The composer's own error state, which only an **untyped** failure earns.
 *
 * @param message The failure description.
 * @param termination The typed cause, when there was one.
 * @return The error state, or [ComposerState.Idle] for a typed stop.
 */
private fun untypedComposerState(message: String, termination: RunTerminationCopy?): ComposerState =
    if (termination == null) ComposerState.Error(message) else ComposerState.Idle

/**
 * The verbatim failure text, reserved for an untyped failure the thread does not
 * already account for.
 *
 * Two exclusions, for different reasons. For a **typed** stop this string is the
 * diagnostic that lands in the run record, and showing it would put
 * `step-ceiling: 15/15 steps` in front of a person. For a failure a settled run
 * has **already announced**, the sentence is in the conversation a few lines up,
 * and a tile repeating it is the duplication this surface was asked to stop —
 * the composer keeps its Retry either way.
 *
 * What survives both is the case with no run behind it at all — a blocked
 * attachment, a model that would not load — where this text is the only account
 * the user gets.
 *
 * @param message The failure description or diagnostic.
 * @param termination The typed cause, when there was one.
 * @param announcedInThread Whether a settled run already wrote the outcome into
 *   the conversation.
 * @return The text to render, or `null` when something else already says it.
 */
private fun untypedErrorMessage(
    message: String,
    termination: RunTerminationCopy?,
    announcedInThread: Boolean,
): String? = message.takeIf { termination == null && !announcedInThread }

/**
 * Projects the live run advisory onto the catalog's strip model.
 *
 * @param resolveText Resolver for the sentence.
 * @return The strip state.
 */
private fun RunNoticeCause.toCatalog(resolveText: (UiText) -> String): ChatRunNoticeUi =
    RunTerminationCopyMapper.noticeCopy(this).let {
        ChatRunNoticeUi(tone = it.tone.toCatalog(), text = resolveText(it.text))
    }

/**
 * Projects the resolved termination copy onto the catalog's local model.
 *
 * @param resolveText Resolver for the [UiText] values the copy carries.
 * @return The catalog tile model, with every string already resolved.
 */
private fun RunTerminationCopy.toCatalog(resolveText: (UiText) -> String): ChatTerminationUi = ChatTerminationUi(
    tone = tone.toCatalog(),
    toneLabel = resolveText(UiText.Resource(tone.labelRes)),
    title = resolveText(title),
    // `body` is deliberately not projected. It is the sentence the run wrote
    // into the conversation as it settled, so the tile would be repeating a line
    // sitting a few rows above it in the same list. `RunTerminationCopy` still
    // carries it — the notifications and the composer banner resolve the same
    // value, and a second wording is what that type exists to prevent.
    // Its own short clause, not the tile's sentence: the strip is clamped to
    // two lines, and one string trying to serve both is what got the copy cut
    // in half at large font scales.
    banner = resolveText(banner),
    meter = meter?.let(resolveText),
    actionLabel = action?.let { resolveText(UiText.Resource(it.labelRes)) },
)

/**
 * Maps the app's tone vocabulary onto the catalog's local mirror, which exists
 * so the design module keeps its zero dependency on `:app`.
 *
 * @return The catalog tone.
 */
private fun RunTerminationTone.toCatalog(): RunTerminationToneUi = when (this) {
    RunTerminationTone.LIMIT -> RunTerminationToneUi.Limit
    RunTerminationTone.STUCK -> RunTerminationToneUi.Stuck
    RunTerminationTone.INFO -> RunTerminationToneUi.Info
}

/**
 * Default [UiText] resolver for unit tests, which call [toViewState] without a
 * `Context`.
 *
 * Renders a resource as `res:<id>` plus its arguments, so a test can assert
 * *which* string a branch chose without loading resources — and so a
 * production call site that forgot to pass a real resolver would be
 * unmistakable on screen rather than quietly blank.
 *
 * @param text The value to render.
 * @return A greppable stand-in for the translated string.
 */
internal fun stubResolveText(text: UiText): String = when (text) {
    is UiText.Resource ->
        "res:${text.id}" + text.args.takeIf { it.isNotEmpty() }?.joinToString(",", "(", ")").orEmpty()
    is UiText.Dynamic -> text.text
    is UiText.Joined -> text.parts.joinToString(text.separator) { stubResolveText(it) }
    is UiText.Plural -> "plural:${text.id}(${text.quantity})"
    UiText.Empty -> ""
}

/**
 * Projects the app-side pending attachment draft onto the catalog
 * [app.knotwork.design.components.chat.ComposerAttachment] consumed by the
 * composer.
 */
private fun ComposerAttachmentDraft.toCatalogComposerAttachment(): ComposerAttachment = when (this) {
    is ComposerAttachmentDraft.Processing -> ComposerAttachment.Processing
    is ComposerAttachmentDraft.Ready -> ComposerAttachment.Ready(model = absolutePath, detail = detail)
}

/** Pre-canned baseline conversation used by every non-Empty state. */
internal fun baselineMessages(modelName: String): List<ChatHomeMessageRow> = listOf(
    ChatHomeMessageRow(
        id = "u1",
        role = ChatRole.User,
        content = ChatContent.Text("Summarise the three PRs that landed yesterday."),
        metadata = ChatMetadata(timestamp = "09:14"),
    ),
    ChatHomeMessageRow(
        id = "a1",
        role = ChatRole.Assistant,
        content = ChatContent.Markdown(
            source = "Pipeline editor refactor, context-window meter for the chat header, " +
                "and a memory-summary regression fix.",
        ),
        metadata = ChatMetadata(
            timestamp = "09:14",
            model = modelName,
            tokens = 64,
            status = ChatMessageStatus.Sent,
        ),
    ),
    ChatHomeMessageRow(
        id = "u2",
        role = ChatRole.User,
        content = ChatContent.Text("Add a 30-minute meeting tomorrow at 10:00 to discuss the rollout."),
        metadata = ChatMetadata(timestamp = "09:15"),
    ),
)

/** Trailing HITL confirmation row appended in the HitlConfirm state. */
internal fun hitlRow(modelName: String, risk: Risk): ChatHomeMessageRow {
    val toolName = when (risk) {
        Risk.Readonly -> "calendar.list_events"
        Risk.Sensitive -> "calendar.create_event"
        Risk.Destructive -> "fs.delete_file"
    }
    val summary = when (risk) {
        Risk.Readonly -> "Read tomorrow's events from your work calendar."
        Risk.Sensitive -> "Add a 30-minute meeting \"Rollout sync\" to your work calendar tomorrow at 10:00."
        Risk.Destructive -> "Permanently remove /Users/me/old-notes.md (4.2 KB)."
    }
    val arguments = when (risk) {
        Risk.Readonly -> mapOf("calendar" to "\"work\"", "range" to "\"tomorrow\"")
        Risk.Sensitive -> mapOf(
            "title" to "\"Rollout sync\"",
            "duration" to "30",
            "calendar" to "\"work\"",
        )
        Risk.Destructive -> mapOf(
            "path" to "\"/Users/me/old-notes.md\"",
            "recursive" to "false",
        )
    }
    return ChatHomeMessageRow(
        id = "a-hitl",
        role = ChatRole.Assistant,
        content = ChatContent.Confirmation(
            model = HitlConfirmationModel(
                risk = risk,
                toolName = toolName,
                summary = summary,
                arguments = arguments,
                timestamp = "09:16",
            ),
        ),
        metadata = ChatMetadata(timestamp = "09:16", model = modelName),
    )
}

/**
 * Trailing HITL confirmation row driven by the live [HitlPending]
 * snapshot the orchestrator captured. Renders the real tool name, risk
 * tier, and JSON-decoded argument map.
 *
 * The card's summary line is left **blank**: a pending confirmation carries
 * no agent-written explanation, and there is no second field to fall back
 * to. Passing the tool name there printed it twice — mono id above, the same
 * string as prose below — which reads as a rendering fault rather than as a
 * missing explanation. The card omits the line when the summary is blank.
 */
internal fun liveHitlRow(modelName: String, pending: HitlPending): ChatHomeMessageRow {
    val argumentsMap = parseHitlArguments(pending.arguments)
    val timestamp = SimpleDateFormat(HITL_TIMESTAMP_PATTERN, Locale.getDefault())
        .format(Date(System.currentTimeMillis()))
    return ChatHomeMessageRow(
        id = "a-hitl-${pending.toolName}",
        role = ChatRole.Assistant,
        content = ChatContent.Confirmation(
            model = HitlConfirmationModel(
                risk = pending.risk.toCatalogRisk(),
                toolName = pending.toolName,
                summary = "",
                arguments = argumentsMap,
                timestamp = timestamp,
            ),
        ),
        metadata = ChatMetadata(timestamp = timestamp, model = modelName),
    )
}

/**
 * Trailing clarification row driven by the live [ClarificationRequest]
 * snapshot the orchestrator captured. Renders the real question text and
 * options as quick-reply chips; free-form fallback is supplied by the
 * catalog `ClarificationCard`.
 */
internal fun liveClarificationRow(modelName: String, request: ClarificationRequest): ChatHomeMessageRow {
    val timestamp = SimpleDateFormat(HITL_TIMESTAMP_PATTERN, Locale.getDefault())
        .format(Date(System.currentTimeMillis()))
    return ChatHomeMessageRow(
        id = "a-clar-${request.id}",
        role = ChatRole.Assistant,
        content = ChatContent.Clarification(
            model = ClarificationCardModel(
                question = request.question,
                quickReplies = request.options ?: emptyList(),
            ),
        ),
        metadata = ChatMetadata(timestamp = timestamp, model = modelName),
    )
}

/**
 * Parses the orchestrator-emitted JSON argument blob into the
 * `Map<String, String>` of rendered JSON fragments the catalog
 * `HitlConfirmationCard` expects. Each value is re-serialised through
 * [JSONObject] so strings keep their surrounding double-quotes, numbers /
 * booleans render bare, and nested objects/arrays stay compact JSON.
 * Falls back to a single `args` entry holding the raw blob when the
 * payload is not a parseable object — defensive against agents emitting
 * non-JSON or array-shaped argument payloads.
 */
internal fun parseHitlArguments(raw: String): Map<String, String> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return emptyMap()
    return try {
        val obj = JSONObject(trimmed)
        val result = linkedMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = renderJsonFragment(obj.get(key))
        }
        result
    } catch (_: JSONException) {
        mapOf(RAW_ARGS_FALLBACK_KEY to trimmed)
    }
}

/**
 * Renders a single JSON value as the fragment string the catalog
 * `HitlConfirmationCard` expects: strings are wrapped in double quotes,
 * numbers and booleans render bare, nested objects/arrays render as
 * compact JSON, and `JSONObject.NULL` becomes `null`.
 */
private fun renderJsonFragment(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is String -> JSONObject.quote(value)
    is JSONObject -> value.toString()
    is JSONArray -> value.toString()
    else -> value.toString()
}

/** Pattern used for the HITL / clarification row timestamp. */
private const val HITL_TIMESTAMP_PATTERN: String = "HH:mm"

/**
 * Formats [epochMs] with the in-chat message clock.
 *
 * One definition rather than one per card. The pattern is `HH:mm`, locale-aware
 * and 24-hour, and it has to match the footer clock exactly — a status card
 * whose time reads differently from the message above it looks like it is
 * timing something else.
 *
 * @param epochMs The instant to render.
 * @return The formatted time.
 */
internal fun chatRowTimestamp(epochMs: Long): String =
    SimpleDateFormat(HITL_TIMESTAMP_PATTERN, Locale.getDefault()).format(Date(epochMs))

/**
 * Fallback subtitle rendered when the pipeline library is still empty
 * (no pipelines have been created yet). Matches the catalog default in
 * `ChatHomeViewState.pipelineName` so the TopAppBar subtitle does not
 * jump between values once a pipeline is created.
 */
internal const val PIPELINE_NAME_PLACEHOLDER: String = "default"

/** Key used when the orchestrator's argument blob cannot be parsed as a JSON object. */
internal const val RAW_ARGS_FALLBACK_KEY: String = "args"

/**
 * Trailing interrupted-run status row driven by the live
 * [InterruptedRunPending] snapshot the reattach protocol captured. Renders
 * the resolved node label inside the catalog `InterruptedRunCard`. The
 * timestamp comes from the snapshot (the run's actual interruption time,
 * formatted once when the card was installed) — recomputing "now" here
 * would both lie about when the run died and change on every
 * recomposition.
 */
internal fun liveInterruptedRow(modelName: String, pending: InterruptedRunPending): ChatHomeMessageRow =
    ChatHomeMessageRow(
        id = "a-interrupted-${pending.runId}",
        role = ChatRole.Assistant,
        content = ChatContent.RunInterrupted(
            model = InterruptedRunCardModel(nodeLabel = pending.nodeLabel, resumable = pending.resumable),
        ),
        metadata = ChatMetadata(timestamp = pending.timestamp, model = modelName),
    )

/** Trailing interrupted-run row appended when the debug picker forces the state without a pending snapshot. */
internal fun interruptedRow(modelName: String): ChatHomeMessageRow = ChatHomeMessageRow(
    id = "a-interrupted",
    role = ChatRole.Assistant,
    content = ChatContent.RunInterrupted(
        model = InterruptedRunCardModel(nodeLabel = "Summarise"),
    ),
    metadata = ChatMetadata(timestamp = "09:16", model = modelName),
)

/**
 * Trailing run-ceiling pause row driven by the live [CeilingPausePending]
 * snapshot.
 *
 * Every string is resolved here, through the same
 * [RunTerminationCopyMapper] that words the run's stop, its console line and
 * its notification. The card takes finished strings — so the alternative would
 * be a second wording of one event, living in the catalog, invisible from the
 * place that owns the first.
 *
 * The timestamp is the pause's own, not "now": a pause answered the next
 * morning must still say when the run stopped.
 */
internal fun ceilingPauseRow(
    modelName: String,
    pending: CeilingPausePending,
    resolveText: (UiText) -> String,
): ChatHomeMessageRow {
    val copy = RunTerminationCopyMapper.ceilingPauseCopy(pending.breach)
    return ChatHomeMessageRow(
        // A live pause has no run id yet (the emission carries none), so the
        // row falls back to a stable literal. There is only ever one pause row
        // in a thread, so it cannot collide with itself.
        id = "a-ceiling-${pending.runId ?: "live"}",
        role = ChatRole.Assistant,
        content = ChatContent.RunCeilingPause(
            model = RunCeilingPauseCardModel(
                title = resolveText(copy.title),
                body = resolveText(copy.body),
                meter = resolveText(copy.meter),
                continueLabel = resolveText(copy.continueLabel),
                stopLabel = resolveText(copy.stopLabel),
            ),
        ),
        metadata = ChatMetadata(timestamp = pending.timestamp, model = modelName),
    )
}

/** Trailing clarification row appended in the Clarification state. */
internal fun clarificationRow(modelName: String): ChatHomeMessageRow = ChatHomeMessageRow(
    id = "a-clar",
    role = ChatRole.Assistant,
    content = ChatContent.Clarification(
        model = ClarificationCardModel(
            question = "Which calendar should I add the meeting to?",
            quickReplies = listOf("Work", "Personal", "Family"),
        ),
    ),
    metadata = ChatMetadata(timestamp = "09:16", model = modelName),
)

/**
 * Stable identifiers for every entry in the debug state picker. Kept apart
 * from [ChatHomeUiState] so adding a state to the picker does not force
 * every consumer of the sealed hierarchy to recompile.
 */
internal object DebugStateIds {
    const val EMPTY: String = "empty"
    const val IDLE: String = "idle"
    const val GENERATING: String = "generating"
    const val HITL_READONLY: String = "hitl_readonly"
    const val HITL_SENSITIVE: String = "hitl_sensitive"
    const val HITL_DESTRUCTIVE: String = "hitl_destructive"
    const val CLARIFICATION: String = "clarification"
    const val INTERRUPTED: String = "interrupted"
    const val ERROR: String = "error"
    const val DRAWER_OPEN: String = "drawer_open"
    const val CONSOLE_PARTIAL: String = "console_partial"
    const val CONSOLE_FULL: String = "console_full"
}

/** Maps a [DebugStateIds] value back to the concrete state the picker should set. */
internal fun debugStateForId(id: String): ChatHomeUiState? = when (id) {
    DebugStateIds.EMPTY -> ChatHomeUiState.Empty
    DebugStateIds.IDLE -> ChatHomeUiState.Idle
    DebugStateIds.GENERATING -> ChatHomeUiState.Generating()
    DebugStateIds.HITL_READONLY -> ChatHomeUiState.HitlConfirm(Risk.Readonly)
    DebugStateIds.HITL_SENSITIVE -> ChatHomeUiState.HitlConfirm(Risk.Sensitive)
    DebugStateIds.HITL_DESTRUCTIVE -> ChatHomeUiState.HitlConfirm(Risk.Destructive)
    DebugStateIds.CLARIFICATION -> ChatHomeUiState.Clarification
    DebugStateIds.INTERRUPTED -> ChatHomeUiState.Interrupted
    DebugStateIds.ERROR -> ChatHomeUiState.Error(message = "Something went wrong while generating the reply.")
    DebugStateIds.DRAWER_OPEN -> ChatHomeUiState.DrawerOpen
    // Console snaps are handled separately via [debugConsoleSnapForId] —
    // they no longer correspond to a top-level [ChatHomeUiState] because
    // the console is rendered as an independent overlay.
    else -> null
}

/**
 * Resolves a [DebugStateIds] entry into the [ConsoleSnap] the debug
 * picker should open the console at. Returns `null` for non-console
 * picker entries — the caller falls back to [debugStateForId] for those.
 */
internal fun debugConsoleSnapForId(id: String): ConsoleSnap? = when (id) {
    DebugStateIds.CONSOLE_PARTIAL -> ConsoleSnap.Partial
    DebugStateIds.CONSOLE_FULL -> ConsoleSnap.Full
    else -> null
}

/**
 * Composes the agent status pill text for the Generating state, appending
 * the running token count when non-zero ("generating" → "generating · 42 tok").
 *
 * @param baseLabel the locale-resolved "generating" string from
 *   [ChatHomeFixtures.statusGenerating].
 * @param tokens approximate streamed-token count.
 */
internal fun formatGeneratingStatus(baseLabel: String, tokens: Int, backend: String? = null): String {
    // The backend hint rides in the label rather than a separate chip: it is only
    // meaningful while tokens are being produced, and it exists to make a silent
    // fallback to CPU visible at the moment it costs the user speed.
    val label = if (backend.isNullOrBlank()) baseLabel else "$baseLabel ($backend)"
    return if (tokens > 0) "$label · $tokens tok" else label
}

/**
 * Resolves the empty-state suggestion cards for the active chat: the cards
 * declared by the active pipeline when it has any, otherwise the generic,
 * pipeline-agnostic fallback set from [fixtures]. Sourcing the cards from the
 * pipeline keeps the `uses · …` tool hints honest — they reflect what that
 * pipeline actually wires rather than a static promise the pipeline may not
 * keep. A `null` [PipelineSamplePrompt.toolsHint] maps to an empty
 * `toolsUsed`, which the catalog card renders without a subtitle.
 *
 * @param fixtures locale-resolved fallback cards.
 * @return the pipeline's cards, or the fallback when it declares none.
 */
private fun ChatHomeScreenState.samplePromptCards(fixtures: ChatHomeFixtures): List<ChatHomeSamplePromptCard> =
    activeSamplePrompts.takeIf { it.isNotEmpty() }?.mapIndexed { index, prompt ->
        ChatHomeSamplePromptCard(
            id = "pipeline-prompt-$index",
            title = prompt.title,
            toolsUsed = prompt.toolsHint.orEmpty(),
        )
    } ?: fixtures.suggestionCards
