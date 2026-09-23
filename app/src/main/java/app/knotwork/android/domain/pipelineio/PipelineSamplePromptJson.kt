package app.knotwork.android.domain.pipelineio

import app.knotwork.android.domain.models.PipelineSamplePrompt
import app.knotwork.android.domain.text.toDisplaySafe
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Single source of truth for the JSON wire shape of a pipeline's
 * [PipelineSamplePrompt] list. Both the Room `Converters` (the
 * `pipelines.samplePrompts` TEXT column) and [PipelineJsonSerializer]
 * (export / import / preset / bundle) delegate here, so the encode/decode rules
 * cannot drift between the two representations.
 *
 * Wire shape: a JSON array of `{ "title": String, "toolsHint"?: String }`
 * objects. `toolsHint` is omitted when `null`; entries without a non-blank
 * `title` are skipped on decode; a blank `toolsHint` decodes back to `null`.
 * Decoding is total — malformed or blank input yields an empty list rather than
 * throwing, so a corrupt column or document never aborts a pipeline load.
 *
 * Decoding is also bounded. The list is rendered, whole, on the new-chat empty
 * state, and a pipeline file can declare it: without a ceiling one import put
 * as many cards there as the file listed. At most [MAX_SAMPLE_PROMPTS] entries
 * are kept, and each title and hint becomes one line within
 * [MAX_TITLE_LENGTH] / [MAX_TOOLS_HINT_LENGTH].
 */
object PipelineSamplePromptJson {

    /** Most sample prompts a pipeline keeps — twice what any bundled preset declares. */
    const val MAX_SAMPLE_PROMPTS: Int = 6

    /** Longest sample-prompt title kept — about three times the longest bundled one. */
    const val MAX_TITLE_LENGTH: Int = 200

    /** Longest tools hint kept; a hint is a short list of tool names. */
    const val MAX_TOOLS_HINT_LENGTH: Int = 120

    private const val KEY_TITLE = "title"
    private const val KEY_TOOLS_HINT = "toolsHint"

    /**
     * Encodes [prompts] into a [JSONArray] of `{title, toolsHint?}` objects.
     *
     * @param prompts The sample prompts to encode.
     * @return A [JSONArray]; empty when [prompts] is empty.
     */
    fun encodeToArray(prompts: List<PipelineSamplePrompt>): JSONArray {
        val array = JSONArray()
        prompts.forEach { prompt ->
            val obj = JSONObject().put(KEY_TITLE, prompt.title)
            prompt.toolsHint?.let { obj.put(KEY_TOOLS_HINT, it) }
            array.put(obj)
        }
        return array
    }

    /**
     * Decodes a [JSONArray] produced by [encodeToArray] back into a list of
     * [PipelineSamplePrompt]. Entries missing a non-blank title are skipped; a
     * missing or blank `toolsHint` decodes to `null`. At most
     * [MAX_SAMPLE_PROMPTS] prompts are returned, each field flattened to one
     * line and cut at its ceiling.
     *
     * @param array The array to decode, or `null` (absent key) → empty list.
     * @return The decoded prompts.
     */
    fun decodeFromArray(array: JSONArray?): List<PipelineSamplePrompt> {
        if (array == null) return emptyList()
        return (0 until array.length()).asSequence()
            .mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val title = obj.optString(KEY_TITLE)
                    .toDisplaySafe(maxLength = MAX_TITLE_LENGTH, ellipsis = "")
                    .takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val hint = obj.optString(KEY_TOOLS_HINT)
                    .toDisplaySafe(maxLength = MAX_TOOLS_HINT_LENGTH, ellipsis = "")
                    .takeIf { it.isNotEmpty() }
                PipelineSamplePrompt(title = title, toolsHint = hint)
            }
            .take(MAX_SAMPLE_PROMPTS)
            .toList()
    }

    /**
     * Encodes [prompts] into a compact JSON array string for single-column
     * storage.
     *
     * @param prompts The sample prompts to encode.
     * @return A JSON array string (`[]` when empty).
     */
    fun encodeToString(prompts: List<PipelineSamplePrompt>): String = encodeToArray(prompts).toString()

    /**
     * Decodes a JSON array string produced by [encodeToString] back into a list
     * of [PipelineSamplePrompt]. Blank or malformed input yields an empty list.
     *
     * @param value The stored JSON array string.
     * @return The decoded prompts, or an empty list on any error / missing data.
     */
    fun decodeFromString(value: String): List<PipelineSamplePrompt> {
        if (value.isBlank()) return emptyList()
        return try {
            decodeFromArray(JSONArray(value))
        } catch (_: JSONException) {
            emptyList()
        }
    }
}
