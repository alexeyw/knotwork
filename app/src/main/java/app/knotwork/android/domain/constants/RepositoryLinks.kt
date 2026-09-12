package app.knotwork.android.domain.constants

/**
 * The public repository, and the URLs the app builds from it.
 *
 * One object because there were three spellings of the same host before it: the
 * issue-tracker endpoint behind "Report response", the About screen's privacy
 * link, and a constant that existed only inside a test. Three copies of a URL
 * survive a repository rename by staying wrong in three different places —
 * GitHub keeps a redirect from the old name, but that redirect dies the moment
 * anyone registers a repository under it.
 *
 * **Two pinning rules live here, and they differ on purpose.**
 *  - **Documentation** is pinned to the *installed version's* tag. A store user
 *    reading `main` is reading about a build they do not have, which is how a
 *    guide ends up describing a screen that is not on their phone.
 *  - **Legal documents** — the privacy policy, the licence — are pinned to
 *    [DEFAULT_BRANCH] instead. What binds is the current edition, not the
 *    edition current when the user happened to install; and the privacy URL is
 *    additionally the one handed to the app stores, where a link that moves per
 *    release is a link that breaks.
 */
object RepositoryLinks {

    /** Canonical public repository URL, without a trailing slash. */
    const val REPOSITORY_URL: String = "https://github.com/alexeyw/knotwork"

    /** The branch legal documents resolve on — always the current edition. */
    const val DEFAULT_BRANCH: String = "main"

    /** `issues/new` endpoint, used to open a prefilled report. */
    const val ISSUES_NEW_URL: String = "$REPOSITORY_URL/issues/new"

    /**
     * Builds the URL of a file in the repository at a given revision.
     *
     * @param ref Git ref to resolve against — a version tag such as `v0.9.0`
     *   for a release build, or [DEFAULT_BRANCH]. Supplied by the caller rather
     *   than read here: the choice is a property of the build, and the domain
     *   layer does not see build configuration.
     * @param path Repository-relative path of the file.
     * @param anchor Heading anchor without the leading `#`, or `null` to open
     *   the file at the top.
     * @return The fully-formed `blob` URL.
     */
    fun blobUrl(ref: String, path: String, anchor: String? = null): String =
        "$REPOSITORY_URL/blob/$ref/$path" + (anchor?.let { "#$it" } ?: "")

    /**
     * Builds the URL of a legal document, which never follows the build's ref.
     *
     * @param path Repository-relative path of the document.
     * @return The `blob` URL on [DEFAULT_BRANCH].
     */
    fun legalDocumentUrl(path: String): String = blobUrl(ref = DEFAULT_BRANCH, path = path)

    /**
     * Reports whether a repository path is a legal document.
     *
     * Needed because a link inside a bundled document can address one: the FAQ
     * points at `../PRIVACY.md` twice, and resolving that against the installed
     * version's tag would quietly show the user the edition that was current
     * when they installed rather than the one that binds them. Keeping the rule
     * here, beside [legalDocumentUrl], is what stops it from being re-decided —
     * differently — at each place a link is turned into a URL.
     *
     * @param path Repository-relative path of the target.
     * @return `true` when the document's current edition is the binding one.
     */
    fun isLegalDocument(path: String): Boolean = path in LEGAL_DOCUMENTS

    /** Repository files whose current edition is the one that binds. */
    private val LEGAL_DOCUMENTS = setOf("PRIVACY.md", "LICENSE", "LICENSE.md")
}
