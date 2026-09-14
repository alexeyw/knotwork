package app.knotwork.android.buildtools

/**
 * The documents that ship **inside** the APK, and the rules that make them
 * readable there.
 *
 * **Why a document has to earn this.** The in-app renderer is not GitHub. It
 * drops constructs it does not implement *silently* — no placeholder, no error
 * — so a document that renders perfectly in a browser can reach a user with
 * paragraphs missing and nothing to suggest anything is absent. The checks in
 * [violationsOf] are that difference, expressed as rules: each one names a
 * construct the renderer cannot show, measured against the renderer this build
 * actually links.
 *
 * **Why the index is generated rather than computed in the app.** The reader
 * needs two answers that only a Markdown reading can give — which heading an
 * anchor names, and where a relative link points. Both already have an owner
 * here ([MarkdownLinks] and [resolve]), and implementing them a second time in
 * `app` would mean two answers to one question, with the build's answer being
 * the one the gate verified and the app's being the one the user gets. So the
 * build ships the answers next to the documents: [renderIndex] writes what
 * [violationsOf] just proved.
 *
 * The consequence worth stating plainly: the app resolves **nothing**. It looks
 * a link target up in a table that the build already checked, and a lookup that
 * misses is a defect in this file, not a case for the app to handle cleverly.
 */
object BundledDocs {

    /** Directory the copies are written to, under `app/src/main/assets`. */
    const val ASSET_DIRECTORY: String = "docs"

    /** File name of the generated index, inside [ASSET_DIRECTORY]. */
    const val INDEX_FILE_NAME: String = "index.json"

    /** Shape version of the index, bumped when the reader's contract changes. */
    const val INDEX_FORMAT_VERSION: Int = 1

    /**
     * What a link target inside a bundled document turns out to mean.
     *
     * @property kind Discriminator written into the index.
     */
    sealed interface Target {

        val kind: String

        /**
         * A heading in the document the link was written in.
         *
         * @property anchor The anchor, without the leading `#`, or `null` when
         *   the link names the document itself and the reader returns to its
         *   top.
         */
        data class SameDocument(val anchor: String?) : Target {
            override val kind: String get() = "anchor"
        }

        /**
         * Another document that also ships in the APK.
         *
         * @property id Registry id of the target document.
         * @property anchor Heading anchor within it, or `null` for the top.
         */
        data class Bundled(val id: String, val anchor: String?) : Target {
            override val kind: String get() = "bundled"
        }

        /**
         * A repository file that is not bundled — opened in the browser.
         *
         * Carries the repository-relative path rather than a URL: the ref a
         * build links against is a property of the build, and the revision
         * rules (documents by tag, legal texts by default branch) live at the
         * app's edge where they are already written once.
         *
         * @property path Repository-relative path of the target.
         * @property anchor Heading anchor within it, or `null` for the top.
         */
        data class Repository(val path: String, val anchor: String?) : Target {
            override val kind: String get() = "repository"
        }
    }

    /**
     * Reports everything that would stop a bundled document from being readable
     * inside the app.
     *
     * The rules, and the defect each exists for:
     *  1. **No Mermaid blocks.** The renderer has no diagram support, so a
     *     diagram arrives as a fenced block of its own source code.
     *  2. **No images.** Nothing resolves a relative image path inside the APK,
     *     and the transformer is a no-op, so an image is an empty box.
     *  3. **No inline or block HTML.** Unsupported by the library by design
     *     (upstream issue #270, open): an HTML node falls through the renderer's
     *     `when` and is dropped without a trace. This is the rule that keeps
     *     `cookbook.md` out — 14 `<br>` tags inside its generated node-reference
     *     tables, each one silently welding a caption onto a code sample.
     *  4. **Every relative link resolves** — to a heading in this document, to
     *     another bundled document, or to a repository file that exists. An
     *     unresolvable link is worse here than on the web: there is no address
     *     bar to recover from, and the reader is offline by assumption.
     *  5. **Every targeted anchor is unique.** A heading written twice yields
     *     `slug` and `slug-1`, and both keep resolving after the sections are
     *     reordered — pointing somewhere else. The same rule
     *     [DocumentationLinkRegistry] applies to its own entries, applied to the
     *     links inside the text.
     *
     * @param documents Repository-relative path to full text, for every
     *   document the gate scanned — both the `docs` tree and the repository's
     *   root-level Markdown, since a bundled document may link to either.
     * @return One human-readable line per violation, empty when every bundled
     *   document is readable.
     */
    fun violationsOf(documents: Map<String, String>): List<String> {
        val violations = mutableListOf<String>()
        for (entry in bundledDocuments()) {
            val markdown = documents[entry.path]
            if (markdown == null) {
                violations += "`${entry.id}` is bundled but `${entry.path}` does not exist."
                continue
            }
            violations += unsupportedConstructsIn(entry.path, markdown)
            violations += unresolvableLinksIn(entry.path, markdown, documents)
        }
        return violations
    }

