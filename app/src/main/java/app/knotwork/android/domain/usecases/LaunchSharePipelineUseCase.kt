package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.ChatSession
import app.knotwork.android.domain.models.EntrySurface
import app.knotwork.android.domain.models.MessageAttachment
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.SharedPayload
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.AttachmentStore
import app.knotwork.android.domain.text.toSingleLineTitle
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

/**
 * Launches the pipeline bound to the share target over the content the user
 * shared into the app via `ACTION_SEND`.
 *
 * The share activity brings the app to the foreground, so unlike the tile this
 * uses the interactive queue ([AgentOrchestratorUseCase]) and returns the id of
 * the session it ran in so the caller can deep-link the user straight into the
 * live run.
 *
 * **Session reuse.** When [SettingsRepository.shareReuseSession] is `true` (the
 * default) every share accumulates in one reusable **Shared** chat
 * ([SHARED_INBOX_SESSION_ID]) — new shares are appended, keeping the history in
 * one legible place. When it is `false` a fresh, auto-named session is created
 * per share (the original behaviour). Either way the active chat is never
 * polluted — shares land in the Shared chat or a brand-new one, not the chat the
 * user was in.
 *
 * Shared images are ingested through [AttachmentStore] exactly like a composer
 * attachment; per the multimodal contract only the text travels the graph while
 * the image rides the user message. An image-only share runs the same
 * image-only default instruction the composer uses. Before anything is stored,
 * an image share passes the composer's multimodal pre-flight
 * ([CheckImageAttachmentUseCase]) for the bound pipeline: a refusal blocks the
 * whole share ([ShareLaunchResult.Blocked]) rather than running the text while
 * the image is quietly dropped.
 *
 * When no pipeline is bound the surface is inert ([ShareLaunchResult.NotConfigured]);
 * an empty share is dropped ([ShareLaunchResult.NothingShared]).
 */
