package app.knotwork.android.buildtools

/**
 * Pure checker that resolves every **internal** link of the documentation set
 * — and every inline code span written as a repository path, see
 * [checkCodePaths] — and reports the ones that lead nowhere.
 *
 * The split between what blocks a build and what only reports is deliberate. A
 * relative path or an `#anchor` is a statement about *this repository*: its
 * verdict is a function of the commit under review and nothing else, so a dead
 * one is a defect the build can and should refuse. An `http` link is a
 * statement about somebody else's server, whose verdict changes without a
 * commit; those are collected here and handed to the non-blocking report
 * instead, never to the gate.
 *
 * The checker takes the file system as two lambdas rather than touching it, so
 * it stays a pure transform and its unit tests describe a repository that does
 * not exist. The Gradle task supplies the real ones.
 *
 * Two failure modes of a checker like this one are worth naming, because both
 * report **zero broken links** while being wrong, and both are closed here:
 *
 *  1. **Choosing inputs from the Git index.** A file set read from
 *     `git ls-files` cannot see the files the branch under review is adding —
 *     the checker then validates everything except the change being reviewed.
 *     The task feeds this checker from declared source trees instead.
 *  2. **A target document that was never scanned.** An anchor into a Markdown
 *     file outside the scanned set cannot be validated. Rather than skip it
 *     quietly, that is reported as [Reason.UNSCANNED_TARGET]: it means the
 *     scanned set has a hole, which is a finding about the gate itself.
 */
object DocLinkChecker {

    /** What a link target turned out to be on disk. */
    enum class PathKind {
        /** Nothing exists at that path. */
        MISSING,

        /** A regular file. */
        FILE,

        /** A directory. */
        DIRECTORY,
    }

    /** Why a link was rejected. */
    enum class Reason(val message: String) {
        /** The path does not exist in the repository. */
        MISSING_FILE("target does not exist"),

        /** The path climbs above the repository root, so it resolves nowhere a reader can follow. */
        OUTSIDE_REPOSITORY("target resolves outside the repository"),

        /** The path starts at `/`, which GitHub resolves against the site root rather than the repository. */
        SITE_ABSOLUTE("root-relative target: GitHub resolves `/…` against the site, not the repository"),

        /** The document exists but has no such heading or explicit anchor. */
        MISSING_ANCHOR("no such anchor in the target document"),

        /** An anchor was written against a directory, which has none. */
        ANCHOR_ON_DIRECTORY("anchor written against a directory"),

        /** The target is Markdown outside the scanned set, so its anchors could not be checked. */
        UNSCANNED_TARGET("target Markdown file is outside the scanned set, so its anchors cannot be verified"),

        /** A code span written as a repository path names no file — from the root, the document or a package. */
        MISSING_CODE_PATH("inline-code path names no file in the repository"),
    }

    /**
     * One rejected link.
     *
     * @property file Path of the document holding the link, relative to the
     *   repository root.
     * @property line 1-indexed line the link was written on.
     * @property target The destination exactly as written.
     * @property reason Why it was rejected.
     */
    data class Violation(
        val file: String,
        val line: Int,
        val target: String,
        val reason: Reason,
    ) {
        /** Renders the violation in the canonical `path:line: message` failure format. */
        fun format(): String = "$file:$line: ${reason.message} -> `$target`"
    }

    /**
     * One `http` link, collected for the non-blocking report.
     *
     * @property file Path of the document holding the link.
     * @property line 1-indexed line the link was written on.
     * @property url The absolute URL.
     */
    data class ExternalLink(val file: String, val line: Int, val url: String)

    /**
     * Everything one pass over the documentation set produced.
     *
     * @property violations Dead internal links, in document order.
     * @property external Every `http` link, for the report task.
     * @property internalLinkCount How many internal links were resolved —
     *   reported on success so a pass that silently stopped reading the
     *   documents does not look like a pass over a healthy repository.
     */
    data class Result(
        val violations: List<Violation>,
        val external: List<ExternalLink>,
        val internalLinkCount: Int,
    )

    /**
     * Everything the inline-code path pass produced.
     *
     * @property violations Spans naming no file, in document order.
     * @property checkedCount How many spans were read as repository paths —
     *   reported on success for the same reason as [Result.internalLinkCount].
     */
    data class CodePathResult(val violations: List<Violation>, val checkedCount: Int)

    /**
     * Extensions of the files a documentation span can name. A span without one
     * — a class name, a package, a directory — is not read as a path: it names
     * something the file system cannot answer for.
     */
    private val SOURCE_EXTENSIONS = listOf(
        ".kt", ".kts", ".java", ".md", ".yml", ".yaml", ".xml", ".json", ".toml",
        ".properties", ".sh", ".py", ".mjs", ".js", ".html", ".pro", ".txt",
    )

