package app.knotwork.android.domain.models

/**
 * Everything bound to one pipeline id: what runs that pipeline without the
 * user choosing it again.
 *
 * The app binds by **id**, not by graph. Deleting a pipeline clears the
 * default and the entry surfaces and switches its triggers off, but triggers,
 * chats and calling pipelines keep the id. Replacing a pipeline on import keeps
 * the id — and so keeps every binding, pointed at the imported graph from then
 * on — and so does importing a file under a deleted pipeline's id. This is what
 * the import confirmation lists, so the user agrees to it knowingly.
 *
 * @property isDefault The pipeline is the app-wide default — what a chat with
 *   no pipeline of its own runs.
 * @property surfaces The entry surfaces bound to it (share target, Quick
 *   Settings tile, requests from other apps).
 * @property triggerCount Automation triggers bound to it, enabled or not.
 * @property chatCount Chats (archived included) bound to it.
 * @property callerNames Names of saved pipelines that run it through a
 *   `PIPELINE` node, in library order.
 */
data class PipelineBindings(
    val isDefault: Boolean = false,
    val surfaces: Set<EntrySurface> = emptySet(),
    val triggerCount: Int = 0,
    val chatCount: Int = 0,
    val callerNames: List<String> = emptyList(),
) {
    /** `true` when nothing is bound to the pipeline. */
    val isEmpty: Boolean
        get() = !isDefault && surfaces.isEmpty() && triggerCount == 0 && chatCount == 0 && callerNames.isEmpty()
}
