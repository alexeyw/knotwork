package app.knotwork.android.presentation.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import app.knotwork.android.R
import app.knotwork.android.domain.usecases.ImageAttachmentBlock
import app.knotwork.android.domain.usecases.LaunchSharePipelineUseCase
import app.knotwork.android.domain.usecases.ParseSharedContentUseCase
import app.knotwork.android.domain.usecases.ShareLaunchResult
import app.knotwork.android.presentation.ui.MainActivity
import app.knotwork.android.presentation.ui.navigation.ChatDeepLink
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Invisible entry-point activity that handles an `ACTION_SEND` share.
 *
 * It only translates the incoming intent into a [app.knotwork.android.domain.models.SharedPayload]
 * (via [ParseSharedContentUseCase]) and delegates the actual work to
 * [LaunchSharePipelineUseCase]; on success it deep-links the user into the
 * freshly created session so they watch the run live, then finishes. The
 * activity carries no UI of its own (transparent theme) — it is purely a router
 * from the OS share sheet into the agent.
 *
 * Lives in the presentation layer because the tap result deep-links into
 * [MainActivity] via the `knotwork://chat/{threadId}` navigation pattern, a
 * dependency the domain/data layers must not carry.
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    @Inject lateinit var parseSharedContent: ParseSharedContentUseCase

    @Inject lateinit var launchSharePipeline: LaunchSharePipelineUseCase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action != Intent.ACTION_SEND) {
            finish()
            return
        }

        val streamUri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val payload = parseSharedContent(
            mimeType = intent.type,
            text = intent.getStringExtra(Intent.EXTRA_TEXT),
            streamUri = streamUri?.toString(),
        )

        lifecycleScope.launch {
            val result = try {
                launchSharePipeline(
                    payload = payload,
                    reusedSessionName = getString(R.string.share_session_reused_name),
                    imageSessionName = getString(R.string.share_session_image_name),
                    contentSessionName = getString(R.string.share_session_content_name),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to launch share pipeline.")
                ShareLaunchResult.NothingShared
            }
            handleResult(result)
            finish()
        }
    }

    /** Routes the user according to the launch outcome. */
    private fun handleResult(result: ShareLaunchResult) {
        when (result) {
            is ShareLaunchResult.Launched -> openChatSession(result.sessionId)
            ShareLaunchResult.NotConfigured -> {
                toast(getString(R.string.share_not_configured))
                openAppHome()
            }
            ShareLaunchResult.NothingShared ->
                toast(getString(R.string.share_nothing_to_share))
            is ShareLaunchResult.Blocked -> toast(getString(blockedMessage(result.reason)))
            ShareLaunchResult.RateLimited -> toast(getString(R.string.share_rate_limited))
        }
    }

    /**
     * The notice for an image the pre-flight refused. The same decision as the
     * chat's, in shorter words: a toast shows at most two lines on Android 12+.
     */
    @StringRes
    private fun blockedMessage(reason: ImageAttachmentBlock): Int = when (reason) {
        ImageAttachmentBlock.CLOUD_ENTRY -> R.string.share_image_blocked_cloud
        ImageAttachmentBlock.MODEL_NO_VISION -> R.string.share_image_blocked_model_no_vision
        ImageAttachmentBlock.NO_VISION_STEP -> R.string.share_image_blocked_no_vision_step
    }

    /** Deep-links into the run's session via [MainActivity] with a synthesised back stack. */
    private fun openChatSession(sessionId: String) {
        ChatDeepLink.backStack(this, sessionId).startActivities()
    }

    /** Opens the app's home (chat) so the user can configure the surface. */
    private fun openAppHome() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
