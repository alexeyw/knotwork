package app.knotwork.design.components.console

/**
 * What the console's value viewer shows under its header: nothing yet while the host
 * formats the value off the main thread, then the formatted lines.
 *
 * The catalog never parses a value. The host decides the [ConsoleValueFormat] and hands
 * over ready lines; the viewer only slices long ones and paints their tokens.
 */
sealed interface ConsoleValueBody {

    /** The host is still formatting the value. */
    data object Formatting : ConsoleValueBody

    /**
     * The formatted value.
     *
     * @property format how the value was read.
     * @property lines the value's lines, top to bottom. A JSON value has one key or item
     *   per line; a text value keeps its own lines, blank ones included.
     */
    data class Ready(val format: ConsoleValueFormat, val lines: List<ConsoleValueLine>) : ConsoleValueBody
}

/** How the host read a value, named in the viewer's meta line. */
enum class ConsoleValueFormat {
    /** JSON, laid out one key or item per line. */
    Json,

    /** Text, shown with its own line breaks. */
    Text,

    /** Started like JSON but did not parse — often a body cut at the HTTP tool's budget. Shown as text. */
    NotValidJson,
}

/** The kind of a [ConsoleValueSpan]; the viewer picks its colour. */
enum class ConsoleValueToken {
    /** An object key. */
    Key,

    /** A string value. */
    Str,

    /** A number. */
    Number,

    /** `true`, `false` or `null`. */
    Literal,

    /** Braces, brackets, colons and commas. */
    Punctuation,
}

/**
 * A run of one [ConsoleValueLine] with a token kind.
 *
 * @property start index of the first character, inclusive.
 * @property end index after the last character, exclusive.
 * @property token what the run is.
 */
data class ConsoleValueSpan(val start: Int, val end: Int, val token: ConsoleValueToken)

/**
 * One line of a formatted value.
 *
 * @property indent nesting level; the viewer pads by two characters per level, capped.
 * @property text the line without its indent.
 * @property spans token runs over [text]; text outside every span is drawn plain.
 */
data class ConsoleValueLine(val indent: Int, val text: String, val spans: List<ConsoleValueSpan> = emptyList())

/**
 * The value viewer's state: the row it was opened on, as it was then, and its body.
 *
 * @property row the Vars row — key, node and the whole value.
 * @property body the formatted value, or [ConsoleValueBody.Formatting].
 */
data class ConsoleValueViewerState(val row: ConsoleVarRow, val body: ConsoleValueBody)
