package app.knotwork.android.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
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
 * Shared plumbing of the two bundled-documentation tasks.
 *
 * Both read every document **through** [documents], the declared input, and
 * never walk the filesystem: a walk would fingerprint one set of files and read
 * another, which is how a gate ends up reporting a cached pass over a file that
 * has since changed.
 *
 * The input set is the Markdown under `docs`, **plus the repository's root
 * Markdown**, and the
 * second half is not incidental. A bundled document links to `../SECURITY.md`
 * and `../PRIVACY.md`, so a gate that declared only the `docs` tree would
 * resolve those links against files it had not declared — and go green after one
 * of them was deleted.
 *
 * The translations of the bundled documents are read the same way, through
 * [translations]: hand-written files at `<asset directory>/<language>/<file>`,
 * each holding the English anchors and the hash of the English it was made from
 * ([BundledDocTranslations]). The build writes one index per language beside
 * them; the translations themselves are sources, never written here.
 */
abstract class AbstractBundledDocsTask : DefaultTask() {

    /**
     * The repository root, used to present paths as a reader writes them.
     *
     * `@Internal`: the checkout location is not part of the result, and hashing
     * it would make every result machine-specific.
     */
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    /** Every document a bundled document may link to, plus the bundled ones. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val documents: ConfigurableFileCollection

    /** Directory the copies and the index are written to. */
    @get:Internal
    abstract val assetDirectory: DirectoryProperty

    /** The translations of the bundled documents, `<asset directory>/<language>/<file>.md`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val translations: ConfigurableFileCollection

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
     * Reads every declared translation.
     *
     * @return Language to English document path to the translation's text. A
     *   file whose name matches no bundled document is keyed by the path it
     *   would translate, `docs/<file>`, so the check can name it.
     */
    protected fun readTranslations(): Map<String, Map<String, String>> {
        val root = assetDirectory.get().asFile
        val bundled = BundledDocs.bundledDocuments().associateBy { it.path.substringAfterLast('/') }
        return translations.files
            .filter { it.isFile }
            .groupBy { it.relativeTo(root).invariantSeparatorsPath.substringBefore('/') }
            .mapValues { (_, files) ->
                files.associate { file -> (bundled[file.name]?.path ?: "docs/${file.name}") to file.readText() }
            }
            .toSortedMap()
    }

    /**
     * Refuses to proceed while any bundled document, or any of its
     * translations, would not survive the in-app renderer — or a translation
     * no longer matches its English.
     *
     * @param documents Every document the task read.
     * @param translations Every translation the task read, by language.
     * @throws VerificationException listing every construct and dead link found.
     */
    protected fun requireDocumentsAreReadable(
        documents: Map<String, String>,
        translations: Map<String, Map<String, String>>,
    ) {
        val violations = BundledDocs.violationsOf(documents) +
            translations.flatMap { (language, texts) ->
                BundledDocTranslations.violationsOf(language, texts, documents)
            }
        if (violations.isEmpty()) return
        throw VerificationException(
            "Documentation bundled into the app would not render correctly (${violations.size}):\n" +
                violations.joinToString("\n") { "  $it" } + "\n\n" +
                "These documents are read inside the app, offline, by someone whose phone is already " +
                "misbehaving — there is no address bar to recover with. Fix the document, or mark it " +
                "`REMOTE` in `buildSrc/.../DocumentationLinkRegistry.kt`.",
        )
    }

    /**
     * The path to full text of everything the build writes into the asset
     * directory: the English copies and their index, and one index per
     * translated language.
     *
     * @param documents Every document the task read.
     * @param translations Every translation the task read, by language.
     * @return Asset-directory-relative path to content.
     */
    protected fun assetContents(
        documents: Map<String, String>,
        translations: Map<String, Map<String, String>>,
    ): Map<String, String> {
        val contents = linkedMapOf<String, String>()
        for (entry in BundledDocs.bundledDocuments()) {
            contents[BundledDocs.assetPathOf(entry.path).substringAfterLast('/')] =
                documents.getValue(entry.path)
        }
        contents[BundledDocs.INDEX_FILE_NAME] = BundledDocs.renderIndex(documents)
        translations.forEach { (language, texts) ->
            contents["$language/${BundledDocTranslations.INDEX_FILE_NAME}"] =
                BundledDocTranslations.renderIndex(language, texts)
        }
        return contents
    }

    /**
     * The files the build wrote into the asset directory earlier: the top-level
     * copies and index, and each language's index — not the translations, which
     * are sources.
     *
     * @return Asset-directory-relative path to content.
     */
    protected fun writtenAssets(): Map<String, String> {
        val directory = assetDirectory.get().asFile
        val topLevel = directory.listFiles().orEmpty().filter { it.isFile }
        val indexes = directory.listFiles().orEmpty().filter { it.isDirectory }
            .mapNotNull { File(it, BundledDocTranslations.INDEX_FILE_NAME).takeIf(File::isFile) }
        return (topLevel + indexes).associate { it.relativeTo(directory).invariantSeparatorsPath to it.readText() }
    }
}