class LaunchSharePipelineUseCase @Inject constructor(
    private val resolveSurfacePipeline: ResolveSurfacePipelineUseCase,
    private val chatRepository: ChatRepository,
    private val attachmentStore: AttachmentStore,
    private val checkImageAttachment: CheckImageAttachmentUseCase,
    private val agentOrchestrator: AgentOrchestratorUseCase,
    private val settingsRepository: SettingsRepository,
    private val pendingInteractionRepository: PendingInteractionRepository,
) {

    /**
     * Resolves the share binding and, when present and the payload is non-empty,
     * creates a bound session and enqueues a run over the shared content.
     *
     * @param payload The normalised shared content (text and/or image URI).
     * @param reusedSessionName Localised name for the single **Shared** chat used
     *   when session reuse is on (the caller resolves it from a string resource —
     *   the domain layer keeps no user-visible literals).
     * @param imageSessionName Localised name for an image-only share's session
     *   (used only in per-share mode).
     * @param contentSessionName Localised name fallback when no readable text or
     *   image is present (used only in per-share mode).
     * @return [ShareLaunchResult.Launched] with the session id,
     *   [ShareLaunchResult.NotConfigured] when nothing is bound,
     *   [ShareLaunchResult.Blocked] when the pipeline may not start with the
     *   shared image, or [ShareLaunchResult.NothingShared] when the payload had
     *   no content.
     */
    suspend operator fun invoke(
        payload: SharedPayload,
        reusedSessionName: String,
        imageSessionName: String,
        contentSessionName: String,
    ): ShareLaunchResult {
        if (payload.isEmpty) return ShareLaunchResult.NothingShared
        val pipelineId = resolveSurfacePipeline(EntrySurface.SHARE) ?: return ShareLaunchResult.NotConfigured
        if (payload.imageUri != null) {
            // Asked before the ingest, so a refused image never reaches the store.
            checkImageAttachment(pipelineId)?.let { return ShareLaunchResult.Blocked(it) }
        }

        val attachment = payload.imageUri?.let { ingestImage(it) }
        val hasText = !payload.text.isNullOrBlank()
        // Image-only share with a failed ingest leaves us nothing to run.
        if (!hasText && attachment == null) return ShareLaunchResult.NothingShared

        val session = resolveSession(
            payload = payload,
            hasImage = attachment != null,
            pipelineId = pipelineId,
            reusedSessionName = reusedSessionName,
            imageSessionName = imageSessionName,
            contentSessionName = contentSessionName,
        )
        chatRepository.saveSession(session)

        // Prompt / display content follow the shared image-only contract.
        val content = AttachmentMessageContent.resolve(payload.text?.trim().orEmpty())

        agentOrchestrator(
            sessionId = session.id,
            userPrompt = content.prompt,
            pipelineId = pipelineId,
            attachment = attachment,
            displayContent = content.displayContent,
            origin = RunOrigin.SHARE,
        )
        return ShareLaunchResult.Launched(session.id)
    }

    /**
     * Picks the session the share runs in.
     *
     * With reuse off, a fresh auto-named session is created per share. With reuse
     * on, the single Shared chat ([SHARED_INBOX_SESSION_ID]) is reused as-is (its
     * pipeline binding is left untouched — the run already carries the current
     * share pipeline explicitly, so re-pointing it would silently clobber a
     * binding the user may have changed) or created if absent.
     *
     * **Collision guard:** if the Shared chat is currently parked awaiting a
     * Human-in-the-loop approval, this share spills into a fresh session instead
     * of reusing it. A second HITL run on the same session would share the
     * per-session approval notification slot and the newest-parked-wins in-chat
     * fallback, making the first, still-pending approval unreachable.
     */
    private suspend fun resolveSession(
        payload: SharedPayload,
        hasImage: Boolean,
        pipelineId: String,
        reusedSessionName: String,
        imageSessionName: String,
        contentSessionName: String,
    ): ChatSession {
        val reuse = settingsRepository.shareReuseSession.first() &&
            pendingInteractionRepository.getForSession(SHARED_INBOX_SESSION_ID) == null
        if (reuse) {
            return chatRepository.getSessionById(SHARED_INBOX_SESSION_ID)
                ?: ChatSession.create(id = SHARED_INBOX_SESSION_ID, name = reusedSessionName, pipelineId = pipelineId)
        }
        return ChatSession.create(
            name = sessionName(payload.text, hasImage, imageSessionName, contentSessionName),
            pipelineId = pipelineId,
        )
    }

    /**
     * Best-effort image ingest; a failure degrades to a text-only (or empty)
     * share. Only the failure's type is logged: its message may quote the URI,
     * which the sending app chose.
     */
    private suspend fun ingestImage(uri: String): MessageAttachment? =
        attachmentStore.ingestUri(uri).getOrElse { error ->
            Timber.w("Failed to ingest shared image (%s); continuing without it.", error.javaClass.simpleName)
            null
        }

    /** Derives a session name from the shared text, falling back to a caller-localised label. */
    private fun sessionName(
        text: String?,
        hasImage: Boolean,
        imageSessionName: String,
        contentSessionName: String,
    ): String {
        // The whole shared payload — not just its first line — contributes to a
        // clean single-line title; a shared article often opens with a short header
        // line, so flowing the following text in makes the title far more useful.
        val title = text?.toSingleLineTitle(SESSION_NAME_MAX_LENGTH, SESSION_NAME_SUFFIX).orEmpty()
        return when {
            title.isNotEmpty() -> title
            hasImage -> imageSessionName
            else -> contentSessionName
        }
    }

    /** Constants for the share launch flow, incl. the reserved Shared-chat id. */
    companion object {
        /**
         * Reserved, stable id of the single **Shared** chat that accumulates every
         * share when session reuse is on. A fixed id (not a random UUID) lets the
         * chat be found and reused across shares, and re-created with the same id
         * if the user deletes it.
         */
        const val SHARED_INBOX_SESSION_ID = "shared-inbox"

        /** Max characters of shared text used for the auto-generated session name. */
        private const val SESSION_NAME_MAX_LENGTH = 60

        /** Suffix appended when the shared text is longer than [SESSION_NAME_MAX_LENGTH]. */
        private const val SESSION_NAME_SUFFIX = "…"
    }
}

/** Outcome of handling an incoming share. */
sealed interface ShareLaunchResult {
    /**
     * A run was enqueued in a freshly created session.
     *
     * @property sessionId The new session to deep-link the user into.
     */
    data class Launched(val sessionId: String) : ShareLaunchResult

    /** No pipeline is bound to the share target; the caller should inform the user. */
    data object NotConfigured : ShareLaunchResult

    /** The share carried nothing actionable (no text and no ingestable image). */
    data object NothingShared : ShareLaunchResult

    /**
     * The share carried an image the bound pipeline may not start with; nothing
     * was stored and no run started. The caller tells the user why.
     *
     * @property reason What the multimodal pre-flight objected to.
     */
    data class Blocked(val reason: ImageAttachmentBlock) : ShareLaunchResult
}
