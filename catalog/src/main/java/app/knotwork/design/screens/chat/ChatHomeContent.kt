@file:Suppress(
    "MatchingDeclarationName", // File hosts ChatHomeContent and its helper composables.
)

package app.knotwork.design.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.a11y.respectReducedMotionTransitions
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.chat.ChatComposer
import app.knotwork.design.components.chat.ChatContent
import app.knotwork.design.components.chat.ChatMessage
import app.knotwork.design.components.chips.KnotworkSuggestionChip
import app.knotwork.design.components.chips.Risk
import app.knotwork.design.components.console.ConsoleEntryStrip
import app.knotwork.design.components.console.ConsolePane
import app.knotwork.design.components.console.ConsoleSnap
import app.knotwork.design.components.misc.KnotworkLoader
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import kotlinx.coroutines.launch

/** Horizontal padding around chat-message rows. */
private val ChatHorizontalPadding = 16.dp

/** Vertical gap between consecutive chat-message rows. */
private val ChatRowGap = 12.dp

/**
 * Bottom clearance reserved under the message list so a short last message
 * (clamped to the bottom of the scroll range) clears the single-line
 * console entry strip sitting above the composer. Sized to the strip's fixed
 * height, which does not grow with font scale — only its status zone gives
 * ground.
 */
private val ChatConsoleClearance = 40.dp

/** Width of the drawer overlay panel (Material spec for navigation drawers). */
private val DrawerWidth = 320.dp

/** Alpha of the scrim painted over the chat surface while the drawer is open. */
private const val DRAWER_SCRIM_ALPHA = 0.32f

/**
 * Alpha applied to the M3 `BottomSheetDefaults.DragHandle` so it picks up
 * the console-foreground colour at the same opacity as the legacy
 * hand-rolled handle (`DRAG_HANDLE_ALPHA` in the deleted code path).
 */
private const val CONSOLE_DRAG_HANDLE_ALPHA = 0.30f

