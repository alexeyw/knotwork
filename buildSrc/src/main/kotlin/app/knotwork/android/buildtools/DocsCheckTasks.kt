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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Returns the declared prefixes that no scanned path falls under.
 *
 * @param paths Repository-relative paths of the files that were found.
 * @param prefixes Prefixes that must each be covered; an empty string stands for
 *   the repository root, i.e. a path holding no slash.
 * @return The uncovered prefixes, in declaration order; empty when all are covered.
 */
internal fun uncoveredPrefixes(paths: Collection<String>, prefixes: List<String>): List<String> =
    prefixes.filter { prefix ->
        paths.none { path -> if (prefix.isEmpty()) !path.contains('/') else path.startsWith(prefix) }
    }

/**
 * Directory names left out of [repositoryFilesOf] wherever they occur: build
 * output and tool state, none of which is part of the repository.
 */
private val UNINDEXED_DIRECTORIES = setOf("build", ".git", ".gradle", ".idea", ".kotlin", "node_modules")

/**
 * Root-level entries left out of [repositoryFilesOf]: they exist only in a
 * developer's checkout (all are Git-ignored), so a documentation path resolving
 * against one would pass locally and fail in CI.
 */
private val CHECKOUT_ONLY_ROOT_ENTRIES = setOf(".claude", "project_docs", "local.properties", "CLAUDE.md", "CLAUDE.local.md")

/**
 * Indexes every file of the working tree, for the inline-code path pass of
 * [VerifyDocLinksTask].
 *
 * Walks the tree rather than asking Git, for the reason the task's documents
 * are declared rather than listed from the index: a file not yet added to the
 * index would be invisible, and a checker blind to it would reject a correct
 * path to it.
 *
 * @param root The repository root.
 * @return Repository-relative paths of every file outside build output, tool
 *   state and checkout-only entries.
 */
internal fun repositoryFilesOf(root: File): Set<String> =
    root.walkTopDown()
        .onEnter { directory ->
            directory == root ||
                directory.name !in UNINDEXED_DIRECTORIES &&
                !(directory.parentFile == root && directory.name in CHECKOUT_ONLY_ROOT_ENTRIES)
        }
        .filter { it.isFile && !(it.parentFile == root && it.name in CHECKOUT_ONLY_ROOT_ENTRIES) }
        .mapTo(HashSet()) { it.relativeTo(root).invariantSeparatorsPath }

/**
 * Shared plumbing of the documentation checks that read the Markdown set.
 *
 * The file set arrives as a declared Gradle input, never from a Git query. A
 * checker that picks its inputs out of the index is blind to exactly the files
 * the branch under review is adding, and then reports a clean pass over
 * everything except the change being reviewed.
 *
 * [requiredPrefixes] closes the other end of the same failure: a scan that
 * matches nothing passes everything. Rather than pin a file count that rots,
 * each declared documentation root must contribute at least one document — so a
 * moved directory or a renamed glob fails loudly instead of quietly shrinking
 * the guarded surface.
 */
abstract class AbstractDocsScanTask : DefaultTask() {

    /**
     * The repository root, used to render paths relative to the checkout.
     *
     * `@Internal` on purpose: where the checkout lives is not part of what the
     * result depends on, and hashing it would make every result machine-specific.
     */
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    /** Every Markdown document in scope. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val documents: ConfigurableFileCollection

    /**
     * Path prefixes that must each contribute at least one document.
     *
     * An empty string means the repository root itself — a document whose path
     * holds no slash.
     */
    @get:Input
    abstract val requiredPrefixes: ListProperty<String>

    /**
     * Reads every document in scope, keyed by repository-relative path.
     *
     * @return Document text by path, ordered by path.
     * @throws GradleException when a declared root contributed no document,
     *   which means the scan has stopped covering it.
     */
    protected fun readDocuments(): Map<String, String> {
        val root = repositoryRoot.get().asFile
        val contents = documents.files
            .filter { it.isFile }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
            .toSortedMap()
        uncoveredPrefixes(contents.keys, requiredPrefixes.get()).firstOrNull()?.let { prefix ->
            throw GradleException(
                "No Markdown document was found under `${prefix.ifEmpty { "<repository root>" }}`. " +
                    "The scan has stopped covering a documentation root it is meant to guard — " +
                    "a directory moved, or a glob in `app/build.gradle.kts` stopped matching.",
            )
        }
        return contents
    }
}

