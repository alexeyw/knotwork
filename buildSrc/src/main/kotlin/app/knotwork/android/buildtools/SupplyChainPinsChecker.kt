package app.knotwork.android.buildtools

/**
 * Guards the pins that decide what code the build executes before any of this
 * repository's own code runs.
 *
 * Two rules, one question — *can the same commit of this repository run
 * different bytes tomorrow?*
 *
 * - **Every third-party action is pinned to a full commit SHA.** A tag is a
 *   pointer its owner can move. The release job decodes the signing keystore and
 *   exports its passwords in the same job that runs `setup-gradle`, which owns the
 *   Gradle user home and its init scripts; a re-pointed tag on that action is a
 *   path to the signing key. The version goes in a trailing comment, because a
 *   bare SHA is unreadable and Dependabot rewrites the version only where the
 *   comment sits on the same line.
 * - **The Gradle distribution carries its SHA-256.** `validateDistributionUrl`
 *   checks that the URL is well formed, not the bytes behind it, and every job —
 *   the release job included — downloads and executes that distribution.
 *
 * Neither rule can check that a SHA is the commit its comment names, or that a
 * checksum is Gradle's: both are one lookup away when reviewing the change that
 * introduced them, and neither can be answered without the network. What these
 * rules catch is the pin *disappearing* — a new step copied from a README, a
 * `./gradlew wrapper` run without `--gradle-distribution-sha256-sum`.
 *
 * Pure `String -> List<Violation>` transforms: no file-system access.
 */
object SupplyChainPinsChecker {

    /**
     * One reference or property that does not pin what it names.
     *
     * @property file Path of the offending file, relative to the repository root.
     * @property line 1-based line of the reference, or 0 for a property that is
     *   missing altogether.
     * @property message What is wrong and what the fix looks like.
     */
    data class Violation(val file: String, val line: Int, val message: String) {
        /** One-line rendering for the build failure message. */
        override fun toString(): String = if (line > 0) "$file:$line: $message" else "$file: $message"
    }

    /**
     * A `uses:` key, as a list item or a plain mapping key, with its value and an
     * optional trailing comment. A line whose first non-blank character is `#`
     * never matches, so the workflows can discuss the old form in comments.
     */
    private val USES = Regex("""^\s*(?:-\s+)?uses:\s*(['"]?)([^'"\s#]+)\1\s*(?:#\s*(.*))?$""")

    /** A full, 40-character commit SHA. Abbreviated SHAs are prefixes, and a prefix can collide. */
    private val COMMIT_SHA = Regex("[0-9a-f]{40}")

    /** The comment Dependabot keeps in step with the SHA: a version such as `v6.1.0`. */
    private val VERSION_COMMENT = Regex("""v\d+(?:\.\d+)*\b.*""")

    /** A container image by content digest — the only immutable form a `docker://` reference has. */
    private val DOCKER_DIGEST = Regex("""docker://[^@\s]+@sha256:[0-9a-f]{64}""")

    /** The wrapper property, with a value that is at least shaped like a SHA-256. */
    private val DISTRIBUTION_SHA = Regex("""(?m)^distributionSha256Sum=([0-9a-f]{64})\s*$""")

    /**
     * Checks every action reference in one workflow (or composite action) file.
     *
     * A local reference (`./…`) passes: its contents are part of the commit being
     * built. A `docker://` reference passes only with a content digest. Anything
     * else must be `owner/repo[/path]@<40-hex SHA>` followed by a version comment.
     *
     * @param path Repository-relative path, used in the report only.
     * @param text File content.
     * @return Every unpinned reference, in file order.
     */
    fun checkWorkflow(path: String, text: String): List<Violation> =
        text.lines().mapIndexedNotNull { index, line ->
            val match = USES.find(line) ?: return@mapIndexedNotNull null
            val reference = match.groupValues[2]
            val comment = match.groupValues[3].trim()
            problemWith(reference, comment)?.let { Violation(path, index + 1, it) }
        }

    /**
     * Counts the action references [checkWorkflow] would examine in [text], local
     * ones included.
     *
     * The task sums this over every file and refuses a total of zero: a pattern
     * that silently stopped matching would otherwise pass every workflow there is.
     */
    fun countReferences(text: String): Int = text.lines().count { USES.containsMatchIn(it) }

    /**
     * Checks that the wrapper pins the distribution it downloads.
     *
     * @param path Repository-relative path of `gradle-wrapper.properties`.
     * @param text File content.
     * @return A violation when `distributionSha256Sum` is missing or is not 64
     *   lowercase hex characters; empty otherwise.
     */
    fun checkWrapperProperties(path: String, text: String): List<Violation> =
        if (DISTRIBUTION_SHA.containsMatchIn(text)) {
            emptyList()
        } else {
            listOf(
                Violation(
                    path,
                    0,
                    "no `distributionSha256Sum` (64 lowercase hex characters). Regenerate with " +
                        "`./gradlew wrapper --gradle-version <v> --gradle-distribution-sha256-sum <sum>`, " +
                        "taking the sum from https://gradle.org/release-checksums/.",
                ),
            )
        }

    /** Why [reference] does not pin what it names, or `null` when it does. */
    private fun problemWith(reference: String, comment: String): String? = when {
        reference.startsWith("./") -> null
        reference.startsWith("docker://") ->
            if (DOCKER_DIGEST.matches(reference)) null else "`$reference` is not pinned to an image digest (`@sha256:…`)"
        '@' !in reference -> "`$reference` names no ref; pin it to a full commit SHA"
        !COMMIT_SHA.matches(reference.substringAfterLast('@')) ->
            "`$reference` is pinned to a mutable ref; pin it to the full 40-character commit SHA, " +
                "with the version in a trailing comment (`@<sha> # vX.Y.Z`)"
        !VERSION_COMMENT.matches(comment) ->
            "`$reference` has no version comment; add `# vX.Y.Z` on the same line so a reader — and " +
                "Dependabot — can tell which release the SHA is"
        else -> null
    }
}
