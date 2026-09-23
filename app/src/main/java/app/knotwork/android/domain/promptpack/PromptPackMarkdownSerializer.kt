package app.knotwork.android.domain.promptpack

import app.knotwork.android.domain.constants.PromptPresetConstants
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PromptPackCandidate
import app.knotwork.android.domain.models.PromptPackImportNotes
import app.knotwork.android.domain.models.PromptPackImportOutcome
import app.knotwork.android.domain.models.PromptPackParseError
import app.knotwork.android.domain.models.PromptPackVersionMismatch
import app.knotwork.android.domain.models.PromptPreset
import app.knotwork.android.domain.models.RefusedCapability
import app.knotwork.android.domain.text.toDisplaySafe

/**
 * Two-way mapper between a [PromptPreset] and its **prompt-pack** file form:
 * markdown with a YAML frontmatter block.
 *
 * ### The format
 *
 * ```markdown
 * ---
 * schemaVersion: 1
 * id: concise-assistant
 * name: Concise assistant
 * description: Single-paragraph answers, no preamble.
 * nodeType: LITE_RT
 * tags: [concise, starter]
 * ---
 * You are a helpful assistant. Answer in one paragraph...
 * ```
 *
 * Required: `name`, `nodeType`, and a non-empty body. Optional: `id` (falls
 * back to the file-name stem, mirroring how a bundled preset takes its id
 * from its file), `description`, `tags`, and `schemaVersion` (absent means
 * [CURRENT_SCHEMA_VERSION]; a hand-written file from a chat should not have
 * to carry a version stamp to be readable).
 *
 * ### Relationship to `SKILL.md`
 *
 * The shape is borrowed from the Agent Skills specification — a `---`-
 * delimited frontmatter block followed by a markdown body that *is* the
 * instruction. The semantics are deliberately **not**: a skill there is a
 * directory that may carry `scripts/`, its `name` must be a kebab-case
 * identifier matching that directory, and it may declare `allowed-tools`.
 * A prompt pack is one file, its `name` is a display name, and it declares
 * no capability at all. Conformance to that spec is therefore not claimed.
 * Its three purely descriptive optional keys ([TOLERATED_KEYS]) are accepted
 * and ignored so a file written for another runtime still imports without a
 * warning about keys the author had every reason to include.
 *
 * ### The capability ceiling
 *
 * **A prompt pack supplies wording and nothing else.** Every key in
 * [REFUSED_KEYS] is recognised precisely so it can be refused *and reported*
 * — a file that asks for tools, steps or scripts is imported as text with
 * the request named. There is no code path from this object to a tool
 * allowlist, a node, or an executable, and [PromptPackCandidate] cannot
 * express one.
 *
 * Pure `domain`: no Android imports, no I/O, and [parse] never throws.
 */
object PromptPackMarkdownSerializer {

    /**
     * Schema version emitted by [serialize].
     *
     * Bumping it is the signal to older readers that the on-disk shape
     * changed; they surface a [PromptPackVersionMismatch] instead of
     * silently dropping whatever is new.
     */
    const val CURRENT_SCHEMA_VERSION = 1

    /** Frontmatter keys this build reads and maps onto the model. */
    private val RECOGNISED_KEYS = setOf("schemaVersion", "id", "name", "description", "nodeType", "tags")

    /**
     * Keys other runtimes put in a `SKILL.md` frontmatter that carry no
     * capability and no meaning for us. Accepted and ignored in silence
     * because reporting them would train the user to dismiss the warning
     * that matters.
     */
    private val TOLERATED_KEYS = setOf("license", "compatibility", "metadata")

    /**
     * Keys that ask for a capability, mapped to the family they belong to.
     *
     * Listed rather than pattern-matched so the refusal is exhaustive and
     * auditable: adding a key here is the only way to widen what gets
     * reported, and nothing here is ever honoured.
     */
    private val REFUSED_KEYS: Map<String, RefusedCapability.Kind> = mapOf(
        "allowed-tools" to RefusedCapability.Kind.TOOLS,
        "allowedTools" to RefusedCapability.Kind.TOOLS,
        "tools" to RefusedCapability.Kind.TOOLS,
        "mcp" to RefusedCapability.Kind.TOOLS,
        "permissions" to RefusedCapability.Kind.TOOLS,
        "nodes" to RefusedCapability.Kind.STEPS,
        "steps" to RefusedCapability.Kind.STEPS,
        "pipeline" to RefusedCapability.Kind.STEPS,
        "scripts" to RefusedCapability.Kind.SCRIPTS,
    )

    /** Longest attacker-supplied value echoed back into a dialog. */
    private const val MAX_REPORTED_VALUE_LENGTH = 60

    /** Most attacker-supplied values echoed back into a dialog, per family. */
    private const val MAX_REPORTED_VALUES = 5

