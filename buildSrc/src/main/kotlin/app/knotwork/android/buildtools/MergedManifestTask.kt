package app.knotwork.android.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.VerificationException

/**
 * Fails the build when the merged manifest of a shipping variant has an entry
 * surface its committed expectation does not list, or has lost one it does — or
 * declares anything carrying a text the expectation forbids with an `absent` line.
 *
 * The expectation is edited by hand, and there is deliberately no task that
 * rewrites it: every line is a decision — a permission the privacy policy has to
 * explain, an export the threat model has to name — and a generator would turn
 * each of those decisions into a keystroke. The failure message prints the exact
 * lines to add or remove. The comparison lives in [MergedManifestInventory],
 * which is pure and unit-tested.
 *
 * @property mergedManifest The manifest packaged into the variant's APK and AAB
 *   (`SingleArtifact.MERGED_MANIFEST`).
 * @property expectation The committed `config/merged-manifest/<variant>.txt`.
 * @property checkedVariant Variant name, for the failure message.
 * @property repositoryRoot Root the expectation path is reported against.
 * @property stampFile Written on success, so the task can be skipped while
 *   neither file has changed.
 */
@CacheableTask
abstract class VerifyMergedManifestTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val expectation: RegularFileProperty

    @get:Input
    abstract val checkedVariant: Property<String>

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    /** Compares the merged manifest's entry surfaces with the expectation. */
    @TaskAction
    fun verify() {
        val manifestXml = mergedManifest.get().asFile.readText()
        val actual = MergedManifestInventory.of(manifestXml)
        val expectationFile = expectation.get().asFile
        val expectationText = expectationFile.readText()
        val expected = MergedManifestInventory.parseExpectation(expectationText)
        val forbidden = MergedManifestInventory.occurrences(
            manifestXml,
            MergedManifestInventory.parseAbsences(expectationText),
        )
        if (actual.isEmpty()) {
            // A manifest with no permission and no export at all is not this app:
            // the parser, not the manifest, has changed.
            throw VerificationException(
                "The merged manifest of `${checkedVariant.get()}` yielded no entries at all. " +
                    "Refusing to pass vacuously.",
            )
        }

        val path = expectationFile.relativeTo(repositoryRoot.get().asFile).invariantSeparatorsPath
        if (forbidden.isNotEmpty()) {
            // Checked before the entry list: a forbidden declaration is the more
            // serious of the two, and it is usually not exported, so the drift
            // below would say nothing about it.
            throw VerificationException(
                buildString {
                    appendLine("The merged manifest of `${checkedVariant.get()}` declares what $path forbids:")
                    forbidden.forEach { appendLine("  ! $it") }
                    appendLine()
                    append(
                        "Find the contributing library in `app/build/outputs/logs/manifest-merger-*-report.txt` " +
                            "and remove the element in the variant's source manifest with `tools:node=\"remove\"`.",
                    )
                },
            )
        }

        val drift = MergedManifestInventory.compare(expected, actual)
        if (!drift.isEmpty) {
            throw VerificationException(
                buildString {
                    appendLine("The merged manifest of `${checkedVariant.get()}` disagrees with $path:")
                    drift.added.forEach { appendLine("  + $it") }
                    drift.removed.forEach { appendLine("  - $it") }
                    drift.duplicated.forEach { appendLine("  listed twice: $it") }
                    appendLine()
                    appendLine(
                        "`+` is in the manifest and not in the expectation; `-` is the reverse. Find where an " +
                            "added entry comes from in `app/build/outputs/logs/manifest-merger-*-report.txt`.",
                    )
                    append(
                        "Edit the expectation by hand. A new permission belongs in PRIVACY.md §5; a new export " +
                            "without a permission belongs in SECURITY.md § Automation triggers and entry surfaces.",
                    )
                },
            )
        }

        val stamp = stampFile.get().asFile
        stamp.parentFile.mkdirs()
        stamp.writeText(actual.joinToString(separator = "\n", postfix = "\n"))
    }
}