/**
 * Stateless Knotwork Chat home — the primary user-facing surface. Drives the
 * 9 documented states deterministically
 * from [state]; the caller (`:app/ChatHomeScreen`) owns navigation, IME
 * insets, deep-link arguments, and the real ViewModel wiring.
 *
 * Layout (per spec):
 *  - [TopAppBar] with thread title (and model picker beneath) on the left;
 *    threads / overflow on the right.
 *  - Body `LazyColumn<ChatHomeMessageRow>` with 16 dp horizontal padding and
 *    12 dp inter-row gap.
 *  - Pinned [ChatComposer] in the Scaffold's `bottomBar`.
 *  - Drawer overlay rendered as a slide-in panel when
 *    [ChatHomeVisualState.DrawerOpen]; backed by a scrim of `0.32`.
 *  - Console overlay rendered as a docked bottom sheet when
 *    [ChatHomeVisualState.ConsoleExpanded].
 *
 * **Stateless** — every callback is hoisted via [callbacks]; the composable
 * never owns its own `remember`-d state beyond the recompose-only pieces
 * (focus, scroll position) that snapshot tests pin via the surrounding
 * test rule.
 *
 * @param state immutable visual snapshot — see [ChatHomeViewState].
 * @param callbacks bundle of one-shot event handlers; defaults to no-op.
 * @param modifier optional layout modifier applied to the screen root.
 * @param markdownRenderer optional renderer applied to every
 *   `ChatContent.Markdown` body. The catalog stays free of any markdown
 *   library — the app supplies the renderer (typically
 *   `com.mikepenz.markdown.m3.Markdown { source -> Markdown(content = source) }`).
 *   When `null` the body falls back to plain text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHomeContent(
    state: ChatHomeViewState,
    modifier: Modifier = Modifier,
    callbacks: ChatHomeCallbacks = noopChatHomeCallbacks(),
    markdownRenderer: (@Composable (String) -> Unit)? = null,
    messageListState: LazyListState = rememberLazyListState(),
) {
    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                app.knotwork.design.components.topbar.KnotworkTopAppBarShell {
                    ChatHomeTopBar(state = state, callbacks = callbacks)
                }
            },
            bottomBar = { ChatHomeBottomBar(state = state, callbacks = callbacks) },
            // The outer `AppShellScaffold` already accounts for the system
            // navigation bar and the in-app bottom nav via its own inner
            // padding. Letting this Scaffold default to `safeDrawing` would
            // add a second nav-bar-height of padding to the composer slot
            // and leave a visible strip beneath the input. The TopAppBar
            // here still handles status-bar inset via its own Material3
            // default `windowInsets`.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            modifier = Modifier.fillMaxSize(),
        ) { padding ->
            ChatHomeBody(
                state = state,
                callbacks = callbacks,
                padding = padding,
                markdownRenderer = markdownRenderer,
                messageListState = messageListState,
            )
        }
        // Drawer slide-in — 280 ms emphasised
        // slide + fade enter; reduced-motion collapses to an 80 ms crossfade
        // through `respectReducedMotionTransitions`.
        val drawerTransitions = respectReducedMotionTransitions(
            enter = slideInHorizontally(
                animationSpec = tween(
                    durationMillis = KnotworkTheme.motion.dur3,
                    easing = KnotworkTheme.motion.easeEmph,
                ),
                initialOffsetX = { full -> -full },
            ) + fadeIn(animationSpec = tween(durationMillis = KnotworkTheme.motion.dur3)),
            exit = slideOutHorizontally(
                animationSpec = tween(
                    durationMillis = KnotworkTheme.motion.dur3,
                    easing = KnotworkTheme.motion.easeEmph,
                ),
                targetOffsetX = { full -> -full },
            ) + fadeOut(animationSpec = tween(durationMillis = KnotworkTheme.motion.dur3)),
        )
        AnimatedVisibility(
            visible = state.visualState == ChatHomeVisualState.DrawerOpen,
            enter = drawerTransitions.enter,
            exit = drawerTransitions.exit,
        ) {
            ChatHomeDrawerOverlay(state = state, callbacks = callbacks)
        }
        if (state.console.snap != null) {
            ChatHomeConsoleOverlay(state = state, callbacks = callbacks)
        }
    }
}

/**
 * TopAppBar — bold thread title, monospace pipeline + token subtitle,
 * leading hamburger, trailing star/favorite + overflow icons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatHomeTopBar(state: ChatHomeViewState, callbacks: ChatHomeCallbacks) {
    val tripleTapHint = stringResource(R.string.knotwork_chat_home_title_debug_hint)
    TopAppBar(
        title = {
            Column(
                modifier = Modifier
                    .semantics { contentDescription = tripleTapHint }
                    .clickableTripleTap(callbacks.onTitleTripleTap),
            ) {
                Text(
                    text = state.threadTitle,
                    style = KnotworkTextStyles.TitleMd.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.archivedReadOnly) {
                    // An archived thread advertises its state in words, not by the
                    // absence of a composer — the user must be able to tell why
                    // they cannot type without inferring it. Pipeline + token
                    // counts are dropped: neither can change on a read-only thread.
                    Text(
                        text = stringResource(R.string.knotwork_chat_home_archived_subtitle),
                        style = KnotworkTextStyles.MonoSm,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                        maxLines = 1,
                    )
                } else {
                    ChatHomeTopBarMeta(state = state)
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = callbacks.onOpenDrawer) {
                Icon(
                    imageVector = AppIcons.Menu,
                    contentDescription = stringResource(R.string.knotwork_chat_home_action_threads),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        actions = {
            IconButton(onClick = callbacks.onToggleFavorite) {
                Icon(
                    imageVector = if (state.favorite) AppIcons.Star else AppIcons.Star,
                    contentDescription = stringResource(R.string.knotwork_chat_home_action_favorite),
                    tint = if (state.favorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            IconButton(onClick = callbacks.onOverflow) {
                Icon(
                    imageVector = AppIcons.More,
                    contentDescription = stringResource(R.string.knotwork_chat_home_action_overflow),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/** Mono `pipeline · used / max tok` line beneath the thread title. */
