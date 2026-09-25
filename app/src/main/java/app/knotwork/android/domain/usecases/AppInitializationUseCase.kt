package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.DbPassphraseUnavailableException
import app.knotwork.android.domain.models.InitFailureKind
import app.knotwork.android.domain.models.InitProgress
import app.knotwork.android.domain.models.InitStage
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import javax.inject.Inject

/**
 * Orchestrates the application's cold-start initialization. Replaces the
 * fire-and-forget `lifecycleScope.launch { initializeAppUseCase() }` block in
 * `MainActivity` with a strictly-ordered sequence whose progress is exposed
 * to the splash screen via [InitProgress] emissions.
 *
 * Order of work (also reflected in [TOTAL_STEPS]):
 *  1. [InitStage.Initializing] — first-launch defaults via [InitializeAppUseCase].
 *  2. [InitStage.FindingModels] — register again the downloaded model files the
 *     registry lost (see [RediscoverDownloadedModelsUseCase]).
 *  3. [InitStage.LoadingPipelines] — pre-warm Room cache for pipelines.
 *  4. [InitStage.LoadingChats] — pre-warm Room cache for chat sessions.
 *  5. [InitStage.LoadingMemory] — pre-warm Room cache for memory chunks.
 *
 * Failure policy:
 *  - Stages that prepare the database ([InitStage.Initializing] and the three
 *    Room prefetches) are treated as fatal. If any throws, the use case emits
 *    [InitStage.Failed] with the failed stage and the throwable's message;
 *    the splash screen surfaces the error and offers a retry.
 *  - [InitStage.FindingModels] is **non-fatal** — a model the pass could not
 *    register is still one download away, and the rest of the app works
 *    without it. Logging the failure is enough for the splash to proceed.
 *
 * **The model is not loaded here.** Every path that runs the model loads it on
 * demand — the chat's send, and each pipeline step through `LoadModelUseCase` —
 * because the engine is unloaded when the agent sits idle and under memory
 * pressure anyway. Loading it on the splash only kept the user waiting, and held
 * the weights in memory until the idle unload whether or not a message was sent.
 */
class AppInitializationUseCase @Inject constructor(
    private val initializeAppUseCase: InitializeAppUseCase,
    private val rediscoverDownloadedModels: RediscoverDownloadedModelsUseCase,
    private val pipelineRepository: PipelineRepository,
    private val chatRepository: ChatRepository,
    private val memoryRepository: MemoryRepository,
) {

    /**
     * Streams [InitProgress] snapshots for every stage transition. Always emits
     * exactly one terminal element ([InitStage.Done] on success or
     * [InitStage.Failed] on the first fatal failure). Callers should observe
     * the flow on a `viewModelScope` and trigger navigation off the terminal
     * value.
     */
    operator fun invoke(): Flow<InitProgress> = flow {
        emit(progress(InitStage.Initializing, "Preparing application…", completed = 0))
        try {
            initializeAppUseCase()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "InitializeAppUseCase failed")
            emit(failure(InitStage.Initializing, e))
            return@flow
        }

        emit(progress(InitStage.FindingModels, "Checking downloaded models…", completed = 1))
        try {
            rediscoverDownloadedModels()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Non-fatal: an unregistered model stays one download away.
            Timber.tag(TAG).w(e, "Downloaded-model rediscovery failed; continuing")
        }

        emit(progress(InitStage.LoadingPipelines, "Reading pipelines…", completed = 2))
        try {
            pipelineRepository.getAllPipelines().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Pipeline prefetch failed")
            emit(failure(InitStage.LoadingPipelines, e))
            return@flow
        }

        emit(progress(InitStage.LoadingChats, "Reading chats…", completed = 3))
        try {
            chatRepository.getSessionsFlow().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Chat sessions prefetch failed")
            emit(failure(InitStage.LoadingChats, e))
            return@flow
        }

        emit(progress(InitStage.LoadingMemory, "Reading memory…", completed = 4))
        try {
            memoryRepository.getRecentMemorySummaries(MEMORY_PREFETCH_LIMIT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Memory prefetch failed")
            emit(failure(InitStage.LoadingMemory, e))
            return@flow
        }

        emit(InitProgress(InitStage.Done, "Ready", completedSteps = TOTAL_STEPS, totalSteps = TOTAL_STEPS))
    }

    private fun progress(stage: InitStage, message: String, completed: Int): InitProgress =
        InitProgress(stage = stage, message = message, completedSteps = completed, totalSteps = TOTAL_STEPS)

    private fun failure(stage: InitStage, cause: Throwable): InitProgress = InitProgress(
        stage = InitStage.Failed(
            cause = cause.localizedMessage ?: cause.javaClass.simpleName,
            failedStage = stage,
            failureKind = classifyFailure(cause),
        ),
        message = cause.localizedMessage ?: "Initialization failed",
        completedSteps = 0,
        totalSteps = TOTAL_STEPS,
    )

    /**
     * Walks the cause chain looking for [DbPassphraseUnavailableException]. Room and the
     * SQLite open-helper stack may wrap the original throwable, so a direct type check on
     * the caught exception is not enough.
     */
    private fun classifyFailure(cause: Throwable): InitFailureKind {
        val isPassphraseUnavailable = generateSequence(cause) { it.cause }
            .take(MAX_CAUSE_CHAIN_DEPTH)
            .any { it is DbPassphraseUnavailableException }
        return if (isPassphraseUnavailable) InitFailureKind.DB_PASSPHRASE_UNAVAILABLE else InitFailureKind.GENERIC
    }

    private companion object {
        const val TAG = "AppInit"
        const val TOTAL_STEPS = 5
        const val MEMORY_PREFETCH_LIMIT = 10

        /** Defensive bound for cause-chain traversal in case of cyclic causes. */
        const val MAX_CAUSE_CHAIN_DEPTH = 20
    }
}
