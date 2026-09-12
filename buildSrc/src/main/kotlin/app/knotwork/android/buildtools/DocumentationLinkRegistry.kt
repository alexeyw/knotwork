package app.knotwork.android.buildtools

/**
 * The one list of documents the app links out to, and the rules that keep those
 * links pointing at something.
 *
 * **Why the build owns this list.** The registry has three readers: the app
 * (which builds the URLs), the anchor gate (which resolves them against
 * the `docs` directory), and — from the bundled-documentation work — the task that copies
 * a subset into the APK's assets. `buildSrc` cannot see `app` code, so exactly
 * one of the three can hold the list natively. Handing it to the build and
 * *generating* the app's copy is the only arrangement where the list **and** the
 * GitHub slug algorithm ([MarkdownLinks.slug]) each have a single owner. The
 * alternative considered and rejected was parsing the Kotlin object back out of
 * `app` with regular expressions: the one precedent for that in this repository
 * skips a record of unexpected shape *silently*, which turns a typo into a
 * missing gate rather than a failure.
 *
 * **Why the app's copy is committed.** It is an ordinary source file after
 * generation, so the app builds, and a reviewer reads it, without running any
 * task first. Drift is what the paired verification task refuses.
 */
object DocumentationLinkRegistry {

    /** Id of the full user guide. */
    const val ID_USER_GUIDE: String = "user-guide"

    /** Id of the frequently-asked-questions router. */
    const val ID_FAQ: String = "faq"

    /** Id of the pipeline cookbook. */
    const val ID_COOKBOOK: String = "cookbook"

    /** Id of the troubleshooting guide. */
    const val ID_TROUBLESHOOTING: String = "troubleshooting"

    /** Id of the external-automation contract. */
    const val ID_EXTERNAL_AUTOMATION: String = "external-automation"

    /** Id of the guide's "Adding an MCP server" section. */
    const val ID_MCP_SETUP: String = "mcp-setup"

    /** Id of the guide's "Triggers" section. */
    const val ID_TRIGGERS: String = "triggers"

    /**
     * One document (or one section of one) the app can open.
     *
     * @property id Stable key the UI names the entry by. Survives a file rename;
     *   the rename changes [path] and nothing else.
     * @property path Repository-relative path, always under `docs/` and always
     *   a `.md` file — see [violationsOf] for why that is a rule and not a
     *   coincidence.
     * @property anchor Heading anchor within the document, without the leading
     *   `#`, or `null` to open the document at the top.
     */
    data class Entry(val id: String, val path: String, val anchor: String?)

    /**
     * Every documented entry point, in the order the About screen lists them.
     *
     * The five whole documents come first because that is the reading order a
     * user arriving from the store needs (what the app is, then what to press,
     * then recipes, then what to do when it breaks); the two section entries
     * are contextual and reached from the screen they describe.
     */
    val ENTRIES: List<Entry> = listOf(
        Entry(id = ID_USER_GUIDE, path = "docs/user-guide.md", anchor = null),
        Entry(id = ID_FAQ, path = "docs/faq.md", anchor = null),
        Entry(id = ID_COOKBOOK, path = "docs/cookbook.md", anchor = null),
        Entry(id = ID_TROUBLESHOOTING, path = "docs/troubleshooting.md", anchor = null),
        Entry(id = ID_EXTERNAL_AUTOMATION, path = "docs/external-automation.md", anchor = null),
        Entry(id = ID_MCP_SETUP, path = "docs/user-guide.md", anchor = "adding-an-mcp-server"),
        Entry(id = ID_TRIGGERS, path = "docs/user-guide.md", anchor = "triggers"),
    )

