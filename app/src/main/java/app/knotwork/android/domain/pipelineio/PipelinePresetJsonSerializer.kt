package app.knotwork.android.domain.pipelineio

import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineImportOutcome
import app.knotwork.android.domain.models.PipelinePreset
import app.knotwork.android.domain.models.PipelinePresetImportOutcome
import app.knotwork.android.domain.models.PresetCategory
import app.knotwork.android.domain.text.JsonNesting
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Two-way mapper between [PipelinePreset] and the schema-versioned JSON
 * format used by the bundled JSON files under `assets/presets/pipelines`
 * and by the browser-side editor's `.preset.json` export.
 *
 * The preset format is **a strict superset** of the pipeline format owned
 * by [PipelineJsonSerializer]: it embeds the same `schemaVersion` / `id` /
 * `name` / `updatedAt` / `nodes` / `connections` document and adds three
 * preset-only top-level fields (`category`, `tags`, `description`). Delegating
 * graph (de)serialisation to [PipelineJsonSerializer] keeps the two formats
 * forever in sync without duplicating the node-shape mapping.
 *
 * ### Schema (version 1)
 *
 * ```
 * {
 *   "schemaVersion": 1,
 *   "id": "local_only_qa",
 *   "name": "Local-only Q&A",
 *   "description": "INPUT → LITE_RT → OUTPUT, no network",
 *   "category": "local",
 *   "tags": ["offline", "qa"],
 *   "internal": false,
 *   "updatedAt": 1730000000000,
 *   "nodes": [ ... ],
 *   "connections": [ ... ]
 * }
 * ```
 *
 * `internal` is optional and defaults to `false`; it is emitted only when
 * actually set, so the shipped user-facing files stay free of the flag. See
 * [PipelinePreset.isInternal] for what it hides.
 *
 * Uses `org.json` per the project's API conventions.
 */
object PipelinePresetJsonSerializer {

    /**
     * Renders [preset] into the schema-versioned JSON form.
     *
     * The embedded graph is serialised through [PipelineJsonSerializer.serialize]
     * and merged with the preset-only fields, so any future change to the
     * pipeline schema is picked up here for free.
     *
     * @param preset The preset to serialise.
     * @return JSON text suitable for writing to disk or shipping in
     *   `assets/presets/pipelines/`.
     */
    fun serialize(preset: PipelinePreset): String {
        // Delegate graph serialisation to PipelineJsonSerializer so we never
        // have to mirror the node-shape mapping. The returned document
        // already carries the canonical schemaVersion / id / name /
        // updatedAt / nodes / connections layout.
        val root = JSONObject(PipelineJsonSerializer.serialize(preset.graph))

        // Overwrite the inner graph id / name with the preset's own id /
        // name so the on-disk file presents the preset identity to the
        // reader (the inner graph is only a *template* — its ids will be
        // regenerated on instantiation anyway).
        root.put("id", preset.id)
        root.put("name", preset.name)

        root.put("description", preset.description)
        root.put("category", preset.category.key)
        val tagsJson = JSONArray()
        preset.tags.forEach { tagsJson.put(it) }
        root.put("tags", tagsJson)

        // Emitted only when set: `internal` is a bundled-catalogue concern, and
        // every user-facing export would otherwise carry an always-false flag.
        if (preset.isInternal) root.put("internal", true)

        return root.toString()
    }

    /**
     * Parses [jsonText] into a [PipelinePreset] and reports the outcome.
     *
     * The function never throws — every parse error is converted into a
     * [PipelinePresetImportOutcome.Failure] with a human-readable message
     * so callers (the bundled-catalogue loader and any UI import flow)
     * can surface it without try/catch boilerplate.
     *
     * @param jsonText Raw JSON text.
     * @param isBundled `true` when [jsonText] originates from
     *   `assets/presets/pipelines/`, `false` when imported from disk or
     *   from the browser editor. Stored verbatim on the resulting
     *   [PipelinePreset].
     */
    fun parse(jsonText: String, isBundled: Boolean): PipelinePresetImportOutcome {
        if (JsonNesting.exceeds(jsonText)) return PipelinePresetImportOutcome.Failure(JsonNesting.FAILURE_MESSAGE)
        val root: JSONObject = try {
            JSONObject(jsonText)
        } catch (e: JSONException) {
            return PipelinePresetImportOutcome.Failure("Invalid JSON: ${e.message}")
        }

        // Delegate the pipeline-graph half to PipelineJsonSerializer. We
        // pass the full document — PipelineJsonSerializer ignores any
        // top-level fields it does not recognise, so the preset-only
        // `category` / `tags` / `description` slots survive untouched.
        return when (val graphOutcome = PipelineJsonSerializer.parse(jsonText)) {
            is PipelineImportOutcome.Failure -> PipelinePresetImportOutcome.Failure(graphOutcome.message)

            is PipelineImportOutcome.Success -> PipelinePresetImportOutcome.Success(
                preset = buildPreset(root = root, graph = graphOutcome.graph, isBundled = isBundled),
            )

            is PipelineImportOutcome.SchemaMismatch -> PipelinePresetImportOutcome.SchemaMismatch(
                preset = buildPreset(root = root, graph = graphOutcome.graph, isBundled = isBundled),
                foundVersion = graphOutcome.foundVersion,
                expectedVersion = graphOutcome.expectedVersion,
            )
        }
    }

    private fun buildPreset(root: JSONObject, graph: PipelineGraph, isBundled: Boolean): PipelinePreset {
        // The preset id and the embedded graph id are independent: a
        // bundled file's id is its filename stem (carried through the
        // top-level `id` field), while the graph keeps its own id so the
        // round-trip through PipelineJsonSerializer is lossless. We use
        // the document's top-level `id` for the preset identity.
        val presetId = root.optString("id").takeIf { it.isNotBlank() } ?: graph.id
        val name = root.optString("name").takeIf { it.isNotBlank() } ?: graph.name
        val description = if (root.has("description") && !root.isNull("description")) {
            root.optString("description", "")
        } else {
            ""
        }
        val category = PresetCategory.fromKey(
            if (root.has("category") && !root.isNull("category")) root.optString("category") else null,
        )
        val tags = root.optJSONArray("tags")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                array.optString(i).takeIf { it.isNotBlank() }
            }
        } ?: emptyList()

        // `internal` is honoured only for the bundled catalogue. An imported
        // document could otherwise hide itself from the user's own "Mine"
        // list with no way to get it back — a footgun with no legitimate use,
        // since only a bundled preset can be the composition target of
        // another bundled preset.
        val isInternal = isBundled && root.optBoolean("internal", false)

        return PipelinePreset(
            id = presetId,
            name = name,
            description = description,
            category = category,
            graph = graph,
            tags = tags,
            isBundled = isBundled,
            isInternal = isInternal,
        )
    }
}