@Composable
private fun ChatHomeTopBarMeta(state: ChatHomeViewState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Pipeline name yields space first (weighted + ellipsis), so a
        // long name truncates instead of pushing the token count out.
        Text(
            text = stringResource(
                R.string.knotwork_chat_home_topbar_pipeline,
                state.pipelineName,
            ),
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (state.tokensMax > 0) {
            // Pinned (non-weighted, no ellipsis) so the token count is
            // always fully visible regardless of the pipeline name.
            Text(
                text = " · " + stringResource(
                    R.string.knotwork_chat_home_topbar_tokens,
                    formatTokenCount(state.tokensUsed),
                    formatTokenCount(state.tokensMax),
                ),
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** "1432" → "1.4k", "8000" → "8k". Falls back to the raw integer for small values. */
private fun formatTokenCount(value: Int): String = when {
    value < TOKEN_FORMAT_THRESHOLD -> value.toString()
    else -> {
        val thousands = value.toFloat() / TOKEN_FORMAT_THRESHOLD.toFloat()
        if (thousands % 1f == 0f) "${thousands.toInt()}k" else "%.1fk".format(thousands)
    }
}

/** Threshold above which token counts are shortened to "Nk". */
private const val TOKEN_FORMAT_THRESHOLD = 1000

/**
 * Pinned [ChatComposer] sitting at the bottom of the surface. Disabled
 * (interactive but not actionable) when the surface is awaiting a HITL
 * confirmation — the buttons remain visible so the user understands the
 * surface is paused, but typing is allowed so the user can prepare the
 * next turn.
 *
 * When [ChatHomeViewState.agentStatusLine] is non-null the console entry
 * strip is rendered above the composer — `console │ [NODE]  idle · ready ⌃`.
 * When the console is open the same strip renders as the sheet's header
 * instead, never both at once.
 */
@Composable
private fun ChatHomeBottomBar(state: ChatHomeViewState, callbacks: ChatHomeCallbacks) {
    if (state.archivedReadOnly) {
        ChatHomeArchivedBar(onRestore = callbacks.onRestoreArchivedThread)
        return
    }
    Column {
        if (state.agentStatusLine != null) {
            AgentStatusStrip(
                text = state.agentStatusLine,
                // Closed here by construction: when the console is up it is the
                // sheet's header instead, never both at once.
                open = false,
                onClick = callbacks.onAgentStatusClick,
                modifier = Modifier.padding(
                    horizontal = ChatHorizontalPadding,
                    vertical = KnotworkTheme.spacing.sp1,
                ),
            )
        }
        // Directly above the composer, where a remark about the run sits in the
        // reader's path without interrupting it. A run still going shows its
        // notice; a run the app stopped shows the short form of why — in the
        // same tone as its tile, which the composer's own error banner could
        // not do.
        state.runNotice?.let { ChatRunNotice(state = it) }
        state.termination?.let { ChatRunNotice(state = ChatRunNoticeUi(tone = it.tone, text = it.banner)) }
        ChatComposer(
            value = state.composerValue,
            onValueChange = callbacks.onComposerValueChange,
            onSend = callbacks.onSend,
            onStop = callbacks.onStop,
            state = state.composerState,
            onMic = callbacks.onMic,
            attachment = state.composerAttachment,
            onAttach = callbacks.onAttach,
            onRemoveAttachment = callbacks.onRemoveAttachment,
            onStopRecording = callbacks.onStopRecording,
            onDiscardRecording = callbacks.onDiscardRecording,
            voiceNotice = state.composerVoiceNotice,
            onChangeModel = callbacks.onChangeModel,
            onOpenSettings = callbacks.onOpenAppSettings,
        )
    }
}

/**
 * Bar that **replaces** the composer on an archived thread.
 *
 * Replaced rather than disabled in place: a disabled composer still reads as
 * "type here, but something is wrong", while the whole point is that this
 * thread is intact and readable and simply not accepting new messages until
 * the user restores it. The bar states that, and carries the Restore action —
 * the third and most contextual of the three Restore placements.
 */
@Composable
private fun ChatHomeArchivedBar(onRestore: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .background(KnotworkTheme.extended.surface2)
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3),
    ) {
        Icon(
            imageVector = AppIcons.Archive,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.size(KnotworkTheme.spacing.sp6),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.knotwork_chat_home_archived_bar_title),
                style = KnotworkTextStyles.LabelLg.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.knotwork_chat_home_archived_bar_body),
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        KnotworkSecondaryButton(
            text = stringResource(R.string.knotwork_chat_home_archived_bar_restore),
            onClick = onRestore,
            size = KnotworkButtonSize.Sm,
            leadingIcon = AppIcons.Unarchive,
        )
    }
}

/**
 * The console entry strip rendered above the composer.
 *
 * Delegates to the catalog [ConsoleEntryStrip] so the chat body and the console
 * sheet's header are literally the same element — see that component for why the
 * strip carries the word `console` rather than a glyph. This wrapper only splits
 * the leading `[TAG]` off the status line so it can be tinted brand-primary
 * while the rest stays console foreground.
 */
@Composable
private fun AgentStatusStrip(text: String, open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (tag, rest) = splitAgentStatusTag(text)
    ConsoleEntryStrip(
        statusLine = rest,
        open = open,
        onClick = onClick,
        modifier = modifier,
        leadingStatus = tag?.let {
            {
                Text(
                    text = it,
                    style = KnotworkTextStyles.MonoSm.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        },
    )
}

/** Splits "[TAG] rest of line" into (`"[TAG]"`, `"rest of line"`). */
private fun splitAgentStatusTag(text: String): Pair<String?, String> {
    if (!text.startsWith("[")) return null to text
    val end = text.indexOf(']')
    if (end <= 0) return null to text
    val tag = text.substring(startIndex = 0, endIndex = end + 1)
    val rest = text.substring(startIndex = end + 1).trimStart()
    return tag to rest
}

/** Body LazyColumn — empty state, idle conversation, or trailing loader/clarification/HITL/error. */
@Composable
private fun ChatHomeBody(
    state: ChatHomeViewState,
    callbacks: ChatHomeCallbacks,
    padding: PaddingValues,
    markdownRenderer: (@Composable (String) -> Unit)?,
    messageListState: LazyListState,
) {
    when (state.visualState) {
        ChatHomeVisualState.Loading -> ChatHomeLoadingBody(padding = padding)
        ChatHomeVisualState.Empty -> ChatHomeEmptyBody(state = state, callbacks = callbacks, padding = padding)
        else -> ChatHomeMessageList(
            state = state,
            callbacks = callbacks,
            padding = padding,
            markdownRenderer = markdownRenderer,
            listState = messageListState,
        )
    }
}

/**
 * Cold-start body — a centred [CircularProgressIndicator] on the chat
 * surface. Renders before the chat repository delivers its first snapshot
 * so the user never sees the [ChatHomeEmptyBody] hero flash for a frame
 * on every app launch.
 */
@Composable
private fun ChatHomeLoadingBody(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Empty-state body rendered for `ChatHomeVisualState.Empty` — vertically
 * centred brand glyph tile, "New chat" headline, pipeline/model caption,
 * and a column of outlined suggestion cards. Tap-targets dispatch through
 * [ChatHomeCallbacks.onSamplePromptCard] (rich cards) with
 * [ChatHomeCallbacks.onSamplePrompt] still available for the legacy chip
 * row when the host only supplies [ChatHomeViewState.samplePrompts].
 */
@Composable
private fun ChatHomeEmptyBody(state: ChatHomeViewState, callbacks: ChatHomeCallbacks, padding: PaddingValues) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = ChatHorizontalPadding),
    ) {
        Spacer(modifier = Modifier.weight(EMPTY_BODY_TOP_WEIGHT))
        BrandGlyphTile()
        Text(
            text = stringResource(R.string.knotwork_chat_home_empty_title),
            style = KnotworkTextStyles.TitleLg.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(
                R.string.knotwork_chat_home_empty_caption,
                state.pipelineName,
                state.modelName,
            ),
            style = KnotworkTextStyles.BodyBase,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
        Spacer(modifier = Modifier.height(KnotworkTheme.spacing.sp2))
        Column(
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            modifier = Modifier.fillMaxWidth(),
        ) {
            state.samplePromptCards.forEach { card ->
                SamplePromptCard(card = card, onClick = { callbacks.onSamplePromptCard(card) })
            }
            // Legacy chip row remains usable when the host hasn't migrated
            // to the rich-card list yet. Drops once `samplePromptCards`
            // is populated.
            if (state.samplePromptCards.isEmpty() && state.samplePrompts.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                    verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                ) {
                    state.samplePrompts.forEach { prompt ->
                        KnotworkSuggestionChip(
                            label = prompt,
                            onClick = { callbacks.onSamplePrompt(prompt) },
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.weight(EMPTY_BODY_BOTTOM_WEIGHT))
    }
}

/** Spring weights placing the brand tile and the suggestion cards in the upper half of the empty body. */
private const val EMPTY_BODY_TOP_WEIGHT = 0.6f
private const val EMPTY_BODY_BOTTOM_WEIGHT = 1.0f

/** Brand tile size in dp. */
private val BrandGlyphTileSize = 80.dp

/** Inner brand glyph size in dp. */
private val BrandGlyphInnerSize = 36.dp

@Composable
private fun BrandGlyphTile() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(BrandGlyphTileSize)
            .clip(KnotworkTheme.shapes.lg)
            .background(color = app.knotwork.design.tokens.KnotworkPalette.Accent100),
    ) {
        Icon(
            imageVector = AppIcons.Hub,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(BrandGlyphInnerSize),
        )
    }
}

@Composable
private fun SamplePromptCard(card: ChatHomeSamplePromptCard, onClick: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .border(
                border = BorderStroke(width = 1.dp, color = KnotworkTheme.extended.outlineStrong),
                shape = KnotworkTheme.shapes.md,
            )
            .clickable(onClick = onClick)
            .padding(
                horizontal = KnotworkTheme.spacing.sp4,
                vertical = KnotworkTheme.spacing.sp3,
            ),
    ) {
        Text(
            text = card.title,
            style = KnotworkTextStyles.TitleMd.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (card.toolsUsed.isNotBlank()) {
            Text(
                text = stringResource(R.string.knotwork_chat_home_empty_card_uses, card.toolsUsed),
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Renders the chronological history plus any trailing state-specific tile
 * (loader, HITL card, clarification card, error tile).
 */
@Composable
private fun ChatHomeMessageList(
    state: ChatHomeViewState,
    callbacks: ChatHomeCallbacks,
    padding: PaddingValues,
    markdownRenderer: (@Composable (String) -> Unit)?,
    listState: LazyListState,
) {
    // Compose the Scaffold-provided insets with the surface's own 16dp
    // horizontal padding into a single `contentPadding` value. Applying
    // them via `Modifier.padding` would shrink the LazyColumn's
    // touch/scroll area and push the scrollbar inset away from the screen
    // edge — passing them through `contentPadding` keeps the full-width
    // scroll surface and only pads the rendered items.
    val mergedContentPadding = PaddingValues(
        start = padding.calculateStartPadding(LocalLayoutDirection.current) + ChatHorizontalPadding,
        end = padding.calculateEndPadding(LocalLayoutDirection.current) + ChatHorizontalPadding,
        top = padding.calculateTopPadding() + KnotworkTheme.spacing.sp2,
        // Extra bottom clearance so a short last message that clamps to the
        // bottom of the scroll range rests clear of the single-line agent-status
        // console entry strip that sits just above the composer, instead of tucking
        // under it.
        bottom = padding.calculateBottomPadding() + KnotworkTheme.spacing.sp2 + ChatConsoleClearance,
    )
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = mergedContentPadding,
        verticalArrangement = Arrangement.spacedBy(ChatRowGap),
    ) {
        items(items = state.messages, key = { it.id }) { row ->
            ChatMessage(
                role = row.role,
                content = row.content,
                metadata = row.metadata,
                pendingTypedConfirm = state.pendingTypedConfirm,
                onTypedConfirmChange = callbacks.onHitlTypedConfirmChange,
                allowOnceEnabled = allowOnceEnabledFor(row.content, state.pendingTypedConfirm),
                onAllowOnce = callbacks.onHitlAllowOnce,
                onAllowAlways = callbacks.onHitlAllowAlways,
                onReject = callbacks.onHitlReject,
                onClarificationReply = callbacks.onClarificationReply,
                onRunResume = callbacks.onResumeRun,
                onRunDiscard = callbacks.onDiscardRun,
                onRunContinue = callbacks.onContinuePastCeiling,
                onRunStop = callbacks.onStopAtCeiling,
                onErrorRetry = callbacks.onErrorRetry,
                onContextAction = { action -> callbacks.onMessageContextAction(row.id, action) },
                markdownRenderer = markdownRenderer,
            )
        }
        if (state.visualState == ChatHomeVisualState.Generating) {
            item { GeneratingLoaderBubble(label = state.loaderLabel) }
        }
        // A stopped run gets exactly one tile. Which one depends on whether we
        // chose to stop it: a fault we did not choose keeps the destructive
        // tone and the Retry that may genuinely help, while a decision we made
        // is explained in its own words and offers something that can actually
        // change the outcome.
        if (state.visualState == ChatHomeVisualState.Error) {
            state.termination?.let { termination ->
                item { ChatTerminationTile(state = termination, onAction = callbacks.onTerminationAction) }
            }
            state.errorMessage?.let { message ->
                item { ChatHomeErrorTile(message = message, onRetry = callbacks.onErrorRetry) }
            }
        }
    }
}

/**
 * Loader bubble shown while the assistant is producing tokens.
 *
 * Sizes to its content (`wrapContentWidth`) so the "Generating…" label
 * never wraps onto a second line. The earlier `fillMaxWidth(0.4f)`
 * fraction was too tight on narrow phones — the label split into
 * `Generatin` / `g…`.
 */
@Composable
private fun GeneratingLoaderBubble(label: String?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .wrapContentWidth()
                .clip(KnotworkTheme.shapes.md)
                .background(color = KnotworkTheme.extended.chatAgentBg)
                .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp3),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            ) {
                KnotworkLoader()
                Text(
                    text = label ?: stringResource(R.string.knotwork_chat_home_generating_label),
                    style = KnotworkTextStyles.BodySm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * Tile explaining a run the app deliberately ended.
 *
 * Everything on it is supplied already resolved, including the tone's word, so
 * the whole vocabulary of a stopped run has exactly one owner in `:app` and
 * this module only decides how it looks. That is the point: the same event used
 * to be worded separately in the engine, the settings search and the
 * documentation, and two of those wordings described a pause the engine never
 * performed.
 *
 * There is no Retry. The action is optional and labelled, because what helps
 * depends on why the run stopped — raise the limit, look at the console, or
 * simply run it again.
 *
 * @param state The resolved copy and tone.
 * @param onAction Invoked by the single action, when there is one.
 */
@Composable
private fun ChatTerminationTile(state: ChatTerminationUi, onAction: () -> Unit) {
    val accent = state.tone.accent()
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = KnotworkTheme.extended.surface1)
            .border(border = BorderStroke(width = 1.dp, color = accent), shape = KnotworkTheme.shapes.md)
            .padding(KnotworkTheme.spacing.sp4),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = state.tone.glyph(),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(KnotworkTheme.spacing.sp4),
            )
            // The glyph and the outline carry the amber; the label is `onSurface2`
            // (amber text is 2.3:1 on the surface). An info tone is muted throughout.
            Text(
                text = state.toneLabel.uppercase(),
                style = KnotworkTextStyles.MonoSm,
                color = if (state.tone == RunTerminationToneUi.Info) accent else KnotworkTheme.extended.onSurface2,
            )
        }
        Text(
            text = state.title,
            style = KnotworkTextStyles.TitleMd,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // No explanatory paragraph. The run wrote its outcome into the
        // conversation as it settled, so the sentence is a few lines above this
        // tile; repeating it here is what this tile was asked to stop doing.
        // What is left is what only a tile can carry — the numbers the run
        // reached, and the one action that can change them.
        state.meter?.let { meter ->
            Text(
                text = meter,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        state.actionLabel?.let { label ->
            KnotworkSecondaryButton(text = label, onClick = onAction)
        }
    }
}

/**
 * The quiet advisory about a run that is still going.
 *
 * Deliberately not a dialog and deliberately not a message: it is a property of
 * the work in flight, so it sits in the composer's own column, is not
 * dismissible, and disappears with the run that raised it.
 *
 * @param state The resolved sentence and tone.
 */
@Composable
private fun ChatRunNotice(state: ChatRunNoticeUi) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp1)
            .clip(KnotworkTheme.shapes.sm)
            .background(color = KnotworkTheme.extended.surface2)
            .border(
                border = BorderStroke(width = 1.dp, color = KnotworkTheme.extended.divider),
                shape = KnotworkTheme.shapes.sm,
            )
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
    ) {
        Icon(
            imageVector = state.tone.glyph(),
            contentDescription = null,
            tint = state.tone.accent(),
            modifier = Modifier.size(KnotworkTheme.spacing.sp4),
        )
        Text(
            text = state.text,
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.onSurface2,
            // Two lines is the strip's budget. The host supplies a clause
            // written to fit it rather than the tile's full sentence.
            maxLines = NOTICE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Lines the run-notice strip will grow to before it truncates. */
private const val NOTICE_MAX_LINES: Int = 2

/** The glyph half of a tone. Never colour alone. */
@Composable
private fun RunTerminationToneUi.glyph() = when (this) {
    RunTerminationToneUi.Limit -> AppIcons.Shield
    RunTerminationToneUi.Stuck -> AppIcons.Warn
    RunTerminationToneUi.Info -> AppIcons.Info
}

/** The colour half of a tone. A limit doing its job is warned, never errored. */
@Composable
private fun RunTerminationToneUi.accent() = when (this) {
    RunTerminationToneUi.Limit, RunTerminationToneUi.Stuck -> KnotworkTheme.extended.signalWarn
    RunTerminationToneUi.Info -> KnotworkTheme.extended.onSurfaceMuted
}

/**
 * Inline error tile rendered in the trailing position of the conversation.
 *
 * The **untyped** half of a stopped run: something broke that we did not
 * choose. Keeps its destructive tone and its Retry, because a transient fault
 * may genuinely not recur — which is exactly what is not true of a limit.
 */
@Composable
private fun ChatHomeErrorTile(message: String, onRetry: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = KnotworkTheme.extended.surface1)
            .border(
                border = BorderStroke(width = 1.dp, color = KnotworkTheme.extended.signalError),
                shape = KnotworkTheme.shapes.md,
            )
            .padding(KnotworkTheme.spacing.sp4),
    ) {
        Text(
            text = stringResource(R.string.knotwork_chat_home_error_title),
            style = KnotworkTextStyles.TitleMd,
            color = KnotworkTheme.extended.signalErrorText,
        )
        Text(
            text = message,
            style = KnotworkTextStyles.BodyBase,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(modifier = Modifier.align(Alignment.End)) {
            KnotworkSecondaryButton(
                text = stringResource(R.string.knotwork_chat_home_error_retry),
                onClick = onRetry,
            )
        }
    }
}

/**
 * Material 3 [ModalBottomSheet] hosting the stateless [ConsolePane].
 *
 * The sheet owns:
 *  - the drag handle (`BottomSheetDefaults.DragHandle`);
 *  - anchored-draggable physics — drag-to-snap, fling-to-snap, swipe-down
 *    to dismiss;
 *  - the scrim above the sheet and tap-outside-to-dismiss;
 *  - enter / exit animations.
 *
 * The host's [ChatHomeConsoleState.snap] drives the sheet via
 * `sheetState.partialExpand()` / `expand()`; user-driven snap changes are
 * mirrored back via [ChatHomeCallbacks.onConsoleSnapChange]. The same
 * channel handles dismiss → [ChatHomeCallbacks.onCloseConsole].
 *
 * The console keeps its "always dark" identity by overriding
 * `containerColor` / `contentColor` with the Knotwork console tokens
 * regardless of the system theme.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatHomeConsoleOverlay(state: ChatHomeViewState, callbacks: ChatHomeCallbacks) {
    val targetSnap = state.console.snap ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()

    // Pull the sheet toward the host-requested snap. Anchors may not be
    // wired the first time this LaunchedEffect runs (sheet hasn't been
    // measured yet) — we retry on every recomposition that changes the
    // target snap, so the host can request Full immediately after open
    // without race conditions.
    LaunchedEffect(targetSnap, sheetState.hasPartiallyExpandedState) {
        when (targetSnap) {
            ConsoleSnap.Partial ->
                if (sheetState.hasPartiallyExpandedState && sheetState.currentValue != SheetValue.PartiallyExpanded) {
                    sheetState.partialExpand()
                }
            ConsoleSnap.Full ->
                if (sheetState.currentValue != SheetValue.Expanded) {
                    sheetState.expand()
                }
        }
    }

    // Mirror user-driven snap changes (drag + fling) back to the host so
    // the persisted snap survives recomposition.
    LaunchedEffect(sheetState.currentValue) {
        val newSnap = when (sheetState.currentValue) {
            SheetValue.PartiallyExpanded -> ConsoleSnap.Partial
            SheetValue.Expanded -> ConsoleSnap.Full
            SheetValue.Hidden -> null
        }
        if (newSnap != null && newSnap != targetSnap) {
            callbacks.onConsoleSnapChange(newSnap)
        }
    }

    ModalBottomSheet(
        onDismissRequest = callbacks.onCloseConsole,
        sheetState = sheetState,
        containerColor = KnotworkTheme.extended.consoleBg,
        contentColor = KnotworkTheme.extended.consoleFg,
        // The strip *becomes* the sheet's header rather than being replaced by a
        // drag handle: one element with one name in two positions, so "where did
        // the strip go" is never the next question. Tapping it here closes the
        // console, which is what the flipped chevron promises.
        dragHandle = {
            if (state.agentStatusLine != null) {
                AgentStatusStrip(
                    text = state.agentStatusLine,
                    open = true,
                    onClick = { scope.launch { sheetState.hide() } },
                )
            } else {
                BottomSheetDefaults.DragHandle(
                    color = KnotworkTheme.extended.consoleFg.copy(alpha = CONSOLE_DRAG_HANDLE_ALPHA),
                )
            }
        },
    ) {
        ConsolePane(
            tab = state.console.tab,
            onTabChange = callbacks.onConsoleTabChange,
            logs = state.console.logs,
            vars = state.console.vars,
            traces = state.console.traces,
            filter = state.console.filter,
            onFilterChange = callbacks.onConsoleFilterChange,
            onSearch = callbacks.onConsoleSearch,
            onCopyAll = callbacks.onConsoleCopyAll,
            onClear = callbacks.onConsoleClear,
            searchQuery = state.console.searchQuery,
            onSearchQueryChange = callbacks.onConsoleSearchQueryChange,
            onCopyLine = callbacks.onConsoleCopyLine,
            onCopyVar = callbacks.onConsoleCopyVar,
            onCopySpan = callbacks.onConsoleCopySpan,
            onFilterByLineSource = callbacks.onConsoleFilterByLineSource,
        )
    }
}

/**
 * Triple-tap gesture wrapper used on the TopAppBar title row. In debug
 * builds this opens the state-picker overlay; release builds wire the
 * callback to a no-op so the gesture is inert.
 *
 * Lives as a `Modifier` extension rather than a separate composable so the
 * gesture coexists with the existing semantics on the title `Column`.
 */
private fun Modifier.clickableTripleTap(onTripleTap: () -> Unit): Modifier = this.pointerInput(onTripleTap) {
    var lastTapMs = 0L
    var tapCount = 0
    detectTapGestures(
        onTap = {
            val now = System.currentTimeMillis()
            tapCount = if (now - lastTapMs <= TRIPLE_TAP_TIMEOUT_MS) tapCount + 1 else 1
            lastTapMs = now
            if (tapCount >= TRIPLE_TAP_COUNT) {
                tapCount = 0
                onTripleTap()
            }
        },
    )
}

/** Tap-count required to fire the debug state picker. */
private const val TRIPLE_TAP_COUNT = 3

/** Maximum gap between two consecutive taps to keep them in the same triple-tap sequence. */
private const val TRIPLE_TAP_TIMEOUT_MS = 400L

/**
 * Marks a scrim layer as the dismiss surface for the overlay it covers.
 * Wraps `Modifier.clickable` with a discarded `MutableInteractionSource`
 * and `indication = null` so the scrim swallows the tap silently — no
 * ripple, no touch feedback, and the click never falls through to the
 * surface underneath.
 */
@Composable
internal fun Modifier.scrimClickable(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
}

/**
 * Marks a panel as a click-absorbing surface — taps on the panel are
 * consumed locally so they don't reach the surrounding scrim and trigger
 * its dismiss handler. Used inside [ChatHomeDrawerOverlay] and
 * [ChatHomeConsoleOverlay] so panel-internal controls (chips, drag
 * handles, tab strips) keep working without dismissing the overlay.
 */
@Composable
internal fun Modifier.absorbClicks(): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = { /* no-op: just absorb the tap */ },
    )
}

/**
 * Per-row resolution of the `allowOnceEnabled` flag passed into
 * [ChatMessage] for HITL confirmations. By rule:
 *  - Readonly → auto-allowed (CTA hidden by the card).
 *  - Sensitive → always enabled.
 *  - Destructive → enabled only when the typed confirmation reads "yes".
 *
 * Non-confirmation content rows return `true` since the value is ignored by
 * the card dispatch in [ChatMessage].
 */
internal fun allowOnceEnabledFor(content: ChatContent, pendingTypedConfirm: String): Boolean {
    val confirmation = content as? ChatContent.Confirmation ?: return true
    return when (confirmation.model.risk) {
        Risk.Readonly, Risk.Sensitive -> true
        Risk.Destructive -> pendingTypedConfirm.trim().equals("yes", ignoreCase = true)
    }
}
