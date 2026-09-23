package app.knotwork.android.presentation.ui.pipeline.editor.config

import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelinePreset
import app.knotwork.android.domain.models.PipelinePresetImportOutcome
import app.knotwork.android.domain.pipelineio.PipelinePresetJsonSerializer
import app.knotwork.design.components.pipelineeditor.NodeConfigValidation
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
 * Gradle's `:app:test` task runs in the `app/` working directory, so the
 * relative asset path resolves.
 */
class BundledPresetEditabilityTest {

    private val catalogDir: File = File("src/main/assets/presets/pipelines")

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
        val failures = mutableListOf<String>()
        bundledPresets().forEach { (file, preset) ->
            preset.graph.nodes.forEach { node ->
                val savedUntouched = NodeConfigCodec.apply(node, NodeConfigCodec.decode(node))
                val before = runView(node)
                val after = runView(savedUntouched)
                val changed = before.keys.filter { before[it] != after[it] }
                    .map { "$it: ${before[it]} -> ${after[it]}" }
                if (changed.isNotEmpty()) {
                    failures += "${file.name} node \"${node.id}\" (${node.type}): $changed"
                }
            }
        }

        assertTrue(
            "Opening these bundled preset nodes and saving them without an edit would change what runs — " +
                "the sheet shows a value the flat `config` does not carry. Put the value in the preset's " +
                "`config` block:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
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
}
