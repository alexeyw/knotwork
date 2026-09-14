package app.knotwork.android.buildtools

/**
 * Pure scanner for vocabulary that must never reach a public reader of the
 * repository or a user of the app.
 *
 * Two families, both of which slipped past review for months because nothing
 * checked them:
 *
 *  1. **The retired product name**, in its prose, identifier and package forms.
 *     The onboarding title kept greeting every user with it for three months
 *     after the rename, shipped in every release including the one in Google
 *     Play, and was found only when a frame of a published demo video was
 *     inspected. The same pass found it in the GitHub issue-template chooser, in
 *     a wake-lock tag that Android vitals displays to the Play Console, and in
 *     the app theme's names.
 *  2. **Internal planning numbering** — a phase number, a task fraction, a
 *     phase branch path. The repository is public; that vocabulary lives in
 *     private planning documents and means nothing to an outside reader. A
 *     one-off clean-up had removed it once, but its search matched only one
 *     spelling, so the hyphenated, lower-case and run-together spellings it
 *     missed kept accumulating in KDoc behind a grep that reported zero.
 *
 * The scanner is a pure `Map<path, content> -> List<Violation>` transform with
 * no file-system access, so it is unit-tested directly; the Gradle task
 * ([VerifyForbiddenVocabularyTask]) resolves the file set and feeds it here.
 *
 * **Scope decisions that belong to the caller**, recorded so they are not
 * reopened: `CHANGELOG.md` is excluded, because its past entries name the
 * retired identifiers as they were at the time and rewriting history to
 * satisfy a gate would be the wrong repair; the lower-case hyphenated project
 * codename (the Gradle root-project name and the Firebase project id) is **not**
 * a forbidden form, because neither is visible to a user and the Firebase id
 * cannot be renamed.
 *
 * **What the numbering rule deliberately does not catch:** a single-digit phase.
 * Tests label the steps of a multi-stage scenario "Phase 1" … "Phase 4", and
 * those are not planning vocabulary. Every real planning phase in this project
 * has two digits, so requiring two digits separates the two cleanly; a
 * historical single-digit reference would slip through, and today none exists.
 * The opposite trade is knowingly accepted for the task-fraction rule: a literal
 * progress label shaped like it (a word "task", digits, a slash, digits) in a
 * preview fixture would be flagged. Production strings build such labels from
 * placeholders, which do not match.
 *
 * Every pattern is assembled from fragments so that this file's own source —
 * which the task scans like any other public source — does not match itself.
 */
object ForbiddenVocabularyChecker {

    /** Which kind of forbidden vocabulary a [Violation] belongs to. */
    enum class Family(val label: String) {
        /** A spelling of the product name the app carried before its rename. */
        RETIRED_PRODUCT_NAME("retired product name"),

        /** A phase number, task fraction or phase branch path from internal planning. */
        INTERNAL_PLANNING_NUMBER("internal planning number"),
    }

    /**
     * A single guard hit.
     *
     * @property file Path of the offending file, relative to the repository root.
     * @property line 1-indexed line on which the match starts.
     * @property token The matched text, with any line break or comment
     *   continuation inside it collapsed to a single space.
     * @property family Which forbidden family matched.
     */
    data class Violation(
        val file: String,
        val line: Int,
        val token: String,
        val family: Family,
    ) {
        /** Renders the violation in the canonical `path:line: message` failure format. */
        fun format(): String = "$file:$line: ${family.label} `$token`"
    }

    /**
     * What may separate the words of a multi-word name: whitespace, including a
     * line break, plus the comment-continuation marks a wrapped KDoc, line
     * comment or Markdown quote puts at the start of the next line.
     */
    private const val WORD_GAP = "[\\s*/#>]+"

    /** The retired name's three words, kept apart so no literal of it exists here. */
    private const val PLATFORM_WORD = "Andr" + "oid"
    private const val AI_WORD = "A" + "I"
    private const val AGENT_WORD = "Ag" + "ent"

