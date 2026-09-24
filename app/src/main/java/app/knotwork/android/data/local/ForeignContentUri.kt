package app.knotwork.android.data.local

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import timber.log.Timber

/**
 * The rule a URI handed to the app by someone else must pass before the app
 * opens it: it is a `content://` URI served by **another** app's provider.
 *
 * `ContentResolver.openInputStream` runs with this app's identity, so without
 * the rule the caller borrows it. A `file://` URI names a path the app can read
 * and the caller cannot — its private `files/` and `databases/` included — and a
 * `content://` URI of the app's own provider reaches exactly the files that
 * provider was declared non-exported to protect (an unexported provider opens
 * freely for its own uid). The share target accepts `EXTRA_STREAM` from any app,
 * so this is the gate between that input and the app's private storage. It
 * follows Android's guidance on content-resolver risks: resolve the authority's
 * owner and refuse the app's own package.
 *
 * Other schemes the resolver accepts (`android.resource`) are refused too: no
 * caller of this rule has a use for them.
 *
 * The app's own captures do not come through here — a camera capture is read
 * from the file it was written to (`ImageCaptureStoreImpl`), which is why this
 * check can sit at the sink without breaking the camera.
 */
internal object ForeignContentUri {

    /**
     * Whether [uri] may be opened on behalf of whoever supplied it.
     *
     * @param context Application context; supplies the package name and the
     *   package manager that resolves the authority's owner.
     * @param uri The URI as received.
     * @return `true` for a `content://` URI whose provider belongs to another
     *   package; `false` for any other scheme, a missing authority, or a provider
     *   of this app.
     */
    fun isAcceptable(context: Context, uri: Uri): Boolean {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) return false
        // The resolver accepts `userId@authority` and resolves the part after `@`
        // (`ContentProvider.getAuthorityWithoutUserId`), so `0@<ours>` would reach
        // our own provider — compare what it resolves, not what was written.
        val authority = uri.authority?.substringAfterLast('@')?.takeIf { it.isNotBlank() } ?: return false
        return !belongsToThisApp(context, authority)
    }

    /**
     * Whether [authority] is served by this app. Two tests, either is enough: the
     * `${applicationId}.` prefix every provider in the merged manifest carries
     * (`fileprovider`, `androidx-startup`, `firebaseinitprovider`), and the owner
     * the package manager resolves — which also covers a library provider that
     * one day declares an authority without the prefix.
     */
    private fun belongsToThisApp(context: Context, authority: String): Boolean {
        val packageName = context.packageName
        if (authority.equals(packageName, ignoreCase = true)) return true
        if (authority.startsWith("$packageName.", ignoreCase = true)) return true
        val owner = try {
            context.packageManager.resolveContentProvider(authority, 0)?.packageName
        } catch (e: RuntimeException) {
            // A resolver that cannot answer leaves only the prefix test, which
            // has already passed; the open itself is then the provider's call.
            Timber.w("Content provider owner lookup failed (%s)", e.javaClass.simpleName)
            null
        }
        return owner == packageName
    }
}
