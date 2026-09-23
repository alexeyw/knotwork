package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.EntrySurface
import app.knotwork.android.domain.models.PipelineBindings
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.TriggerRepository
import app.knotwork.android.domain.services.findDependentPipelines
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Lists what is bound to pipeline ids: the default pipeline, the entry
 * surfaces, automation triggers, chats and the pipelines that call it.
 *
 * The single place that enumerates id-keyed bindings, so a confirmation that
 * re-points an id (import with Replace) and any later verb that needs the same
 * answer read one list. Surfaces are read through [ResolveSurfacePipelineUseCase]
 * over `EntrySurface.entries`, so a new surface is covered without a change
 * here; `PipelineBindingCensusTest` fails when a new id-keyed binding appears
 * that this use case does not account for.
 */
class FindPipelineBindingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val resolveSurfacePipelineUseCase: ResolveSurfacePipelineUseCase,
    private val triggerRepository: TriggerRepository,
    private val chatRepository: ChatRepository,
    private val pipelineRepository: PipelineRepository,
) {

    /**
     * Returns the bindings of every id in [pipelineIds], reading each source
     * once however many ids are asked about.
     *
     * @param pipelineIds The pipeline ids to look up.
     * @return One [PipelineBindings] per requested id (empty when nothing is bound).
     */
    suspend operator fun invoke(pipelineIds: Collection<String>): Map<String, PipelineBindings> {
        if (pipelineIds.isEmpty()) return emptyMap()
        val defaultId = settingsRepository.defaultPipelineId.first()
        val surfaceIds = EntrySurface.entries.associateWith { resolveSurfacePipelineUseCase(it) }
        val triggers = triggerRepository.observeTriggers().first()
        val chats = chatRepository.getSessionsFlow(includeArchived = true).first()
        val library = pipelineRepository.getAllPipelines().first()
        return pipelineIds.associateWith { id ->
            PipelineBindings(
                isDefault = defaultId == id,
                surfaces = surfaceIds.filterValues { it == id }.keys,
                triggerCount = triggers.count { it.pipelineId == id },
                chatCount = chats.count { it.pipelineId == id },
                callerNames = findDependentPipelines(id, library).map { it.name },
            )
        }
    }

    /**
     * Returns the bindings of one pipeline id.
     *
     * @param pipelineId The pipeline id to look up.
     * @return Its bindings (empty when nothing is bound).
     */
    suspend fun of(pipelineId: String): PipelineBindings = invoke(listOf(pipelineId)).getValue(pipelineId)
}
