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
 * Fails the build when the size and complexity suppressions in the sources differ
 * from the committed list.
 *
 * Typed rather than an ad-hoc `doLast` for the reasons the file-map pair records:
 * configuration-cache compatibility, and a declared output so `check` skips it while
 * nothing it reads has changed. The comparison lives in [SizeSuppressionRatchet],
 * which is pure and unit-tested; this task only reads the files it declares.
 *
 * @property repositoryRoot Root the reported paths are made relative to, so the list
 *   and every failure message are the same on every machine.
 * @property sources Kotlin sources and build scripts to scan.
 * @property listFile The committed list of permitted suppressions.
 * @property stampFile Written on success, so the task can be up to date.
 */
@CacheableTask
abstract class VerifySizeSuppressionsTask : DefaultTask() {

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val listFile: RegularFileProperty

    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    /** Scans the sources and compares what it finds with the list. */
    @TaskAction
    fun verify() {
        val root = repositoryRoot.get().asFile
        val files = sources.files
            .filter { it.isFile }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
        val scan = SizeSuppressionRatchet.scan(files)
        if (scan.unreadable.isNotEmpty()) {
            throw VerificationException(
                "Suppressions the ratchet cannot read:\n" + scan.unreadable.joinToString("\n") { "  $it" },
            )
        }
        val listPath = listFile.get().asFile.relativeTo(root).invariantSeparatorsPath
        val listed = SizeSuppressionRatchet.parseList(listFile.get().asFile.readText())
        val verdict = SizeSuppressionRatchet.compare(scan.entries, listed)
        if (!verdict.clean) throw VerificationException(failureMessage(verdict, listPath))
        val stamp = stampFile.get().asFile
        stamp.parentFile.mkdirs()
        stamp.writeText("${files.size} files scanned, ${scan.entries.size} listed suppressions\n")
    }

    private fun failureMessage(verdict: SizeSuppressionRatchet.Verdict, listPath: String): String = buildString {
        appendLine("Size and complexity suppressions differ from $listPath.")
        if (verdict.unlisted.isNotEmpty()) {
            appendLine()
            appendLine("Not listed — split the code instead of suppressing the rule:")
            verdict.unlisted.forEach { appendLine("  $it") }
        }
        if (verdict.stale.isNotEmpty()) {
            appendLine()
            appendLine("Listed but gone from the sources — delete these lines from the list:")
            verdict.stale.forEach { appendLine("  $it") }
        }
        appendLine()
        append("The list only shrinks; see docs/static-analysis.md, \"Size-suppression ratchet\".")
    }
}