    /**
     * Marks of a span that stands for many paths or for part of one: an elision
     * (`…`, `...`), a glob, or a template placeholder. Such a span is shorthand,
     * not a claim that one file exists.
     */
    private val SHORTHAND = Regex("""[*?\[\]<>{}${'$'}…]|\.\.\.""")

    /** Schemes that address something other than a document, and are simply skipped. */
    private val IGNORED_SCHEMES = listOf("mailto:", "tel:", "data:", "javascript:")

    /**
     * Resolves every link of every scanned document.
     *
     * @param docs Document text by repository-relative path. The map is both
     *   the set of files scanned **and** the corpus anchors are resolved
     *   against.
     * @param resolve What exists at a given repository-relative path.
     * @return The violations, the external links and the number of internal
     *   links seen.
     */
    fun check(docs: Map<String, String>, resolve: (String) -> PathKind): Result {
        val anchors = docs.mapValues { (_, text) -> MarkdownLinks.anchorsOf(text) }
        val violations = mutableListOf<Violation>()
        val external = mutableListOf<ExternalLink>()
        var internal = 0
        for ((path, text) in docs.entries.sortedBy { it.key }) {
            for (link in MarkdownLinks.linksOf(text)) {
                val target = link.target.trim()
                if (target.isEmpty()) continue
                if (isExternal(target)) {
                    external += ExternalLink(path, link.line, target)
                    continue
                }
                if (IGNORED_SCHEMES.any { target.startsWith(it, ignoreCase = true) }) continue
                internal++
                violations += resolveOne(path, link.line, target, anchors, resolve) ?: continue
            }
        }
        return Result(violations, external, internal)
    }

    /**
     * Resolves every inline code span that is written as a repository path.
     *
     * Documentation names source files in code spans far more often than in
     * links, and [check] never reads a span — so a rule sending implementers to
     * "the canonical parser in `domain/parser/ToolArgumentParser.kt`" pointed at a
     * file that never existed, for four months, with every gate green. This pass
     * reads those spans as the claims they are.
     *
     * A span is read as a path only when it looks like one: it holds a `/`, no
     * whitespace and no scheme, ends in a [SOURCE_EXTENSIONS] extension (after an
     * optional `:line` or `#anchor`), is not [SHORTHAND], has no `build` segment
     * (build output is not in the tree being checked), does not start with `/`
     * (an absolute path is a device path, never a repository one), and either
     * starts with `./` / `../` or begins with a directory name that exists
     * somewhere in the repository — so `reports/name.md` in a user-facing example
     * is left alone while `domain/…` is not.
     *
     * It resolves if it names a file from the repository root, from the
     * document's directory, or — for a path without `..` — as the tail of a file's
     * path on a segment boundary: the documentation writes sources relative to
     * the package root (`data/mcp/KoogMcpClient.kt`) as often as from the root.
     *
     * @param docs Document text by repository-relative path.
     * @param files Every file of the repository, by repository-relative path.
     * @return The spans naming no file, and how many spans were read as paths.
     */
    fun checkCodePaths(docs: Map<String, String>, files: Set<String>): CodePathResult {
        val directoryNames = files.flatMapTo(HashSet()) { it.split('/').dropLast(1) }
        val violations = mutableListOf<Violation>()
        var checked = 0
        for ((path, text) in docs.entries.sortedBy { it.key }) {
            for (span in MarkdownLinks.codeSpansOf(text)) {
                val candidate = repositoryPathOf(span.text, directoryNames) ?: continue
                checked++
                if (!resolvesToFile(path, candidate, files)) {
                    violations += Violation(path, span.line, span.text, Reason.MISSING_CODE_PATH)
                }
            }
        }
        return CodePathResult(violations, checked)
    }

    /**
     * Reads a code span as a repository path, when it is written as one.
     *
     * @param span The span's content.
     * @param directoryNames Every directory name occurring in the repository.
     * @return The path part of the span, or `null` when the span is not a
     *   repository path (see [checkCodePaths] for the rules).
     */
    private fun repositoryPathOf(span: String, directoryNames: Set<String>): String? {
        if ('/' !in span || span.any { it.isWhitespace() } || "://" in span) return null
        val path = span.substringBefore('#').substringBefore(':')
        val isCandidate = !path.startsWith("/") &&
            SOURCE_EXTENSIONS.any { path.endsWith(it, ignoreCase = true) } &&
            !SHORTHAND.containsMatchIn(path) &&
            "build" !in path.split('/')
        if (!isCandidate) return null
        val explicitlyRelative = path.startsWith("./") || path.startsWith("../")
        return path.takeIf { explicitlyRelative || path.substringBefore('/') in directoryNames }
    }

