package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.models.ChatSession
import app.knotwork.android.domain.models.MessageAttachment
import app.knotwork.android.domain.models.PendingInteraction
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.SharedPayload
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.ShareAdmissionRepository
import app.knotwork.android.domain.services.AttachmentStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LaunchSharePipelineUseCase]: the empty / unbound guards, the
 * rate ceiling, the text-only, image-only and failed-ingest launch branches, and
 * the single-chat session-reuse toggle.
 */
class LaunchSharePipelineUseCaseTest {

    private val resolveSurfacePipeline = mockk<ResolveLaunchableSurfacePipelineUseCase>()
    private val chatRepository = mockk<ChatRepository>(relaxed = true)
    private val attachmentStore = mockk<AttachmentStore>()
    private val checkImageAttachment = mockk<CheckImageAttachmentUseCase>()
    private val orchestrator = mockk<AgentOrchestratorUseCase>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>()
    private val pendingInteractionRepository = mockk<PendingInteractionRepository>()
    private val shareAdmissions = mockk<ShareAdmissionRepository>()
    private val useCase = LaunchSharePipelineUseCase(
        resolveSurfacePipeline,
        chatRepository,
        attachmentStore,
        checkImageAttachment,
        orchestrator,
        settingsRepository,
        pendingInteractionRepository,
        shareAdmissions,
    )

    init {
        // Default: per-share mode (a new session each time). Reuse tests override.
        every { settingsRepository.shareReuseSession } returns flowOf(false)
        coEvery { chatRepository.getSessionById(any()) } returns null
        // Default: the Shared chat is not mid-approval.
        coEvery { pendingInteractionRepository.getForSession(any()) } returns null
        // Default: the multimodal pre-flight lets an image through.
        coEvery { checkImageAttachment(any()) } returns null
        // Default: under the rate ceiling.
        coEvery { shareAdmissions.admitWithinCeiling(any(), any(), any()) } returns true
    }

    private suspend fun launch(payload: SharedPayload): ShareLaunchResult = useCase(
        payload,
        reusedSessionName = "Shared",
        imageSessionName = "Shared image",
        contentSessionName = "Shared content",
        nowMillis = NOW,
    )

    // --- Rate ceiling -------------------------------------------------------

