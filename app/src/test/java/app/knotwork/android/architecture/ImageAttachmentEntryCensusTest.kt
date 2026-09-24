package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Census of the production files that start runs through `AgentOrchestratorUseCase`,
 * with whether each can attach an image — and, if it can, proof that it asks the
 * multimodal pre-flight (`CheckImageAttachmentUseCase`) first.
 *
 * **Why.** The pre-flight blocks an image run that would start on a cloud node,
 * or that no on-device step could read. It had one caller, the chat composer;
 * the share target attached images through the same orchestrator and never asked,
 * so a shared image was dropped without a word while the model answered about a
 * picture it never saw. A documented guard with one caller is a guard for one
 * path. This census makes a new entry that starts runs say whether it attaches
 * images, and makes an image entry show the call.
 *
 * **What it reads.** The production sources, comments removed ([ProductionSources]).
 * A [Entry.TextOnly] file must not mention an attachment at all — the word is
 * what any code handing one over would contain — and an [Entry.AttachesImages]
 * file must contain its pre-flight call. It cannot tell that the call guards the
 * very run the file starts; `LaunchSharePipelineUseCaseTest` and
 * `ChatHomeViewModelTest` pin that for the two entries there are.
 */
class ImageAttachmentEntryCensusTest {

    /** What a file that starts runs does with images. */
    private sealed interface Entry {
        /**
         * The file can start a run carrying an image.
         *
         * @property preflightCall The pre-flight call its code must contain.
         */
        data class AttachesImages(val preflightCall: String) : Entry

        /**
         * The file only ever starts text runs.
         *
         * @property reason Why, in words a reviewer can check against the file.
         */
        data class TextOnly(val reason: String) : Entry
    }

    @Test
    fun `every production file that starts runs is in the census`() {
        assertEquals(
            "the set of files using AgentOrchestratorUseCase changed. Add the new one to " +
                "ImageAttachmentEntryCensusTest.CENSUS — AttachesImages with its pre-flight call if it can hand the " +
                "orchestrator an image, TextOnly with the reason otherwise — or remove the one that is gone.",
            CENSUS.keys,
            ProductionSources.code
                .filterValues { ORCHESTRATOR.containsMatchIn(it) }
                .keys - "${BASE}domain/usecases/AgentOrchestratorUseCase.kt",
        )
    }

    @Test
    fun `every entry that attaches images asks the pre-flight`() {
        val missing = CENSUS.filterValues { it is Entry.AttachesImages }
            .filterNot { (path, entry) -> codeOf(path).contains((entry as Entry.AttachesImages).preflightCall) }
            .keys

        assertEquals(emptySet<String>(), missing)
    }

    @Test
    fun `the composer's pre-flight is the shared decision`() {
        val delegate = codeOf("${BASE}presentation/ui/chat/home/ChatHomeAttachmentDelegate.kt")

        assertTrue(
            "preflightBlockReason must delegate to CheckImageAttachmentUseCase, not decide on its own",
            delegate.contains("checkImageAttachment("),
        )
    }

    @Test
    fun `no text-only entry handles an attachment`() {
        val handling = CENSUS.filterValues { it is Entry.TextOnly }
            .filterKeys { path -> ATTACHMENT.containsMatchIn(codeOf(path)) }
            .keys

        assertEquals(
            "a file listed as text-only now handles an attachment: make it AttachesImages and call the pre-flight",
            emptySet<String>(),
            handling,
        )
    }

    private fun codeOf(path: String): String = ProductionSources.code.getValue(path)

    private companion object {
        const val BASE = "main/java/app/knotwork/android/"

        val ORCHESTRATOR = Regex("""\bAgentOrchestratorUseCase\b""")
        val ATTACHMENT = Regex("""attachment""", RegexOption.IGNORE_CASE)

        val CENSUS: Map<String, Entry> = mapOf(
            "${BASE}presentation/ui/chat/home/ChatHomeViewModel.kt" to
                Entry.AttachesImages(preflightCall = "attachments.preflightBlockReason("),
            "${BASE}domain/usecases/LaunchSharePipelineUseCase.kt" to
                Entry.AttachesImages(preflightCall = "checkImageAttachment(pipelineId)"),
            "${BASE}presentation/ui/chat/home/ChatHomeReattachDelegate.kt" to
                Entry.TextOnly("reads the pending approval of a run already in flight; it starts none"),
            "${BASE}data/services/AgentWorker.kt" to
                Entry.TextOnly("enqueueScheduled — trigger, schedule and external runs carry a prompt only"),
            "${BASE}data/services/AgentForegroundService.kt" to
                Entry.TextOnly("observes globalState to stay in the foreground; it starts no run"),
        )
    }
}
