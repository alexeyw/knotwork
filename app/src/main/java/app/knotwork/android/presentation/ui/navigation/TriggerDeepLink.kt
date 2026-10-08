package app.knotwork.android.presentation.ui.navigation

import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import app.knotwork.android.presentation.ui.MainActivity

/**
 * Builds the deep link into one trigger, `knotwork://triggers/{id}`, with the back
 * stack synthesised — the overdue-trigger notification's tap. The same shape as
 * [ChatDeepLink], so both notifications open the app the same way.
 */
object TriggerDeepLink {

    /**
     * The `ACTION_VIEW` intent targeting [MainActivity] for [triggerId].
     *
     * @param context Any context.
     * @param triggerId The trigger to open.
     * @return The intent.
     */
    fun intent(context: Context, triggerId: String): Intent = Intent(
        Intent.ACTION_VIEW,
        "${NavRoutes.TRIGGER_DEEP_LINK_PREFIX}${android.net.Uri.encode(triggerId)}".toUri(),
        context,
        MainActivity::class.java,
    )

    /**
     * A [TaskStackBuilder] carrying [intent] with the parent back stack synthesised.
     *
     * @param context Any context.
     * @param triggerId The trigger to open.
     * @return The builder.
     */
    fun backStack(context: Context, triggerId: String): TaskStackBuilder =
        TaskStackBuilder.create(context).apply { addNextIntentWithParentStack(intent(context, triggerId)) }
}
