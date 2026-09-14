package app.knotwork.android.domain.models

/**
 * One document that ships inside the APK, as the reader needs it.
 *
 * **Why this carries answers rather than text to interpret.** Two questions
 * have to be answered before a documentation link can be followed — which
 * heading a `#anchor` names, and where a relative path points — and both are
 * Markdown questions with an owner in the build (`MarkdownLinks` and
 * `BundledDocs`). Answering them again here would mean two implementations of
 * the GitHub slug algorithm and two implementations of path resolution, with
 * the build's copy being the one the gate verified and this one being the one
 * the user gets. So the build ships the answers alongside the documents and
 * this model is where they land: [anchors] and [links] are lookup tables, not
 * inputs to a parser.
 *
 * @property id Registry id, matching a `DocumentationLinks.ID_*` constant.
 * @property path Repository-relative path of the source document.
 * @property assetPath Path of the copy inside the APK's assets.
 * @property anchors Anchor, without the leading `#`, to the character offset of
 *   the heading that produces it. The offset is into the document's own text,
 *   which is what lets the reader scroll to it without knowing what a heading
 *   is.
 * @property links Raw link target, exactly as written in the Markdown, to what
 *   the reader should do about it. Absolute URLs are absent: they go to the
 *   browser unchanged and need no entry.
 */
data class BundledDocument(
    val id: String,
    val path: String,
    val assetPath: String,
    val anchors: Map<String, Int>,
    val links: Map<String, DocumentationTarget>,
)

/**
 * What following a link inside a bundled document means.
 *
 * Resolved by the build, not here — see [BundledDocument].
 */
sealed interface DocumentationTarget {

    /**
     * A heading in the document the link was written in.
     *
     * @property anchor The anchor, or `null` to return to the document's top.
     */
    data class SameDocument(val anchor: String?) : DocumentationTarget

    /**
     * Another document that also ships in the APK.
     *
     * @property id Registry id of the target document.
     * @property anchor Heading anchor within it, or `null` for its top.
     */
    data class Bundled(val id: String, val anchor: String?) : DocumentationTarget

    /**
     * A repository file that is not bundled, opened in the browser.
     *
     * Carries a path rather than a URL on purpose: which revision a build links
     * against, and the separate rule that pins legal texts to the default
     * branch, both live at the app's edge where they are already written once.
     *
     * @property path Repository-relative path of the target.
     * @property anchor Heading anchor within it, or `null` for its top.
     */
    data class Repository(val path: String, val anchor: String?) : DocumentationTarget

    /**
     * An address outside the repository, opened in the browser as written.
     *
     * @property url The absolute URL.
     */
    data class External(val url: String) : DocumentationTarget
}
