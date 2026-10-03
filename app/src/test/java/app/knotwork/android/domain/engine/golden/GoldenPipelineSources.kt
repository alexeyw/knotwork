package app.knotwork.android.domain.engine.golden

import app.knotwork.android.domain.models.PipelineBundleImportOutcome
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineImportOutcome
import app.knotwork.android.domain.models.PipelinePresetImportOutcome
import app.knotwork.android.domain.models.Skill
import app.knotwork.android.domain.models.SkillImportOutcome
import app.knotwork.android.domain.pipelineio.PipelineBundleJsonSerializer
import app.knotwork.android.domain.pipelineio.PipelineJsonSerializer
import app.knotwork.android.domain.pipelineio.PipelinePresetJsonSerializer
import app.knotwork.android.domain.skillio.SkillJsonSerializer
import java.io.File

/**
 * Loads the pipelines and skills the golden harness runs, through the same parsers the app
 * uses for each format.
 *
 * Bundled presets are run as their templates — with the ids written in the asset — rather
 * than materialised through `LoadPipelineFromPresetUseCase`, which only regenerates ids and
 * would make every node id in a trace random. A sub-pipeline is resolved by the id a
 * `PIPELINE` node names, which for a preset is its file's `id`, exactly as the app stores the
 * internal sub-presets it depends on.
 *
 * Paths are relative to the `app/` module directory, the working directory Gradle gives the
 * unit-test task.
 */
internal object GoldenPipelineSources {

    /** The bundled pipeline presets. */
    val presetsDir: File = File("src/main/assets/presets/pipelines")

    /** The published recipes. */
    val recipesDir: File = File("../docs/recipes")

    /** The test-only fixture pipelines. */
    val fixturesDir: File = File("src/test/golden/fixtures")

    /** The bundled skills. */
    private val skillsDir: File = File("src/main/assets/presets/skills")

    /**
     * Every file stem of [kind], sorted, so the catalogue test can demand a scenario for each.
     *
     * @param kind The collection.
     * @return The `.json` file names without extension.
     */
    fun stems(kind: GoldenSourceKind): List<String> = directoryOf(kind)
        .listFiles { _, name -> name.endsWith(".json") }
        .orEmpty()
        .map { it.name.removeSuffix(".json") }
        .sorted()

    /**
     * Every pipeline a run can reach, keyed by id: all presets, all recipes (both pipelines of
     * a bundle) and all fixtures.
     *
     * @return The library a `PIPELINE` node resolves its target from.
     */
    fun library(): Map<String, PipelineGraph> = GoldenSourceKind.entries
        .flatMap { kind -> stems(kind).flatMap { stem -> parse(kind, stem) } }
        .associateBy { it.id }

    /**
     * The pipeline [source] starts from.
     *
     * @param source The scenario's pipeline source.
     * @return The root graph.
     */
    fun root(source: GoldenPipelineSource): PipelineGraph {
        val graphs = parse(source.kind, source.fileStem)
        return if (source.entryPipelineId == null) {
            graphs.single()
        } else {
            graphs.single { it.id == source.entryPipelineId }
        }
    }

    /**
     * The bundled skills, keyed by id.
     *
     * @return Every skill under the bundled skills directory.
     */
    fun skills(): Map<String, Skill> = skillsDir.listFiles { _, name -> name.endsWith(".json") }
        .orEmpty()
        .sortedBy { it.name }
        .map { file ->
            when (val outcome = SkillJsonSerializer.parse(file.readText(), isBundled = true)) {
                is SkillImportOutcome.Success -> outcome.skill
                else -> error("Bundled skill ${file.name} did not parse: $outcome")
            }
        }
        .associateBy { it.id }

    private fun directoryOf(kind: GoldenSourceKind): File = when (kind) {
        GoldenSourceKind.PRESET -> presetsDir
        GoldenSourceKind.RECIPE -> recipesDir
        GoldenSourceKind.FIXTURE -> fixturesDir
    }

    private fun parse(kind: GoldenSourceKind, stem: String): List<PipelineGraph> {
        val text = File(directoryOf(kind), "$stem.json").readText()
        return when {
            kind == GoldenSourceKind.PRESET -> listOf(parsePreset(stem, text))
            PipelineBundleJsonSerializer.looksLikeBundle(text) -> parseBundle(stem, text)
            else -> listOf(parsePipeline(stem, text))
        }
    }

    private fun parsePreset(stem: String, text: String): PipelineGraph =
        when (val outcome = PipelinePresetJsonSerializer.parse(text, isBundled = true)) {
            is PipelinePresetImportOutcome.Success -> outcome.preset.graph
            else -> error("Preset $stem did not parse: $outcome")
        }

    private fun parseBundle(stem: String, text: String): List<PipelineGraph> =
        when (val outcome = PipelineBundleJsonSerializer.parse(text)) {
            is PipelineBundleImportOutcome.Success -> outcome.pipelines
            else -> error("Bundle $stem did not parse: $outcome")
        }

    private fun parsePipeline(stem: String, text: String): PipelineGraph =
        when (val outcome = PipelineJsonSerializer.parse(text)) {
            is PipelineImportOutcome.Success -> outcome.graph
            else -> error("Pipeline $stem did not parse: $outcome")
        }
}
