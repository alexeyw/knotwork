package app.knotwork.android.domain.models

/**
 * Why a use case refused what it was asked to do with the user's input — a name
 * that does not fit, a prompt left blank, an id that names nothing.
 *
 * A value, not a sentence: the screen words it in the interface language. The
 * exception that carries it is [InvalidInputException].
 */
sealed interface InputProblem {

    /** What the input names. */
    enum class Subject {
        /** A pipeline in the library. */
        PIPELINE,

        /** A pipeline or prompt preset. */
        PRESET,

        /** A prompt preset, looked up by id. */
        PROMPT_PRESET,

        /** A first-run scenario. */
        SCENARIO,
    }

    /**
     * A name is empty or blank.
     *
     * @property subject What the name is for.
     */
    data class BlankName(val subject: Subject) : InputProblem

    /**
     * A name is longer than the limit.
     *
     * @property subject What the name is for.
     * @property maxLength The longest name accepted.
     */
    data class NameTooLong(val subject: Subject, val maxLength: Int) : InputProblem

    /** A prompt preset's system prompt is empty or blank. */
    data object BlankSystemPrompt : InputProblem

    /**
     * A prompt preset's system prompt is longer than the limit.
     *
     * @property maxLength The longest prompt accepted.
     */
    data class SystemPromptTooLong(val maxLength: Int) : InputProblem

    /**
     * A prompt preset names a node type that takes no prompt.
     *
     * @property nodeType The type's code, e.g. `INPUT`.
     */
    data class NoPromptForNodeType(val nodeType: String) : InputProblem

    /**
     * An id names nothing that exists.
     *
     * @property subject What the id was meant to name.
     * @property id The id, when the sentence names it.
     */
    data class NotFound(val subject: Subject, val id: String? = null) : InputProblem
}

/**
 * A use case refused its input; [problem] says why, for the screen to word.
 *
 * An [IllegalArgumentException], as the refusals it replaces were, so a caller
 * that treats a bad argument as a field-level error keeps doing so. Its message
 * is [problem]'s description, for logs only.
 *
 * @property problem Why the input was refused.
 */
class InvalidInputException(val problem: InputProblem) : IllegalArgumentException(problem.toString())
