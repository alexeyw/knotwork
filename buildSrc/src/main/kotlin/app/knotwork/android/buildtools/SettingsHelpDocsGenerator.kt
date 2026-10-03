package app.knotwork.android.buildtools

/**
 * Derives the settings reference table of `docs/user-guide.md` from the shipped
 * help strings, and injects it between dedicated `AUTO-GEN` markers.
 *
 * **Why generated rather than written.** One setting's meaning used to be
 * written in four places — the row subtitle in the catalog, the search-index
 * description, a second copy of the subtitle in the app resources, and the
 * guide's own prose. Closed testing found them already disagreeing: the
 * long-running-tasks row said "runs > 8 s", the search index said "runs long",
 * the guide said "exceeds the long-running threshold", and no constant in the
 * code held any such number. Prose kept in sync by review alone drifts, and a
 * reader has no way to tell which copy is the true one.
 *
 * So `strings_settings_help.xml` is the single source of truth and this
 * generator quotes it. The in-app hint and the documented sentence are the same
 * sentence, by construction rather than by discipline.
 *
 * **The failure mode this guards against.** A doc generator paired with its own
 * drift check can delete a real row and stay green: if the parser silently fails
 * to see a declaration, the generated block loses it and the verify task happily
 * confirms the shortened block matches. Every parse step here therefore
 * *counts*, and [render] refuses to emit a table that does not cover every
 * registry anchor exactly once. A parse miss becomes a build failure, never a
 * quietly shorter table.
 *
 * The prose of the `Settings` section — what each category is for, the measured
 * timeout and retry numbers, the behaviour notes — is hand-written and left
 * untouched. The generated table answers "what does this option mean"; the prose
 * answers "what happens when you change it".
 *
 * The entry points are [render] (pure transform used by both Gradle tasks) and
 * [drift] (comparison used by the verify task).
 */
object SettingsHelpDocsGenerator {

    /** Thrown when a source cannot be parsed, or the parsed rows do not line up. */
    class GenerationException(message: String) : RuntimeException(message)

    /** The single generated block this generator owns. */
    const val BLOCK_SETTINGS_HELP: String = "SETTINGS_HELP"

    /**
     * One row of the generated reference table.
     *
     * @property category Human-readable category title.
     * @property name The setting's display name, as the app shows it.
     * @property meaning The shipped help text, or `null` for a row that
     *   deliberately carries none.
     * @property noHintReason The recorded reason a row carries no help text;
     *   `null` when [meaning] is present.
     */
    data class HelpRow(
        val category: String,
        val name: String,
        val meaning: String?,
        val noHintReason: String?,
    )

    /**
     * Rebuilds the `AUTO-GEN` block of the guide.
     *
     * Pure and idempotent: `render(render(x)) == render(x)`.
     *
     * @param markdown Current contents of `docs/user-guide.md`.
     * @param registrySource Source of `SettingsRegistry.kt` — the anchor order.
     * @param helpCatalogSource Source of `SettingsHelpCatalog.kt`.
     * @param searchCatalogSource Source of `SettingsSearchCatalog.kt` — display names.
     * @param helpStringsXml Contents of `strings_settings_help.xml`.
     * @param nameStringsXml Contents of every values XML that holds a setting's
     *   display name. A name that lives in none of them fails generation rather
     *   than rendering an empty cell.
     * @return The Markdown with the block's body replaced.
     * @throws GenerationException when any source fails to parse, or the rows do
     *   not cover the registry exactly.
     */
    fun render(
        markdown: String,
        registrySource: String,
        helpCatalogSource: String,
        searchCatalogSource: String,
        helpStringsXml: String,
        nameStringsXml: List<String>,
    ): String {
        val rows = buildRows(registrySource, helpCatalogSource, searchCatalogSource, helpStringsXml, nameStringsXml)
        return replaceBlock(markdown, BLOCK_SETTINGS_HELP, renderTable(rows))
    }

    /**
     * Reports whether the committed guide has drifted from the sources.
     *
     * @return `true` when the committed block differs from a freshly rendered one.
     */
    fun drift(
        markdown: String,
        registrySource: String,
        helpCatalogSource: String,
        searchCatalogSource: String,
        helpStringsXml: String,
        nameStringsXml: List<String>,
    ): Boolean = render(
        markdown = markdown,
        registrySource = registrySource,
        helpCatalogSource = helpCatalogSource,
        searchCatalogSource = searchCatalogSource,
        helpStringsXml = helpStringsXml,
        nameStringsXml = nameStringsXml,
    ) != markdown

