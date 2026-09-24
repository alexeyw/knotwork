package app.knotwork.android.buildtools

/**
 * The commit identifier compiled into `BuildConfig.GIT_SHA`: the first
 * [LENGTH] characters of the full commit hash.
 *
 * It used to be `git rev-parse --short HEAD`, whose length is not a property of
 * the commit. `--short` prints the shortest prefix that is unambiguous *in the
 * repository it runs in*, never shorter than `core.abbrev` — so a shallow CI
 * clone printed 7 characters, a full clone of the same commit printed 8, and a
 * developer whose git config sets `core.abbrev` gets their own length again.
 * The string lands in `classes.dex`, so one commit built on two hosts produced
 * two different artefacts. Truncating the full hash depends on the commit alone.
 *
 * The build script runs [COMMAND] through `providers.exec` and passes the output
 * to [of]; the test runs the same command against real repositories.
 */
object GitCommitId {

    /** Characters kept: the length `--short` printed for this repository on a full clone. */
    const val LENGTH = 8

    /** Prints the full hash of `HEAD`, whatever the clone depth or git configuration. */
    val COMMAND: List<String> = listOf("git", "rev-parse", "HEAD")

    /** A full SHA-1 (40) or SHA-256 (64) object name. */
    private val FULL_HASH = Regex("[0-9a-f]{40}|[0-9a-f]{64}")

    /**
     * Derives the identifier from the output of [COMMAND].
     *
     * @param revParseOutput Standard output of [COMMAND], trailing newline included.
     * @return The first [LENGTH] characters of the hash, or `null` when the output
     *   is not a full hash (which is what the old `--short` command printed).
     */
    fun of(revParseOutput: String): String? =
        revParseOutput.trim().takeIf { FULL_HASH.matches(it) }?.take(LENGTH)
}
