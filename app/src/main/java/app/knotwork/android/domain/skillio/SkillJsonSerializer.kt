package app.knotwork.android.domain.skillio

import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.Skill
import app.knotwork.android.domain.models.SkillImportOutcome
import app.knotwork.android.domain.text.JsonNesting
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Two-way mapper between [Skill] and the schema-versioned JSON format used by
 * the bundled JSON files under `assets/presets/skills`.
 *
 * ### Schema (version 1)
 *
 * ```
 * {
 *   "schemaVersion": 1,
 *   "id": "summarizer",
 *   "name": "Summarizer",
 *   "description": "Condenses the input into a short summary.",
 *   "instruction": "Summarise the following text...",
 *   "toolAllowlist": [],
 *   "contextConfig": {
 *     "chatHistory": false, "originalTask": true, "nodeInput": true,
 *     "longTermMemory": false, "toolResults": false
 *   },
 *   "updatedAt": 1748304000000
 * }
 * ```
 *
 * ### The `toolAllowlist` field — null vs. empty
 *
 * The null-vs-empty distinction is load-bearing and round-trips exactly:
 * - **field absent or JSON `null`** → [Skill.toolAllowlist] is `null` (all
 *   tools, unrestricted).
 * - **`[]`** → empty list (no tools, instruction-only).
 * - **`["write_file", …]`** → exactly those tool ids (a subset).
 *
 * Uses `org.json` per the project's API conventions. [parse] never throws —
 * every error is converted to [SkillImportOutcome.Failure] with a
 * human-readable message.
 */
object SkillJsonSerializer {

    /**
     * Current schema version emitted by [serialize]. Bumping this constant is
     * the canonical signal to readers that the on-disk shape changed; older
     * readers surface a [SkillImportOutcome.SchemaMismatch] rather than
     * silently dropping unknown fields.
     */
    const val CURRENT_SCHEMA_VERSION = 1

    private const val KEY_SCHEMA_VERSION = "schemaVersion"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_DESCRIPTION = "description"
    private const val KEY_INSTRUCTION = "instruction"
    private const val KEY_TOOL_ALLOWLIST = "toolAllowlist"
    private const val KEY_CONTEXT_CONFIG = "contextConfig"
    private const val KEY_UPDATED_AT = "updatedAt"
    private const val KEY_CREATED_AT = "createdAt"

    private const val KEY_CHAT_HISTORY = "chatHistory"
    private const val KEY_ORIGINAL_TASK = "originalTask"
    private const val KEY_NODE_INPUT = "nodeInput"
    private const val KEY_LONG_TERM_MEMORY = "longTermMemory"
    private const val KEY_TOOL_RESULTS = "toolResults"

