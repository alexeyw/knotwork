package app.knotwork.android.presentation.ui.pipeline.editor.config

import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineBundleImportOutcome
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineImportOutcome
import app.knotwork.android.domain.models.PipelinePreset
import app.knotwork.android.domain.models.PipelinePresetImportOutcome
import app.knotwork.android.domain.pipelineio.PipelineBundleJsonSerializer
import app.knotwork.android.domain.pipelineio.PipelineJsonSerializer
import app.knotwork.android.domain.pipelineio.PipelinePresetJsonSerializer
import app.knotwork.design.components.pipelineeditor.NodeConfigValidation
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Proves every node of every bundled pipeline preset opens **cleanly** in the
 * editor's `NodeConfigSheet` — decoded by [NodeConfigCodec] and accepted by
 * [NodeConfigValidation] with zero field errors.
 *
 * Why this exists as a separate gate from
 * [app.knotwork.android.domain.pipelineio.PipelinePresetCatalogValidationTest]:
 * that test proves a preset is a well-formed, *runnable* graph, which is a
 * strictly weaker property than being *editable*. A preset shipped without a
 * `nodeConfig` envelope ran fine — the engine reads only the flat `config`
 * fields — while the codec's legacy path reconstructed an `INTENT_ROUTER` with
 * no classes at all. The form requires 2..6, so the node opened with an empty
 * class list and a validation error that blocked Save, and nothing in the
 * build caught it. This test closes that gap for every node type at once,
 * rather than re-deriving per-type reasoning by hand each time a preset is
 * authored.
 *
 * Titles are validated against their real peers (uniqueness is a per-pipeline
 * rule), so a preset with two identically-named nodes also fails here.
 *
 * A second check holds the sheet to what the preset runs: opening any node and
 * saving it untouched must change nothing the engine reads. Presets carry each
 * node twice (flat `config` for the run, `nodeConfig` for the editor), and
 * when a sheet field was wired to the run the shipped presets' flat values were
 * not filled in — the default pipeline showed sub-task caps it did not apply.
 *
 * The same check covers the cookbook recipes in `docs/recipes/`: a reader copies
 * a recipe and opens it in the editor, so a value the recipe shows only in
 * `nodeConfig` is a value the reader believes the recipe runs with.
 *
 * Gradle's `:app:test` task runs in the `app/` working directory, so the
 * relative asset path resolves.
 */
class BundledPresetEditabilityTest {

    private val catalogDir: File = File("src/main/assets/presets/pipelines")
    private val recipeDir: File = File("../docs/recipes")

