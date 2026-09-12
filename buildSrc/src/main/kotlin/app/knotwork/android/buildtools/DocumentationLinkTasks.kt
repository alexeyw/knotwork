package app.knotwork.android.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
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
 * Shared plumbing of the two documentation-link tasks.
 *
 * Typed rather than an ad-hoc `doLast` block, for the reasons measured on the
 * file-map pair: an ad-hoc block capturing a build-script `val` captures the
 * whole build script and breaks the configuration cache, and an untyped
 * verification task declares no output, which Gradle can never treat as up to
 * date — so it would re-run on every `check` and grow the no-op build.
 *
 * Both tasks read the documents **through** [documents], the declared input,
 * and never walk the filesystem themselves: a walk would fingerprint one set of
 * files and read another.
 */
abstract class AbstractDocumentationLinkTask : DefaultTask() {

    /**
     * The repository root, used to present paths as a reader writes them.
     *
     * `@Internal`: the checkout location is not part of the result, and hashing
     * it would make every result machine-specific.
     */
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    /**
     * Every document a registry entry may target.
     *
     * The registry confines its targets to Markdown under `docs`, which makes
     * this input set *complete* — and completeness is what lets the verify task
     * be cacheable honestly. Deleting a target changes this fingerprint, so a
     * cached pass over a deleted document cannot happen.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val documents: ConfigurableFileCollection

    /** Package the generated object is declared in. */
    @get:Input
    abstract val generatedPackage: Property<String>

    /**
     * Reads every declared document, keyed the way the registry spells it.
     *
     * @return Repository-relative path to full text.
     */
    protected fun readDocuments(): Map<String, String> {
        val root = repositoryRoot.get().asFile
        return documents.files
            .filter { it.isFile }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
    }

    /**
     * Resolves the registry against the documents and fails on what is broken.
     *
     * @throws VerificationException when any entry names a document or heading
     *   that does not resolve.
     */
    protected fun requireLinksResolve() {
        val violations = DocumentationLinkRegistry.violationsOf(readDocuments())
        if (violations.isEmpty()) return
        throw VerificationException(
            "The app links out to documentation that does not resolve (${violations.size}):\n" +
                violations.joinToString("\n") { "  $it" } + "\n\n" +
                "These links are opened from the app, against the tag of the installed version, so a dead " +
                "one reaches a user who cannot see this build. Fix the heading, or the entry in " +
                "`buildSrc/.../DocumentationLinkRegistry.kt`.",
        )
    }

    /** The Kotlin source the registry currently renders to. */
    protected fun rendered(): String = DocumentationLinkRegistry.render(generatedPackage.get())
}

/**
 * Rewrites the app-side copy of the documentation-link registry.
 *
 * Refuses to write while any entry fails to resolve: generating a registry that
 * points at a heading nobody can reach would ship the defect into the app and
 * leave the verification task to report it afterwards, on a file already
 * committed.
 */
@UntrackedTask(because = "it rewrites a committed source file, which must not become a declared build output")
abstract class GenerateDocumentationLinksTask : AbstractDocumentationLinkTask() {

    /**
     * The generated Kotlin file.
     *
     * `@Internal`, and the task untracked — not an oversight, and the reason is
     * specific to *where* this file lives. Declaring it as an `@OutputFile`
     * would make it a build output sitting inside `app/src/main`, which every
     * task that reads the main source set also reads: compilation, detekt,
     * ktlint, lint, the file-map and FQN guards. Gradle's implicit-dependency
     * validation is an **error**, so each of them would fail the moment this
     * task appeared in the same invocation — and
     * `./gradlew :app:generateDocumentationLinks check` is exactly what the
     * contribution workflow prescribes after editing the registry. (Observed:
     * `checkNoInternalFqn` failed on precisely that.)
     *
     * The repository's usual answer — `mustRunAfter` on each consumer — was
     * rejected here because that list is the whole Kotlin toolchain, and a
     * consumer added later would silently re-open the hole. Declaring nothing
     * removes the edge for every consumer, present and future.
     *
     * What is given up is up-to-date checking on a manual command that takes
     * milliseconds and is not part of `check`. The gate that *is* in `check`,
     * [VerifyDocumentationLinksTask], keeps its typed inputs and its stamp
     * output, so it is still skipped when nothing has changed.
     */
    @get:Internal
    abstract val outputSource: RegularFileProperty

    /** Resolves every entry, then writes the app-side copy. */
    @TaskAction
    fun generate() {
        requireLinksResolve()
        val file = outputSource.get().asFile
        val source = rendered()
        if (!file.isFile || file.readText() != source) {
            file.parentFile.mkdirs()
            file.writeText(source)
            logger.lifecycle("Regenerated ${file.name}: ${DocumentationLinkRegistry.ENTRIES.size} entries.")
        }
    }
}

/**
 * Fails the build when the committed registry has drifted from the build-side
 * list, or when an entry no longer resolves to the heading it names.
 *
 * Declares a stamp output so `check` skips it while nothing it reads has
 * changed.
 */
@CacheableTask
abstract class VerifyDocumentationLinksTask : AbstractDocumentationLinkTask() {

    /**
     * The committed Kotlin file.
     *
     * A collection rather than a single `@InputFile` so a *missing* file is
     * reported by this task's own message — `@InputFile` refuses to snapshot
     * one that does not exist, and the build would fail before saying which
     * command writes it.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val committedSource: ConfigurableFileCollection

    /** Written on success so the task can be up to date. */
    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    /** Checks every entry's target, then the committed copy. */
    @TaskAction
    fun verify() {
        // Targets before drift, and the order is the message. Editing the
        // registry makes the committed copy stale *as well as* wrong, and
        // "regenerate" is unhelpful advice for an entry that names a heading
        // written twice — the regeneration would refuse for the real reason
        // one command later. Reporting the real reason first collapses that to
        // one step; a merely stale file still falls through to the drift
        // message below.
        requireLinksResolve()
        val file: File? = committedSource.files.firstOrNull()
        if (file == null || !file.isFile || file.readText() != rendered()) {
            throw VerificationException(
                "The committed documentation-link registry has drifted from the build-side list.\n" +
                    "Run `./gradlew :app:generateDocumentationLinks` and commit the result.",
            )
        }
        val stamp = stampFile.get().asFile
        stamp.parentFile.mkdirs()
        stamp.writeText("ok\n")
        logger.lifecycle("Resolved ${DocumentationLinkRegistry.ENTRIES.size} documentation link(s).")
    }
}
