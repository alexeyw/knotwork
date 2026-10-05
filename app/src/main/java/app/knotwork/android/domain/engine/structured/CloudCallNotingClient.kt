package app.knotwork.android.domain.engine.structured

import app.knotwork.android.domain.engine.NodeInference

/**
 * A cloud-backed [StructuredInferenceClient] that notes each call with the
 * node's [NodeInference] before making it.
 *
 * A structured node (a router, a decomposition, a condition, a TOOL node's
 * argument step) set to a cloud provider answers through the provider's client,
 * which never reaches the on-device engine. Without a note the run's record
 * would show such a visit as one that made no model call at all; with it, a
 * later check reports the visit as answered in the cloud — not repeatable, and
 * said so.
 *
 * @property provider The provider id the client calls.
 * @property inference The node's inference, which keeps the note.
 * @property client The cloud client that does the work.
 */
class CloudCallNotingClient(
    private val provider: String,
    private val inference: NodeInference,
    private val client: StructuredInferenceClient,
) : StructuredInferenceClient {

    /**
     * Notes the call, then makes it.
     *
     * @param prompt The fully rendered prompt.
     * @param temperature The repair temperature, or `null` for the first attempt.
     * @return The provider's concatenated answer.
     */
    override suspend fun infer(prompt: String, temperature: Float?): String {
        inference.cloudCall(provider, model = null)
        return client.infer(prompt, temperature)
    }
}