    /**
     * Renders [preset] into its prompt-pack file form.
     *
     * @param preset The prompt to write out. Bundled presets serialise just
     *   as user ones do — exporting is a read, and handing someone a copy of
     *   a curated prompt is the likeliest reason to export at all.
     * @return Markdown text ready to be written to a document.
     */
    fun serialize(preset: PromptPreset): String = buildString {
        appendLine("---")
        appendLine("schemaVersion: $CURRENT_SCHEMA_VERSION")
        appendLine("id: ${scalar(preset.id)}")
        appendLine("name: ${scalar(preset.name)}")
        if (preset.description.isNotBlank()) {
            appendLine("description: ${scalar(preset.description)}")
        }
        appendLine("nodeType: ${preset.nodeType.name}")
        if (preset.tags.isNotEmpty()) {
            // The inline form splits on commas before quotes are resolved, so
            // a tag carrying one would come back as two. Such a tag is against
            // convention (kebab-case, lower-case) but not impossible, and
            // silently rewriting someone's label is not ours to do — the block
            // form round-trips it exactly.
            if (preset.tags.any { it.contains(',') || it.contains('[') }) {
                appendLine("tags:")
                preset.tags.forEach { appendLine("  - ${scalar(it)}") }
            } else {
                appendLine("tags: [${preset.tags.joinToString(separator = ", ") { scalar(it) }}]")
            }
        }
        appendLine("---")
        appendLine()
        append(preset.systemPrompt.trim())
        appendLine()
    }

    /**
     * Suggests a file name for [preset]: the display name lower-cased with
     * runs of non-alphanumerics collapsed to single hyphens, plus `.md`.
     *
     * @param preset The prompt being exported.
     * @return A file-name suggestion for the document picker. Falls back to
     *   `prompt.md` when the name has no usable characters at all (a
     *   name made entirely of emoji, say), because an empty stem would
     *   produce a hidden dotfile.
     */
    fun suggestFileName(preset: PromptPreset): String {
        val stem = preset.name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return if (stem.isEmpty()) "prompt.md" else "$stem.md"
    }

    /**
     * Reads a prompt-pack document.
     *
     * @param document The full file text.
     * @param fallbackId Identifier to use when the frontmatter omits `id` —
     *   normally the picked file's name without its extension, which mirrors
     *   how a bundled preset takes its id from its file name and makes
     *   re-importing the same file update rather than duplicate.
     * @return [PromptPackImportOutcome.Success] when the file is clean,
     *   [PromptPackImportOutcome.Partial] when it produced a prompt but
     *   something had to be reported, or [PromptPackImportOutcome.Failure]
     *   naming why nothing could be imported.
     */
    @Suppress("ReturnCount") // One early return per refusal; collapsing them would hide which check fired.
    fun parse(document: String, fallbackId: String): PromptPackImportOutcome {
        val parsed = when (val result = PromptPackFrontmatterParser.parse(document)) {
            is FrontmatterParseResult.Invalid ->
                return PromptPackImportOutcome.Failure(PromptPackParseError.MalformedFrontmatter(result.reason))

            is FrontmatterParseResult.Parsed -> result
        }

        val name = parsed.scalar("name")
            ?: return PromptPackImportOutcome.Failure(PromptPackParseError.MissingRequiredKey("name"))
        if (name.isEmpty()) {
            return PromptPackImportOutcome.Failure(PromptPackParseError.MissingRequiredKey("name"))
        }
        if (name.length > PromptPresetConstants.MAX_NAME_LENGTH) {
            return PromptPackImportOutcome.Failure(
                PromptPackParseError.NameTooLong(PromptPresetConstants.MAX_NAME_LENGTH),
            )
        }

        val rawNodeType = parsed.scalar("nodeType")
            ?: return PromptPackImportOutcome.Failure(PromptPackParseError.MissingRequiredKey("nodeType"))
        val nodeType = NodeType.entries.firstOrNull { it.name == rawNodeType.uppercase() }
            ?: return PromptPackImportOutcome.Failure(PromptPackParseError.UnknownNodeType(sanitize(rawNodeType)))
        if (nodeType !in PromptPresetConstants.LLM_DRIVEN_NODE_TYPES) {
            return PromptPackImportOutcome.Failure(PromptPackParseError.NonLlmNodeType(nodeType))
        }

        val body = parsed.body.trim()
        if (body.isEmpty()) {
            return PromptPackImportOutcome.Failure(PromptPackParseError.MissingPromptText)
        }
        if (body.length > PromptPresetConstants.MAX_SYSTEM_PROMPT_LENGTH) {
            return PromptPackImportOutcome.Failure(
                PromptPackParseError.PromptTooLong(PromptPresetConstants.MAX_SYSTEM_PROMPT_LENGTH),
            )
        }

        val candidate = PromptPackCandidate(
            id = parsed.scalar("id")?.takeIf { it.isNotEmpty() } ?: fallbackId,
            name = name,
            description = parsed.scalar("description").orEmpty(),
            nodeType = nodeType,
            systemPrompt = body,
            tags = parsed.items("tags"),
        )

        val notes = PromptPackImportNotes(
            versionMismatch = versionMismatch(parsed),
            refused = refusals(parsed),
            droppedKeys = parsed.entries.keys.filterNot {
                it in RECOGNISED_KEYS || it in TOLERATED_KEYS || it in REFUSED_KEYS
            },
        )
        return if (notes.isEmpty) {
            PromptPackImportOutcome.Success(candidate)
        } else {
            PromptPackImportOutcome.Partial(preset = candidate, notes = notes)
        }
    }

