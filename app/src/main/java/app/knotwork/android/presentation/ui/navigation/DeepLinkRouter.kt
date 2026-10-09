package app.knotwork.android.presentation.ui.navigation

import android.content.Intent
import androidx.navigation.NavHostController
import app.knotwork.android.presentation.state.ChatEntryRequestRelay

/**
 * Routes a `knotwork://` deep-link [Intent] into the app's single chat home.
 *
 * The chat *is* the home — there is one chat destination ([NavRoutes.CHAT_TAB]),
 * not a per-thread screen — so every chat deep link lands on that one home and
 * merely changes which session it shows (posted through [chatEntryRelay]). The
 * back stack stays a single chat entry, so Back from a shortcut-opened chat
 * closes the app, exactly like a normal launch.
 *
 * Supported hosts (the app's `knotwork://` entry-surface URIs):
 *  - `knotwork://chat/{threadId}` → chat home switched to that session;
 *  - `knotwork://chat`            → chat home on the current session;
 *  - `knotwork://new-chat`        → chat home with a fresh empty session;
 *  - `knotwork://pipelines`       → the pipeline library ([NavRoutes.PIPELINE_LIBRARY]);
 *  - `knotwork://triggers/{id}`   → the triggers screen, under the More tab, with that
 *    trigger open (an overdue trigger's notification).
 *
 * Used for both the cold launch intent (applied once the splash reaches home)
 * and warm intents delivered to the single-task instance via `onNewIntent`.
 *
 * @param intent the deep-link intent.
 * @param chatEntryRelay bus the chat-session side effect (open-thread / new-chat)
 *   is posted to; the `CHAT_TAB` composable drains it.
 * @return `true` when the intent carried a recognised `knotwork://` deep link and
 *   a navigation was dispatched; `false` otherwise (caller leaves the current
 *   destination untouched).
 */
internal fun NavHostController.navigateToDeepLink(intent: Intent, chatEntryRelay: ChatEntryRequestRelay): Boolean {
    val uri = intent.data ?: return false
    if (uri.scheme != NavRoutes.DEEP_LINK_SCHEME) return false
    when (uri.host) {
        "chat" -> {
            navigateToChatHome()
            // `knotwork://chat/<threadId>` switches sessions; bare `knotwork://chat`
            // just shows whatever is current.
            uri.pathSegments.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { chatEntryRelay.openThread(it) }
        }
        "new-chat" -> {
            navigateToChatHome()
            chatEntryRelay.newChat()
        }
        // The Pipelines *tab* route, not its start destination: entering it the
        // way the nav bar does keeps `restoreState`'s key identical, so a
        // shortcut launch restores the same saved subtree a tab tap would.
        "pipelines" -> navigateToTab(NavRoutes.PIPELINES_GRAPH)
        // Through the More tab, as a tap there would arrive: Back returns to More,
        // and the tab stays highlighted. Straight above More, never above whatever
        // the tab switch restored there (a triggers list left open would stack twice).
        "triggers" -> {
            navigateToTab(NavRoutes.MORE)
            val id = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
            navigate(id?.let(NavRoutes::triggersRoute) ?: NavRoutes.TRIGGERS) { popUpTo(NavRoutes.MORE) }
        }
        else -> return false
    }
    return true
}

/**
 * Lands on the single chat home through the one sanctioned tab entry point
 * ([navigateToTab]), so a chat deep link never stacks a second chat entry and
 * never leaves a tab root sitting on top of another subtree's stack.
 *
 * The options are the nav bar's own: `popUpTo(TAB_BACK_STACK_ANCHOR)` anchors
 * the chat at the bottom of the post-splash stack, and `saveState` /
 * `restoreState` mean a notification tap no longer discards the tab subtree the
 * user was in.
 */
private fun NavHostController.navigateToChatHome() {
    navigateToTab(NavRoutes.CHAT_TAB)
}
