package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Census of every place the domain stores a pipeline id, each with a decision:
 * is it a binding the Replace confirmation must list, or not, and why.
 *
 * **What it pins.** The app binds by id, not by graph. Importing with Replace
 * keeps the id, so everything bound to it runs the imported graph from then on,
 * and `FindPipelineBindingsUseCase` is what the confirmation lists. A new model
 * field or setting holding a pipeline id is a new thing that could start or
 * reach a pipeline — and the delete path's clean-up and the Replace
 * confirmation both fail open on a binding nobody listed. So a new one fails
 * here until someone decides which side it is on.
 *
 * **Which declarations.** Every property named `pipelineId` or `…PipelineId`
 * of a class in `domain.models`, and of `SettingsRepository`, the contract the
 * persisted bindings live behind. Data-layer entities mirror these and
 * presentation state derives from them, so they add nothing to decide.
 */
class PipelineBindingCensusTest {

    @Test
    fun `every stored pipeline id is either a listed binding or recorded as not one`() {
        val found = ArchitectureScope.production.classes(includeNested = true)
            .filter { it.resideInPackage("..domain.models..") }
            .flatMap { declaration ->
                declaration.properties(includeNested = false)
                    .filter { it.name.isPipelineIdName() }
                    .map { "${declaration.name}.${it.name}" }
            } + ArchitectureScope.production.interfaces()
            .filter { it.name == "SettingsRepository" }
            .flatMap { repository ->
                repository.properties().filter { it.name.isPipelineIdName() }.map { "SettingsRepository.${it.name}" }
            }

        assertEquals(
            "The set of stored pipeline ids changed. For a new one, decide whether it starts or reaches a " +
                "pipeline: if it does, count it in FindPipelineBindingsUseCase (the Replace confirmation lists " +
                "it) and check the delete path clears it; either way, record it here with the reason.",
            (BINDINGS.keys + NOT_BINDINGS.keys).sorted(),
            found.distinct().sorted(),
        )
    }

    private fun String.isPipelineIdName() = this == "pipelineId" || endsWith("PipelineId")

    private companion object {

        /** Counted by `FindPipelineBindingsUseCase`, and where. */
        val BINDINGS = mapOf(
            "SettingsRepository.defaultPipelineId" to "isDefault",
            "SettingsRepository.shareTargetPipelineId" to "surfaces, via ResolveSurfacePipelineUseCase",
            "SettingsRepository.quickSettingsTilePipelineId" to "surfaces, via ResolveSurfacePipelineUseCase",
            "SettingsRepository.externalAutomationPipelineId" to "surfaces, via ResolveSurfacePipelineUseCase",
            "ExternalAutomationBinding.pipelineId" to "a view of externalAutomationPipelineId (surfaces)",
            "Trigger.pipelineId" to "triggerCount",
            "ChatSession.pipelineId" to "chatCount",
            "NodeModel.targetPipelineId" to "callerNames",
        )

        /** Hold a pipeline id without starting or reaching that pipeline later. */
        val NOT_BINDINGS = mapOf(
            "AgentTask.pipelineId" to "one queued run; a resumed run re-checks the graph's content hash",
            "ById.pipelineId" to "an incoming request naming a pipeline, checked against the bound one",
            "OnboardingJourney.scenarioPipelineId" to "an onboarding metric",
            "PipelineRun.pipelineId" to "run history",
            "PipelineRunTally.pipelineId" to "usage statistics",
            "UsagePipelineDay.pipelineId" to "usage statistics",
            "PipelineTargetAvailability.pipelineId" to "a row of the editor's sub-pipeline picker",
            "SchemaMismatch.pipelineId" to "an import report",
            "TargetPipelineNotFound.targetPipelineId" to "a validation error",
        )
    }
}