/**
 * Fails the build when an internal documentation link leads nowhere.
 *
 * Deliberately untracked. The task's verdict depends on files it cannot declare
 * as inputs — a link may point at any path in the repository, and the defect
 * this gate exists to catch is precisely that such a path stopped existing.
 * Declaring the whole working tree as an input would hash 138 MB on every build
 * to answer a question that costs milliseconds; declaring only the documents
 * would let the task report a cached pass over a target that was deleted after
 * it last ran. Running every time is the honest option, and it is cheap.
 */
@UntrackedTask(because = "a link may point at any path in the repository, so the inputs cannot be declared")
abstract class VerifyDocLinksTask : AbstractDocsScanTask() {

    /**
     * Documents whose inline-code paths are not checked, by repository-relative
     * path. Their links still are.
     *
     * Meant for history: a changelog entry names the files of the tree it was
     * written against, and a file that has since moved does not make the entry
     * wrong.
     */
    @get:Input
    abstract val codePathExemptions: ListProperty<String>

    /** Resolves the internal links and the inline-code paths, and fails on the ones that lead nowhere. */
    @TaskAction
    fun verify() {
        val root = repositoryRoot.get().asFile
        val documents = readDocuments()
        val result = DocLinkChecker.check(documents) { path ->
            val file = File(root, path)
            when {
                file.isDirectory -> DocLinkChecker.PathKind.DIRECTORY
                file.isFile -> DocLinkChecker.PathKind.FILE
                else -> DocLinkChecker.PathKind.MISSING
            }
        }
        val exempt = codePathExemptions.get().toSet()
        val codePaths = DocLinkChecker.checkCodePaths(documents.filterKeys { it !in exempt }, repositoryFilesOf(root))
        val violations = (result.violations + codePaths.violations).sortedWith(compareBy({ it.file }, { it.line }))
        if (violations.isNotEmpty()) {
            throw VerificationException(
                "Dead internal documentation links and paths (${violations.size}):\n" +
                    violations.joinToString("\n") { "  ${it.format()}" } + "\n\n" +
                    "Relative paths and `#anchors` resolve against this repository, so a dead one is a " +
                    "defect in the commit, not in somebody else's server. An inline-code span written as a " +
                    "repository path is a claim too: point it at the file that exists, or — when it is " +
                    "deliberately not one file — elide it (`…`), or name the file without its directory. " +
                    "External `http` links are not checked here; `./gradlew :app:reportExternalDocLinks` " +
                    "reports on those without gating.",
            )
        }
        logger.lifecycle(
            "Checked ${result.internalLinkCount} internal link(s) and ${codePaths.checkedCount} inline-code " +
                "path(s) across ${documents.size} document(s); ${result.external.size} external link(s) left " +
                "to the report.",
        )
    }
}

/**
 * Probes every external `http` link and writes a report.
 *
 * Never fails the build, and never runs as part of `check`: an external link's
 * verdict is a statement about somebody else's server, which can flip without a
 * commit. Gating on it would make merges depend on the uptime of third parties.
 */
@UntrackedTask(because = "the result depends on remote servers, so it must never be reused from a previous run")
abstract class ReportExternalDocLinksTask : AbstractDocsScanTask() {

    /** Per-request timeout, in seconds. */
    @get:Input
    abstract val timeoutSeconds: Property<Int>

    /** Where the Markdown report is written. */
    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    /** Probes every distinct URL and writes the report. */
    @TaskAction
    fun report() {
        val documents = readDocuments()
        val targets = ExternalLinkReport.targetsOf(DocLinkChecker.externalLinksOf(documents))
        val timeout = Duration.ofSeconds(timeoutSeconds.get().toLong())
        val client = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val outcomes = probeAll(client, timeout, targets)
        val report = ExternalLinkReport.render(outcomes)
        val file = reportFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(report)
        val failures = outcomes.count { !it.ok }
        logger.lifecycle("Probed ${outcomes.size} external URL(s); $failures did not answer. Report: ${file.path}")
        for (outcome in outcomes.filterNot { it.ok }) {
            logger.lifecycle("  ${outcome.status}  ${outcome.url}  (${outcome.references.joinToString(", ")})")
        }
    }