    /**
     * Reports whether a path read from a code span names a file.
     *
     * @param document Path of the document holding the span.
     * @param path The span's path part.
     * @param files Every file of the repository.
     * @return `true` when the path names a file from the root, from the
     *   document's directory, or as a path tail.
     */
    private fun resolvesToFile(document: String, path: String, files: Set<String>): Boolean {
        if (normalize("", path) in files) return true
        if (normalize(parentOf(document), path) in files) return true
        if (path.split('/').contains("..")) return false
        val tail = normalize("", path) ?: return false
        return files.any { it.endsWith("/$tail") }
    }

    /**
     * Collects the `http` links alone, without touching the file system.
     *
     * The external-link report needs exactly this and nothing else. Having it
     * call [check] with a resolver that claims every path exists would work,
     * but it would be a lie in the code: the report would be computing internal
     * verdicts against a repository it invented, and discarding them.
     *
     * @param docs Document text by repository-relative path.
     * @return Every external link, in document order.
     */
    fun externalLinksOf(docs: Map<String, String>): List<ExternalLink> =
        docs.entries.sortedBy { it.key }.flatMap { (path, text) ->
            MarkdownLinks.linksOf(text)
                .filter { isExternal(it.target.trim()) }
                .map { ExternalLink(path, it.line, it.target.trim()) }
        }

    /**
     * Reports whether a target addresses somebody else's server.
     *
     * Shared by [check] and [externalLinksOf] so the gate and the report cannot
     * end up disagreeing about which links each one owns.
     *
     * @param target The destination as written, trimmed.
     * @return `true` for an absolute `http` URL.
     */
    private fun isExternal(target: String): Boolean =
        target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)

    /**
     * Resolves one internal link.
     *
     * @param file Path of the document holding the link.
     * @param line 1-indexed line the link was written on.
     * @param target The destination as written.
     * @param anchors Anchors of every scanned document, by path.
     * @param resolve What exists at a given repository-relative path.
     * @return The violation, or `null` when the link resolves.
     */
    private fun resolveOne(
        file: String,
        line: Int,
        target: String,
        anchors: Map<String, Set<String>>,
        resolve: (String) -> PathKind,
    ): Violation? {
        fun reject(reason: Reason) = Violation(file, line, target, reason)
        if (target.startsWith("/")) return reject(Reason.SITE_ABSOLUTE)
        val path = target.substringBefore('#')
        val anchor = target.substringAfter('#', missingDelimiterValue = "")
        val resolved = if (path.isEmpty()) file else normalize(parentOf(file), path) ?: return reject(
            Reason.OUTSIDE_REPOSITORY,
        )
        val kind = if (path.isEmpty()) PathKind.FILE else resolve(resolved)
        if (kind == PathKind.MISSING) return reject(Reason.MISSING_FILE)
        if (anchor.isEmpty()) return null
        if (kind == PathKind.DIRECTORY) return reject(Reason.ANCHOR_ON_DIRECTORY)
        if (!resolved.endsWith(".md", ignoreCase = true)) return null
        val targetAnchors = anchors[resolved] ?: return reject(Reason.UNSCANNED_TARGET)
        val decoded = decode(anchor)
        return if (targetAnchors.any { it.equals(decoded, ignoreCase = true) }) null else reject(Reason.MISSING_ANCHOR)
    }

    /**
     * Directory part of a repository-relative file path.
     *
     * @param file A repository-relative path such as `docs/faq.md`.
     * @return Its directory (`docs`), or the empty string for a root-level file.
     */
    private fun parentOf(file: String): String = file.substringBeforeLast('/', missingDelimiterValue = "")

    /**
     * Resolves a relative path against a directory, POSIX-style.
     *
     * @param base The directory the link is written from, `""` for the root.
     * @param relative The link's path part.
     * @return The repository-relative path, or `null` when it climbs above the
     *   repository root.
     */
    private fun normalize(base: String, relative: String): String? {
        val segments = mutableListOf<String>()
        if (base.isNotEmpty()) segments += base.split("/")
        for (segment in relative.split("/")) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.size - 1)
                else -> segments += segment
            }
        }
        return segments.joinToString("/")
    }

    /**
     * Percent-decodes an anchor.
     *
     * GitHub percent-encodes non-ASCII anchors in the links it generates while
     * the heading's own slug is the decoded text; comparing the two without
     * decoding would reject a working link.
     *
     * @param anchor The anchor as written, without the leading `#`.
     * @return The decoded anchor, or the input when it holds no valid escape.
     */
    private fun decode(anchor: String): String {
        if (!anchor.contains('%')) return anchor
        val bytes = ArrayList<Byte>(anchor.length)
        var index = 0
        while (index < anchor.length) {
            val character = anchor[index]
            if (character == '%' && index + 2 < anchor.length) {
                val hex = anchor.substring(index + 1, index + 3).toIntOrNull(radix = 16)
                if (hex != null) {
                    bytes += hex.toByte()
                    index += 3
                    continue
                }
            }
            bytes += character.toString().toByteArray(Charsets.UTF_8).toTypedArray()
            index++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
