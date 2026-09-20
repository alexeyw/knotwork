package app.knotwork.android.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.VerificationException
import java.io.File

/**
 * Shared plumbing of the two file-map tasks.
 *
 * Written as a typed task rather than an ad-hoc `doLast` block for two reasons,
 * both measured on each pair's own task graph rather than assumed. First, an
 * ad-hoc block capturing a build-script `val` captures the whole build script:
 * `verifyCookbookDocs --configuration-cache` fails with "cannot serialize
 * Gradle script object references", while this pair stores and reuses an entry.
 * Second, an ad-hoc verification task has untyped inputs and no output, and
 * Gradle never treats a task without outputs as up to date — so it re-runs on
 * every `check`. [VerifyFileMapTask] declares a stamp output and is skipped when
 * nothing it reads has changed.
 *
 * The action reads the source tree **through** [sources], the declared input,
 * and never walks the filesystem on its own: a walk would fingerprint one set
 * of files and read another, which is the classic way a guard passes while
 * describing a repository that no longer exists.
 */
abstract class AbstractFileMapTask : DefaultTask() {

    /**
     * The repository root, used to resolve the relative paths held in [specs].
     *
     * `@Internal` on purpose: the checkout location is not part of what the
     * task's result depends on, and hashing it would make every result
     * machine-specific.
     */
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    /** Every Kotlin file under every root named by [specs]. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** The `FILE_MAP.md` files the blocks live in. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val maps: ConfigurableFileCollection

    /** The blocks to generate, in the order they are reported. */
    @get:Input
    abstract val specs: ListProperty<FileMapSpec>

    /**
     * Renders every block and returns the outcome per map file.
     *
     * @return One entry per `FILE_MAP.md`, carrying the rewritten Markdown and
     *   the counts the callers report or ratchet.
     * @throws GradleException when a declared root is missing or matches no
     *   Kotlin file — either would render an empty block, silently deleting a
     *   map that a reader would still trust.
     */
    protected fun renderAll(): List<MapOutcome> {
        val root = repositoryRoot.get().asFile
        val declaredSources = sources.files
        val outcomes = mutableListOf<MapOutcome>()
        for ((mapPath, blockSpecs) in specs.get().groupBy { it.mapPath }) {
            val mapFile = File(root, mapPath)
            if (!mapFile.isFile) throw GradleException("File map `$mapPath` does not exist.")
            var markdown = mapFile.readText()
            val counts = linkedMapOf<String, Int>()
            val undescribed = mutableListOf<String>()
            val dropped = mutableListOf<Pair<String, String>>()
            var files = 0
            for (spec in blockSpecs) {
                val result = renderBlock(root, spec, declaredSources, markdown)
                markdown = result.markdown
                counts["${spec.baselineKey}.undescribed"] = result.undescribed.size
                counts["${spec.baselineKey}.no-kdoc-seed"] = result.filesWithoutSeed.size
                undescribed += result.undescribed
                dropped += result.dropped
                files += result.fileCount
            }
            outcomes += MapOutcome(
                file = mapFile,
                displayPath = mapFile.relativeTo(root).invariantSeparatorsPath,
                markdown = markdown,
                fileCount = files,
                counts = counts,
                undescribed = undescribed,
                dropped = dropped,
            )
        }
        return outcomes
    }

    /**
     * Folds every block's counts into the one map the ratchet is read and
     * written with.
     *
     * Shared so the numbers [GenerateFileMapTask] records and the numbers
     * [VerifyFileMapTask] checks cannot drift apart.
     *
     * @param outcomes The rendered maps.
     * @return Measured count by baseline key.
     */
    protected fun measuredCounts(outcomes: List<MapOutcome>): Map<String, Int> =
        outcomes.fold(linkedMapOf()) { acc, outcome -> acc.apply { putAll(outcome.counts) } }

