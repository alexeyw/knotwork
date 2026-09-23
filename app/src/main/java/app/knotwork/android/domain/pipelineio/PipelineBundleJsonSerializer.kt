package app.knotwork.android.domain.pipelineio

import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineBundleImportOutcome
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineImportOutcome
import app.knotwork.android.domain.text.toDisplaySafe
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Two-way mapper between a list of [PipelineGraph]s and the schema-versioned
 * **bundle** JSON envelope used to move a composite pipeline (a root plus the
 * transitive closure of its `PIPELINE`-node dependencies) between the browser
 * editor and the app in a single file.
 *
 * The bundle is a thin envelope that **delegates** every element to
 * [PipelineJsonSerializer] — exactly as [PipelinePresetJsonSerializer]
 * delegates the graph half of a preset — so the single-pipeline schema is
 * never mirrored here and stays the single source of truth.
 *
 * ### Schema (version 1)
 *
 * ```
 * {
 *   "bundleVersion": 1,
 *   "exportedAt": 1730000000000,
 *   "pipelines": [
 *     { "schemaVersion": 1, "id": "root", "name": "Root", "nodes": [...], ... },
 *     { "schemaVersion": 1, "id": "sub_a", "name": "Sub A", "nodes": [...], ... }
 *   ]
 * }
 * ```
 *
 * Each element of `pipelines` is a complete [PipelineJsonSerializer] document.
 * Element order carries no semantics — referential integrity is resolved by
 * id, not by position.
 *
 * ### Referential integrity
 *
 * A well-formed bundle is **self-contained**: every `PIPELINE` node's
 * `targetPipelineId` must resolve to the id of some pipeline in the same file.
 * [parse] enforces this and rejects a bundle with a dangling reference — a
 * broken bundle would otherwise silently degrade at the receiver. Duplicate
 * ids across elements are likewise rejected.
 *
 * Uses `org.json` per the project's API conventions.
 */
object PipelineBundleJsonSerializer {

    /**
     * Version stamp emitted on every [serialize] call and required by [parse].
     * Bumped only on a breaking change to the *envelope* shape; the embedded
     * per-pipeline documents carry their own `schemaVersion`.
     */
    const val CURRENT_BUNDLE_VERSION: Int = 1

    /**
     * Hard ceiling on the number of pipelines a single bundle may contain.
     * Guards both export (a runaway dependency closure) and import (a
     * hand-crafted or corrupt file). A composite legitimately larger than this
     * signals a modelling problem, not a transfer need.
     */
    const val MAX_BUNDLE_PIPELINES: Int = 50

    /* ----------------------------------------------------------------- *
     *  Detection
     * ----------------------------------------------------------------- */

    /**
     * Cheap structural check that distinguishes a bundle envelope from a
     * single-pipeline document, so the shared "Import JSON" affordance can
     * branch without fully parsing first. Returns `true` when [jsonText] is a
     * JSON object carrying a top-level `bundleVersion` key; `false` for a
     * single-pipeline document, a preset, or unparseable text (those flow down
     * the single-import path, which reports its own parse errors).
     */
    fun looksLikeBundle(jsonText: String): Boolean = try {
        JSONObject(jsonText).has("bundleVersion")
    } catch (_: JSONException) {
        false
    }

    /* ----------------------------------------------------------------- *
     *  Serialise
     * ----------------------------------------------------------------- */

    /**
     * Renders [pipelines] into the schema-versioned bundle envelope. Each
     * pipeline is serialised through [PipelineJsonSerializer.serialize], so any
     * future change to the single-pipeline schema is picked up here for free.
     *
     * @param pipelines The transitive closure to bundle (root first by
     *   convention, though order is not semantically significant).
     * @param exportedAt Wall-clock millisecond timestamp stamped into the
     *   envelope for provenance; supplied by the caller so this function stays
     *   deterministic under test.
     * @return JSON text suitable for writing to disk.
     */
    fun serialize(pipelines: List<PipelineGraph>, exportedAt: Long): String {
        val root = JSONObject()
        root.put("bundleVersion", CURRENT_BUNDLE_VERSION)
        root.put("exportedAt", exportedAt)

        val pipelinesJson = JSONArray()
        pipelines.forEach { graph ->
            // Reparse the delegate's output into a JSONObject so the element is
            // a nested object, not an escaped string.
            pipelinesJson.put(JSONObject(PipelineJsonSerializer.serialize(graph)))
        }
        root.put("pipelines", pipelinesJson)

        return root.toString()
    }

    /* ----------------------------------------------------------------- *
     *  Parse
     * ----------------------------------------------------------------- */

