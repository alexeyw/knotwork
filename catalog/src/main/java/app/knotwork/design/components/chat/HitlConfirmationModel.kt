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
 * Catalog API takes the arguments as one already laid-out text. The catalog
 * never parses them: laying the raw LLM-emitted blob out — and showing it as
 * it came when it is not JSON — is the host's, which lays out every JSON
 * surface the same way.
 *
 * @property risk risk tier (drives card border + button row).
 * @property toolName fully-qualified tool id (e.g. `"fs.write_file"`).
 * @property summary single-line plaintext description of what the tool
 * is about to do (the LLM-generated explanation). **Blank means the caller
 * has no explanation to show**, and the card then omits the line entirely
 * rather than repeating [toolName] in its place — a card printing the same
 * tool id twice reads as a rendering fault.
 * @property arguments the call's arguments as the host laid them out, drawn
 * in the mono block as they are: collapsed to two lines, every character once
 * expanded. Whatever the text, it is what the call will run with.
 * @property timestamp pre-formatted timestamp shown next to the risk pill.
 * @property request what the run was asked to do, shown above the call;
 * `null` draws the card without the block.
 */
data class HitlConfirmationModel(
    val risk: Risk,
    val toolName: String,
    val summary: String,
    val arguments: String,
    val timestamp: String,
    val request: HitlRequestContext? = null,
)