    /**
     * Renders one block of one map.
     *
     * @param root The repository root.
     * @param spec The block to render.
     * @param declaredSources Every file Gradle fingerprinted as an input.
     * @param markdown The map's current text, already carrying earlier blocks'
     *   output when a map owns several.
     * @return The generator's result for this block.
     */
    private fun renderBlock(
        root: File,
        spec: FileMapSpec,
        declaredSources: Set<File>,
        markdown: String,
    ): FileMapGenerator.RenderResult {
        val roots = spec.roots.map { declared ->
            val dir = File(root, declared.dir)
            if (!dir.isDirectory) {
                throw GradleException("Source root `${declared.dir}` of `${spec.mapPath}` does not exist.")
            }
            dir to declared.prefix
        }
        val files = declaredSources.mapNotNull { file ->
            val (dir, prefix) = roots.firstOrNull { (dir, _) -> file.startsWith(dir) } ?: return@mapNotNull null
            FileMapGenerator.SourceFile(
                path = prefix + file.relativeTo(dir).invariantSeparatorsPath,
                kdocSentence = KdocSentenceExtractor.firstSentence(file.name, file.readText()),
            )
        }
        if (files.isEmpty()) {
            throw GradleException(
                "Block `${spec.blockId}` of `${spec.mapPath}` matched no Kotlin file. " +
                    "Rendering it would empty a map that readers still trust, so this is a failure, not a no-op.",
            )
        }
        return FileMapGenerator.render(markdown, spec.blockId, files)
    }

    /**
     * What one `FILE_MAP.md` looks like after regeneration.
     *
     * @property file The map file.
     * @property displayPath Its path relative to the repository root — what a
     *   failure message names, so the output does not depend on where the
     *   checkout lives.
     * @property markdown Its regenerated contents.
     * @property fileCount Kotlin files rendered across the map's blocks.
     * @property counts Ratchet counts by baseline key.
     * @property undescribed Paths rendered with the "no description" marker.
     * @property dropped Descriptions whose path no longer exists.
     */
    protected data class MapOutcome(
        val file: File,
        val displayPath: String,
        val markdown: String,
        val fileCount: Int,
        val counts: Map<String, Int>,
        val undescribed: List<String>,
        val dropped: List<Pair<String, String>>,
    )
}

/**
 * Rewrites the generated blocks of every `FILE_MAP.md` and lowers the
 * documentation ratchet.
 *
 * Refuses to write when regeneration would discard a hand-written description
 * whose path has left the repository — a rename would otherwise eat a paragraph
 * of rationale in a diff that reads as a routine map update. The text is
 * printed in full so it can be moved to the new path; passing
 * `-PacceptFileMapDrops` confirms it is genuinely obsolete.
 *
 * Arranged like [GenerateDocumentationLinksTask], and for the same reason: the
 * maps it rewrites live **inside `app/src/main/java`**, which every task that
 * reads the main source set also reads — Kotlin compilation, KSP, detekt, ktlint,
 * lint. Gradle's implicit-dependency validation is an error, not a warning, so
 * each of those failed the moment this task appeared in the same invocation, and
 * `./gradlew :app:generateFileMap check` is exactly what the File Map Rule
 * prescribes after a Kotlin file is added or moved. (Observed: `kspFossDebugKotlin`
 * failed on precisely that, and again when the annotation below was reverted.)
 *
 * The two annotations do different jobs, measured separately:
 *
 *  - `@Internal` on [outputMaps] is what removes the collision. With
 *    [UntrackedTask] already in place but the maps still declared as
 *    `@OutputFiles`, the failure came back unchanged.
 *  - [UntrackedTask] is what keeps the task honest afterwards. Once its real
 *    product is not a declared output, Gradle would otherwise judge it up to date
 *    from its inputs and the ratchet file alone, and skip a regeneration that was
 *    needed. The cost — it always runs — is acceptable for a task nothing but a
 *    human invokes.
 *
 * The ordering declared in `app/build.gradle.kts` is a separate matter and stays:
 * untracking removes the *validation* error, not the reason a verifier must not
 * read a map this task is about to rewrite.
 */
@UntrackedTask(
    because = "it rewrites committed files inside the main source set, which must not become declared build outputs",
)
abstract class GenerateFileMapTask : AbstractFileMapTask() {

    /** Whether a dropped description is accepted rather than reported as a failure. */
    @get:Input
    abstract val acceptDroppedDescriptions: Property<Boolean>

    /** The ratchet file, rewritten with lowered counts. */
    @get:OutputFile
    abstract val baselineFile: RegularFileProperty

