package app.knotwork.android.buildtools

/**
 * Pure Markdown reader shared by every documentation check that has to look at
 * links: the blocking internal-link gate, the non-blocking external-link
 * report, and the heading anchors the first of those resolves against.
 *
 * It exists as one object on purpose. The gate and the report ask different
 * questions about the same links, and two extractors would answer them from two
 * slightly different readings of the same file — the gate green on a link the
 * report never saw, or the other way round. One reader cannot disagree with
 * itself.
 *
 * What it deliberately does **not** do is parse Markdown properly. It masks the
 * constructs that would otherwise produce phantom links (fenced code blocks,
 * inline code spans, HTML comments) and then scans for the two link forms the
 * project's documentation actually uses — inline `[text](target)` and reference
 * definitions `[label]: target` — plus autolinks. Consequences worth naming,
 * because they bound what the gate can promise:
 *
 *  - **Shortcut and collapsed reference usages** (`[label]` / `[label][]`) are
 *    not resolved. `CHANGELOG.md` is full of them and they are validated only
 *    in as much as their *definitions* are checked.
 *  - **Code spans are masked per line.** A span opened on one line and closed on
 *    the next keeps its links visible to the scanner.
 *  - **Link destinations are read within one line**, which is how every link in
 *    this repository is written.
 *
 * Line numbers survive every masking pass — a mask replaces characters, never
 * lines — so a violation can always name the line it came from.
 */
object MarkdownLinks {

    /**
     * One link destination found in a document.
     *
     * @property target The raw destination text, exactly as written — the
     *   checker, not the reader, decides what it means.
     * @property line 1-indexed line the destination was written on.
     */
    data class Link(val target: String, val line: Int)

    /** Opening or closing fence of a code block: three or more backticks or tildes. */
    private val FENCE = Regex("""^ {0,3}(`{3,}|~{3,})""")

    /** A reference-style link definition: `[label]: destination "optional title"`. */
    private val REFERENCE_DEFINITION = Regex("""^ {0,3}\[([^\]]+)]:\s+(\S+)""")

    /** An autolink: a bare URL wrapped in angle brackets. */
    private val AUTOLINK = Regex("""<((?:https?|mailto):[^>\s]+)>""")

