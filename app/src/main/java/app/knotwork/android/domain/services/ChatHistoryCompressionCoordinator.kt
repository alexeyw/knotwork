package app.knotwork.android.domain.services

import app.knotwork.android.domain.constants.TimeAndIdConstants
import app.knotwork.android.domain.engine.TaskQueueManager
import app.knotwork.android.domain.models.isBusy
import app.knotwork.android.domain.repositories.MemorySettings
import app.knotwork.android.domain.usecases.CompressChatHistoryUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped listener that kicks off background chat-history compression
 * after a pipeline run finishes.
 *
 * The chat layer calls [onPipelineCompleted] when it observes the terminal
 * `Completed` orchestrator state. Per session, this coordinator then:
 *  - **debounces** by [DEBOUNCE_MS] — back-to-back completions for the same
 *    session collapse into a single compression once the conversation goes
 *    quiet, instead of re-running the model every turn;
 *  - **short-circuits** when the `chatHistoryCompressionEnabled` setting is off;
 *  - **defers while the agent is busy** — compression runs the local model, and
 *    [app.knotwork.android.domain.engine.LlmInferenceEngine] allows only one
 *    active conversation. While [TaskQueueManager.globalState] shows an active
 *    pipeline, the coordinator waits another debounce window and re-checks
 *    rather than racing it;
 *  - delegates the pass itself to [CompressChatHistoryUseCase].
 *
 * It owns its own [CoroutineScope] (a [SupervisorJob] on [Dispatchers.Default])
 * so an in-flight compression survives the chat `ViewModel` being cleared — the
 * pass is fire-and-forget background work, not tied to any screen's lifecycle.
 * The scope is overridable (via [scope]) so tests can drive it with a test
 * dispatcher and virtual time.
 *
 * This mirrors [MemoryAutoExtractionCoordinator]: both are debounced,
 * agent-busy-gated background passes over the shared inference engine, kept as
 * separate coordinators so each owns its own toggle and debounce lifecycle.
 *
 * @property memorySettings Source of the compression toggle.
 * @property compressChatHistoryUseCase The compression pass itself.
 * @property taskQueueManager Source of the agent-busy signal
 *   ([TaskQueueManager.globalState]) used to defer compression while a
 *   foreground pipeline is generating on the shared inference engine.
 */
@Singleton
class ChatHistoryCompressionCoordinator @Inject constructor(
    private val memorySettings: MemorySettings,
    private val compressChatHistoryUseCase: CompressChatHistoryUseCase,
    private val taskQueueManager: TaskQueueManager,
) {

    /**
     * Scope the debounced compression jobs run in. Defaults to an
     * application-lifetime supervisor scope; tests replace it with a test scope
     * to control timing deterministically.
     */
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** In-flight (debouncing) compression job per session, so a newer completion can cancel the older. */
    private val pendingJobs = ConcurrentHashMap<String, Job>()

    /**
     * Schedules a debounced compression pass for [sessionId].
     *
     * Safe to call on every `Completed` state: a fresh call cancels any
     * still-debouncing job for the same session and restarts the timer, so only
     * the last completion in a burst triggers compression. No-op for a blank id.
     *
     * @param sessionId The chat session whose conversation just completed.
     */
    fun onPipelineCompleted(sessionId: String) {
        if (sessionId.isBlank()) return

        pendingJobs[sessionId]?.cancel()
        pendingJobs[sessionId] = scope.launch {
            try {
                // Wait for the conversation to go quiet, then make sure no
                // foreground generation is in flight before touching the shared
                // engine. While a pipeline is active, re-wait a debounce window
                // and re-check instead of racing it — a new completion cancels
                // and replaces this job, so the loop never outlives its session.
                while (true) {
                    delay(DEBOUNCE_MS)
                    // Read the toggle at compression time so a user flipping it
                    // off during the debounce window still cancels the work.
                    if (!memorySettings.chatHistoryCompressionEnabled.first()) return@launch
                    if (taskQueueManager.globalState.value.isBusy) continue

                    compressChatHistoryUseCase(sessionId)
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Chat-history compression pass failed for session %s", sessionId)
            } finally {
                // Remove only if the map still points at *this* job — a newer
                // completion may have already replaced it, and a cancelled older
                // job must not evict its successor's entry (which would defeat
                // the debounce dedup).
                pendingJobs.remove(sessionId, coroutineContext[Job])
            }
        }
    }

    private companion object {
        const val TAG = "HistoryCompression"

        /**
         * Quiet period after a completion before compression runs. 30 s matches
         * [MemoryAutoExtractionCoordinator]: long enough to coalesce a multi-turn
         * burst, short enough that the summary lands before the model is unloaded
         * by `AgentIdleManager` (5 min idle).
         */
        const val DEBOUNCE_MS: Long = 30L * TimeAndIdConstants.MS_PER_SECOND
    }
}