    /**
     * The map files this task rewrites.
     *
     * `@Internal`, not `@OutputFiles`, and the class KDoc says why: they live in
     * the main source set, and a build output there collides with every task
     * that reads it.
     */
    @get:Internal
    abstract val outputMaps: ConfigurableFileCollection

    /** Rewrites every block, then lowers the ratchet. */
    @TaskAction
    fun generate() {
        val outcomes = renderAll()
        val dropped = outcomes.flatMap { outcome -> outcome.dropped.map { outcome.displayPath to it } }
        if (dropped.isNotEmpty() && acceptDroppedDescriptions.get()) {
            // The flag says "I accept the loss", not "do not tell me what it was".
            logger.lifecycle("Dropped ${dropped.size} description(s) whose path no longer exists:")
            for ((map, entry) in dropped) logger.lifecycle("  $map :: ${entry.first}\n      ${entry.second}")
        }
        if (dropped.isNotEmpty() && !acceptDroppedDescriptions.get()) {
            throw GradleException(
                buildString {
                    appendLine("Regenerating the file maps would discard ${dropped.size} hand-written description(s)")
                    appendLine("whose path no longer exists. Move the text to the new path, or re-run with")
                    appendLine("`-PacceptFileMapDrops` if it is genuinely obsolete.")
                    appendLine()
                    for ((map, entry) in dropped) appendLine("  $map :: ${entry.first}\n      ${entry.second}")
                },
            )
        }
        for (outcome in outcomes) {
            if (outcome.markdown != outcome.file.readText()) {
                outcome.file.writeText(outcome.markdown)
                logger.lifecycle("Regenerated ${outcome.displayPath}: ${outcome.fileCount} Kotlin files.")
            }
        }
        writeBaseline(outcomes)
        val undescribed = outcomes.sumOf { it.undescribed.size }
        if (undescribed > 0) {
            logger.lifecycle("$undescribed file-map entries carry no description; see the ratchet baseline.")
        }
    }

    /** Writes the ratchet with every measured count lowered, never raised. */
    private fun writeBaseline(outcomes: List<MapOutcome>) {
        val file = baselineFile.get().asFile
        val measured = measuredCounts(outcomes)
        val recorded = if (file.isFile) FileMapBaseline.parse(file.readText()) else emptyMap()
        val rendered = FileMapBaseline.render(FileMapBaseline.lowered(measured, recorded))
        if (!file.isFile || file.readText() != rendered) {
            file.parentFile.mkdirs()
            file.writeText(rendered)
            logger.lifecycle("Updated the file-map ratchet at ${file.name}.")
        }
    }
}

/**
 * Fails the build when a committed `FILE_MAP.md` has drifted from the source
 * tree, or when a documentation gap has grown past the ratchet.
 *
 * Declares a stamp output so `check` skips it while nothing it reads has
 * changed; without an output Gradle would re-run it on every build.
 */
@CacheableTask
abstract class VerifyFileMapTask : AbstractFileMapTask() {

    /** The committed ratchet. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baselineFile: RegularFileProperty

    /** Written on success so the task can be up to date. */
    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    /** Compares the committed maps and ratchet against the source tree. */
    @TaskAction
    fun verify() {
        val outcomes = renderAll()
        val drifted = outcomes.filter { it.markdown != it.file.readText() }
        if (drifted.isNotEmpty()) {
            throw VerificationException(
                "These file maps have drifted from the source tree: " +
                    drifted.joinToString(", ") { it.displayPath } + ".\n" +
                    "Run `./gradlew :app:generateFileMap` and commit the result.",
            )
        }
        val measured = measuredCounts(outcomes)
        val recorded = FileMapBaseline.parse(baselineFile.get().asFile.readText())
        val violations = FileMapBaseline.violations(measured, recorded)
        if (violations.isNotEmpty()) {
            throw VerificationException(
                "Undocumented files grew past the ratchet:\n" +
                    violations.joinToString("\n") { "  $it" } + "\n" +
                    "Describe the new files, or raise the number in the baseline deliberately.",
            )
        }
        val stamp = stampFile.get().asFile
        stamp.parentFile.mkdirs()
        stamp.writeText(outcomes.joinToString("\n") { "${it.displayPath}=${it.fileCount}" })
    }
}