    /**
     * Renders the index the reader consults at runtime.
     *
     * @param documents Repository-relative path to full text.
     * @return The complete JSON text, ending in a newline.
     */
    fun renderIndex(documents: Map<String, String>): String = buildString {
        appendLine("{")
        appendLine("""  "formatVersion": $INDEX_FORMAT_VERSION,""")
        appendLine("""  "documents": [""")
        val entries = bundledDocuments()
        entries.forEachIndexed { index, entry ->
            val markdown = documents.getValue(entry.path)
            appendLine("    {")
            appendLine("""      "id": ${quote(entry.id)},""")
            appendLine("""      "path": ${quote(entry.path)},""")
            appendLine("""      "asset": ${quote(assetPathOf(entry.path))},""")
            appendObject("anchors", MarkdownLinks.anchorOffsets(markdown), trailingComma = true) { it.toString() }
            appendObject("links", resolvedLinksOf(entry.path, markdown), trailingComma = false) { renderTarget(it) }
            appendLine("    }${if (index == entries.lastIndex) "" else ","}")
        }
        appendLine("  ]")
        appendLine("}")
    }

    /**
     * The asset path a bundled document is copied to.
     *
     * @param path Repository-relative path of the document.
     * @return Path under `assets`, keeping the file name and flattening the
     *   `docs/` prefix onto [ASSET_DIRECTORY].
     */
    fun assetPathOf(path: String): String = "$ASSET_DIRECTORY/${path.substringAfterLast('/')}"

    /** Every registry entry naming a whole document that ships in the APK. */
    fun bundledDocuments(): List<DocumentationLinkRegistry.Entry> =
        DocumentationLinkRegistry.ENTRIES.filter {
            it.anchor == null && it.delivery == DocumentationLinkRegistry.Delivery.BUNDLED
        }

    /**
     * Resolves every relative link of a document to what the reader should do.
     *
     * @param path Repository-relative path of the document the links live in.
     * @param markdown Its full text.
     * @return Raw link target, exactly as written, to its resolved meaning.
     *   Absolute URLs are absent: the reader hands those to the browser
     *   unchanged and needs nothing from the build to do it.
     */
    fun resolvedLinksOf(path: String, markdown: String): Map<String, Target> {
        val resolved = linkedMapOf<String, Target>()
        for (link in MarkdownLinks.linksOf(markdown)) {
            if (isAbsolute(link.target)) continue
            resolve(path, link.target)?.let { resolved.putIfAbsent(link.target, it) }
        }
        return resolved
    }

    /**
     * Resolves one raw link target written inside a document.
     *
     * @param path Repository-relative path of the document holding the link.
     * @param target The destination exactly as written.
     * @return What the reader should do, or `null` when the target is not a
     *   relative reference this scheme covers.
     */
    fun resolve(path: String, target: String): Target? {
        if (isAbsolute(target)) return null
        val anchor = target.substringAfter('#', missingDelimiterValue = "").ifEmpty { null }
        val file = target.substringBefore('#')
        if (file.isEmpty()) return Target.SameDocument(anchor)
        val resolvedPath = normalise(path.substringBeforeLast('/'), file) ?: return null
        if (resolvedPath == path) return Target.SameDocument(anchor)
        val bundled = bundledDocuments().firstOrNull { it.path == resolvedPath }
        return if (bundled != null) Target.Bundled(bundled.id, anchor) else Target.Repository(resolvedPath, anchor)
    }

    /**
     * Reports the constructs the in-app renderer cannot show.
     *
     * @param path Repository-relative path, for the message.
     * @param markdown The document's full text.
     * @return One line per unsupported construct found.
     */
    private fun unsupportedConstructsIn(path: String, markdown: String): List<String> {
        val violations = mutableListOf<String>()
        MarkdownLinks.mermaidBlockLines(markdown).forEach { line ->
            violations += "`$path:$line` opens a Mermaid block. The in-app renderer draws no diagrams, so it " +
                "would show the diagram's source as a code block. Keep this document `REMOTE`, or drop the diagram."
        }
        MarkdownLinks.imageLines(markdown).forEach { line ->
            violations += "`$path:$line` embeds an image. Nothing resolves an image path inside the APK, so it " +
                "would render as an empty box."
        }
        MarkdownLinks.htmlTagLines(markdown).forEach { (line, tag) ->
            violations += "`$path:$line` contains the HTML tag `$tag`. The renderer drops HTML without a trace " +
                "(upstream issue #270), so the text around it would silently change meaning."
        }
        return violations
    }