    /**
     * Probes every URL, a few at a time.
     *
     * Concurrent rather than sequential because of the worst case, not the
     * common one: a runner with no egress times out on every probe, and one
     * request after another that is 45 URLs times two requests times the
     * timeout — half an hour, against a job budget of twenty minutes. The job
     * would be killed and report nothing, which looks like a broken check
     * rather than the unreachable network it is. A small fixed pool bounds
     * that; it is deliberately small, since the point is a bounded wait, not
     * load on anybody's server.
     *
     * @param client The shared HTTP client.
     * @param timeout Per-request timeout.
     * @param targets Every distinct URL.
     * @return One outcome per target, in the order they were given.
     */
    private fun probeAll(
        client: HttpClient,
        timeout: Duration,
        targets: List<ExternalLinkReport.Target>,
    ): List<ExternalLinkReport.Outcome> {
        if (targets.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(PROBE_CONCURRENCY, targets.size))
        return try {
            pool.invokeAll(targets.map { target -> Callable { probe(client, timeout, target) } }).map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    /**
     * Asks one server whether a URL still resolves.
     *
     * Tries `HEAD` first and falls back to `GET` when a server rejects the
     * method, which is common enough that treating it as a dead link would fill
     * the report with noise.
     *
     * @param client The shared HTTP client.
     * @param timeout Per-request timeout.
     * @param target The URL and where it is written.
     * @return The outcome for the report.
     */
    private fun probe(
        client: HttpClient,
        timeout: Duration,
        target: ExternalLinkReport.Target,
    ): ExternalLinkReport.Outcome {
        val head = request(client, timeout, target.url, "HEAD")
        val status = if (head != null && head < METHOD_REJECTED_FLOOR) {
            head
        } else {
            request(client, timeout, target.url, "GET")
        }
        return when {
            status == null -> ExternalLinkReport.Outcome(target.url, false, "unreachable", target.references)
            status < HTTP_ERROR_FLOOR -> ExternalLinkReport.Outcome(target.url, true, "HTTP $status", target.references)
            else -> ExternalLinkReport.Outcome(target.url, false, "HTTP $status", target.references)
        }
    }

    /**
     * Performs one request.
     *
     * @param client The shared HTTP client.
     * @param timeout Per-request timeout.
     * @param url The URL to probe.
     * @param method `HEAD` or `GET`.
     * @return The status code, or `null` when the request could not be made at
     *   all — a malformed URL, DNS failure, TLS failure or timeout.
     */
    private fun request(client: HttpClient, timeout: Duration, url: String, method: String): Int? =
        try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .build()
            client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        } catch (error: Exception) {
            logger.info("Probing $url with $method failed: ${error.message}")
            null
        }

    private companion object {
        /** Status at or above which a `HEAD` is retried as a `GET`. */
        const val METHOD_REJECTED_FLOOR = 400

        /** Status at or above which a link counts as not answering. */
        const val HTTP_ERROR_FLOOR = 400

        /** Sent so servers that reject unknown clients answer the probe. */
        const val USER_AGENT = "knotwork-docs-link-report"

        /** How many URLs are probed at once. */
        const val PROBE_CONCURRENCY = 8
    }
}

/**
 * Fails the build when an embedded Mermaid diagram is structurally broken.
 *
 * Fully declarable, unlike the link gate: the verdict is a function of the
 * documents alone, so the task declares a stamp output and is skipped while
 * none of them has changed.
 */
@CacheableTask
abstract class VerifyMermaidDiagramsTask : AbstractDocsScanTask() {