    @Test
    fun `given the share ceiling is reached when a further share arrives then no run is enqueued`() = runTest {
        coEvery { resolveSurfacePipeline(any()) } returns "pipe-1"
        coEvery { shareAdmissions.admitWithinCeiling(any(), any(), any()) } returns false
        coEvery { attachmentStore.ingestUri(any()) } returns Result.success(
            MessageAttachment(path = "a.jpg", mimeType = "image/jpeg", width = 1, height = 1),
        )

        val result = launch(SharedPayload(text = "Read my notes and mail them", imageUri = "content://photos/1"))

        assertEquals(ShareLaunchResult.RateLimited, result)
        // Refused before any work: nothing stored, no chat created, no run.
        coVerify(exactly = 0) { attachmentStore.ingestUri(any()) }
        coVerify(exactly = 0) { chatRepository.saveSession(any()) }
        coVerify(exactly = 0) { orchestrator(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `given a share under the ceiling when launched then one admission is taken over the share window`() = runTest {
        coEvery { resolveSurfacePipeline(any()) } returns "pipe-1"

        val result = launch(SharedPayload(text = "summarise", imageUri = null))

        assertTrue(result is ShareLaunchResult.Launched)
        coVerify(exactly = 1) {
            shareAdmissions.admitWithinCeiling(
                nowMillis = NOW,
                windowStartEpochMs = NOW - RunRateCeiling.ONE_HOUR_MILLIS,
                limitPerWindow = RunRateCeiling.SHARE.limitPerWindow,
            )
        }
    }

    @Test
    fun `given a share that will not run when invoked then it takes no admission`() = runTest {
        // Only a share that is about to start a run counts against the ceiling: an
        // empty, unbound or image-blocked share must not use up the hour's budget.
        launch(SharedPayload(text = null, imageUri = null))
        coEvery { resolveSurfacePipeline(any()) } returns null
        launch(SharedPayload(text = "summarise", imageUri = null))
        coEvery { resolveSurfacePipeline(any()) } returns "cloud-pipe"
        coEvery { checkImageAttachment("cloud-pipe") } returns ImageAttachmentBlock.CLOUD_ENTRY
        launch(SharedPayload(text = null, imageUri = "content://photos/1"))

        coVerify(exactly = 0) { shareAdmissions.admitWithinCeiling(any(), any(), any()) }
    }

    @Test
    fun `given the share pipeline starts on a CLOUD node when an image is shared then nothing is ingested`() = runTest {
        coEvery { resolveSurfacePipeline(any()) } returns "cloud-pipe"
        coEvery { checkImageAttachment("cloud-pipe") } returns ImageAttachmentBlock.CLOUD_ENTRY
        coEvery { attachmentStore.ingestUri(any()) } returns Result.success(
            MessageAttachment(path = "a.jpg", mimeType = "image/jpeg", width = 1, height = 1),
        )

        val result = launch(SharedPayload(text = "what is this", imageUri = "content://photos/1"))

        assertEquals(ShareLaunchResult.Blocked(ImageAttachmentBlock.CLOUD_ENTRY), result)
        coVerify(exactly = 0) { attachmentStore.ingestUri(any()) }
        coVerify(exactly = 0) { chatRepository.saveSession(any()) }
        coVerify(exactly = 0) { orchestrator(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `given a text-only model when an image is shared then the share is blocked with that reason`() = runTest {
        coEvery { resolveSurfacePipeline(any()) } returns "local-pipe"
        coEvery { checkImageAttachment("local-pipe") } returns ImageAttachmentBlock.MODEL_NO_VISION

        val result = launch(SharedPayload(text = null, imageUri = "content://photos/2"))

        assertEquals(ShareLaunchResult.Blocked(ImageAttachmentBlock.MODEL_NO_VISION), result)
    }

    @Test
    fun `given a text-only share into a cloud-first pipeline when invoked then the pre-flight is not consulted`() =
        runTest {
            coEvery { resolveSurfacePipeline(any()) } returns "cloud-pipe"
            coEvery { checkImageAttachment(any()) } returns ImageAttachmentBlock.CLOUD_ENTRY

            val result = launch(SharedPayload(text = "summarise", imageUri = null))

            assertTrue(result is ShareLaunchResult.Launched)
            coVerify(exactly = 0) { checkImageAttachment(any()) }
        }

    @Test
    fun `given empty payload when invoked then reports NothingShared`() = runTest {
        val result = launch(SharedPayload(text = null, imageUri = null))

        assertEquals(ShareLaunchResult.NothingShared, result)
    }

    @Test
    fun `given no bound share pipeline when invoked then reports NotConfigured`() = runTest {
        coEvery { resolveSurfacePipeline(any()) } returns null

        val result = launch(SharedPayload(text = "hi", imageUri = null))

        assertEquals(ShareLaunchResult.NotConfigured, result)
    }

    @Test
    fun `given text share when invoked then creates a bound session and enqueues a SHARE run`() = runTest {
        coEvery { resolveSurfacePipeline(any()) } returns "share-pipe"
        val sessionSlot = slot<ChatSession>()
        coEvery { chatRepository.saveSession(capture(sessionSlot)) } returns Unit

        val result = launch(SharedPayload(text = "summarise this", imageUri = null))

        val sessionId = (result as ShareLaunchResult.Launched).sessionId
        assertEquals(sessionId, sessionSlot.captured.id)
        assertEquals("share-pipe", sessionSlot.captured.pipelineId)
        coVerify {
            orchestrator(
                sessionId = sessionId,
                userPrompt = "summarise this",
                pipelineId = "share-pipe",
                attachment = null,
                displayContent = null,
                origin = RunOrigin.SHARE,
            )
        }
    }

    @Test
    fun `given multi-line share when invoked then title flows the whole text onto one line`() = runTest {
        coEvery { resolveSurfacePipeline(any()) } returns "share-pipe"
        val sessionSlot = slot<ChatSession>()
        coEvery { chatRepository.saveSession(capture(sessionSlot)) } returns Unit

        launch(SharedPayload(text = "Weekend plan\n\nvisit   the museum", imageUri = null))

        // Not just the first line ("Weekend plan"): the following text flows in,
        // with runs of whitespace collapsed to single spaces.
        assertEquals("Weekend plan visit the museum", sessionSlot.captured.name)
    }

    @Test
    fun `given over-long share when invoked then title is truncated with an ellipsis`() = runTest {
        coEvery { resolveSurfacePipeline(any()) } returns "share-pipe"
        val sessionSlot = slot<ChatSession>()
        coEvery { chatRepository.saveSession(capture(sessionSlot)) } returns Unit

        val long = "x".repeat(80) // no whitespace, well over the 60-char cap
        launch(SharedPayload(text = long, imageUri = null))

        val name = sessionSlot.captured.name
        assertTrue("Title must end with an ellipsis when truncated", name.endsWith("…"))
        assertEquals(61, name.length) // 60 chars + the ellipsis
    }

    @Test
    fun `given image-only share when invoked then uses the image-only instruction with empty display`() = runTest {
        coEvery { resolveSurfacePipeline(any()) } returns "share-pipe"
        val attachment = MessageAttachment(path = "img.jpg", mimeType = "image/jpeg", width = 100, height = 80)
        coEvery { attachmentStore.ingestUri("content://media/1") } returns Result.success(attachment)

        val result = launch(SharedPayload(text = null, imageUri = "content://media/1"))

        val sessionId = (result as ShareLaunchResult.Launched).sessionId
        coVerify {
            orchestrator(
                sessionId = sessionId,
                userPrompt = DefaultPrompts.IMAGE_ONLY_DEFAULT_INSTRUCTION,
                pipelineId = "share-pipe",
                attachment = attachment,
                displayContent = "",
                origin = RunOrigin.SHARE,
            )
        }
    }

    @Test
    fun `given image-only share whose ingest fails when invoked then reports NothingShared`() = runTest {
        coEvery { resolveSurfacePipeline(any()) } returns "share-pipe"
        coEvery { attachmentStore.ingestUri(any()) } returns Result.failure(IllegalStateException("bad"))

        val result = launch(SharedPayload(text = null, imageUri = "content://media/1"))

        assertTrue(result is ShareLaunchResult.NothingShared)
    }

    // ─── Session reuse (keep shares in one chat) ─────────────────────────────

    @Test
    fun `given reuse on and no existing chat when shared then creates the reserved Shared chat`() = runTest {
        every { settingsRepository.shareReuseSession } returns flowOf(true)
        coEvery { resolveSurfacePipeline(any()) } returns "share-pipe"
        coEvery { chatRepository.getSessionById(LaunchSharePipelineUseCase.SHARED_INBOX_SESSION_ID) } returns null
        val sessionSlot = slot<ChatSession>()
        coEvery { chatRepository.saveSession(capture(sessionSlot)) } returns Unit

        val result = launch(SharedPayload(text = "hello", imageUri = null))

        assertEquals(LaunchSharePipelineUseCase.SHARED_INBOX_SESSION_ID, sessionSlot.captured.id)
        assertEquals("Shared", sessionSlot.captured.name)
        assertEquals(
            LaunchSharePipelineUseCase.SHARED_INBOX_SESSION_ID,
            (result as ShareLaunchResult.Launched).sessionId,
        )
    }

    @Test
    fun `given reuse on and an existing Shared chat when shared then appends to it and preserves its binding`() =
        runTest {
            every { settingsRepository.shareReuseSession } returns flowOf(true)
            coEvery { resolveSurfacePipeline(any()) } returns "share-pipe"
            val existing = ChatSession.create(
                name = "Shared",
                pipelineId = "old-pipe",
                id = LaunchSharePipelineUseCase.SHARED_INBOX_SESSION_ID,
            )
            coEvery {
                chatRepository.getSessionById(LaunchSharePipelineUseCase.SHARED_INBOX_SESSION_ID)
            } returns existing
            val sessionSlot = slot<ChatSession>()
            coEvery { chatRepository.saveSession(capture(sessionSlot)) } returns Unit

            launch(SharedPayload(text = "another", imageUri = null))

            assertEquals(LaunchSharePipelineUseCase.SHARED_INBOX_SESSION_ID, sessionSlot.captured.id)
            assertEquals("Shared", sessionSlot.captured.name) // name preserved, not overwritten by share text
            // The chat's own binding is left untouched — not silently re-pointed.
            assertEquals("old-pipe", sessionSlot.captured.pipelineId)
            coVerify {
                // The run still uses the current share pipeline, passed explicitly.
                orchestrator(
                    sessionId = LaunchSharePipelineUseCase.SHARED_INBOX_SESSION_ID,
                    userPrompt = "another",
                    pipelineId = "share-pipe",
                    attachment = null,
                    displayContent = null,
                    origin = RunOrigin.SHARE,
                )
            }
        }

    @Test
    fun `given reuse on but the Shared chat is mid-approval when shared then spills into a fresh session`() = runTest {
        every { settingsRepository.shareReuseSession } returns flowOf(true)
        coEvery { resolveSurfacePipeline(any()) } returns "share-pipe"
        // The Shared chat is parked awaiting a HITL approval.
        coEvery {
            pendingInteractionRepository.getForSession(LaunchSharePipelineUseCase.SHARED_INBOX_SESSION_ID)
        } returns mockk<PendingInteraction>()
        val sessionSlot = slot<ChatSession>()
        coEvery { chatRepository.saveSession(capture(sessionSlot)) } returns Unit

        launch(SharedPayload(text = "urgent", imageUri = null))

        // Must NOT collide on the reserved Shared session while it awaits approval.
        assertNotEquals(LaunchSharePipelineUseCase.SHARED_INBOX_SESSION_ID, sessionSlot.captured.id)
        assertEquals("urgent", sessionSlot.captured.name)
    }

    @Test
    fun `given reuse off when two shares arrive then each opens a distinct new session`() = runTest {
        every { settingsRepository.shareReuseSession } returns flowOf(false)
        coEvery { resolveSurfacePipeline(any()) } returns "share-pipe"
        val ids = mutableListOf<String>()
        coEvery { chatRepository.saveSession(any()) } answers { ids.add(firstArg<ChatSession>().id) }

        launch(SharedPayload(text = "first", imageUri = null))
        launch(SharedPayload(text = "second", imageUri = null))

        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
        assertNotEquals(LaunchSharePipelineUseCase.SHARED_INBOX_SESSION_ID, ids[0])
    }

    private companion object {
        /** A fixed "now" for the ceiling arithmetic. */
        const val NOW = 1_800_000_000_000L
    }
}
