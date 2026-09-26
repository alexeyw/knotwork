package app.knotwork.android.domain.services

/**
 * Whether a scheduled agent task fires once or on a repeating interval.
 *
 * The names are written into the background runtime's tag and read back by the
 * task monitor, so they must never be renamed.
 */
enum class ScheduledTaskKind {
    /** Fires once, after an optional delay. */
    ONE_TIME,

    /** Fires repeatedly on a fixed interval. */
    PERIODIC,
}

/**
 * What a scheduled task is, recovered from the tag its background work carries.
 *
 * @property kind One-shot or repeating.
 * @property intervalHours Repeat interval for a [ScheduledTaskKind.PERIODIC]
 *   task; `0` for a one-time one.
 * @property sessionId Id of the chat session the run lands its result in, or
 *   `null` when the task was scheduled without a bound session (the worker then
 *   creates a fresh one per run).
 * @property promptId Id of the task's prompt in the encrypted store
 *   ([app.knotwork.android.domain.repositories.BackgroundPromptRepository]), or
 *   `null` for a task scheduled by a release that wrote the preview itself.
 * @property promptPreview What to show for the prompt: the first
 *   [ScheduledTaskTag.PROMPT_PREVIEW_MAX_CHARS] characters, whitespace-collapsed —
 *   enough to recognise the task, never the whole instruction. Empty until the
 *   reader resolves [promptId] (see [ScheduledTaskTag.preview]).
 */
data class ScheduledTaskLabel(
    val kind: ScheduledTaskKind,
    val intervalHours: Long,
    val sessionId: String?,
    val promptId: String? = null,
    val promptPreview: String = "",
)

/**
 * The tag vocabulary that makes a scheduled agent task recognisable — and
 * therefore cancellable — after it has been handed to the background runtime.
 *
 * **Why a tag at all.** The runtime does not expose a queued task's input data,
 * so nothing about a pending task (its prompt, its schedule, the chat it belongs
 * to) can be recovered from the queue itself. Before this existed, every
 * scheduled task showed up as an anonymous "Background Task", which made
 * cancelling *the right one* guesswork and cancelling *all* of them impossible
 * without also killing trigger and Quick-Settings runs. A runaway task that
 * re-scheduled itself therefore had no off switch short of clearing the app's
 * data.
 *
 * Two tags are attached per task: [MARKER], the flat marker that scopes
 * "cancel every scheduled task" to exactly the tasks this tool created, and the
 * [encode]d label carrying the human-readable detail.
 *
 * **What goes in the label.** Only what the user needs to choose between rows —
 * kind, interval, bound session — and the **id** of the prompt, never its text.
 * The runtime stores tags in the clear, like its input data, so the prompt lives
 * in the encrypted database and the monitor looks its preview up by id. Labels
 * written by earlier releases (`kst1`) carried a preview of their own; they are
 * still read, so a task scheduled before the change stays recognisable.
 *
 * Pure string handling with no framework types, so the writer (the scheduler,
 * in `data`) and the reader (the task monitor, in `presentation`) share one
 * vocabulary without either depending on the other.
 */
object ScheduledTaskTag {

    /**
     * Flat marker present on every task scheduled by the `schedule_task` tool.
     * Cancelling by this tag settles exactly those tasks and leaves trigger,
     * Quick-Settings and model-download work untouched.
     */
    const val MARKER: String = "knotwork-scheduled-task"

    /** Maximum prompt characters kept in the label. */
    const val PROMPT_PREVIEW_MAX_CHARS: Int = 80

    /** Version prefix of the current label, carrying a prompt id. */
    private const val PREFIX = "kst2"

    /** Version prefix of a label written before prompts left the runtime, carrying a preview. */
    private const val LEGACY_PREFIX = "kst1"

    /** Field separator. Safe because the last field (an id, or a legacy preview) is read whole. */
    private const val SEPARATOR = '|'

    /** Number of fields in an encoded label. */
    private const val FIELD_COUNT = 5

    /**
     * Encodes one task's label into a single tag string.
     *
     * @param kind One-shot or repeating.
     * @param intervalHours Repeat interval in hours; `0` for a one-time task.
     * @param sessionId Bound chat session, or `null`.
     * @param promptId Id of the task's prompt in the encrypted store.
     * @return The tag to attach to the scheduled work.
     */
    fun encode(kind: ScheduledTaskKind, intervalHours: Long, sessionId: String?, promptId: String): String = listOf(
        PREFIX,
        kind.name,
        intervalHours.toString(),
        sessionId.orEmpty(),
        promptId,
    ).joinToString(SEPARATOR.toString())

    /**
     * Recovers the label from the tag set of one scheduled work item.
     *
     * Tolerant by design: an unrecognised, truncated or future-format tag yields
     * `null` and the caller falls back to a generic row rather than hiding the
     * task — an unlabelled task the user can still cancel beats no task at all.
     *
     * @param tags All tags carried by the work item.
     * @return The decoded label, or `null` when none of [tags] is a readable one.
     */
    fun parse(tags: Collection<String>): ScheduledTaskLabel? {
        val raw = tags.firstOrNull { it.startsWith("$PREFIX$SEPARATOR") || it.startsWith("$LEGACY_PREFIX$SEPARATOR") }
            ?: return null
        val fields = raw.split(SEPARATOR, limit = FIELD_COUNT)
        if (fields.size < FIELD_COUNT) return null
        val kind = ScheduledTaskKind.entries.firstOrNull { it.name == fields[1] } ?: return null
        val intervalHours = fields[2].toLongOrNull() ?: return null
        val legacy = fields[0] == LEGACY_PREFIX
        return ScheduledTaskLabel(
            kind = kind,
            intervalHours = intervalHours,
            sessionId = fields[3].ifEmpty { null },
            promptId = fields[4].takeUnless { legacy },
            promptPreview = if (legacy) fields[4] else "",
        )
    }

    /**
     * Collapses [prompt] to a single line and truncates it to
     * [PROMPT_PREVIEW_MAX_CHARS], appending an ellipsis when it was cut — what a
     * task row shows of a prompt it looked up by [ScheduledTaskLabel.promptId].
     *
     * @param prompt The full prompt.
     * @return Its preview.
     */
    fun preview(prompt: String): String {
        val collapsed = prompt.replace(WHITESPACE_RUN, " ").trim()
        return if (collapsed.length <= PROMPT_PREVIEW_MAX_CHARS) {
            collapsed
        } else {
            collapsed.take(PROMPT_PREVIEW_MAX_CHARS).trimEnd() + "…"
        }
    }

    private val WHITESPACE_RUN = Regex("\\s+")
}
