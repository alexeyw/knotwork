package app.knotwork.android.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.VerificationException

/**
 * Fails the build when a GitHub Actions reference or the Gradle wrapper stops
 * pinning the bytes it runs. The rules live in [SupplyChainPinsChecker], which is
 * pure and unit-tested; this task feeds it the files and turns violations into a
 * build failure.
 *
 * @property repositoryRoot Root the reported paths are made relative to, so a
 *   failure message reads the same on every machine.
 * @property workflowFiles Every YAML file under `.github/` — workflows, and any
 *   composite action added later.
 * @property wrapperProperties `gradle/wrapper/gradle-wrapper.properties`.
 * @property stampFile Written on success, so the task can be skipped while
 *   nothing it reads has changed.
 */
@CacheableTask
abstract class VerifySupplyChainPinsTask : DefaultTask() {

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val workflowFiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val wrapperProperties: RegularFileProperty

    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    /** Checks every reference and property, and fails on anything unpinned. */
    @TaskAction
    fun verify() {
        val root = repositoryRoot.get().asFile
        val workflows = workflowFiles.files
            .filter { it.isFile }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
        val references = workflows.values.sumOf { SupplyChainPinsChecker.countReferences(it) }
        if (references == 0) {
            // A pattern that stopped matching would pass every workflow there is.
            throw VerificationException(
                "Found no `uses:` reference in ${workflows.size} file(s) under .github/ — either the " +
                    "workflows moved or the checker no longer recognises them. Refusing to pass vacuously.",
            )
        }

        val wrapper = wrapperProperties.get().asFile
        val violations = workflows.flatMap { (path, text) -> SupplyChainPinsChecker.checkWorkflow(path, text) } +
            SupplyChainPinsChecker.checkWrapperProperties(
                wrapper.relativeTo(root).invariantSeparatorsPath,
                wrapper.readText(),
            )
        if (violations.isNotEmpty()) {
            throw VerificationException(
                "Supply-chain pins are missing (${violations.size}):\n" +
                    violations.joinToString("\n") { "  $it" } + "\n\n" +
                    "A mutable reference lets the same commit of this repository run different code " +
                    "tomorrow. See docs/static-analysis.md § Supply-chain pin guard.",
            )
        }

        val stamp = stampFile.get().asFile
        stamp.parentFile.mkdirs()
        stamp.writeText("workflows=${workflows.size}\nreferences=$references\n")
    }
}