    @Test
    fun `every bundled preset node decodes into a config the editor accepts`() {
        val failures = mutableListOf<String>()
        bundledPresets().forEach { (file, preset) ->
            val titlesById = preset.graph.nodes.associate { node ->
                node.id to NodeConfigCodec.decode(node).title
            }
            preset.graph.nodes.forEach { node ->
                val config = NodeConfigCodec.decode(node)
                val peerTitles = titlesById.filterKeys { it != node.id }.values.toSet()
                val errors = NodeConfigValidation.validate(config, peerTitles)
                if (errors.isNotEmpty()) {
                    failures += "${file.name} node \"${node.id}\" (${node.type}): $errors"
                }
            }
        }

        assertTrue(
            "These bundled preset nodes would open in the editor with a validation error " +
                "blocking Save:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `every bundled preset node shows in the sheet the values it runs with`() {
        assertSheetShowsWhatRuns(bundledPresets().map { (file, preset) -> file.name to preset.graph }, "bundled preset")
    }

    @Test
    fun `every cookbook recipe node shows in the sheet the values it runs with`() {
        assertSheetShowsWhatRuns(recipeGraphs(), "cookbook recipe")
    }

    private fun assertSheetShowsWhatRuns(graphs: List<Pair<String, PipelineGraph>>, what: String) {
        val failures = mutableListOf<String>()
        graphs.forEach { (name, graph) ->
            graph.nodes.forEach { node ->
                val savedUntouched = NodeConfigCodec.apply(node, NodeConfigCodec.decode(node))
                val before = runView(node)
                val after = runView(savedUntouched)
                val changed = before.keys.filter { before[it] != after[it] }
                    .map { "$it: ${before[it]} -> ${after[it]}" }
                if (changed.isNotEmpty()) {
                    failures += "$name node \"${node.id}\" (${node.type}): $changed"
                }
            }
        }

        assertTrue(
            "Opening these $what nodes and saving them without an edit would change what runs — " +
                "the sheet shows a value the flat `config` does not carry. Put the value in the " +
                "`config` block:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `every preset and recipe node carries the same value in config and nodeConfig`() {
        // The browser editor reads and writes `nodeConfig`; the run reads `config`. A
        // value in one alone is shown without running, or run without being shown —
        // and saving a SUMMARY in the browser rewrites the run's prompt from
        // `nodeConfig.customPrompt`.
        val documents = catalogDir.jsonFiles() + recipeDir.jsonFiles()
        val failures = documents.flatMap { file ->
            val root = JSONObject(file.readText())
            val pipelines = root.optJSONArray("pipelines")
                ?.let { array -> List(array.length()) { array.getJSONObject(it) } }
                ?: listOf(root)
            pipelines.flatMap { pipeline ->
                val nodes = pipeline.getJSONArray("nodes")
                List(nodes.length()) { nodes.getJSONObject(it) }.flatMap { node -> mirrorMismatches(file.name, node) }
            }
        }

        assertTrue(
            "These nodes carry a value in only one of `config` and `nodeConfig`, or two different values:\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /** Where [node]'s `config` and `nodeConfig` disagree on a value both carry under a mirrored key. */
    private fun mirrorMismatches(file: String, node: JSONObject): List<String> {
        val flat = node.optJSONObject("config") ?: return emptyList()
        val envelope = node.optJSONObject("nodeConfig") ?: return emptyList()
        val pairs = MIRRORED_KEYS.map { it to it } +
            if (node.optString("type") == "SUMMARY") listOf("systemPrompt" to "customPrompt") else emptyList()
        return pairs.mapNotNull { (flatKey, envelopeKey) ->
            if (!envelope.has(envelopeKey)) return@mapNotNull null
            val run = flat.opt(flatKey).takeUnless { it == JSONObject.NULL }
            val shown = envelope.opt(envelopeKey).takeUnless { it == JSONObject.NULL }
            if (run == shown) {
                null
            } else {
                "$file node \"${node.optString("id")}\": $flatKey=$run, nodeConfig.$envelopeKey=$shown"
            }
        }
    }

    private fun File.jsonFiles(): List<File> =
        listFiles { _, name -> name.endsWith(".json", ignoreCase = true) }?.sortedBy { it.name }.orEmpty()

    /** Every cookbook recipe's pipelines, parsed the way an import reads them; a bundle contributes each member. */
    private fun recipeGraphs(): List<Pair<String, PipelineGraph>> {
        val files = recipeDir.listFiles { _, name -> name.endsWith(".json", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue("No recipes found in ${recipeDir.absolutePath}", files.isNotEmpty())
        return files.flatMap { file ->
            val text = file.readText()
            if (PipelineBundleJsonSerializer.looksLikeBundle(text)) {
                val bundle = PipelineBundleJsonSerializer.parse(text) as? PipelineBundleImportOutcome.Success
                    ?: error("Recipe bundle ${file.name} did not parse")
                bundle.pipelines.map { "${file.name}/${it.id}" to it }
            } else {
                val outcome = PipelineJsonSerializer.parse(text) as? PipelineImportOutcome.Success
                    ?: error("Recipe ${file.name} did not parse")
                listOf(file.name to outcome.graph)
            }
        }
    }

    /**
     * The flat properties as the engine reads them. Saving the sheet writes a
     * few values in a different spelling from the one a hand-authored preset
     * uses, and the engine reads both spellings the same way; only a change the
     * run can tell apart is a failure here.
     */
    private fun runView(node: NodeModel): Map<String, Any?> = NodeConfigMutations.flatProperties(node) + mapOf(
        // `OutputNodeExecutor` rewords only when the prompt is not blank.
        "systemPrompt" to if (node.type == NodeType.OUTPUT) {
            node.systemPrompt?.takeIf { it.isNotBlank() }
        } else {
            node.systemPrompt
        },
        // `EvaluateIfConditionUseCase` branches on an image only for `true`.
        "conditionHasImage" to (node.conditionHasImage == true),
        // The queue keeps going past a failed item only for an explicit `false`.
        "stopOnError" to (node.stopOnError != false),
        // The gate adds a prompt only for `true`.
        "alwaysConfirm" to (node.alwaysConfirm == true),
    )

    /** Every bundled preset file, parsed the way the catalogue loads it. */
    private fun bundledPresets(): List<Pair<File, PipelinePreset>> {
        val files = catalogDir.listFiles { _, name -> name.endsWith(".json", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue("No bundled preset files found in ${catalogDir.absolutePath}", files.isNotEmpty())
        return files.map { file ->
            val outcome = PipelinePresetJsonSerializer.parse(file.readText(), isBundled = true)
            val preset = (outcome as? PipelinePresetImportOutcome.Success)?.preset
                ?: error("Bundled preset ${file.name} did not parse: $outcome")
            file to preset
        }
    }

    private companion object {
        /** Keys a node carries under the same name in `config` (run) and `nodeConfig` (editors). */
        val MIRRORED_KEYS = listOf("fallbackClass", "maxSubtasks", "stopOnError")
    }
}