    /**
     * Assembles every table row, in the registry's ratified display order.
     *
     * @throws GenerationException when a registry anchor has no help decision or
     *   no display name, when a referenced string resource is missing or blank,
     *   or when the row count does not match the registry's.
     */
    fun buildRows(
        registrySource: String,
        helpCatalogSource: String,
        searchCatalogSource: String,
        helpStringsXml: String,
        nameStringsXml: List<String>,
    ): List<HelpRow> {
        val anchors = parseRegistryAnchors(registrySource)
        if (anchors.isEmpty()) throw GenerationException("No settings parsed from SettingsRegistry.kt.")
        val help = parseHelpCatalog(helpCatalogSource)
        val names = parseSearchNames(searchCatalogSource)
        val helpStrings = parseStrings(helpStringsXml)
        val nameStrings = nameStringsXml.fold(emptyMap<String, String>()) { acc, xml -> acc + parseStrings(xml) }

        val rows = anchors.map { (category, anchor) ->
            val decision = help[anchor]
                ?: throw GenerationException("SettingsHelpCatalog has no decision for registry anchor `$anchor`.")
            val nameRes = names[anchor]
                ?: throw GenerationException("SettingsSearchCatalog has no display name for anchor `$anchor`.")
            val name = nameStrings[nameRes]
                ?: throw GenerationException("String resource `$nameRes` (name of `$anchor`) not found.")
            val meaning = decision.helpRes?.let { res ->
                helpStrings[res]
                    ?: throw GenerationException("String resource `$res` (help for `$anchor`) not found.")
            }
            if (meaning != null && meaning.isBlank()) {
                throw GenerationException("Help text for `$anchor` is blank.")
            }
            HelpRow(
                category = categoryTitle(category),
                name = name,
                meaning = meaning,
                noHintReason = decision.noHintReason?.let(::noHintReason),
            )
        }
        // The guard the sibling generator learned the hard way: a parser that
        // silently drops a declaration produces a shorter table that its own
        // drift check then certifies as correct.
        //
        // Note carefully what this compares against, because two earlier drafts
        // got it wrong in the same way one level apart. Comparing `rows.size` to
        // `anchors.size` is worthless — both come from the registry parse, so
        // they agree exactly when that parse is wrong; the first draft did this
        // and emitted five rows for fifty-six settings, green. Re-counting the
        // registry with the same regex is worthless for the same reason.
        //
        // The count has to come from a **different file read by a different
        // parser**: the help catalogue, whose keys `SettingsHelpCatalogTest`
        // independently pins to the registry. A registry entry the walk above
        // fails to see now leaves `rows` short of `help`, and the build stops.
        if (rows.size != help.size) {
            throw GenerationException(
                "Rendered ${rows.size} rows but SettingsHelpCatalog decides ${help.size} settings. " +
                    "A registry entry was not parsed — fix the generator, not the table.",
            )
        }
        return rows
    }

    /** One row's help decision, as parsed out of the catalogue. */
    private data class Decision(val helpRes: String?, val noHintReason: String?)

    /**
     * Extracts `category -> anchor` pairs in declaration order, applying the same
     * anchor derivation as the domain's `SettingEntry.anchorKey()`.
     */
    private fun parseRegistryAnchors(source: String): List<Pair<String, String>> =
        LIST_RE.findAll(source).flatMap { list ->
            val category = list.groupValues[1]
            val listBody = list.groupValues[2]
            ENTRY_START_RE.findAll(listBody).map { start ->
                val kind = start.groupValues[1]
                val body = balancedArguments(listBody, start.range.last)
                val explicitKey = KEY_ARG_RE.find(body)?.groupValues?.get(1)
                    ?: FIRST_LITERAL_KEY_RE.find(body)?.groupValues?.get(1).takeIf { kind == "setting" }
                val anchor = when {
                    explicitKey != null -> explicitKey
                    kind == "link" -> "LINK_" + (
                        FIRST_STRING_RE.find(body)?.groupValues?.get(1)
                            ?: throw GenerationException("`link(...)` entry with no destination literal.")
                        ).uppercase().replace(NON_ALNUM, "_").trim('_')
                    else -> ROW_CONTROL_RE.find(body)?.groupValues?.get(1)
                        ?: throw GenerationException("`row(...)` entry with no control type.")
                }
                category to anchor
            }
        }.toList()

