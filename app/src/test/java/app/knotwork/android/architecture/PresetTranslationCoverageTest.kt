package app.knotwork.android.architecture

import app.knotwork.android.domain.constants.PipelineConstants
import app.knotwork.android.domain.constants.PromptPresetConstants
import app.knotwork.android.domain.models.PipelinePreset
import app.knotwork.android.domain.models.PipelinePresetImportOutcome
import app.knotwork.android.domain.models.PromptPreset
import app.knotwork.android.domain.models.PromptPresetImportOutcome
import app.knotwork.android.domain.pipelineio.PipelinePresetJsonSerializer
import app.knotwork.android.domain.pipelineio.PipelineSamplePromptJson
import app.knotwork.android.domain.presetl10n.PresetTranslations
import app.knotwork.android.domain.presetl10n.PresetTranslationsJson
import app.knotwork.android.domain.presetl10n.TranslatedText
import app.knotwork.android.domain.promptio.PromptPresetJsonSerializer
import app.knotwork.android.presentation.ui.pipeline.editor.config.NodeConfigCodec
import app.knotwork.design.components.pipelineeditor.NodeConfigValidation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every text a person reads in a bundled preset exists in every language the app
 * ships, and was translated from the English the preset carries now.
 *
 * Bundled presets are written in English; their names, descriptions, node
 * titles, starter prompts and template tags come from
 * `assets/presets/l10n/<language>.json` (decisions §113, R1). At run time a text
 * whose English has changed since it was translated shows in English — so this
 * test is what turns a reworded preset into a failing build instead of a
 * half-translated screen. It holds, for every language:
 * - the file exists and reads ([PresetTranslationsJson] refuses any member that
 *   is not a text a person reads, so a system prompt cannot get in);
 * - every name, description, node title and starter prompt of every bundled
 *   pipeline preset, and every name, description and tag of every bundled
 *   template, has a translation, written for its current English;
 * - nothing in the file names a preset, node, prompt or tag that is not there;
 * - one English tag has one translation, or the picker would offer two chips for it;
 * - a translated preset still fits the limits its English fits (name, starter
 *   prompt), still validates, opens in the editor with every node title
 *   distinct, and carries the same title in the `nodeConfig` an export hands
 *   the browser editor.
 *
 * Reads `app/src/main/assets` and `app/src/main/res`; both are under `src/main`,
 * so the test task re-runs when they change.
 */
class PresetTranslationCoverageTest {

    @Test
    fun `every shipped language has a preset translation, and every translation is a shipped language`() {
        assertEquals(
            "assets/presets/l10n must hold one <language>.json per values-<language> folder of app",
            shippedLanguages(),
            translationFiles().keys,
        )
        assertTrue("ru" in shippedLanguages())
    }

    @Test
    fun `every text of every bundled pipeline preset is translated from its current English`() {
        forEachLanguage { language, translations ->
            val problems = pipelinePresets().flatMap { preset ->
                val translation = translations.pipelines[preset.id]
                    ?: return@flatMap listOf("${preset.id}: no translation")
                buildList {
                    addAll(mismatches("${preset.id} name", preset.name, translation.name))
                    addAll(mismatches("${preset.id} description", preset.description, translation.description))
                    preset.graph.nodes.forEach { node ->
                        addAll(mismatches("${preset.id} node ${node.id}", node.label, translation.nodeLabels[node.id]))
                    }
                    preset.graph.samplePrompts.forEach { prompt ->
                        if (translation.samplePrompts.none { it.source == prompt.title }) {
                            add("${preset.id} starter prompt \"${prompt.title}\": no translation of this English")
                        }
                    }
                }
            }
            assertTrue(problems.report(language), problems.isEmpty())
        }
    }

    @Test
    fun `every text of every bundled template is translated from its current English`() {
        forEachLanguage { language, translations ->
            val problems = promptPresets().flatMap { preset ->
                val translation = translations.prompts[preset.id]
                    ?: return@flatMap listOf("${preset.id}: no translation")
                buildList {
                    addAll(mismatches("${preset.id} name", preset.name, translation.name))
                    addAll(mismatches("${preset.id} description", preset.description, translation.description))
                    preset.tags.filter { tag -> translation.tags.none { it.source == tag } }
                        .forEach { add("${preset.id} tag \"$it\": no translation") }
                    val name = translations.translate(preset).name
                    if (name.length > PromptPresetConstants.MAX_NAME_LENGTH) {
                        add("${preset.id} name is longer than ${PromptPresetConstants.MAX_NAME_LENGTH}: $name")
                    }
                }
            }
            assertTrue(problems.report(language), problems.isEmpty())
        }
    }

    @Test
    fun `a translation names only presets, nodes, prompts and tags that exist`() {
        val pipelines = pipelinePresets().associateBy { it.id }
        val prompts = promptPresets().associateBy { it.id }
        forEachLanguage { language, translations ->
            val orphans = buildList {
                translations.pipelines.forEach { (id, translation) ->
                    val preset = pipelines[id]
                    if (preset == null) {
                        add("pipelines.$id: no such bundled preset")
                        return@forEach
                    }
                    val nodeIds = preset.graph.nodes.map { it.id }.toSet()
                    (translation.nodeLabels.keys - nodeIds).forEach { add("pipelines.$id.nodes.$it: no such node") }
                    val titles = preset.graph.samplePrompts.map { it.title }.toSet()
                    addAll(extras("pipelines.$id.samplePrompts", translation.samplePrompts, titles))
                }
                translations.prompts.forEach { (id, translation) ->
                    val preset = prompts[id]
                    if (preset == null) {
                        add("prompts.$id: no such bundled template")
                        return@forEach
                    }
                    addAll(extras("prompts.$id.tags", translation.tags, preset.tags.toSet()))
                }
            }
            assertTrue(orphans.report(language), orphans.isEmpty())
        }
    }

