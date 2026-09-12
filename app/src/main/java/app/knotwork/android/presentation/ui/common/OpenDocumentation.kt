package app.knotwork.android.presentation.ui.common

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import app.knotwork.android.BuildConfig
import app.knotwork.android.domain.constants.DocumentationLinks
import app.knotwork.android.domain.constants.RepositoryLinks
import timber.log.Timber

/**
 * Opens a document from the [DocumentationLinks] registry in the browser.
 *
 * The single place `BuildConfig.DOCS_REF` is read. The registry says *which*
 * document an entry means; the ref says *which revision of the repository* this
 * build is entitled to show, and only the build knows that — which is why the
 * domain layer holds the path and never the URL.
 *
 * @param context Context used to start the browser.
 * @param id One of the `DocumentationLinks.ID_*` constants.
 */
fun openDocumentation(context: Context, id: String) {
    val entry = DocumentationLinks.byId(id)
    if (entry == null) {
        // Unreachable through the UI — every caller passes a generated
        // constant — but an unknown id must not look like a link that simply
        // did nothing.
        Timber.e("No documentation entry is registered under id '%s'", id)
        return
    }
    openDocumentationUrl(context, documentationUrl(entry))
}

/**
 * Builds the URL a registry entry resolves to for this build.
 *
 * @param entry The registry entry.
 * @return The `blob` URL, pinned to this build's ref.
 */
fun documentationUrl(entry: DocumentationLinks.Entry): String =
    RepositoryLinks.blobUrl(ref = BuildConfig.DOCS_REF, path = entry.path, anchor = entry.anchor)

/**
 * Starts the browser on [url], surviving a device with nothing able to open it.
 *
 * @param context Context used to start the browser.
 * @param url The URL to open.
 */
private fun openDocumentationUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        .onFailure { Timber.w(it, "No activity could open the documentation link") }
}