    /**
     * Every rule, in reporting order within a line.
     *
     * - The three-word prose form is case-insensitive: the name was written in
     *   title case and in lower case, and neither is a phrase this product would
     *   use for itself today.
     * - The run-together identifier form is case-sensitive, so it catches theme
     *   names, wake-lock tags and composable names without matching ordinary
     *   words.
     * - The older title-case descriptor is case-sensitive on purpose: the
     *   lower-case "on-device AI agent" is the current tagline and must pass.
     * - The retired root package is matched with dots escaped.
     * - Phase numbers accept a space, a hyphen or nothing before the digits
     *   (all three spellings existed in the source), and require two digits.
     */
    private val RULES: List<Pair<Regex, Family>> = listOf(
        Regex(
            "\\b$PLATFORM_WORD$WORD_GAP$AI_WORD$WORD_GAP$AGENT_WORD\\b",
            RegexOption.IGNORE_CASE,
        ) to Family.RETIRED_PRODUCT_NAME,
        Regex(PLATFORM_WORD + AI_WORD + AGENT_WORD) to Family.RETIRED_PRODUCT_NAME,
        Regex("\\bOn-Device$WORD_GAP$AI_WORD$WORD_GAP$AGENT_WORD\\b") to Family.RETIRED_PRODUCT_NAME,
        Regex("\\b" + "ai" + "\\." + "agent" + "\\." + "android" + "\\b") to Family.RETIRED_PRODUCT_NAME,
        Regex("\\b" + "pha" + "se" + "[ \\t-]?\\d{2,}", RegexOption.IGNORE_CASE) to Family.INTERNAL_PLANNING_NUMBER,
        Regex("\\b" + "ta" + "sk" + "[ \\t]+\\d+/\\d+", RegexOption.IGNORE_CASE) to Family.INTERNAL_PLANNING_NUMBER,
        Regex("\\b" + "pha" + "se" + "/\\d+", RegexOption.IGNORE_CASE) to Family.INTERNAL_PLANNING_NUMBER,
    )

    /**
     * A line break together with the comment-continuation marks around it.
     *
     * Only a break is collapsed: a slash inside a single-line token (a branch
     * path, a task fraction) is part of what the reader must recognise.
     */
    private val TOKEN_GAP = Regex("[ \\t]*\\n[\\s*/#>]*")

    /**
     * Scans the supplied files for both families.
     *
     * @param files Map of repository-root-relative path to full file content. The
     *   caller restricts this to the public text contour and excludes
     *   `CHANGELOG.md`.
     * @return Every [Violation] found, ordered by file, then line, then family,
     *   so the failure message is stable and diff-friendly.
     */
    fun scan(files: Map<String, String>): List<Violation> {
        val violations = mutableListOf<Violation>()
        for ((path, content) in files) {
            val lineStarts = lineStartOffsets(content)
            for ((regex, family) in RULES) {
                regex.findAll(content).forEach { match ->
                    violations += Violation(
                        file = path,
                        line = lineOf(match.range.first, lineStarts),
                        token = match.value.replace(TOKEN_GAP, " "),
                        family = family,
                    )
                }
            }
        }
        return violations.sortedWith(compareBy({ it.file }, { it.line }, { it.family }))
    }

    /**
     * Offsets at which each line of [content] starts.
     *
     * @param content Full file text.
     * @return Ascending offsets; the first is always `0`.
     */
    private fun lineStartOffsets(content: String): IntArray {
        val starts = mutableListOf(0)
        content.forEachIndexed { index, char -> if (char == '\n') starts += index + 1 }
        return starts.toIntArray()
    }

    /**
     * Resolves a character offset to its 1-indexed line.
     *
     * @param offset Offset of the first character of a match.
     * @param lineStarts Result of [lineStartOffsets] for the same content.
     * @return The line number containing [offset].
     */
    private fun lineOf(offset: Int, lineStarts: IntArray): Int {
        val search = lineStarts.binarySearch(offset)
        // An exact hit is a match starting a line; otherwise binarySearch returns
        // -(insertion point) - 1 and the line is the one before that point.
        return if (search >= 0) search + 1 else -search - 1
    }
}
