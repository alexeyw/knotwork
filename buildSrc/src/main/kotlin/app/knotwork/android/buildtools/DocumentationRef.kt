package app.knotwork.android.buildtools

/**
 * Decides which revision of the repository a build's documentation links point
 * at.
 *
 * **Why this is a build-type decision and not a string operation.** A release
 * build must link at the tag of the version the user installed: linking at
 * `main` would hand a store user a document describing a build they do not
 * have — the same class of defect as a guide sending a reader to a file that
 * does not exist for them. A debug build has no tag to point at, so it links at
 * `main`.
 *
 * The tempting shortcut is to read `versionName` and treat "carries a suffix"
 * as "is a debug build", because today only the debug build type sets
 * `versionNameSuffix`. That makes the rule a side effect of an unrelated
 * string: the day a build type adds a suffix for another reason, the links
 * silently change target. The build type is the fact being asked about, so the
 * build type is what is passed in.
 *
 * It lives in `buildSrc` rather than in the app for a second reason: a unit
 * test can only ever observe the debug `BuildConfig`, so the release branch of
 * this rule is untestable anywhere else. Here both branches are ordinary
 * arguments.
 */
object DocumentationRef {

    /**
     * Returns the git ref a build of this kind should link its documentation at.
     *
     * @param release Whether this is the release build type.
     * @param versionName The build's version name, without any suffix — e.g.
     *   `0.9.0`. Read only when [release] is `true`.
     * @return `v<versionName>` for a release build, [DEFAULT_BRANCH] otherwise.
     */
    fun of(release: Boolean, versionName: String): String =
        if (release) "$TAG_PREFIX$versionName" else DEFAULT_BRANCH

    /** The branch a build without a tag of its own links at. */
    const val DEFAULT_BRANCH: String = "main"

    /** Prefix of the annotated tag cut for every published version. */
    private const val TAG_PREFIX = "v"
}