    /** An explicit HTML anchor, which GitHub honours as a link target. */
    private val HTML_ANCHOR = Regex("""<a\s+(?:id|name)\s*=\s*"([^"]+)"""")

    /** An ATX heading, with its optional closing run of `#` characters. */
    private val HEADING = Regex("""^ {0,3}(#{1,6})\s+(.*?)\s*#*$""")

    /** A second-level ATX heading — the level [sectionCountOf] counts. */
    private val SECTION_HEADING = Regex("""^ {0,3}##\s+(.*?)\s*#*$""")

    /** A fence opening a Mermaid block, in either fence character. */
    private val MERMAID_FENCE = Regex("""^ {0,3}(?:`{3,}|~{3,})\s*mermaid\b""", RegexOption.IGNORE_CASE)

    /** An image: the link form with a leading `!`, which [inlineDestinations] deliberately keeps. */
    private val IMAGE = Regex("""!\[[^\]]*]\(""")

    /**
     * An HTML tag, strictly enough to exclude a Markdown autolink.
     *
     * [HTML_TAG] is deliberately not reused: it matches anything between angle
     * brackets, which includes `<https://example.com>` — a link, not a tag, and
     * one the renderer handles perfectly well. Requiring a tag name followed by
     * whitespace, `/` or `>` separates them, because a scheme is always followed
     * by `:`.
     */
    private val HTML_ELEMENT = Regex("""</?[A-Za-z][A-Za-z0-9-]*(?:\s[^<>]*)?/?>""")

    /**
     * Slugs of the headings a document uses to navigate itself.
     *
     * Matched on the slug rather than the raw text so casing and punctuation
     * ("See also" / "See Also:") cannot smuggle one back into the count.
     */
    private val NAVIGATION_SECTIONS = setOf("contents", "see-also")

    /** Inline image or link syntax inside a heading, reduced to its text by [slug]. */
    private val INLINE_LINK_IN_HEADING = Regex("""!?\[([^\]]*)]\([^)]*\)""")

    /** An HTML tag inside a heading, dropped by [slug] the way GitHub drops it. */
    private val HTML_TAG = Regex("""<[^>]+>""")

    /** Everything GitHub strips from a heading before it slugifies the rest. */
    private val NON_SLUG_CHARACTERS = Regex("""[^\p{L}\p{N}\s_-]""")

    /**
     * Extracts every link destination of a document.
     *
     * @param markdown The document's full text.
     * @return Inline destinations, reference definitions and autolinks, in the
     *   order they appear.
     */
    fun linksOf(markdown: String): List<Link> {
        val links = mutableListOf<Link>()
        maskedLines(markdown, maskCodeSpans = true).forEachIndexed { index, line ->
            val lineNumber = index + 1
            REFERENCE_DEFINITION.find(line)?.let { links += Link(it.groupValues[2], lineNumber) }
            AUTOLINK.findAll(line).forEach { links += Link(it.groupValues[1], lineNumber) }
            inlineDestinations(line).forEach { links += Link(it, lineNumber) }
        }
        return links
    }

    /**
     * Collects every anchor a link can target inside a document: the slug of
     * each heading, plus any explicit HTML anchor.
     *
     * Headings are read from text with code blocks masked but code **spans**
     * intact — a heading like `### The `check` task` owes part of its slug to
     * the text inside the span, and masking it would invent an anchor nobody
     * can link to.
     *
     * @param markdown The document's full text.
     * @return Every valid anchor, without the leading `#`.
     */
    fun anchorsOf(markdown: String): Set<String> {
        val anchors = linkedSetOf<String>()
        val used = mutableMapOf<String, Int>()
        for (line in maskedLines(markdown, maskCodeSpans = false)) {
            HTML_ANCHOR.findAll(line).forEach { anchors += it.groupValues[1] }
            val base = headingSlugOf(line) ?: continue
            val seen = used.getOrDefault(base, 0)
            used[base] = seen + 1
            anchors += if (seen == 0) base else "$base-$seen"
        }
        return anchors
    }

    /**
     * Counts how many headings produce each base slug.
     *
     * [anchorsOf] answers "can this anchor be linked to", which a duplicated
     * heading answers `true` to twice over: two headings spelled alike yield
     * `slug` and `slug-1`, and both are valid targets forever. That is exactly
     * the anchor nobody should depend on — reordering the sections keeps both
     * anchors alive while silently swapping what they point at. A caller that
     * has to refuse such an anchor needs the count, not the membership, so it
     * is read here rather than inferred from the anchor set (where `step-1`
     * from a heading "Step 1" is indistinguishable from an ordinal suffix).
     *
     * @param markdown The document's full text.
     * @return Base slug to the number of headings that produce it, in document
     *   order. Explicit HTML anchors are not counted: they are written by hand
     *   and carry no ordinal suffix.
     */
    fun headingSlugCounts(markdown: String): Map<String, Int> {
        val counts = linkedMapOf<String, Int>()
        for (line in maskedLines(markdown, maskCodeSpans = false)) {
            val base = headingSlugOf(line) ?: continue
            counts[base] = counts.getOrDefault(base, 0) + 1
        }
        return counts
    }

    /**
     * Finds the lines that open a Mermaid fenced block.
     *
     * Fence state is tracked here rather than reused from [maskedLines],
     * because masking is what *removes* fenced blocks — the construct being
     * looked for. Tracking it means a `mermaid` fence quoted **inside** another
     * fenced block is not reported: documentation about diagrams shows the
     * syntax, and a gate that fails a valid document teaches everyone to
     * distrust it.
     *
     * @param markdown The document's full text.
     * @return 1-indexed lines opening a Mermaid block at the top level.
     */
    fun mermaidBlockLines(markdown: String): List<Int> {
        val lines = mutableListOf<Int>()
        var fence: String? = null
        markdown.split("\n").forEachIndexed { index, line ->
            val opener = FENCE.find(line)?.groupValues?.get(1)
            when {
                fence == null && opener != null -> {
                    fence = opener
                    if (MERMAID_FENCE.containsMatchIn(line)) lines += index + 1
                }

                fence != null && opener != null &&
                    opener.first() == fence.first() && opener.length >= fence.length -> fence = null
            }
        }
        return lines
    }

    /**
     * Finds the lines carrying an image.
     *
     * @param markdown The document's full text.
     * @return 1-indexed lines on which an image is written.
     */
    fun imageLines(markdown: String): List<Int> =
        maskedLines(markdown, maskCodeSpans = true).mapIndexedNotNull { index, line ->
            (index + 1).takeIf { IMAGE.containsMatchIn(line) }
        }

    /**
     * Finds the HTML tags a document contains.
     *
     * Code blocks and code spans are masked first: documentation about HTML
     * quotes tags constantly, and a quoted tag renders as the text it is.
     *
     * @param markdown The document's full text.
     * @return 1-indexed line and the tag's text, one entry per tag.
     */
    fun htmlTagLines(markdown: String): List<Pair<Int, String>> =
        maskedLines(markdown, maskCodeSpans = true).flatMapIndexed { index, line ->
            HTML_ELEMENT.findAll(line).map { (index + 1) to it.value }.toList()
        }

    /**
     * Maps every anchor to the character offset of the line that produces it.
     *
     * This is what lets the in-app reader scroll to an anchor without a second
     * implementation of [slug]. The reader parses the document with its own
     * Markdown library and knows each rendered block's offset into the source;
     * it does not know which heading a GitHub anchor refers to, and teaching it
     * would be the second owner of the slug algorithm this object exists to
     * prevent. Shipping the answer instead keeps one owner.
     *
     * Offsets are counted over the **original** text while headings are
     * recognised in the **masked** text. Masking replaces a fenced block's
     * lines with empty ones — it preserves the line *count*, which is what makes
     * the two readings line up, but not their lengths, so an offset taken from
     * the masked text would drift by the width of every fence above it.
     *
     * @param markdown The document's full text.
     * @return Anchor without the leading `#`, to the offset of its line's first
     *   character. Ordinal duplicates (`slug-1`) are included, since a link may
     *   legitimately name one.
     */
    fun anchorOffsets(markdown: String): Map<String, Int> {
        val offsets = linkedMapOf<String, Int>()
        val used = mutableMapOf<String, Int>()
        val original = markdown.split("\n")
        val masked = maskedLines(markdown, maskCodeSpans = false)
        var offset = 0
        for (index in masked.indices) {
            val line = masked[index]
            HTML_ANCHOR.findAll(line).forEach { offsets.putIfAbsent(it.groupValues[1], offset) }
            headingSlugOf(line)?.let { base ->
                val seen = used.getOrDefault(base, 0)
                used[base] = seen + 1
                offsets.putIfAbsent(if (seen == 0) base else "$base-$seen", offset)
            }
            // +1 for the newline that `split` consumed. The last line has none,
            // but nothing is measured after it.
            offset += original[index].length + 1
        }
        return offsets
    }

    /**
     * Counts the lines of a document.
     *
     * @param markdown The document's full text.
     * @return Line count, counting a trailing newline as ending its line rather
     *   than opening an empty one — the number `wc -l` reports.
     */
    fun lineCountOf(markdown: String): Int = markdown.trimEnd('\n').count { it == '\n' } + 1

    /**
     * Counts a document's second-level sections, as a reader would.
     *
     * `##` rather than every heading, because that is the level the two bundled
     * documents structure themselves at — one `##` per question in the FAQ, one
     * per failure in the troubleshooting guide — and the number is shown to a
     * user choosing between documents, not to a tool.
     *
     * [NAVIGATION_SECTIONS] are excluded: a document's own contents list and its
     * see-also are navigation *about* the sections, and counting them would tell
     * the reader there are two more answers inside than there are.
     *
     * @param markdown The document's full text.
     * @return The number of `##` headings that carry content.
     */
    fun sectionCountOf(markdown: String): Int =
        maskedLines(markdown, maskCodeSpans = false)
            .mapNotNull { line -> SECTION_HEADING.matchEntire(line)?.groupValues?.get(1) }
            .count { slug(it) !in NAVIGATION_SECTIONS }

    /**
     * Reads one line as a heading and returns its base slug.
     *
     * The single owner of "this line is a heading, and this is the anchor it
     * contributes": [anchorsOf] and [headingSlugCounts] answer different
     * questions and must never answer them from two different readings.
     *
     * @param line One masked source line.
     * @return The heading's base slug, or `null` when the line is not a heading
     *   or slugifies to nothing.
     */
    private fun headingSlugOf(line: String): String? {
        val heading = HEADING.matchEntire(line) ?: return null
        return slug(heading.groupValues[2]).takeIf { it.isNotEmpty() }
    }

    /**
     * Reproduces GitHub's heading-to-anchor transform.
     *
     * The one rule that is easy to get wrong, and was: GitHub strips punctuation
     * **in place** and then replaces **each remaining space** with a hyphen. A
     * heading such as `the gate (`a` + `b`)` therefore keeps both spaces around
     * the `+` and slugifies to a *double* hyphen. Collapsing runs of whitespace
     * instead would report perfectly valid links as broken.
     *
     * @param headingText The heading's source text, without the leading `#`s.
     * @return The anchor GitHub would generate for it.
     */
    fun slug(headingText: String): String {
        var text = headingText.trim()
        text = INLINE_LINK_IN_HEADING.replace(text) { it.groupValues[1] }
        text = HTML_TAG.replace(text, "")
        text = text.replace("`", "").replace("*", "").replace("~", "")
        text = NON_SLUG_CHARACTERS.replace(text, "")
        return text.lowercase().replace(" ", "-")
    }

    /**
     * Blanks out the regions of a document that look like links but are not.
     *
     * Fenced code blocks and HTML comments are masked always; inline code spans
     * only for the link pass, since [anchorsOf] needs their text. Masking
     * replaces characters with spaces rather than deleting them, so every
     * remaining character keeps its line and column.
     *
     * @param markdown The document's full text.
     * @param maskCodeSpans Whether backtick spans are masked as well.
     * @return The document's lines, masked.
     */
    private fun maskedLines(markdown: String, maskCodeSpans: Boolean): List<String> {
        val lines = markdown.split("\n").toMutableList()
        var fence: String? = null
        var inComment = false
        for (index in lines.indices) {
            val line = lines[index]
            val fenceMatch = FENCE.find(line)?.groupValues?.get(1)
            if (fence == null && fenceMatch != null) {
                fence = fenceMatch
                lines[index] = ""
                continue
            }
            if (fence != null) {
                val closes = fenceMatch != null &&
                    fenceMatch.first() == fence.first() &&
                    fenceMatch.length >= fence.length
                if (closes) fence = null
                lines[index] = ""
                continue
            }
            val (masked, stillInComment) = maskComments(line, inComment)
            inComment = stillInComment
            lines[index] = if (maskCodeSpans) maskCodeSpans(masked) else masked
        }
        return lines
    }

    /**
     * Masks HTML comment text on one line.
     *
     * @param line The line to mask.
     * @param openOnEntry Whether a comment was already open when this line began.
     * @return The masked line, and whether a comment is still open after it.
     */
    private fun maskComments(line: String, openOnEntry: Boolean): Pair<String, Boolean> {
        if (!openOnEntry && !line.contains("<!--")) return line to false
        val masked = StringBuilder()
        var open = openOnEntry
        var index = 0
        while (index < line.length) {
            if (!open && line.startsWith("<!--", index)) {
                open = true
                masked.append("    ")
                index += 4
            } else if (open && line.startsWith("-->", index)) {
                open = false
                masked.append("   ")
                index += 3
            } else {
                masked.append(if (open) ' ' else line[index])
                index++
            }
        }
        return masked.toString() to open
    }

    /**
     * Masks the contents of inline code spans on one line.
     *
     * A span opens and closes on a run of backticks of equal length; an unclosed
     * run is left alone, since masking to the end of the line would hide real
     * links after a stray backtick.
     *
     * @param line The line to mask.
     * @return The line with span contents replaced by spaces.
     */
    private fun maskCodeSpans(line: String): String {
        if (!line.contains('`')) return line
        val masked = line.toCharArray()
        var index = 0
        while (index < masked.size) {
            if (masked[index] != '`') {
                index++
                continue
            }
            val openStart = index
            while (index < masked.size && masked[index] == '`') index++
            val openLength = index - openStart
            var scan = index
            while (scan < masked.size) {
                if (masked[scan] == '`') {
                    val closeStart = scan
                    while (scan < masked.size && masked[scan] == '`') scan++
                    if (scan - closeStart == openLength) {
                        for (position in openStart until scan) masked[position] = ' '
                        index = scan
                        break
                    }
                } else {
                    scan++
                }
            }
            if (scan >= masked.size) break
        }
        return String(masked)
    }

    /**
     * Reads every inline link destination on one line.
     *
     * Scans for the `](` seam rather than matching the whole construct, because
     * link *text* nests brackets freely and a regex over the whole form drops
     * those links silently. The destination itself is read with balanced
     * parentheses and an optional `<...>` wrapper, and an optional title is
     * discarded.
     *
     * @param line One masked line.
     * @return The destinations found, in order.
     */
    private fun inlineDestinations(line: String): List<String> {
        val destinations = mutableListOf<String>()
        var index = 0
        while (index < line.length - 1) {
            if (line[index] != ']' || line[index + 1] != '(' || isEscaped(line, index)) {
                index++
                continue
            }
            val destination = readDestination(line, index + 2)
            if (destination == null) {
                index += 2
                continue
            }
            if (destination.first.isNotEmpty()) destinations += destination.first
            index = destination.second
        }
        return destinations
    }

    /**
     * Reads one destination starting just after the opening parenthesis.
     *
     * @param line The line being scanned.
     * @param start Index of the first character after `(`.
     * @return The destination and the index just past the closing `)`, or `null`
     *   when the parenthesis never closes on this line.
     */
    private fun readDestination(line: String, start: Int): Pair<String, Int>? {
        var index = start
        val destination = StringBuilder()
        if (index < line.length && line[index] == '<') {
            index++
            while (index < line.length && line[index] != '>') destination.append(line[index++])
            if (index >= line.length) return null
            index++
        } else {
            var depth = 0
            while (index < line.length) {
                val character = line[index]
                if (character == '\\' && index + 1 < line.length) {
                    destination.append(line[index + 1])
                    index += 2
                    continue
                }
                if (character.isWhitespace() || (character == ')' && depth == 0)) break
                if (character == '(') depth++
                if (character == ')') depth--
                destination.append(character)
                index++
            }
        }
        while (index < line.length && line[index] != ')') index++
        if (index >= line.length) return null
        return destination.toString() to index + 1
    }

    /**
     * Reports whether the character at [index] is backslash-escaped.
     *
     * @param line The line being scanned.
     * @param index Index of the character in question.
     * @return `true` when an odd number of backslashes precedes it.
     */
    private fun isEscaped(line: String, index: Int): Boolean {
        var backslashes = 0
        var scan = index - 1
        while (scan >= 0 && line[scan] == '\\') {
            backslashes++
            scan--
        }
        return backslashes % 2 == 1
    }
}
