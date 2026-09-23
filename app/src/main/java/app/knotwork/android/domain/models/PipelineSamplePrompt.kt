package app.knotwork.android.domain.models

/**
 * A single starter ("quick action") prompt suggested by a pipeline and shown on
 * the new-chat empty state. Because the suggestion is declared by the pipeline
 * that will actually run it, the [toolsHint] can advertise the tools that
 * pipeline wires — unlike a static, pipeline-agnostic suggestion which could
 * promise tools the active pipeline does not have.
 *
 * That holds for a pipeline the user built or the app ships. For an imported
 * file the declaration is the file author's, so on import the hint keeps only
 * the tools a TOOL node of the same graph calls
 * (`domain/pipelineio/ImportedPipelineClaims`).
 *
 * Tapping the card fills the composer with [title]; the message is not sent
 * automatically.
 *
 * @property title The prompt text — both the card headline and the value placed
 *   into the composer when the card is tapped.
 * @property toolsHint Optional comma-separated hint of the tools this prompt
 *   would exercise (rendered as the card's `uses · …` subtitle), or `null` to
 *   render the card without a subtitle.
 */
data class PipelineSamplePrompt(val title: String, val toolsHint: String? = null)
