package app.knotwork.android.presentation.ui.orchestrator

import app.knotwork.android.R
import app.knotwork.android.domain.models.EntrySurface
import app.knotwork.android.domain.models.PipelineBindings
import app.knotwork.android.domain.models.PipelineCollision
import app.knotwork.android.domain.text.toDisplaySafe
import app.knotwork.android.presentation.ui.common.UiText

/**
 * The text the Replace confirmation shows for what is bound to a pipeline.
 *
 * Pure, so the wording decision is testable without a composition. Names come
 * from the library, which older imports could fill with anything, so they are
 * made display-safe here.
 */
internal object PipelineBindingsText {

    /**
     * One line per binding, in the order a reader weighs them: what runs the
     * pipeline without asking (the default, the entry surfaces, triggers)
     * before the chats and the pipelines that call it.
     *
     * @param bindings What is bound to the pipeline.
     * @return The lines, empty when nothing is bound.
     */
    fun lines(bindings: PipelineBindings): List<UiText> = buildList {
        if (bindings.isDefault) add(UiText(R.string.orchestrator_library_binding_default))
        // `EntrySurface.entries` order, so the list reads the same every time.
        EntrySurface.entries.filter { it in bindings.surfaces }.forEach { add(UiText(it.bindingLabel())) }
        if (bindings.triggerCount > 0) {
            add(
                UiText.Plural(
                    R.plurals.orchestrator_library_binding_triggers,
                    bindings.triggerCount,
                    listOf(bindings.triggerCount),
                ),
            )
        }
        if (bindings.chatCount > 0) {
            add(
                UiText.Plural(
                    R.plurals.orchestrator_library_binding_chats,
                    bindings.chatCount,
                    listOf(bindings.chatCount),
                ),
            )
        }
        bindings.callerNames.forEach {
            add(UiText.of(R.string.orchestrator_library_binding_caller, it.toDisplaySafe()))
        }
    }

    /**
     * One line of the bundle confirmation: the library pipeline's name, then
     * what runs it, if anything.
     *
     * @param collision A bundle pipeline whose id is already taken.
     * @return `“Name”`, or `“Name” — line; line; …`; for an id only a deleted
     *   pipeline's bindings hold, the file's name marked as such.
     */
    fun summaryLine(collision: PipelineCollision): UiText {
        val name = collision.existingName?.let {
            UiText.of(R.string.orchestrator_library_import_bundle_collision_item, it.toDisplaySafe())
        } ?: UiText.of(R.string.orchestrator_library_import_bundle_orphan_item, collision.incoming.name.toDisplaySafe())
        val bound = lines(collision.bindings)
        return if (bound.isEmpty()) name else UiText.Joined(listOf(name, UiText.Joined(bound, "; ")), " — ")
    }

    /**
     * The label for a surface a pipeline is bound to. Exhaustive on purpose: a
     * new [EntrySurface] does not compile until someone decides what the
     * Replace confirmation calls it.
     */
    private fun EntrySurface.bindingLabel(): Int = when (this) {
        EntrySurface.SHARE -> R.string.orchestrator_library_binding_share
        EntrySurface.QUICK_TILE -> R.string.orchestrator_library_binding_tile
        EntrySurface.EXTERNAL_AUTOMATION -> R.string.orchestrator_library_binding_external
    }
}
