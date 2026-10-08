package app.knotwork.android.domain.engine.structured

import app.knotwork.android.domain.models.CloudProvider

/**
 * Domain-level factory that builds a cloud-backed [StructuredInferenceClient]
 * for the [StructuredOutputGate].
 *
 * It is the cloud counterpart of the local
 * [EngineStructuredInferenceClient]: structured-output consumers
 * (INTENT_ROUTER / DECOMPOSITION / EVALUATION / IF_CONDITION / TOOL) pick the
 * cloud path when their node carries a [CloudProvider] (the same
 * `node.cloudProvider` selector the SKILL node uses to choose its engine), and
 * fall back to the local engine otherwise.
 *
 * The concrete implementation lives in the data layer (`KoogStructuredInference
 * ClientFactory`) and bridges to the Koog cloud client (retry-wrapped). Keeping
 * this seam in domain lets the consumers stay free of `data.*` / Koog imports.
 */
fun interface CloudStructuredInferenceClientFactory {

    /**
     * Builds a cloud structured-inference client for [provider].
     *
     * @param provider The cloud provider the node selected.
     * @param onToken Invoked with each streamed token so the executor can keep
     *   showing a live "Thinking" state, mirroring the local client's hook.
     * @return The client, or `null` when the provider has no configured
     *   credentials (the caller then surfaces an error or falls back).
     */
    suspend fun create(provider: CloudProvider, onToken: suspend (String) -> Unit): StructuredInferenceClient?
}
