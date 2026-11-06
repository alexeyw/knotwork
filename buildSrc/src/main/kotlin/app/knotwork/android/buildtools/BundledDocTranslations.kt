package app.knotwork.android.buildtools

import java.security.MessageDigest

/**
 * Translations of the documents that ship inside the APK, and the rules that
 * keep them readable and current.
 *
 * A bundled document is written in English under `docs/`; its translation into
 * a language is written by hand at `app/src/main/assets/docs/<language>/<file>`
 * (decisions §113, R8). The reader shows the translation in that interface
 * language, and the app keeps linking to sections by their **English** anchors —
 * from its own screens, and from one document into another. So a translation
 * carries two lines of bookkeeping, both HTML comments the renderer drops:
 *
 * - **Its first line names the English it was translated from**, by hash:
 *   `<!-- translation of docs/faq.md, sha256 … -->`. When `docs/faq.md`
 *   changes, the hash no longer matches and the build fails until the
 *   translation is updated — a stale translation is a document that answers an
 *   older version of the app, offline, with nothing to say so.
 * - **Each heading is preceded by its English anchor**: `<!-- en: slug -->`.
 *   The index maps that anchor to the translated heading's offset, so every
 *   English anchor works in the translation, and the build requires exactly
 *   the English anchors, in the English order.
 *
 * Everything else holds as for the English: no diagrams, images or HTML, and
 * every relative link resolves — against the English anchors.
 */
object BundledDocTranslations {

    /** Name of a translation's generated index, inside its language directory. */
    const val INDEX_FILE_NAME: String = BundledDocs.INDEX_FILE_NAME

    private val SOURCE_LINE = Regex("""^<!-- translation of (\S+), sha256 ([0-9a-f]{64}) -->$""")
    private val ANCHOR_LINE = Regex("""^<!-- en: (\S+) -->$""")

    /**
     * The first line a translation of [path] must carry while [english] is its text.
     *
     * @param path Repository-relative path of the English document.
     * @param english The English document's full text.
     * @return The line, without its newline.
     */
    fun sourceLineFor(path: String, english: String): String =
        "<!-- translation of $path, sha256 ${sha256(english)} -->"

    /**
     * Where the translation of [path] into [language] lives, under `assets`.
     *
     * @param language Language tag of the translation.
     * @param path Repository-relative path of the English document.
     * @return The asset path.
     */
    fun assetPathOf(language: String, path: String): String =
        "${BundledDocs.ASSET_DIRECTORY}/$language/${path.substringAfterLast('/')}"

    /**
     * Maps each English anchor a translation marks to the offset of the
     * translated heading it marks.
     *
     * @param markdown The translation's full text.
     * @return English anchor to the offset of the heading line after its marker,
     *   in document order. A marker with no heading after it is left out —
     *   [violationsOf] reports it.
     */
    fun anchorOffsets(markdown: String): Map<String, Int> {
        val lines = markdown.split("\n")
        val headings = MarkdownLinks.headingLineIndices(markdown).toSet()
        val starts = lineStarts(lines)
        val offsets = linkedMapOf<String, Int>()
        lines.forEachIndexed { index, line ->
            val anchor = ANCHOR_LINE.matchEntire(line)?.groupValues?.get(1) ?: return@forEachIndexed
            if (index + 1 in headings) offsets.putIfAbsent(anchor, starts[index + 1])
        }
        return offsets
    }

    /**
     * Reports everything that would make a translation unreadable, stale, or
     * out of step with its English.
     *
     * @param language Language tag of the translations.
     * @param translations English document path to its translation's text, for
     *   every translation file found in the language's directory; a file that
     *   translates no bundled document is keyed by the path it would translate.
     * @param documents Repository-relative path to full text, as for
     *   [BundledDocs.violationsOf].
     * @return One human-readable line per violation, empty when every bundled
     *   document is translated and the translations hold.
     */
    fun violationsOf(
        language: String,
        translations: Map<String, String>,
        documents: Map<String, String>,
    ): List<String> {
        val violations = mutableListOf<String>()
        val bundled = BundledDocs.bundledDocuments()
        (translations.keys - bundled.map { it.path }.toSet()).sorted().forEach { path ->
            violations += "`${assetPathOf(language, path)}` translates `$path`, which is not a bundled document."
        }
        for (entry in bundled) {
            val english = documents[entry.path] ?: continue // BundledDocs.violationsOf reports it.
            val translation = translations[entry.path]
            val asset = assetPathOf(language, entry.path)
            if (translation == null) {
                violations += "`${entry.path}` is bundled but has no `$language` translation at `$asset`."
                continue
            }
            violations += staleness(asset, entry.path, english, translation)
            violations += anchorViolations(asset, english, translation)
            violations += BundledDocs.unsupportedConstructsIn(asset, translation)
            violations += unresolvableLinksIn(asset, entry.path, translation, documents)
        }
        return violations
    }