    /**
     * Reports the links a reader could follow to nowhere.
     *
     * @param path Repository-relative path of the document under test.
     * @param markdown Its full text.
     * @param documents Every document the gate scanned.
     * @return One line per unresolvable link.
     */
    private fun unresolvableLinksIn(
        path: String,
        markdown: String,
        documents: Map<String, String>,
    ): List<String> {
        val violations = mutableListOf<String>()
        for (link in MarkdownLinks.linksOf(markdown)) {
            if (isAbsolute(link.target)) continue
            val where = "`$path:${link.line}`"
            val resolved = resolve(path, link.target)
            if (resolved == null) {
                violations += "$where links to `${link.target}`, which is not a relative reference this build " +
                    "can resolve. A bundled document may link inside `docs/` or to a root Markdown file."
                continue
            }
            val violation = when (resolved) {
                is Target.SameDocument -> anchorViolation(where, link.target, path, markdown, resolved.anchor)
                is Target.Bundled -> {
                    // Safe: a Bundled target is only ever constructed from an
                    // entry this very list produced.
                    val targetPath = bundledDocuments().first { it.id == resolved.id }.path
                    anchorViolation(where, link.target, targetPath, documents[targetPath], resolved.anchor)
                }

                is Target.Repository ->
                    anchorViolation(where, link.target, resolved.path, documents[resolved.path], resolved.anchor)
            }
            if (violation != null) violations += violation
        }
        return violations
    }

    /**
     * Checks one link's target document and anchor.
     *
     * @param where Document and line the link was written on.
     * @param target The raw link target, for the message.
     * @param targetPath Repository-relative path the link resolved to.
     * @param targetMarkdown The target's text, or `null` when it does not exist.
     * @param anchor The anchor the link names, or `null`/empty for the top.
     * @return The violation, or `null` when the link resolves.
     */
    private fun anchorViolation(
        where: String,
        target: String,
        targetPath: String,
        targetMarkdown: String?,
        anchor: String?,
    ): String? {
        if (targetMarkdown == null) {
            return "$where links to `$target`, and `$targetPath` does not exist."
        }
        if (anchor.isNullOrEmpty()) return null
        if (anchor !in MarkdownLinks.anchorsOf(targetMarkdown)) {
            return "$where links to `$target`, and no heading in `$targetPath` produces `#$anchor`."
        }
        val count = MarkdownLinks.headingSlugCounts(targetMarkdown)[anchor]
        if (count != null && count > 1) {
            return "$where links to `$target`, and $count headings in `$targetPath` carry that text. " +
                "Reordering them moves the link without breaking it — rename one heading."
        }
        return null
    }

    /**
     * Reports whether a link target addresses something outside the repository.
     *
     * @param target The raw link target.
     * @return `true` for anything carrying a URI scheme.
     */
    private fun isAbsolute(target: String): Boolean = SCHEME.containsMatchIn(target)

    /**
     * Resolves a relative reference against the directory it was written in.
     *
     * @param directory Repository-relative directory of the source document.
     * @param reference The reference, without any anchor.
     * @return The repository-relative path, or `null` when the reference climbs
     *   out of the repository.
     */
    private fun normalise(directory: String, reference: String): String? {
        val segments = mutableListOf<String>()
        if (!reference.startsWith('/')) segments += directory.split('/').filter { it.isNotEmpty() }
        for (segment in reference.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        return segments.joinToString("/").ifEmpty { null }
    }

    /**
     * Appends one JSON object member whose values are rendered by [render].
     *
     * @param name Member name.
     * @param values The map to write, in iteration order.
     * @param trailingComma Whether another member follows this one.
     * @param render Renders one value as JSON.
     */
    private fun <T> StringBuilder.appendObject(
        name: String,
        values: Map<String, T>,
        trailingComma: Boolean,
        render: (T) -> String,
    ) {
        val tail = if (trailingComma) "," else ""
        if (values.isEmpty()) {
            appendLine("""      "$name": {}$tail""")
            return
        }
        appendLine("""      "$name": {""")
        values.entries.forEachIndexed { index, (key, value) ->
            val comma = if (index == values.size - 1) "" else ","
            appendLine("""        ${quote(key)}: ${render(value)}$comma""")
        }
        appendLine("      }$tail")
    }

    /**
     * Renders one resolved target as a JSON object.
     *
     * @param target The target to render.
     * @return Its JSON form.
     */
    private fun renderTarget(target: Target): String = when (target) {
        is Target.SameDocument -> """{"kind": ${quote(target.kind)}, "anchor": ${quoteOrNull(target.anchor)}}"""
        is Target.Bundled ->
            """{"kind": ${quote(target.kind)}, "id": ${quote(target.id)}, "anchor": ${quoteOrNull(target.anchor)}}"""

        is Target.Repository ->
            """{"kind": ${quote(target.kind)}, "path": ${quote(target.path)}, """ +
                """"anchor": ${quoteOrNull(target.anchor)}}"""
    }

    /**
     * Quotes a string as a JSON value.
     *
     * @param value The string.
     * @return The quoted, escaped literal.
     */
    private fun quote(value: String): String = buildString {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    /**
     * Quotes a string, or renders JSON `null`.
     *
     * @param value The string, or `null`.
     * @return The quoted literal or `null`.
     */
    private fun quoteOrNull(value: String?): String = value?.let { quote(it) } ?: "null"

    /** A URI scheme at the start of a target, which makes it absolute. */
    private val SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")
}
