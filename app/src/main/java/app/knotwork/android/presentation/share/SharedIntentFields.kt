package app.knotwork.android.presentation.share

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import timber.log.Timber

/**
 * The three values a share intent carries that [ShareReceiverActivity] reads:
 * its MIME type, its text and its stream.
 *
 * @property mimeType The intent's declared MIME type, as the sender set it.
 * @property text `EXTRA_TEXT`, or `null` when absent.
 * @property streamUri `EXTRA_STREAM` rendered as a string, or `null` when absent.
 */
data class SharedIntentFields(val mimeType: String?, val text: String?, val streamUri: String?) {

    companion object {
        /**
         * Reads the share fields out of a caller-supplied intent.
         *
         * Wrapped defensively, as the external-automation receiver's read is: the
         * share target is exported without a permission, so the extras come from
         * any installed app, and unmarshalling a malformed or hostile parcel
         * throws. An exception escaping `onCreate` would crash the app — and every
         * run in flight — on demand, before the binding check or the rate ceiling
         * has seen the share. An unreadable intent is treated as nothing shared.
         *
         * @param intent The `ACTION_SEND` intent the activity was started with.
         * @return The fields, or `null` when the extras cannot be read.
         */
        fun read(intent: Intent): SharedIntentFields? = try {
            SharedIntentFields(
                mimeType = intent.type,
                text = intent.getStringExtra(Intent.EXTRA_TEXT),
                streamUri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.toString(),
            )
        } catch (e: RuntimeException) {
            Timber.w(e, "Unreadable share extras; treating the share as empty")
            null
        }
    }
}