    @Test
    fun `one English tag has one translation across the templates`() {
        forEachLanguage { language, translations ->
            val conflicting = translations.prompts.values.flatMap { it.tags }
                .groupBy({ it.source }, { it.text })
                .mapValues { (_, texts) -> texts.toSet() }
                .filterValues { it.size > 1 }
                .map { (tag, texts) -> "tag \"$tag\" is translated as $texts" }
            assertTrue(conflicting.report(language), conflicting.isEmpty())
        }
    }

    @Test
    fun `a translated pipeline preset fits the limits, validates, and opens in the editor`() {
        forEachLanguage { language, translations ->
            val problems = pipelinePresets().flatMap { english ->
                val preset = translations.translate(english)
                val titles = preset.graph.nodes.associate { it.id to NodeConfigCodec.decode(it).title }
                buildList {
                    if (preset.name.length > PipelineConstants.MAX_NAME_LENGTH) {
                        add("${preset.id} name is longer than ${PipelineConstants.MAX_NAME_LENGTH}: ${preset.name}")
                    }
                    preset.graph.samplePrompts.filter { it.title.length > PipelineSamplePromptJson.MAX_TITLE_LENGTH }
                        .forEach { add("${preset.id} starter prompt is too long: ${it.title}") }
                    preset.graph.validate().forEach { add("${preset.id} does not validate: $it") }
                    preset.graph.nodes.forEach { node ->
                        val exported = node.configJson?.let { JSONObject(it).optString("title") }
                        if (exported != null && exported != node.label) {
                            add("${preset.id} node ${node.id}: titled \"${node.label}\", but an export would show " +
                                "the browser editor \"$exported\"")
                        }
                        val peers = titles.filterKeys { it != node.id }.values.toSet()
                        val errors = NodeConfigValidation.validate(NodeConfigCodec.decode(node), peers)
                        if (errors.isNotEmpty()) add("${preset.id} node ${node.id} opens with $errors")
                    }
                }
            }
            assertTrue(problems.report(language), problems.isEmpty())
        }
    }

    @Test
    fun `the census reads every bundled preset`() {
        assertTrue(pipelinePresets().size >= MIN_PIPELINE_PRESETS)
        assertTrue(promptPresets().size >= MIN_PROMPT_PRESETS)
        assertTrue(pipelinePresets().any { it.isInternal })
        assertTrue(pipelinePresets().any { it.graph.samplePrompts.isNotEmpty() })
    }

    // ─── Checks ──────────────────────────────────────────────────────────────

    private fun mismatches(what: String, english: String, translation: TranslatedText?): List<String> = when {
        translation == null -> listOf("$what: no translation")
        translation.source != english -> listOf(
            "$what: translated from \"${translation.source}\", the English is now \"$english\"",
        )
        else -> emptyList()
    }

    /** Entries of [list] translating no English in [existing], or one translated twice. */
    private fun extras(where: String, list: List<TranslatedText>, existing: Set<String>): List<String> =
        list.filter { it.source !in existing }.map { "$where: \"${it.source}\" is not in the preset" } +
            list.groupBy { it.source }.filterValues { it.size > 1 }.keys.map { "$where: \"$it\" is listed twice" }

    private fun List<String>.report(language: String): String =
        "assets/presets/l10n/$language.json:\n" + joinToString("\n")

    // ─── Reading the files ───────────────────────────────────────────────────

    private fun forEachLanguage(block: (language: String, translations: PresetTranslations) -> Unit) {
        val files = translationFiles()
        assertTrue("no preset translation files", files.isNotEmpty())
        files.forEach { (language, file) -> block(language, PresetTranslationsJson.parse(file.readText(), language)) }
    }

    private fun assets(): File = File(ProductionSources.moduleDirectory(), "src/main/assets")

    private fun translationFiles(): Map<String, File> = File(assets(), "presets/l10n").listFiles().orEmpty()
        .filter { it.isFile && it.extension == "json" }
        .associateBy { it.nameWithoutExtension }

    private fun shippedLanguages(): Set<String> = File(ProductionSources.moduleDirectory(), "src/main/res")
        .listFiles().orEmpty()
        .filter { it.isDirectory && LANGUAGE_FOLDER.matches(it.name) }
        .map { it.name.removePrefix("values-") }
        .toSet()

    private fun pipelinePresets(): List<PipelinePreset> = jsonFiles("presets/pipelines").map { file ->
        when (val outcome = PipelinePresetJsonSerializer.parse(file.readText(), isBundled = true)) {
            is PipelinePresetImportOutcome.Success -> outcome.preset
            else -> error("${file.name} does not parse: $outcome")
        }
    }

    private fun promptPresets(): List<PromptPreset> = jsonFiles("presets/prompts").map { file ->
        when (val outcome = PromptPresetJsonSerializer.parse(file.readText(), isBundled = true)) {
            is PromptPresetImportOutcome.Success -> outcome.preset
            else -> error("${file.name} does not parse: $outcome")
        }
    }

    private fun jsonFiles(directory: String): List<File> = File(assets(), directory).listFiles().orEmpty()
        .filter { it.isFile && it.extension == "json" }
        .sortedBy { it.name }

    private companion object {
        const val MIN_PIPELINE_PRESETS = 15
        const val MIN_PROMPT_PRESETS = 21
        val LANGUAGE_FOLDER = Regex("values-[a-z]{2,3}(-r[A-Z]{2})?")
    }
}