/**
 * Copies the bundled documents into the APK's assets and writes their index.
 *
 * Refuses to write while any document fails the pre-checks: producing copies of
 * a document the renderer cannot show would ship the defect and leave the
 * verification task to report it afterwards, against files already committed.
 */
@UntrackedTask(
    because = "it writes into app/src/main/assets, which must not become a declared build output",
)
abstract class SyncBundledDocsTask : AbstractBundledDocsTask() {

    /**
     * Writes the copies and the index.
     *
     * The asset directory is **not** declared as an `@OutputDirectory`, and the
     * task is untracked, for the reason measured on the documentation-link
     * generator in the previous task: a declared output inside `app/src/main`
     * is read by every task that reads the source set — here `mergeAssets` and
     * the packaging tasks — and Gradle's implicit-dependency validation is an
     * error, so each of them fails the moment this task joins the same
     * invocation. `./gradlew :app:syncBundledDocs check` is precisely what the
     * contribution workflow prescribes after editing a bundled document.
     *
     * The repository's usual answer, `mustRunAfter` on each consumer, was
     * rejected there and is rejected here for the same reason: the consumer
     * list is open-ended, so a consumer added later silently re-opens the hole.
     * The gate that *is* in `check` keeps its typed inputs and its stamp output.
     */
    @TaskAction
    fun sync() {
        val documents = readDocuments()
        val translations = readTranslations()
        requireDocumentsAreReadable(documents, translations)
        val directory = assetDirectory.get().asFile
        directory.mkdirs()
        val expected = assetContents(documents, translations)
        var written = 0
        for ((name, content) in expected) {
            val file = File(directory, name)
            if (!file.isFile || file.readText() != content) {
                file.parentFile.mkdirs()
                file.writeText(content)
                written++
            }
        }
        // A document that stops being bundled leaves its copy behind, and a
        // stale copy is worse than a missing one: it is a document the reader
        // can still open and that nothing checks any more. The same holds for
        // the index of a language whose translations are gone.
        val removed = writtenAssets().keys
            .filter { it !in expected }
            .onEach { File(directory, it).delete() }
        logger.lifecycle(
            "Bundled documentation: ${BundledDocs.bundledDocuments().size} document(s) + index, " +
                "${translations.size} translation(s); $written written, ${removed.size} stale file(s) removed.",
        )
    }
}

/**
 * Fails the build when the committed copies drifted from `docs/`, or when a
 * bundled document stopped being renderable inside the app.
 *
 * Declares a stamp output so `check` skips it while nothing it reads has
 * changed — the reason the pair is typed rather than two `doLast` blocks.
 */
@CacheableTask
abstract class VerifyBundledDocsTask : AbstractBundledDocsTask() {

    /**
     * The committed asset files.
     *
     * A collection rather than a single `@InputDirectory` so a *missing*
     * directory is reported by this task's own message, naming the command that
     * writes it, rather than by a snapshotting failure earlier in the build.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val committedAssets: ConfigurableFileCollection

    /** Written on success so the task can be up to date. */
    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    /** Checks renderability first, then drift. */
    @TaskAction
    fun verify() {
        val documents = readDocuments()
        val translations = readTranslations()
        // Renderability before drift, and the order is the message. Editing a
        // bundled document makes the committed copy stale *as well as*
        // possibly unrenderable, and "re-sync" is unhelpful advice for a
        // document that has just grown a Mermaid diagram — the sync would
        // refuse for the real reason one command later.
        requireDocumentsAreReadable(documents, translations)
        val expected = assetContents(documents, translations)
        val actual = writtenAssets()
        val missing = expected.keys - actual.keys
        val stale = actual.keys - expected.keys
        val changed = expected.filter { (name, content) -> actual[name] != null && actual[name] != content }.keys
        if (missing.isNotEmpty() || stale.isNotEmpty() || changed.isNotEmpty()) {
            throw VerificationException(
                buildString {
                    appendLine("The bundled documentation in the app's assets has drifted from `docs/`.")
                    if (missing.isNotEmpty()) appendLine("  Missing: ${missing.sorted().joinToString(", ")}")
                    if (changed.isNotEmpty()) appendLine("  Out of date: ${changed.sorted().joinToString(", ")}")
                    if (stale.isNotEmpty()) appendLine("  No longer bundled: ${stale.sorted().joinToString(", ")}")
                    append("Run `./gradlew :app:syncBundledDocs` and commit the result.")
                },
            )
        }
        val stamp = stampFile.get().asFile
        stamp.parentFile.mkdirs()
        stamp.writeText("ok\n")
        logger.lifecycle(
            "Verified ${BundledDocs.bundledDocuments().size} bundled document(s), their index and " +
                "${translations.size} translation(s).",
        )
    }
}