    /**
     * Returns the argument text of a call whose opening parenthesis sits at
     * [openParenIndex], balancing nested parentheses.
     *
     * A regex cannot do this: registry entries are written both on one line and
     * across several, and matching only the multi-line shape is exactly how the
     * first draft of this generator silently emitted five rows for fifty-six
     * settings.
     */
    private fun balancedArguments(source: String, openParenIndex: Int): String {
        var depth = 0
        for (i in openParenIndex until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(openParenIndex + 1, i)
                }
            }
        }
        throw GenerationException("Unbalanced parentheses in a SettingsRegistry entry.")
    }

    /** Extracts `anchor -> decision` from the help catalogue. */
    private fun parseHelpCatalog(source: String): Map<String, Decision> {
        val body = source.substringAfter("val HELP: Map<String, SettingHelp> = mapOf(", "")
        if (body.isEmpty()) throw GenerationException("`HELP` map not found in SettingsHelpCatalog.kt.")
        val text = HELP_TEXT_RE.findAll(body).map { it.groupValues[1] to Decision(it.groupValues[2], null) }
        // `notShipped(...)` rows are read exactly like `text(...)` ones: their
        // sentence already opens with "Not wired up yet", so nothing needs to
        // mark them a second time.
        val notShipped = HELP_NOT_SHIPPED_RE.findAll(body)
            .map { it.groupValues[1] to Decision(it.groupValues[2], null) }
        val none = HELP_NONE_RE.findAll(body).map { it.groupValues[1] to Decision(null, it.groupValues[2]) }
        return (text + notShipped + none).toMap()
    }

    /** Extracts `anchor -> name-resource` from the search catalogue. */
    private fun parseSearchNames(source: String): Map<String, String> {
        val body = source.substringAfter("val SEARCH_STRINGS: Map<String, SettingsSearchStrings> = mapOf(", "")
        if (body.isEmpty()) throw GenerationException("`SEARCH_STRINGS` map not found in SettingsSearchCatalog.kt.")
        val literal = SEARCH_NAME_RE.findAll(body).map { it.groupValues[1] to it.groupValues[2] }
        // One anchor is keyed by a constant rather than a literal, because it is
        // also referenced from the foss-variant filter.
        val viaConstant = SEARCH_NAME_CONST_RE.findAll(body).mapNotNull { match ->
            val constant = match.groupValues[1]
            val value = Regex("""const val $constant(?:: String)? = "([A-Z_0-9]+)"""").find(source)
                ?.groupValues?.get(1) ?: return@mapNotNull null
            value to match.groupValues[2]
        }
        return (literal + viaConstant).toMap()
    }

    /** Extracts `resource-name -> text` from a values XML file. */
    private fun parseStrings(xml: String): Map<String, String> =
        STRING_RE.findAll(xml).associate { it.groupValues[1] to unescape(it.groupValues[2]) }

    /** Renders the table body, grouped by category heading in registry order. */
    private fun renderTable(rows: List<HelpRow>): String {
        val out = StringBuilder()
        rows.groupBy { it.category }.forEach { (category, categoryRows) ->
            out.append("\n#### ").append(category).append("\n\n")
            out.append("| Setting | What it means |\n|---|---|\n")
            categoryRows.forEach { row ->
                val meaning = row.meaning ?: "*(no explanation — ${row.noHintReason})*"
                out.append("| **").append(row.name).append("** | ").append(escapePipes(meaning)).append(" |\n")
            }
        }
        return out.toString()
    }

    /**
     * Reader-facing wording for a no-hint reason.
     *
     * Written out rather than derived: lowercasing the enum name published
     * "Self evident" and "Behaviour not shipped" into the public guide, which
     * reads as a leaked constant. An unknown reason fails generation.
     */
    private fun noHintReason(raw: String): String = NO_HINT_REASONS[raw]
        ?: throw GenerationException("No reader-facing wording known for no-hint reason `$raw`.")

    private val NO_HINT_REASONS: Map<String, String> = mapOf(
        "LINK_ROW" to "opens a screen that explains itself",
        "DISPLAY_ROW" to "shows a value, decides nothing",
        "SELF_EVIDENT" to "does what its name says",
        "BEHAVIOUR_NOT_SHIPPED" to "behaviour not shipped yet",
    )

    /**
     * Display title for a category, keyed by the registry's Kotlin list name.
     *
     * Not derived from that name: the `PIPELINES` category was deliberately
     * renamed to "Run limits & structured output" (closed-test finding `#12` —
     * the old title promised the pipelines themselves), and humanising the
     * variable would have published the very title the rename removed. A
     * category missing from this table fails generation rather than shipping a
     * guessed heading.
     */
    private fun categoryTitle(raw: String): String = CATEGORY_TITLES[raw]
        ?: throw GenerationException("No display title known for settings category `$raw`.")

    private val CATEGORY_TITLES: Map<String, String> = mapOf(
        "GENERATION" to "Generation",
        "MODELS" to "Models",
        "MEMORY" to "Memory",
        "PIPELINES" to "Run limits & structured output",
        "TOOLS" to "Tools & workspace",
        "BACKGROUND" to "Background & triggers",
        "PRIVACY" to "Privacy",
        "ABOUT" to "About",
    )

    /** Table cells are pipe-delimited, so a literal pipe has to be escaped. */
    private fun escapePipes(text: String): String = text.replace("|", "\\|")

    /** Reverses the XML escaping applied when the strings were authored. */
    private fun unescape(text: String): String = text
        .replace("\\'", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .trim()

    /**
     * Replaces one block's body, leaving its markers and everything else intact.
     *
     * @throws GenerationException when the markers are missing or inverted.
     */
    private fun replaceBlock(markdown: String, block: String, body: String): String {
        val open = "<!-- AUTO-GEN:$block -->"
        val close = "<!-- /AUTO-GEN:$block -->"
        val start = markdown.indexOf(open)
        val end = markdown.indexOf(close)
        if (start < 0 || end < 0 || end < start) {
            throw GenerationException("Markers for block `$block` are missing or inverted in the Markdown.")
        }
        return markdown.substring(0, start + open.length) + body + markdown.substring(end)
    }

    private val LIST_RE = Regex("""private val (\w+)_ENTRIES = listOf\(([\s\S]*?)\n\)\n""")
    private val ENTRY_START_RE = Regex("""\n {4}(setting|link|row)\(""")
    private val KEY_ARG_RE = Regex("""key = "([A-Z_0-9]+)"""")
    private val FIRST_LITERAL_KEY_RE = Regex("""^\s*"([A-Z_0-9]+)"""")
    private val FIRST_STRING_RE = Regex(""""([^"]+)"""")
    private val ROW_CONTROL_RE = Regex("""^\s*\w+,\s*(\w+)""")
    private val NON_ALNUM = Regex("[^A-Z0-9]+")
    // `\s*` around every separator on purpose: ktlint wraps a long entry onto a
    // second line, and a regex that only matched the one-line shape would stop
    // seeing that row — silently, which is the whole failure mode this file is
    // written against. The count guard in [buildRows] catches it either way, but
    // a formatter should not be able to break the build at all.
    private val HELP_TEXT_RE = Regex(""""([A-Z_0-9]+)"\s+to\s+text\(\s*R\.string\.(\w+)\s*,?\s*\)""")
    private val HELP_NOT_SHIPPED_RE =
        Regex(""""([A-Z_0-9]+)"\s+to\s+notShipped\(\s*R\.string\.(\w+)\s*,?\s*\)""")
    private val HELP_NONE_RE = Regex(""""([A-Z_0-9]+)"\s+to\s+none\(\s*NoHint\.(\w+)\s*,?\s*\)""")
    private val SEARCH_NAME_RE = Regex(""""([A-Z_0-9]+)"\s+to\s+strings\(\s*R\.string\.(\w+)""")
    private val SEARCH_NAME_CONST_RE = Regex("""(\w+_ANCHOR)\s+to\s+strings\(\s*R\.string\.(\w+)""")
    private val STRING_RE = Regex("""<string name="(\w+)">([\s\S]*?)</string>""")

}
