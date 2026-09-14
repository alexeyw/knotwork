package app.knotwork.android.presentation.ui.common

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import app.knotwork.android.BuildConfig
import app.knotwork.android.domain.constants.DocumentationLinks
import app.knotwork.android.domain.constants.RepositoryLinks
import timber.log.Timber

/**
 * Opens a document from the [DocumentationLinks] registry.
 *
 * **The registry decides where it opens, and no caller knows the difference.**
 * A document that ships in the app goes to the in-app reader; one that lives on
 * the web goes to the browser. That is the whole point of `delivery` being a
 * registry field: the six entry points added with the link registry call this
 * and stayed unchanged when two of their documents moved into the APK.
 *
 * @param context Context used to start the browser.
 * @param id One of the `DocumentationLinks.ID_*` constants.
 * @param onOpenReader Navigates to the in-app reader. A caller with no reader
 *   to navigate to — a notification, say — may omit it, and a bundled document
 *   then opens on the web like any other.
 */
fun openDocumentation(context: Context, id: String, onOpenReader: ((String) -> Unit)? = null) {
    val entry = DocumentationLinks.byId(id)
    if (entry == null) {
        // Unreachable through the UI — every caller passes a generated
        // constant — but an unknown id must not look like a link that simply
        // did nothing.
        Timber.e("No documentation entry is registered under id '%s'", id)
        return
    }
    if (entry.delivery == DocumentationLinks.Delivery.BUNDLED && onOpenReader != null) {
        onOpenReader(id)
        return
    }
    openDocumentationUrl(context, documentationUrl(entry))
}

/**
 * Opens a registry document in the browser, whatever its delivery.
 *
 * The reader's own "Open in browser" action, and the fallback offered when a
 * bundled copy cannot be read.
 *
 * @param context Context used to start the browser.
 * @param id One of the `DocumentationLinks.ID_*` constants.
 */
fun openDocumentationInBrowser(context: Context, id: String) {
    val entry = DocumentationLinks.byId(id) ?: return
    openDocumentationUrl(context, documentationUrl(entry))
}

/**
 * Builds the URL a registry entry resolves to for this build.
 *
 * @param entry The registry entry.
 * @return The `blob` URL, pinned to this build's ref.
 */
fun documentationUrl(entry: DocumentationLinks.Entry): String =
    repositoryDocumentUrl(path = entry.path, anchor = entry.anchor)

/**
 * Builds the URL of any repository file a documentation link resolved to.
 *
 * The single place `BuildConfig.DOCS_REF` is read, and the single place the
 * legal-document exception is applied. A link inside a bundled document can
 * point at `../PRIVACY.md`, and what binds a user is the *current* privacy
 * policy rather than the edition current when they installed — so legal texts
 * resolve on the default branch while everything else follows this build's tag.
 *
 * @param path Repository-relative path of the target.
 * @param anchor Heading anchor without the leading `#`, or `null`.
 * @return The fully-formed `blob` URL.
 */
fun repositoryDocumentUrl(path: String, anchor: String? = null): String = if (RepositoryLinks.isLegalDocument(path)) {
    RepositoryLinks.legalDocumentUrl(path)
} else {
    RepositoryLinks.blobUrl(ref = BuildConfig.DOCS_REF, path = path, anchor = anchor)
}

/**
 * Starts the browser on [url], surviving a device with nothing able to open it.
 *
 * @param context Context used to start the browser.
 * @param url The URL to open.
 */
fun openDocumentationUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        .onFailure { Timber.w(it, "No activity could open the documentation link") }
}