    /**
     * Reports everything wrong with the registry, read against the real
     * documents.
     *
     * The rules, and the defect each exists for:
     *  1. **Ids are unique** — two entries under one id make the second
     *     unreachable, and `byId` would pick by declaration order.
     *  2. **Every path is under `docs/` and ends in `.md`** — this is what
     *     closes the gate's input set. The repository's other link gate
     *     (`verifyDocLinks`) is deliberately untracked because a link may
     *     address *any* path, so a cached pass could hide a target deleted
     *     after the last run. Confining every target to the `docs` tree makes the
     *     targets *themselves* declarable inputs, and deleting one changes the
     *     fingerprint — which is what lets this gate be typed and cacheable
     *     without lying.
     *  3. **The document exists** among the scanned set.
     *  4. **The anchor resolves** to a real heading or explicit HTML anchor.
     *  5. **The anchor's heading is unique** in its document. A heading text
     *     that appears twice yields `slug` and `slug-1`, and *both* keep
     *     resolving after the sections are reordered — pointing somewhere else.
     *     "The anchor exists" cannot catch that, so uniqueness is the rule.
     *     `docs/user-guide.md` carries a live example: "Background & triggers"
     *     is written twice.
     *
     * @param documents Repository-relative path to full text, for every
     *   document the gate scanned.
     * @return One human-readable line per violation, empty when the registry
     *   resolves completely.
     */
    fun violationsOf(documents: Map<String, String>): List<String> = violationsOf(ENTRIES, documents)

    /**
     * Resolves an arbitrary entry list — the rules of [violationsOf], applied to
     * a list the caller supplies.
     *
     * Exposed so each rule can be tested against a document written to break
     * exactly that rule. Asserting them through [ENTRIES] alone would only ever
     * prove that today's registry happens to be valid, which is the one thing
     * the gate already guarantees.
     *
     * @param entries The entries to resolve.
     * @param documents Repository-relative path to full text.
     * @return One human-readable line per violation.
     */
    fun violationsOf(entries: List<Entry>, documents: Map<String, String>): List<String> {
        val violations = mutableListOf<String>()
        for ((id, declarations) in entries.groupBy { it.id }) {
            if (declarations.size > 1) violations += "`$id` is declared ${declarations.size} times."
        }
        for (entry in entries) {
            // Shape before existence. An id becomes a Kotlin constant name and a
            // path is quoted into the generated file's KDoc, so an unexpected
            // character does not produce a bad link — it produces a file that
            // does not compile, and a Kotlin syntax error in generated code is a
            // far worse message than this one. (A `/` followed by `*` inside the
            // KDoc would open a nested block comment, and Kotlin's nest.)
            if (!ID_SHAPE.matches(entry.id)) {
                violations += "`${entry.id}` is not a valid id: expected lower-case words joined by hyphens, " +
                    "since the id becomes the generated constant `${constantNameOf(entry.id)}`."
                continue
            }
            if (!PATH_SHAPE.matches(entry.path)) {
                violations += "`${entry.id}` points at `${entry.path}`, which is not a plain document path."
                continue
            }
            val located = entry.path.startsWith(DOCS_PREFIX) && entry.path.endsWith(MARKDOWN_SUFFIX)
            if (!located) {
                violations += "`${entry.id}` points at `${entry.path}`, which is not a " +
                    "`$DOCS_PREFIX*$MARKDOWN_SUFFIX` document. Every target must be one, so the gate's " +
                    "inputs stay declarable."
                continue
            }
            val markdown = documents[entry.path]
            if (markdown == null) {
                violations += "`${entry.id}` points at `${entry.path}`, which does not exist."
                continue
            }
            violations += anchorViolationsOf(entry, markdown)
        }
        return violations
    }

    /**
     * Resolves one entry's anchor against its document.
     *
     * @param entry The registry entry under test.
     * @param markdown The target document's full text.
     * @return The violations the anchor produces; empty for an entry with no
     *   anchor, or one resolving to a single heading.
     */
    private fun anchorViolationsOf(entry: Entry, markdown: String): List<String> {
        val anchor = entry.anchor ?: return emptyList()
        if (anchor !in MarkdownLinks.anchorsOf(markdown)) {
            return listOf("`${entry.id}` targets `${entry.path}#$anchor`, which no heading produces.")
        }
        val headings = MarkdownLinks.headingSlugCounts(markdown)
        val duplicated = headings[anchor]?.let { it > 1 } == true
        if (duplicated) {
            return listOf(
                "`${entry.id}` targets `${entry.path}#$anchor`, and ${headings[anchor]} headings carry that text. " +
                    "Reordering them moves the anchor without breaking it — rename one heading.",
            )
        }
        val ordinalBase = ORDINAL_SUFFIX.find(anchor)?.let { anchor.removeSuffix(it.value) }
        if (ordinalBase != null && headings.containsKey(ordinalBase)) {
            return listOf(
                "`${entry.id}` targets `${entry.path}#$anchor`, an ordinal anchor: it exists only because " +
                    "`$ordinalBase` is written more than once, and it survives the reordering that moves it.",
            )
        }
        return emptyList()
    }

