package app.knotwork.android.domain.presetl10n

import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.PipelinePreset
import app.knotwork.android.domain.models.PromptPreset
import org.json.JSONException
import org.json.JSONObject

/**
 * One text of a bundled preset in another language, with the English it
 * translates.
 *
 * The English travels with the translation so a translation is applied only to
 * the text it was written for: when a preset's English changes and its
 * translation does not, the preset shows its new English rather than a
 * translation of the old one.
 *
 * @property source The English text, exactly as the bundled preset carries it.
 * @property text The same text in the other language.
 */
data class TranslatedText(val source: String, val text: String)

/**
 * The texts of one bundled pipeline preset a person reads — never what a model
 * reads, which stays in English (decisions §113, R1).
 *
 * @property name The preset's name; a pipeline made from it is named so.
 * @property description The preset's description in the gallery.
 * @property nodeLabels Node titles by node id. A node's title is shown on the
 *   canvas, in the editor and in a run's console. A model meets it in one place
 *   only — as the name a TOOL node's result is filed under when its tool could
 *   not be resolved — as it meets any title a person gives a node.
 * @property samplePrompts The starter prompts a new chat offers. They are the
 *   user's words to the agent, so they are translated (decisions §113, R2).
 */
data class PipelinePresetTranslation(
    val name: TranslatedText? = null,
    val description: TranslatedText? = null,
    val nodeLabels: Map<String, TranslatedText> = emptyMap(),
    val samplePrompts: List<TranslatedText> = emptyList(),
)

/**
 * The texts of one bundled prompt template a person reads. The system prompt
 * itself is what a model reads and is never translated.
 *
 * @property name The template's name in the library and the picker.
 * @property description The template's description.
 * @property tags The template's tags, which the picker offers as filters.
 */
data class PromptPresetTranslation(
    val name: TranslatedText? = null,
    val description: TranslatedText? = null,
    val tags: List<TranslatedText> = emptyList(),
)

/**
 * The bundled presets' texts in one interface language.
 *
 * Bundled presets are JSON assets written in English, and their system prompts
 * stay so; what a person reads in them — names, descriptions, node titles,
 * starter prompts, tags — comes from a translation file kept beside them and is
 * applied as they are read. A pipeline made from a preset keeps the words it
 * was made with, like every other name the app gives (decisions §113, R1).
 *
 * @property pipelines Pipeline preset translations by preset id.
 * @property prompts Prompt template translations by template id.
 */
data class PresetTranslations(
    val pipelines: Map<String, PipelinePresetTranslation> = emptyMap(),
    val prompts: Map<String, PromptPresetTranslation> = emptyMap(),
) {

    /**
     * Returns [preset] with its translated texts.
     *
     * Each text is replaced only when its English is the one the translation was
     * written for; any other text, and every user preset, is returned as it is.
     * A node's title is changed both where the app reads it ([NodeModel.label],
     * which the canvas, the editor and a run's console show) and in the `title`
     * of [NodeModel.configJson], which an exported pipeline hands the browser
     * editor, so the two never disagree.
     *
     * @param preset A preset as the catalogue read it.
     * @return The preset in this language, or [preset] itself when nothing applies.
     */
    fun translate(preset: PipelinePreset): PipelinePreset {
        val translation = pipelines[preset.id]?.takeIf { preset.isBundled } ?: return preset
        val graph = preset.graph
        val samplePrompts = translation.samplePrompts.associate { it.source to it.text }
        return preset.copy(
            name = translation.name.applyTo(preset.name),
            description = translation.description.applyTo(preset.description),
            graph = graph.copy(
                name = translation.name.applyTo(graph.name),
                nodes = graph.nodes.map { node -> node.retitled(translation.nodeLabels[node.id]) },
                samplePrompts = graph.samplePrompts.map { prompt ->
                    samplePrompts[prompt.title]?.let { prompt.copy(title = it) } ?: prompt
                },
            ),
        )
    }

    /**
     * Returns [preset] with its translated texts; the system prompt is kept.
     *
     * @param preset A template as the catalogue read it.
     * @return The template in this language, or [preset] itself when nothing applies.
     */
    fun translate(preset: PromptPreset): PromptPreset {
        val translation = prompts[preset.id]?.takeIf { preset.isBundled } ?: return preset
        val tags = translation.tags.associate { it.source to it.text }
        return preset.copy(
            name = translation.name.applyTo(preset.name),
            description = translation.description.applyTo(preset.description),
            tags = preset.tags.map { tags[it] ?: it },
        )
    }

    /** Constants. */
    companion object {
        /** No translation: every preset is shown as it is written, in English. */
        val NONE: PresetTranslations = PresetTranslations()

        /** The `configJson` member holding the title the editor shows. */
        private const val TITLE_KEY = "title"

        private fun TranslatedText?.applyTo(current: String): String =
            if (this != null && source == current) text else current

        /**
         * This node with [translation]'s title, when the node carries the
         * English it was written for; the node itself otherwise.
         */
        private fun NodeModel.retitled(translation: TranslatedText?): NodeModel {
            if (translation == null || label != translation.source) return this
            return copy(label = translation.text, configJson = configJson?.let { retitle(it, translation) })
        }

        /**
         * [configJson] with its title translated when it holds the same English;
         * unchanged when it does not, or when it cannot be read — the editor then
         * reads it as it would have.
         */
        private fun retitle(configJson: String, translation: TranslatedText): String = try {
            val config = JSONObject(configJson)
            if (config.optString(TITLE_KEY) == translation.source) {
                config.put(TITLE_KEY, translation.text).toString()
            } else {
                configJson
            }
        } catch (_: JSONException) {
            configJson
        }
    }
}
