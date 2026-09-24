package app.knotwork.android.domain.repositories

import kotlinx.coroutines.flow.StateFlow

/**
 * Records and exposes the **outbound** network activity of the app for
 * privacy-status surfaces (the More tab's footer pill).
 *
 * Distinct from [NetworkStateRepository], which reflects connectivity
 * (is there Wi-Fi / cellular?). This tracker answers the orthogonal
 * question "when did this app last actually *use* the network?".
 *
 * **Every path the app itself opens is recorded**, because the pill's words are
 * "no network calls" and anything less makes them false. Each caller records on
 * the line that sends, after its own checks have passed, and a streaming caller
 * records again as data arrives, so a transfer that lasts minutes keeps the pill
 * "online" for all of them:
 *  - cloud model calls — `CloudLlmNodeExecutor` (per frame),
 *    `KoogStructuredInferenceClient` (per frame), `DelegateTaskTool` (per frame);
 *  - memory embeddings — `CloudEmbeddingProvider`, `OllamaEmbeddingProvider`;
 *  - MCP — `KoogMcpClient.connect` and `executeTool`;
 *  - tools — `SearchTool`, `HttpRequestExecutor` (per redirect hop);
 *  - Hugging Face — `HuggingFaceModelApi` (Discover), `ResumableFileDownloader`
 *    (per chunk).
 *
 * Not recorded: crash reports (the Firebase SDK uploads on its own schedule, and
 * the app never sees that connection) and DNS / system network probes. The list
 * is held to the code by `NetworkEgressInventoryKonsistTest`: every inventoried
 * egress path either reaches [recordOutbound] or states why it does not.
 */
interface NetworkActivityTracker {
    /**
     * Latest outbound-call timestamp in epoch milliseconds, or `null` if
     * no call has been recorded since the process started.
     */
    val lastOutboundAt: StateFlow<Long?>

    /**
     * Mark "right now" as the moment of an outbound call.
     *
     * Cheap enough to call per streamed frame or per downloaded chunk: an
     * implementation may coarsen the stored time (to a second, say) so a fast
     * stream does not wake every observer on every chunk.
     */
    fun recordOutbound()
}
