package app.knotwork.android.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.VerificationException

/**
 * Fails the build when public text carries the retired product name or internal
 * planning numbering.
 *
 * A typed, cacheable task rather than an ad-hoc `doLast`, for the reasons the
 * file-map pair records: a verification task with no declared output can never
 * be up to date and re-runs on every `check`, and the scan it performs is a
 * pure function of the text it reads. The detection lives in
 * [ForbiddenVocabularyChecker].
 *
 * The file set arrives as a declared input, never from a Git query, so a file the
 * branch under review is adding is scanned like any other. [requiredPrefixes]
 * closes the opposite hole: each declared root must contribute at least one file,
 * so a glob that stops matching fails instead of passing over nothing.
 *
 * @property repositoryRoot Root the reported paths are made relative to, so a
 *   failure message is the same on every machine.
 * @property sources Every public text file in scope.
 * @property requiredPrefixes Repository-relative path prefixes that must each
 *   contribute at least one file; an empty string means the repository root.
 * @property stampFile Written on success, so the task can be skipped while
 *   nothing it reads has changed.
 */
@CacheableTask
abstract class VerifyForbiddenVocabularyTask : DefaultTask() {

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val requiredPrefixes: ListProperty<String>

    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    /** Scans every file in scope and fails with the full list of hits. */
    @TaskAction
    fun verify() {
        val root = repositoryRoot.get().asFile
        val files = sources.files
            .filter { it.isFile }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
            .toSortedMap()
        val uncovered = uncoveredPrefixes(files.keys, requiredPrefixes.get())
        if (uncovered.isNotEmpty()) {
            throw GradleException(
                "No file was found under ${uncovered.joinToString { "`${it.ifEmpty { "<repository root>" }}`" }}. " +
                    "The vocabulary scan has stopped covering a root it is meant to guard — a directory " +
                    "moved, or a glob in `app/build.gradle.kts` stopped matching.",
            )
        }
        val violations = ForbiddenVocabularyChecker.scan(files)
        if (violations.isNotEmpty()) {
            throw VerificationException(
                "Forbidden vocabulary in public text (${violations.size} hit(s)):\n" +
                    violations.joinToString("\n") { "  ${it.format()}" } + "\n\n" +
                    "Name the product as it is called today. Describe a change by what it does, not by the " +
                    "planning phase or task that produced it — an outside reader cannot look either up. " +
                    "See `docs/static-analysis.md`.",
            )
        }
        val stamp = stampFile.get().asFile
        stamp.parentFile.mkdirs()
        stamp.writeText("${files.size} files scanned, no forbidden vocabulary\n")
    }
}
