package app.knotwork.design.components.pipelineeditor

/**
 * Catalog-side enumeration of the pipeline-editor node types.
 *
 * Independent of `app.knotwork.android.domain.models.NodeType` so the design
 * catalog has zero dependency on the production `:app` module. The two
 * enums are kept name-for-name aligned; callers in `:app` translate via a
 * thin mapper when wiring the editor screen.
 *
 * Order is the catalog harness layout (input / outputs first, then
 * LLM-driven, then control-flow, then post-processing). Within each
 * [NodeTypeGroup] the "Add node" sheet lists the types in this order too.
 */
enum class NodeType {
    /** Pipeline entry point. Exactly one per graph. */
    INPUT,

    /** Pipeline exit point. Exactly one per graph. */
    OUTPUT,

    /** On-device LiteRT-LM inference. */
    LITE_RT,

    /** External cloud LLM (OpenAI / Anthropic / Google / Compatible). */
    CLOUD,

    /** Multi-class intent router — one out-port per declared class. */
    INTENT_ROUTER,

    /** Boolean branch — `True` / `False` out-ports. */
    IF_CONDITION,

    /** Mid-pipeline clarification question to the user. */
    CLARIFICATION,

    /** AppFunctions / MCP tool invocation. */
    TOOL,

    /** Breaks a complex task into a list of subtasks. */
    DECOMPOSITION,

    /** Iterates a list — `Item` / `Done` out-ports. */
    QUEUE_PROCESSOR,

    /** Judges a step's result — `Pass` / `Retry` / `Fail` out-ports. */
    EVALUATION,

    /** Condenses many node outputs into one. */
    SUMMARY,

    /** Runs another saved pipeline as a nested sub-call (composition). */
    PIPELINE,

    /** Runs a reusable skill (fixed instruction + tool allowlist) as an inference step. */
    SKILL,
}
