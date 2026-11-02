package app.knotwork.design.components.chat

/**
 * Status of a [ChatContent.ToolCall] surfaced inline in the chat surface.
 *
 * Drives the tile's left accent strip colour and the trailing glyph.
 */
enum class ToolCallStatus {
    /** Tool is currently executing. Renders an inline `KnotworkLoader` tail. */
    Running,

    /** Tool returned successfully. Renders a check glyph in `signalSuccess`. */
    Success,

    /** Tool errored. Renders the error glyph + message in `signalError`. */
    Failed,
}

/**
 * Sealed body of a [ChatMessage]. Every variant is rendered by [ChatMessage];
 * adding a new variant requires extending the `when` block there. The base
 * variants match the chat surface.
 *
 * Catalog API takes already-prepared strings (no JSON / Markdown parsing) so
 * `:catalog` stays self-contained — interpretation of JSON args, AST building
 * for Markdown, and tool-result formatting are presentation-layer concerns.
 */
sealed interface ChatContent {

    /**
     * Plain-text body. Rendered with `KnotworkTextStyles.BodyBase`.
     *
     * @property text user-visible text: the whole message, which Copy and the reader take.
     * @property preview what the bubble shows instead of a message longer than
     *   [CHAT_PREVIEW_THRESHOLD] characters, or `null` to show [text] whole.
     */
    data class Text(val text: String, val preview: ChatMessagePreview? = null) : ChatContent

    /**
     * Markdown body. Rendered as plain text in catalog v0 (no markdown
     * library dependency). The `:app` integration
     * upgrades this to a real renderer; the catalog API is forward-compatible
     * because callers already pass the raw source.
     *
     * @property source raw markdown source: the whole message, which Copy and the reader take.
     * @property preview what the bubble shows instead of a message longer than
     *   [CHAT_PREVIEW_THRESHOLD] characters, or `null` to show [source] whole.
     */
    data class Markdown(val source: String, val preview: ChatMessagePreview? = null) : ChatContent

    /**
     * HITL confirmation prompt — `HitlConfirmationCard` rendered inside the
     * assistant bubble. Typed-confirm input is hoisted to the screen via
     * [ChatMessage]'s `confirmation*` callbacks (see [ChatMessage]).
     *
     * @property model immutable payload describing the prompt.
     */
    data class Confirmation(val model: HitlConfirmationModel) : ChatContent

    /**
     * Clarification request — `ClarificationCard` rendered inside the
     * assistant bubble.
     *
     * @property model immutable payload describing the question + replies.
     */
    data class Clarification(val model: ClarificationCardModel) : ChatContent

    /**
     * Interrupted-run status card — `InterruptedRunCard` rendered inside the
     * assistant bubble when the session's most recent pipeline run died with
     * its process instead of finishing. Resume / Discard actions are hoisted
     * to the screen via [ChatMessage]'s `onRunResume` / `onRunDiscard`
     * callbacks.
     *
     * @property model immutable payload (resolved node label).
     */
    data class RunInterrupted(val model: InterruptedRunCardModel) : ChatContent

    /**
     * Run-ceiling pause card — `RunCeilingPauseCard` rendered inside the
     * assistant bubble when a run has spent one of the limits the user set for
     * it and is waiting to be told whether it may carry on. Continue / Stop
     * actions are hoisted to the screen via [ChatMessage]'s `onRunContinue` /
     * `onRunStop` callbacks.
     *
     * Its own variant rather than a state of [RunInterrupted], even though the
     * two share their chrome: an interrupted run is a fact being reported, and
     * this is a question being asked. Collapsing them would make the host
     * dispatch two different decisions through one pair of callbacks.
     *
     * @property model immutable payload (resolved copy, numbers and CTA labels).
     */
    data class RunCeilingPause(val model: RunCeilingPauseCardModel) : ChatContent

    /**
     * Error tile — the message failed at the runtime layer (LLM error,
     * network error, validation failure). Renders with a `signalError`
     * border and an optional retry CTA.
     *
     * @property message user-visible error description.
     * @property retry optional retry action; when `null`, the retry button
     * is hidden.
     */
    data class Error(val message: String, val retry: (() -> Unit)? = null) : ChatContent

    /**
     * Tool invocation surfaced inline as a compact `mono` tile. Useful for
     * keeping the user informed about side-effecting tools (file reads,
     * MCP fetches, …) without dumping raw JSON into the chat.
     *
     * @property toolName fully-qualified tool id (e.g. `"fs.read_file"`).
     * @property argsJson already-serialised arguments string.
     * @property result tool result preview; `null` while [status] is
     * [ToolCallStatus.Running].
     * @property status execution status driving the tile accent.
     */
    data class ToolCall(val toolName: String, val argsJson: String, val result: String?, val status: ToolCallStatus) :
        ChatContent

    /**
     * Image attachment on a user message — a thumbnail rendered inside the user
     * bubble, tappable to open a full-screen viewer. The image is shown
     * aspect-**fit** (never square-cropped). For an image-only message [caption]
     * is `null` and the bubble shrinks to the thumbnail.
     *
     * @property model image loader model (typically the absolute file path of
     *   the stored attachment). `null` renders the broken/missing fallback.
     * @property caption optional user caption rendered below the thumbnail.
     * @property aspectRatio width / height of the stored image, used to size the
     *   thumbnail within the bounding box; defaults to `1f` (square) when unknown.
     * @property onTap optional handler opening the full-screen viewer.
     */
    data class Image(
        val model: Any?,
        val caption: String? = null,
        val aspectRatio: Float = 1f,
        val onTap: (() -> Unit)? = null,
    ) : ChatContent
}
