package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.engine.DefaultPipelineFactory
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunTerminationReason
import app.knotwork.android.domain.repositories.AppStateSettings
import app.knotwork.android.domain.repositories.EntryPointSettings
import app.knotwork.android.domain.repositories.GenerationSettings
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Use case executed when the application is launched.
 * It checks if this is the first launch, and if so, initializes default settings,
 * such as saving the default system prompts to the settings repository,
 * and materialises the bundled showcase pipeline as the application default.
 *
 * On **every** invocation (not just the first launch) it additionally sweeps
 * orphaned pipeline-run records: every non-terminal run whose owning process
 * has died is finalised as [PipelineRunStatus.INTERRUPTED]. Orphan detection
 * is ownership-based, not status-based — the repository excludes runs created
 * by the live process — so re-running the sweep (Activity recreation, splash
 * retry) can never interrupt a run that is still executing in this process
 * (e.g. kept alive by the foreground service or a WorkManager worker).
 * WAITING_* runs of dead processes are swept only while their wait was still
 * in its live in-process phase (in-memory deferreds that died with the
 * process). A run parked on a persistent pending-interaction record is
 * exempt: it is waiting by design — the user's response or the
 * approval-window expiry settles it, not the sweep. The sweep is best-effort
 * by the repository contract: a damaged run store degrades to an empty sweep
 * instead of failing app initialization.
 */
class InitializeAppUseCase @Inject constructor(
    private val appStateSettings: AppStateSettings,
    private val entryPointSettings: EntryPointSettings,
    private val generationSettings: GenerationSettings,
    private val pipelineRepository: PipelineRepository,
    private val loadPipelineFromPresetUseCase: LoadPipelineFromPresetUseCase,
    private val pipelineRunRepository: PipelineRunRepository,
    private val pendingInteractionRepository: PendingInteractionRepository,
    private val seedBundledSkillsUseCase: SeedBundledSkillsUseCase,
) {
    /**
     * Executes the initialization logic.
     *
     * On the very first launch the seed pipeline is materialised from the
     * bundled [SHOWCASE_PRESET_ID] preset via [LoadPipelineFromPresetUseCase]
     * (fresh node / connection ids, validated, persisted). This gives the new
     * user a pipeline that exercises the full breadth of the agent — intent
     * routing, a local/cloud fork, task decomposition, a tool call, a
     * human-in-the-loop clarification, summarisation and evaluation — instead
     * of a minimal stub. If the preset cannot be loaded (e.g. a corrupt or
     * missing asset) the seed falls back to the code-level
     * [DefaultPipelineFactory] so first launch never leaves the library empty.
     */
    suspend operator fun invoke() {
        sweepOrphanedRuns()

        // Idempotently seed the bundled skills on every launch (not gated on
        // first launch) so users upgrading from a build without skills still
        // receive the bundled catalogue. Upsert-by-stable-id makes repeats safe.
        seedBundledSkillsUseCase()

        val isFirstLaunch = appStateSettings.isFirstLaunch.first()

        if (isFirstLaunch) {
            // Save the default prompt to settings so the user can edit it later.
            generationSettings.setSystemPromptPrefix(DefaultPrompts.SYSTEM_PROMPT_PREFIX)

            // Materialise the bundled showcase pipeline as the seed; fall back
            // to the code-level factory if the preset asset is unavailable.
            val seededPipelineId = loadPipelineFromPresetUseCase(SHOWCASE_PRESET_ID)
                .getOrNull()
                ?: seedFallbackPipeline()

            // Mark the seeded pipeline as the application default so the
            // chat surfaces ("Use default pipeline (…)" label, TopAppBar
            // subtitle) show a concrete name from the very first launch
            // instead of relying on the implicit "first in library" fallback.
            entryPointSettings.setDefaultPipelineId(seededPipelineId)

            // Mark first launch as complete
            appStateSettings.setFirstLaunch(false)
        }
    }

    /**
     * Finalises every run record orphaned by a process death: non-terminal
     * records not owned by the live process are moved to
     * [PipelineRunStatus.INTERRUPTED] with [ORPHANED_RUN_MESSAGE] as the
     * reason. Idempotent and safe to call at any time — ownership filtering
     * happens in the repository, and both calls are best-effort by its
     * contract (failures log and degrade, never abort initialization).
     */
    private suspend fun sweepOrphanedRuns() {
        // Runs parked on a persistent pending-interaction record are not
        // orphans: their machinery is *supposed* to be gone — the user's
        // background response (or the approval-window expiry pass) is what
        // settles them.
        val parkedRunIds = pendingInteractionRepository.getAllRunIds()
        pipelineRunRepository.getOrphanedRuns()
            .filterNot { it.id in parkedRunIds }
            .forEach { run ->
                pipelineRunRepository.finishRun(
                    run.id,
                    PipelineRunStatus.INTERRUPTED,
                    ORPHANED_RUN_MESSAGE,
                    RunTerminationReason.ProcessDied,
                )
            }
    }

    /**
     * Builds and persists the code-level default pipeline, returning its id.
     * Used only when the bundled showcase preset fails to materialise.
     */
    private suspend fun seedFallbackPipeline(): String {
        val defaultPipeline = DefaultPipelineFactory.create("Default System Pipeline")
        pipelineRepository.savePipeline(defaultPipeline)
        return defaultPipeline.id
    }

    private companion object {
        /**
         * Stable id of the bundled showcase preset
         * (`assets/presets/pipelines/showcase_full_agent.json`) materialised as
         * the first-launch seed pipeline.
         */
        const val SHOWCASE_PRESET_ID = "showcase_full_agent"

        /**
         * Reason written into run records finalised by the orphan sweep —
         * their owning process died before the run could finish.
         */
        const val ORPHANED_RUN_MESSAGE = "Process terminated during execution"
    }
}