    /**
     * Parses [jsonText] into the closure of pipelines it contains and reports
     * the outcome.
     *
     * The function never throws — every parse error is converted into a
     * [PipelineBundleImportOutcome.Failure] with a human-readable message.
     * Beyond delegating each element to [PipelineJsonSerializer.parse], it
     * enforces the envelope invariants: `bundleVersion` present, a non-empty
     * `pipelines` array within [MAX_BUNDLE_PIPELINES], no duplicate ids, and
     * full referential integrity of every `targetPipelineId`.
     */
    // Reason: parse is a single linear validation pipeline (envelope fields →
    // per-element delegation → duplicate-id check → referential integrity).
    // Each early return is one distinct, independently-messaged rejection;
    // extracting them would scatter the contract without reducing branches.
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun parse(jsonText: String): PipelineBundleImportOutcome {
        val root = try {
            JSONObject(jsonText)
        } catch (e: JSONException) {
            // Clamped: on Android the message quotes the entire input.
            return PipelineBundleImportOutcome.Failure("Invalid JSON: ${e.message.orEmpty().toDisplaySafe()}")
        }

        if (!root.has("bundleVersion")) {
            return PipelineBundleImportOutcome.Failure("Missing required field: bundleVersion")
        }

        val pipelinesJson = root.optJSONArray("pipelines")
            ?: return PipelineBundleImportOutcome.Failure("Missing required field: pipelines")
        if (pipelinesJson.length() == 0) {
            return PipelineBundleImportOutcome.Failure("Bundle contains no pipelines")
        }
        if (pipelinesJson.length() > MAX_BUNDLE_PIPELINES) {
            return PipelineBundleImportOutcome.Failure(
                "Bundle contains ${pipelinesJson.length()} pipelines, exceeding the limit of $MAX_BUNDLE_PIPELINES",
            )
        }

        val graphs = mutableListOf<PipelineGraph>()
        val mismatches = mutableListOf<PipelineBundleImportOutcome.SchemaMismatch>()
        for (i in 0 until pipelinesJson.length()) {
            val element = pipelinesJson.optJSONObject(i)
                ?: return PipelineBundleImportOutcome.Failure("Bundle pipeline #$i is not a JSON object")
            when (val outcome = PipelineJsonSerializer.parse(element.toString())) {
                is PipelineImportOutcome.Failure ->
                    return PipelineBundleImportOutcome.Failure("Bundle pipeline #$i is invalid: ${outcome.message}")

                is PipelineImportOutcome.Success -> graphs.add(outcome.graph)

                is PipelineImportOutcome.SchemaMismatch -> {
                    graphs.add(outcome.graph)
                    mismatches.add(
                        PipelineBundleImportOutcome.SchemaMismatch(
                            pipelineId = outcome.graph.id,
                            foundVersion = outcome.foundVersion,
                            expectedVersion = outcome.expectedVersion,
                        ),
                    )
                }
            }
        }

        val duplicateIds = graphs.map { it.id }.groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            return PipelineBundleImportOutcome.Failure(
                "Bundle contains duplicate pipeline ids: ${quoteSome(duplicateIds.sorted())}",
            )
        }

        val danglingReferences = danglingTargets(graphs)
        if (danglingReferences.isNotEmpty()) {
            return PipelineBundleImportOutcome.Failure(
                "Bundle references pipelines not contained in it: ${quoteSome(danglingReferences)}",
            )
        }

        return if (mismatches.isEmpty()) {
            PipelineBundleImportOutcome.Success(graphs)
        } else {
            PipelineBundleImportOutcome.PartialSchemaMismatch(pipelines = graphs, mismatches = mismatches)
        }
    }

    /**
     * Quotes at most [MAX_QUOTED_IDS] file-supplied ids for an error message,
     * each made display-safe, and says how many more there were. A bundle holds
     * up to [MAX_BUNDLE_PIPELINES] pipelines, so an unbounded list would let
     * the file decide how long the error is.
     */
    private fun quoteSome(ids: List<String>): String {
        val quoted = ids.take(MAX_QUOTED_IDS).joinToString { "\"${it.toDisplaySafe()}\"" }
        val more = ids.size - MAX_QUOTED_IDS
        return if (more > 0) "$quoted and $more more" else quoted
    }

    /** Most ids an error message quotes before summarising the rest. */
    private const val MAX_QUOTED_IDS = 5

    /**
     * Collects every `PIPELINE`-node `targetPipelineId` across [graphs] that
     * does not resolve to an id present in [graphs] — the dangling references
     * that make a bundle non-self-contained.
     */
    private fun danglingTargets(graphs: List<PipelineGraph>): List<String> {
        val presentIds = graphs.mapTo(mutableSetOf()) { it.id }
        return graphs
            .flatMap { graph ->
                graph.nodes
                    .filter { it.type == NodeType.PIPELINE }
                    .mapNotNull { it.targetPipelineId?.takeIf { target -> target.isNotBlank() } }
            }
            .filter { it !in presentIds }
            .distinct()
    }
}
