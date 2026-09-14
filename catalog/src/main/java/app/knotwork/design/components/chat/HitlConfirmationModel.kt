package app.knotwork.design.components.chat

import app.knotwork.design.components.chips.Risk

/**
 * Immutable payload backing a [HitlConfirmationCard] embedded inside a
 * [ChatContent.Confirmation] message.
 *
 * The card itself is stateless — typed-confirm input is hoisted to the
 * caller via [pendingTypedConfirm] / `onTypedConfirmChange`. The model
 * carries the parts that do not change with user input so the screen can
 * persist the prompt across recompositions cheaply.
 *
 * Catalog API takes arguments as `Map<String, String>` of already-rendered
 * JSON-fragment strings (e.g. `"\"path\"" to "\"/etc/hosts\""`). We
 * deliberately do not pull a JSON library into `:catalog`; serialising the
 * raw LLM-emitted `args` blob is a presentation-layer concern.
 *
 * @property risk risk tier (drives card border + button row).
 * @property toolName fully-qualified tool id (e.g. `"fs.write_file"`).
 * @property summary single-line plaintext description of what the tool
 * is about to do (the LLM-generated explanation). **Blank means the caller
 * has no explanation to show**, and the card then omits the line entirely
 * rather than repeating [toolName] in its place — a card printing the same
 * tool id twice reads as a rendering fault.
 * @property arguments key → JSON-fragment pairs rendered inside the JSON
 * args mono block (insertion order preserved by the caller).
 * @property timestamp pre-formatted timestamp shown next to the risk pill.
 */
data class HitlConfirmationModel(
    val risk: Risk,
    val toolName: String,
    val summary: String,
    val arguments: Map<String, String>,
    val timestamp: String,
)