    /**
     * Reads `schemaVersion`, reporting anything that is not the version this
     * build emits.
     *
     * An absent key is *not* a mismatch: a prompt pack is meant to be
     * writable by hand, and demanding a version stamp from someone pasting a
     * prompt into a text file would make the format's whole purpose harder.
     * A present-but-unparseable value is reported as version `0`, which no
     * build emits, so it cannot be mistaken for agreement.
     */
    private fun versionMismatch(parsed: FrontmatterParseResult.Parsed): PromptPackVersionMismatch? {
        val raw = parsed.scalar("schemaVersion") ?: return null
        val found = raw.toIntOrNull() ?: 0
        return if (found == CURRENT_SCHEMA_VERSION) {
            null
        } else {
            PromptPackVersionMismatch(foundVersion = found, expectedVersion = CURRENT_SCHEMA_VERSION)
        }
    }

    /**
     * Collects every capability the document asked for, grouped by family
     * and sanitised for display.
     */
    private fun refusals(parsed: FrontmatterParseResult.Parsed): List<RefusedCapability> = parsed.entries
        .mapNotNull { (key, value) -> REFUSED_KEYS[key]?.let { kind -> kind to value } }
        .groupBy({ it.first }, { it.second })
        .map { (kind, values) ->
            RefusedCapability(
                kind = kind,
                values = values.flatMap(::describe)
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(MAX_REPORTED_VALUES),
            )
        }
        // Stable order so the dialog does not reshuffle between reads of
        // the same file, and so the snapshot tests have one answer.
        .sortedBy { it.kind.ordinal }

    /**
     * Flattens one refused frontmatter value into the strings the dialog
     * lists. An `allowed-tools: Bash Read` scalar carries several names on
     * one line, which is how the Agent Skills spec spells that field.
     */
    private fun describe(value: FrontmatterValue): List<String> = when (value) {
        is FrontmatterValue.Scalar -> value.text.split(' ', ',').map(::sanitize)
        is FrontmatterValue.Items -> value.values.map(::sanitize)
        is FrontmatterValue.Block -> value.keys.map(::sanitize)
    }

    /**
     * Makes an attacker-supplied string safe to show: line breaks, control and
     * bidi characters out, whitespace runs collapsed, length clamped — the rule
     * every importer shares ([toDisplaySafe]).
     *
     * Without this a crafted file can write its own dialog — a break plus
     * enough characters turns a "left out" list item into what looks like the
     * app's own sentence. `U+2028` / `U+2029` can reach here through today's
     * grammar even though `\n` cannot: a value is one line by construction, but
     * a text renderer breaks on both separators and Kotlin's `lines()` does not.
     */
    private fun sanitize(raw: String): String = raw.toDisplaySafe(MAX_REPORTED_VALUE_LENGTH)

    /**
     * Quotes a scalar on the way out when leaving it bare would change how
     * it reads back — a leading bracket, hash or quote, a `: ` that would
     * look like a nested key, or a value that is empty. Newlines are folded
     * to spaces because the grammar has no multi-line scalar.
     */
    private fun scalar(value: String): String {
        val flat = value.replace(Regex("\\s*\\R\\s*"), " ").trim()
        val needsQuotes = flat.isEmpty() ||
            flat.first() in charArrayOf('[', '#', '"', '\'', '-') ||
            flat.contains(": ") ||
            flat.endsWith(':')
        if (!needsQuotes) return flat
        val escaped = flat.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    /** Reads [key] as a scalar, or `null` when absent or not a scalar. */
    private fun FrontmatterParseResult.Parsed.scalar(key: String): String? =
        (entries[key] as? FrontmatterValue.Scalar)?.text

    /**
     * Reads [key] as a list, tolerating a single scalar written without
     * brackets.
     *
     * Blanks are dropped whichever spelling the author used. The block form
     * (`- ""`) can otherwise carry an empty entry all the way into
     * `PromptPreset.tags`, where it renders as an empty chip in the picker's
     * tag filter — a control the user can neither name nor remove.
     */
    private fun FrontmatterParseResult.Parsed.items(key: String): List<String> = when (val value = entries[key]) {
        is FrontmatterValue.Items -> value.values
        is FrontmatterValue.Scalar -> value.text.split(',')
        else -> emptyList()
    }.map { it.trim() }.filter { it.isNotEmpty() }
}
