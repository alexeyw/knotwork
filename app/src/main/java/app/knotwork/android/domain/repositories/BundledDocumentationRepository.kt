package app.knotwork.android.domain.repositories

import app.knotwork.android.domain.models.BundledDocument

/**
 * Access to the documentation that ships inside the APK.
 *
 * Reading is split from the index deliberately. The Help list needs to know
 * *which* documents are bundled before the user has chosen one, and it must be
 * able to say so with the radio off and without paying to read two files that
 * may never be opened; the reader then needs exactly one document's text.
 *
 * Both answer in the language the interface is shown in when the documents are
 * translated into it, and in English otherwise. Anchors are the English ones in
 * every language, so a link by anchor lands on the same section either way.
 */
interface BundledDocumentationRepository {

    /**
     * Loads the index of every bundled document.
     *
     * @return The documents in registry order, or a failure when the index is
     *   missing or unreadable — which, because the index ships with the build,
     *   means a damaged installation rather than anything the user did.
     */
    suspend fun documents(): Result<List<BundledDocument>>

    /**
     * Loads one bundled document's Markdown.
     *
     * @param id Registry id, matching a `DocumentationLinks.ID_*` constant.
     * @return The document's full text, or a failure when no bundled document
     *   carries that id or its copy cannot be read.
     */
    suspend fun content(id: String): Result<String>
}