    /**
     * Renders [skill] into the schema-versioned JSON form. Omits
     * `toolAllowlist` entirely when [Skill.toolAllowlist] is `null` so the
     * "all tools" state round-trips back to `null` on re-parse.
     *
     * @param skill The skill to serialise.
     * @return JSON text suitable for writing to disk or shipping under
     *   `assets/presets/skills/`.
     */
    fun serialize(skill: Skill): String {
        val root = JSONObject()
        root.put(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
        root.put(KEY_ID, skill.id)
        root.put(KEY_NAME, skill.name)
        root.put(KEY_DESCRIPTION, skill.description)
        root.put(KEY_INSTRUCTION, skill.instruction)
        // null = "all tools": omit the key so re-parse yields null again.
        skill.toolAllowlist?.let { allowlist ->
            val array = JSONArray()
            allowlist.forEach { array.put(it) }
            root.put(KEY_TOOL_ALLOWLIST, array)
        }
        root.put(KEY_CONTEXT_CONFIG, serializeContextConfig(skill.contextConfig))
        root.put(KEY_CREATED_AT, skill.createdAt)
        root.put(KEY_UPDATED_AT, skill.updatedAt)
        return root.toString()
    }

    /**
     * Parses [jsonText] into a [Skill] and reports the outcome. Never throws —
     * every parse error becomes a [SkillImportOutcome.Failure].
     *
     * @param jsonText Raw JSON text.
     * @param isBundled `true` when [jsonText] originates from
     *   `assets/presets/skills/`. Stored verbatim on the resulting [Skill].
     * @return The parse outcome.
     */
    @Suppress("ReturnCount")
    fun parse(jsonText: String, isBundled: Boolean): SkillImportOutcome {
        if (JsonNesting.exceeds(jsonText)) return SkillImportOutcome.Failure(JsonNesting.FAILURE_MESSAGE)
        val root: JSONObject = try {
            JSONObject(jsonText)
        } catch (e: JSONException) {
            return SkillImportOutcome.Failure("Invalid JSON: ${e.message}")
        }

        val foundVersion = root.optInt(KEY_SCHEMA_VERSION, -1)
        if (foundVersion == -1) {
            return SkillImportOutcome.Failure("Missing schemaVersion field")
        }

        val id = root.optString(KEY_ID).takeIf { it.isNotBlank() }
            ?: return SkillImportOutcome.Failure("Missing or blank id field")
        val name = root.optString(KEY_NAME).takeIf { it.isNotBlank() }
            ?: return SkillImportOutcome.Failure("Missing or blank name field")
        val description = if (root.has(KEY_DESCRIPTION) && !root.isNull(KEY_DESCRIPTION)) {
            root.optString(KEY_DESCRIPTION, "")
        } else {
            ""
        }
        val instruction = root.optString(KEY_INSTRUCTION).takeIf { it.isNotBlank() }
            ?: return SkillImportOutcome.Failure("Missing or blank instruction field")

        val toolAllowlist = parseToolAllowlist(root)
        val contextConfig = parseContextConfig(root.optJSONObject(KEY_CONTEXT_CONFIG))
        val updatedAt = root.optLong(KEY_UPDATED_AT, 0L)
        val createdAt = root.optLong(KEY_CREATED_AT, updatedAt)

        val skill = Skill(
            id = id,
            name = name,
            description = description,
            instruction = instruction,
            toolAllowlist = toolAllowlist,
            contextConfig = contextConfig,
            isBundled = isBundled,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

        return if (foundVersion != CURRENT_SCHEMA_VERSION) {
            SkillImportOutcome.SchemaMismatch(
                skill = skill,
                foundVersion = foundVersion,
                expectedVersion = CURRENT_SCHEMA_VERSION,
            )
        } else {
            SkillImportOutcome.Success(skill)
        }
    }

    /**
     * Resolves the [Skill.toolAllowlist] from the JSON root, preserving the
     * null-vs-empty distinction. A missing key or JSON `null` means "all
     * tools" (`null`); a present array (possibly empty) is taken verbatim.
     */
    private fun parseToolAllowlist(root: JSONObject): List<String>? {
        if (!root.has(KEY_TOOL_ALLOWLIST) || root.isNull(KEY_TOOL_ALLOWLIST)) return null
        val array = root.optJSONArray(KEY_TOOL_ALLOWLIST) ?: return null
        return (0 until array.length()).mapNotNull { i ->
            array.optString(i).takeIf { it.isNotBlank() }
        }
    }

    /**
     * Decodes a `contextConfig` object into a [NodeContextConfig], defaulting
     * each missing flag to `true` (matching the all-enabled fallback used by
     * the Room converter). A `null` object yields [NodeContextConfig.ALL_ENABLED].
     */
    private fun parseContextConfig(obj: JSONObject?): NodeContextConfig {
        if (obj == null) return NodeContextConfig.ALL_ENABLED
        return NodeContextConfig(
            chatHistory = obj.optBoolean(KEY_CHAT_HISTORY, true),
            originalTask = obj.optBoolean(KEY_ORIGINAL_TASK, true),
            nodeInput = obj.optBoolean(KEY_NODE_INPUT, true),
            longTermMemory = obj.optBoolean(KEY_LONG_TERM_MEMORY, true),
            toolResults = obj.optBoolean(KEY_TOOL_RESULTS, true),
        )
    }

    /** Encodes a [NodeContextConfig] into its `contextConfig` JSON object. */
    private fun serializeContextConfig(config: NodeContextConfig): JSONObject = JSONObject().apply {
        put(KEY_CHAT_HISTORY, config.chatHistory)
        put(KEY_ORIGINAL_TASK, config.originalTask)
        put(KEY_NODE_INPUT, config.nodeInput)
        put(KEY_LONG_TERM_MEMORY, config.longTermMemory)
        put(KEY_TOOL_RESULTS, config.toolResults)
    }
}
