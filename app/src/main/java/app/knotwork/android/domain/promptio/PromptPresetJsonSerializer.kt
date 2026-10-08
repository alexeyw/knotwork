package app.knotwork.android.domain.promptio

import app.knotwork.android.domain.constants.PromptPresetConstants
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PromptPreset
import app.knotwork.android.domain.models.PromptPresetImportOutcome
import app.knotwork.android.domain.text.JsonNesting
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Two-way mapper between [PromptPreset] and the schema-versioned JSON
 * format used by the bundled JSON files under `assets/presets/prompts`
 * and by user-saved rows in `prompt_presets`.
 *
 * ### Schema (version 1)
 *
 * ```
 * {
 *   "schemaVersion": 1,
 *   "id": "litert_concise_assistant",
 *   "name": "Concise assistant",
 *   "description": "Single-paragraph answers, no preamble.",
 *   "nodeType": "LITE_RT",
 *   "systemPrompt": "You are a helpful assistant. ...",
 *   "tags": ["concise", "starter"]
 * }
 * ```
 *
 * Uses `org.json` per the project's API conventions. The serializer never
 * throws on parse — every error is converted to
 * [PromptPresetImportOutcome.Failure] with a human-readable message.
 */
object PromptPresetJsonSerializer {

    /**
     * Current schema version emitted by [serialize].
     *
     * Bumping this constant is the canonical signal to readers that the
     * on-disk shape changed; older readers will surface a
     * [PromptPresetImportOutcome.SchemaMismatch] rather than silently
     * dropping unknown fields.
     */
    const val CURRENT_SCHEMA_VERSION = 1

    /**
     * Renders [preset] into the schema-versioned JSON form.
     *
     * @param preset The preset to serialise.
     * @return JSON text suitable for writing to disk or shipping in
     *   `assets/presets/prompts/`.
     */
    fun serialize(preset: PromptPreset): String {
        val root = JSONObject()
        root.put("schemaVersion", CURRENT_SCHEMA_VERSION)
        root.put("id", preset.id)
        root.put("name", preset.name)
        root.put("description", preset.description)
        root.put("nodeType", preset.nodeType.name)
        root.put("systemPrompt", preset.systemPrompt)
        val tagsJson = JSONArray()
        preset.tags.forEach { tagsJson.put(it) }
        root.put("tags", tagsJson)
        return root.toString()
    }

    /**
     * Parses [jsonText] into a [PromptPreset] and reports the outcome.
     *
     * The function never throws — every parse error is converted into a
     * [PromptPresetImportOutcome.Failure] with a human-readable message
     * so callers (the bundled-catalogue loader and the user-row decoder)
     * can surface it without try/catch boilerplate.
     *
     * @param jsonText Raw JSON text.
     * @param isBundled `true` when [jsonText] originates from
     *   `assets/presets/prompts/`, `false` when read from the
     *   `prompt_presets` Room table. Stored verbatim on the resulting
     *   [PromptPreset].
     */
    @Suppress("ReturnCount")
    fun parse(jsonText: String, isBundled: Boolean): PromptPresetImportOutcome {
        if (JsonNesting.exceeds(jsonText)) return PromptPresetImportOutcome.Failure(JsonNesting.FAILURE_MESSAGE)
        val root: JSONObject = try {
            JSONObject(jsonText)
        } catch (e: JSONException) {
            return PromptPresetImportOutcome.Failure("Invalid JSON: ${e.message}")
        }

        val foundVersion = root.optInt("schemaVersion", -1)
        if (foundVersion == -1) {
            return PromptPresetImportOutcome.Failure("Missing schemaVersion field")
        }

        val id = root.optString("id").takeIf { it.isNotBlank() }
            ?: return PromptPresetImportOutcome.Failure("Missing or blank id field")
        val name = root.optString("name").takeIf { it.isNotBlank() }
            ?: return PromptPresetImportOutcome.Failure("Missing or blank name field")
        val description = if (root.has("description") && !root.isNull("description")) {
            root.optString("description", "")
        } else {
            ""
        }
        val systemPrompt = root.optString("systemPrompt").takeIf { it.isNotBlank() }
            ?: return PromptPresetImportOutcome.Failure("Missing or blank systemPrompt field")

        val nodeTypeKey = root.optString("nodeType").takeIf { it.isNotBlank() }
            ?: return PromptPresetImportOutcome.Failure("Missing or blank nodeType field")
        val nodeType = try {
            NodeType.valueOf(nodeTypeKey)
        } catch (e: IllegalArgumentException) {
            return PromptPresetImportOutcome.Failure("Unknown NodeType: $nodeTypeKey")
        }
        if (nodeType !in PromptPresetConstants.LLM_DRIVEN_NODE_TYPES) {
            return PromptPresetImportOutcome.Failure(
                "NodeType $nodeType is not LLM-driven and cannot have a prompt preset",
            )
        }

        val tags = root.optJSONArray("tags")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                array.optString(i).takeIf { it.isNotBlank() }
            }
        } ?: emptyList()

        val preset = PromptPreset(
            id = id,
            name = name,
            description = description,
            nodeType = nodeType,
            systemPrompt = systemPrompt,
            tags = tags,
            isBundled = isBundled,
        )

        return if (foundVersion != CURRENT_SCHEMA_VERSION) {
            PromptPresetImportOutcome.SchemaMismatch(
                preset = preset,
                foundVersion = foundVersion,
                expectedVersion = CURRENT_SCHEMA_VERSION,
            )
        } else {
            PromptPresetImportOutcome.Success(preset)
        }
    }
}
