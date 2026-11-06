package app.knotwork.android.domain.presetl10n

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads a bundled preset translation file, `assets/presets/l10n/<language>.json`.
 *
 * ### Format (version 1)
 *
 * ```
 * {
 *   "formatVersion": 1,
 *   "language": "ru",
 *   "pipelines": {
 *     "local_only_qa": {
 *       "name": { "en": "Local-only Q&A", "ru": "…" },
 *       "description": { "en": "…", "ru": "…" },
 *       "nodes": { "input": { "en": "Input", "ru": "…" } },
 *       "samplePrompts": [ { "en": "…", "ru": "…" } ]
 *     }
 *   },
 *   "prompts": {
 *     "litert_concise_assistant": {
 *       "name": { … }, "description": { … }, "tags": [ { "en": "offline", "ru": "…" } ]
 *     }
 *   }
 * }
 * ```
 *
 * Every text is a pair of the English it translates and the translation, so the
 * file reads as a side-by-side for whoever checks it.
 *
 * The reader is strict: a member it does not know fails the whole file. The
 * members it knows are exactly the texts a person reads, so a system prompt or
 * any other text a model reads cannot be added to a translation unnoticed
 * (decisions §113, R1).
 *
 * Uses `org.json` per the project's API conventions.
 */
object PresetTranslationsJson {

    /** The format this build reads. */
    const val FORMAT_VERSION: Int = 1

    /** The language every bundled preset is written in. */
    const val SOURCE_LANGUAGE: String = "en"

    private val ROOT_KEYS = setOf("formatVersion", "language", "pipelines", "prompts")
    private val PIPELINE_KEYS = setOf("name", "description", "nodes", "samplePrompts")
    private val PROMPT_KEYS = setOf("name", "description", "tags")

    /**
     * Parses a translation file.
     *
     * @param jsonText The file's text.
     * @param language The language the file must be written for — the name of
     *   the file it was read from.
     * @return The translations it holds.
     * @throws IllegalArgumentException when the text is not a translation file
     *   for [language] in this format, naming where it went wrong.
     */
    fun parse(jsonText: String, language: String): PresetTranslations {
        val root = try {
            JSONObject(jsonText)
        } catch (e: JSONException) {
            throw IllegalArgumentException("Preset translation for '$language' is not JSON", e)
        }
        root.requireOnly(ROOT_KEYS, where = "root")
        val version = root.optInt("formatVersion", -1)
        require(version == FORMAT_VERSION) {
            "Preset translation for '$language' is format $version, this build reads $FORMAT_VERSION"
        }
        val declared = root.optString("language")
        require(declared == language) { "Preset translation file for '$language' declares '$declared'" }
        val pair = TextPair(language)
        return PresetTranslations(
            pipelines = root.objectMembers("pipelines").mapValues { (id, value) ->
                pipelineTranslation(value.asObject("pipelines.$id"), "pipelines.$id", pair)
            },
            prompts = root.objectMembers("prompts").mapValues { (id, value) ->
                promptTranslation(value.asObject("prompts.$id"), "prompts.$id", pair)
            },
        )
    }

    private fun pipelineTranslation(json: JSONObject, where: String, pair: TextPair): PipelinePresetTranslation {
        json.requireOnly(PIPELINE_KEYS, where)
        return PipelinePresetTranslation(
            name = json.optionalPair("name", where, pair),
            description = json.optionalPair("description", where, pair),
            nodeLabels = json.objectMembers("nodes").mapValues { (nodeId, value) ->
                pair.read(value, "$where.nodes.$nodeId")
            },
            samplePrompts = json.pairList("samplePrompts", where, pair),
        )
    }

    private fun promptTranslation(json: JSONObject, where: String, pair: TextPair): PromptPresetTranslation {
        json.requireOnly(PROMPT_KEYS, where)
        return PromptPresetTranslation(
            name = json.optionalPair("name", where, pair),
            description = json.optionalPair("description", where, pair),
            tags = json.pairList("tags", where, pair),
        )
    }

    private fun JSONObject.optionalPair(key: String, where: String, pair: TextPair): TranslatedText? =
        opt(key)?.let { pair.read(it, "$where.$key") }

    private fun JSONObject.pairList(key: String, where: String, pair: TextPair): List<TranslatedText> {
        val value = opt(key) ?: return emptyList()
        require(value is JSONArray) { "$where.$key is not a list" }
        return List(value.length()) { index -> pair.read(value.get(index), "$where.$key[$index]") }
    }

    private fun JSONObject.objectMembers(key: String): Map<String, Any> {
        val value = opt(key) ?: return emptyMap()
        val members = value.asObject(key)
        return members.keys().asSequence().associateWith { members.get(it) }
    }

    private fun Any.asObject(where: String): JSONObject {
        require(this is JSONObject) { "$where is not an object" }
        return this
    }

    private fun JSONObject.requireOnly(allowed: Set<String>, where: String) {
        val unknown = keys().asSequence().filterNot { it in allowed }.toList()
        require(unknown.isEmpty()) { "$where has members a preset translation does not have: $unknown" }
    }

    /** Reads `{ "en": …, "<language>": … }` pairs. */
    private class TextPair(private val language: String) {
        private val keys = setOf(SOURCE_LANGUAGE, language)

        fun read(value: Any, where: String): TranslatedText {
            val json = value.asObject(where)
            json.requireOnly(keys, where)
            val source = json.opt(SOURCE_LANGUAGE)
            val text = json.opt(language)
            require(source is String && text is String) {
                "$where needs the text in '$SOURCE_LANGUAGE' and in '$language'"
            }
            require(text.isNotBlank()) { "$where has an empty '$language' text" }
            return TranslatedText(source = source, text = text)
        }
    }
}
