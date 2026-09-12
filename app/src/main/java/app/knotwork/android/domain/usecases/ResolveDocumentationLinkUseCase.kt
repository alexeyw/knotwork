package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.BundledDocument
import app.knotwork.android.domain.models.DocumentationTarget
import javax.inject.Inject

/**
 * Decides what tapping a link inside a bundled document should do.
 *
 * **This deliberately resolves almost nothing.** Every relative target in a
 * bundled document was resolved at build time and shipped in
 * [BundledDocument.links], because path resolution and the GitHub slug
 * algorithm each already have exactly one owner in `buildSrc`, and a second
 * implementation here would be a second answer to the same question — the
 * build's verified by a gate, this one reaching the user. So the work here is a
 * lookup plus one rule the build cannot express: an absolute URL goes to the
 * browser as written.
 *
 * The invariant that makes the lookup total is enforced by `verifyBundledDocs`:
 * every relative link in a bundled document resolves, or the build fails. A
 * miss therefore means the index and the document disagree, which is a defect
 * in the build rather than a case to guess about — [invoke] reports it as
 * `null` rather than inventing a destination.
 */
class ResolveDocumentationLinkUseCase @Inject constructor() {

    /**
     * Resolves one raw link target.
     *
     * @param document The document the link was tapped in.
     * @param rawTarget The destination exactly as the Markdown wrote it — this
     *   is what the renderer hands back through `LocalUriHandler`.
     * @return What to do, or `null` when the target is neither an absolute URL
     *   nor a link the build resolved.
     */
    operator fun invoke(document: BundledDocument, rawTarget: String): DocumentationTarget? {
        val target = rawTarget.trim()
        if (target.isEmpty()) return null
        document.links[target]?.let { return it }
        // Not in the index. Only an absolute URL can legitimately get here: the
        // renderer skips index entries for them precisely because they need no
        // resolving.
        return if (SCHEME.containsMatchIn(target)) DocumentationTarget.External(target) else null
    }

    private companion object {
        /** A URI scheme at the start of a target, which makes it absolute. */
        val SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")
    }
}