    /**
     * Renders the index the reader consults for [language].
     *
     * Each entry keeps the English document's id and path — a link to "open in
     * the browser" opens the English, which is the only one on the web — and
     * points its asset at the translation.
     *
     * @param language Language tag of the translations.
     * @param translations English document path to its translation's text.
     * @return The complete JSON text, ending in a newline.
     */
    fun renderIndex(language: String, translations: Map<String, String>): String = BundledDocs.renderIndexOf(
        BundledDocs.bundledDocuments().map { entry ->
            val markdown = translations.getValue(entry.path)
            BundledDocs.IndexEntry(
                id = entry.id,
                path = entry.path,
                asset = assetPathOf(language, entry.path),
                anchors = anchorOffsets(markdown),
                links = BundledDocs.resolvedLinksOf(entry.path, markdown),
            )
        },
    )

    /** Whether the translation was made from the English as it is now. */
    private fun staleness(asset: String, path: String, english: String, translation: String): List<String> {
        val expected = sourceLineFor(path, english)
        val first = translation.substringBefore('\n')
        val source = SOURCE_LINE.matchEntire(first)
        return when {
            source == null -> listOf(
                "`$asset` does not name the English it translates. Its first line must be:\n    $expected",
            )

            source.groupValues[1] != path -> listOf(
                "`$asset` names `${source.groupValues[1]}` as its English, but translates `$path`.",
            )

            first != expected -> listOf(
                "`$path` changed since `$asset` was translated from it. Bring the translation up to date with " +
                    "the English, then replace its first line with:\n    $expected",
            )

            else -> emptyList()
        }
    }

    /** Whether every heading carries an English anchor, and the anchors are the English ones, in order. */
    private fun anchorViolations(asset: String, english: String, translation: String): List<String> {
        val violations = mutableListOf<String>()
        val lines = translation.split("\n")
        val headings = MarkdownLinks.headingLineIndices(translation).toSet()
        val markers = mutableListOf<String>()
        lines.forEachIndexed { index, line ->
            val anchor = ANCHOR_LINE.matchEntire(line)?.groupValues?.get(1)
            if (anchor != null) {
                if (index + 1 in headings) {
                    markers += anchor
                } else {
                    violations += "`$asset:${index + 1}` marks `#$anchor`, but the next line is not a heading."
                }
            }
            if (index in headings && (index == 0 || ANCHOR_LINE.matchEntire(lines[index - 1]) == null)) {
                violations += "`$asset:${index + 1}` is a heading without its English anchor on the line " +
                    "above (`<!-- en: … -->`)."
            }
        }
        val expected = MarkdownLinks.anchorOffsets(english).keys.toList()
        val missing = expected - markers.toSet()
        val unknown = markers.toSet() - expected.toSet()
        val repeated = markers.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (missing.isNotEmpty()) violations += "`$asset` has no heading for the English anchors: $missing"
        if (unknown.isNotEmpty()) violations += "`$asset` marks anchors the English does not have: $unknown"
        if (repeated.isNotEmpty()) violations += "`$asset` marks these anchors more than once: $repeated"
        if (missing.isEmpty() && unknown.isEmpty() && repeated.isEmpty() && markers != expected) {
            violations += "`$asset` has the English sections in another order. Keep the English order: " +
                "the contents list and the section count follow it."
        }
        return violations
    }

    /** Every relative link in a translation, checked against the English anchors. */
    private fun unresolvableLinksIn(
        asset: String,
        path: String,
        translation: String,
        documents: Map<String, String>,
    ): List<String> {
        val violations = mutableListOf<String>()
        for (link in MarkdownLinks.linksOf(translation)) {
            val target = BundledDocs.resolve(path, link.target)
            if (target == null) {
                // Absolute URLs resolve to null and need nothing; anything else is a bad reference.
                if (!ABSOLUTE.containsMatchIn(link.target)) {
                    violations += "`$asset:${link.line}` links to `${link.target}`, which is not a relative " +
                        "reference this build can resolve."
                }
                continue
            }
            // An anchor into a bundled document is checked against its English:
            // every translation carries exactly the English anchors.
            val (targetPath, anchor) = when (target) {
                is BundledDocs.Target.SameDocument -> path to target.anchor
                is BundledDocs.Target.Bundled ->
                    BundledDocs.bundledDocuments().first { it.id == target.id }.path to target.anchor

                is BundledDocs.Target.Repository -> target.path to target.anchor
            }
            val where = "`$asset:${link.line}`"
            BundledDocs.anchorViolation(where, link.target, targetPath, documents[targetPath], anchor)
                ?.let { violations += it }
        }
        return violations
    }

    /** Offset of each line's first character. */
    private fun lineStarts(lines: List<String>): List<Int> {
        val starts = ArrayList<Int>(lines.size)
        var offset = 0
        for (line in lines) {
            starts += offset
            offset += line.length + 1
        }
        return starts
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** A URI scheme at the start of a target, which makes it absolute. */
    private val ABSOLUTE = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")
}