    /**
     * Renders the Kotlin source of the app-side copy.
     *
     * @param packageName Package the generated object is declared in.
     * @return The complete file text, ending in a newline.
     */
    fun render(packageName: String): String = buildString {
        appendLine("package $packageName")
        appendLine()
        appendLine("/**")
        appendLine(" * Every documentation entry point the app can open, generated from the build.")
        appendLine(" *")
        appendLine(" * DO NOT EDIT BY HAND. The list lives in `buildSrc`")
        appendLine(" * (`DocumentationLinkRegistry`) because the build resolves each entry's")
        appendLine(" * anchor against the real Markdown under `docs` before it lets a build pass, and")
        appendLine(" * `buildSrc` cannot read this module. Run `./gradlew :app:generateDocumentationLinks`")
        appendLine(" * and commit the result; `verifyDocumentationLinks` fails `check` on drift.")
        appendLine(" *")
        appendLine(" * The entries carry no URL: the repository ref a build links against depends")
        appendLine(" * on the build type, so the URL is assembled at the edge by")
        appendLine(" * `RepositoryLinks.blobUrl`.")
        appendLine(" */")
        appendLine("object DocumentationLinks {")
        appendLine()
        appendLine("    /**")
        appendLine("     * One document, or one section of one.")
        appendLine("     *")
        appendLine("     * @property id Stable key the UI names the entry by.")
        appendLine("     * @property path Repository-relative path under `docs/`.")
        appendLine("     * @property anchor Heading anchor without the leading `#`, or `null` for")
        appendLine("     *   the top of the document.")
        appendLine("     */")
        appendLine("    data class Entry(val id: String, val path: String, val anchor: String?)")
        appendLine()
        for (entry in ENTRIES) {
            appendLine("    /** Id of `${entry.path}${entry.anchor?.let { "#$it" } ?: ""}`. */")
            appendLine("    const val ${constantNameOf(entry.id)}: String = \"${entry.id}\"")
            appendLine()
        }
        appendLine("    /** Every entry, in the order the About screen lists them. */")
        appendLine("    val ENTRIES: List<Entry> = listOf(")
        for (entry in ENTRIES) {
            val anchor = entry.anchor?.let { "\"$it\"" } ?: "null"
            appendLine("        Entry(id = ${constantNameOf(entry.id)}, path = \"${entry.path}\", anchor = $anchor),")
        }
        appendLine("    )")
        appendLine()
        appendLine("    /**")
        appendLine("     * Looks an entry up by its stable id.")
        appendLine("     *")
        appendLine("     * @param id One of the `ID_` constants above.")
        appendLine("     * @return The entry, or `null` for an unknown id.")
        appendLine("     */")
        appendLine("    fun byId(id: String): Entry? = ENTRIES.firstOrNull { it.id == id }")
        appendLine("}")
    }

    /**
     * Derives the generated constant's name from an entry id.
     *
     * @param id The entry id, in kebab case.
     * @return The `ID_SCREAMING_SNAKE_CASE` constant name.
     */
    private fun constantNameOf(id: String): String = "ID_" + id.uppercase().replace('-', '_')

    /** Directory every target must live under. */
    private const val DOCS_PREFIX = "docs/"

    /** Extension every target must carry. */
    private const val MARKDOWN_SUFFIX = ".md"

    /** Trailing `-<digits>`, the shape GitHub gives a repeated heading. */
    private val ORDINAL_SUFFIX = Regex("""-\d+$""")

    /** Lower-case words joined by hyphens — what maps cleanly onto a constant name. */
    private val ID_SHAPE = Regex("""[a-z][a-z0-9]*(-[a-z0-9]+)*""")

    /**
     * Slash-separated segments of letters, digits, `.`, `_` and `-`.
     *
     * Deliberately narrower than "a legal filename": it excludes the `*` that
     * would turn the generated KDoc into a nested comment, and spaces, which no
     * document in this repository uses.
     */
    private val PATH_SHAPE = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)*""")
}