    /** Written on success so the task can be up to date. */
    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    /** Applies the structural rules to every Mermaid block. */
    @TaskAction
    fun verify() {
        val documents = readDocuments()
        val summary = MermaidBlockChecker.check(documents)
        if (summary.violations.isNotEmpty()) {
            throw VerificationException(
                "Broken Mermaid diagrams (${summary.violations.size}):\n" +
                    summary.violations.joinToString("\n") { "  ${it.format()}" } + "\n\n" +
                    "These are structural rules, not a full Mermaid parse; see `docs/static-analysis.md`.",
            )
        }
        val stamp = stampFile.get().asFile
        stamp.parentFile.mkdirs()
        stamp.writeText("blocks=${summary.blockCount}\ndocuments=${documents.size}\n")
    }
}

/**
 * Fails the build when a public document carries an LLM tool-call artifact or a
 * reference into the internal-only tree.
 *
 * Shares [documentationFiles][AbstractDocsScanTask.documents] with the other
 * documentation gates rather than resolving its own narrower set. It used to
 * scan only the repository root, `NOTICE` and `docs/`, which left the generated
 * `FILE_MAP.md` files outside every rule the gate enforces — and they are
 * exactly the documents most likely to acquire an internal reference, because
 * their descriptions are seeded from KDoc written for maintainers. Widening the
 * set costs nothing: the shared collection is already declared, already
 * prefix-guarded against a glob that stops matching, and the scan is a pure
 * function of the text.
 *
 * `CHANGELOG.md` stays out, and that exclusion is the caller's: it is a
 * historical journal whose past entries legitimately name internal documents as
 * they were called at the time.
 */
@CacheableTask
abstract class VerifyDocsHygieneTask : AbstractDocsScanTask() {

    /** Written on success so the task can be up to date. */
    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    /** Scans every document in scope and fails on the first crop of violations. */
    @TaskAction
    fun verify() {
        val documents = readDocuments()
        val violations = DocsHygieneChecker.scan(documents)
        if (violations.isNotEmpty()) {
            throw VerificationException(
                "Public documentation hygiene check failed (${violations.size} violation(s)):\n" +
                    violations.joinToString(separator = "\n") { "  " + it.format() } + "\n\n" +
                    "A public reader cannot open the internal tree, so a reference into it is a dead " +
                    "end. Link the public canon instead, or state the reason inline without the link.",
            )
        }
        val stamp = stampFile.get().asFile
        stamp.parentFile.mkdirs()
        stamp.writeText("documents=${documents.size}\n")
    }
}

/**
 * Fails the build when a hand-written copy of the version number disagrees with
 * the `versionName` the build declares.
 */
@CacheableTask
abstract class VerifyVersionSourcesTask : DefaultTask() {

    /** The `versionName` from the app's `defaultConfig`. */
    @get:Input
    abstract val declaredVersionName: Property<String>

    /** `README.md`, which carries the version badge. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val readmeFile: RegularFileProperty

    /** `CHANGELOG.md`, which carries the release heading and the compare links. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val changelogFile: RegularFileProperty

    /** `SECURITY.md`, whose every version names the supported release line. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val securityFile: RegularFileProperty

    /** `docs/roadmap.md`, which names the pre-release line it describes. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val roadmapFile: RegularFileProperty

    /** Written on success so the task can be up to date. */
    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    /** Compares every hand-written copy of the version against the build's. */
    @TaskAction
    fun verify() {
        val version = declaredVersionName.get()
        val violations = VersionSourcesChecker.check(
            versionName = version,
            readme = readmeFile.get().asFile.readText(),
            changelog = changelogFile.get().asFile.readText(),
            security = securityFile.get().asFile.readText(),
            roadmap = roadmapFile.get().asFile.readText(),
        )
        if (violations.isNotEmpty()) {
            throw VerificationException(
                "The version number disagrees with itself (${violations.size}):\n" +
                    violations.joinToString("\n") { "  $it" } + "\n\n" +
                    "`versionName` in `app/build.gradle.kts` is the single source of truth; every other " +
                    "copy is written for humans and has to be updated with it.",
            )
        }
        val stamp = stampFile.get().asFile
        stamp.parentFile.mkdirs()
        stamp.writeText("version=$version\n")
    }
}
