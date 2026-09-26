package app.knotwork.android.domain.models

/**
 * An imported pipeline whose id is already taken: it names a pipeline in the
 * library, or something is still bound to it — the id of a deleted pipeline,
 * which chats, triggers and calling pipelines keep.
 *
 * Carries what the confirmation needs to be truthful: the name of the
 * pipeline that is **already there** — not the file's own `name`, which is
 * the author's to choose — and everything bound to that id, which keeping the
 * id hands over to the imported graph.
 *
 * @property incoming The imported graph, checked and ready to persist.
 * @property existingName The library pipeline's current name, or `null` when no
 *   library pipeline holds the id and only [bindings] make it taken.
 * @property bindings What is bound to the shared id today; never empty when
 *   [existingName] is `null`.
 */
data class PipelineCollision(val incoming: PipelineGraph, val existingName: String?, val bindings: PipelineBindings)
