package app.knotwork.android.presentation.tile

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import app.knotwork.android.R
import app.knotwork.android.domain.models.EntrySurface
import app.knotwork.android.domain.usecases.LaunchTilePipelineUseCase
import app.knotwork.android.domain.usecases.ResolveLaunchableSurfacePipelineUseCase
import app.knotwork.android.domain.usecases.TileLaunchResult
import app.knotwork.android.presentation.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Quick Settings tile that runs the user's bound "duty" pipeline with one tap
 * from the system shade.
 *
 * Because the shade can be pulled with the app fully backgrounded, the run is
 * dispatched through the background WorkManager path (see [LaunchTilePipelineUseCase]),
 * not the in-process interactive queue; the result is announced by the
 * scheduled-task notification with a deep-link into the run's session. When no
 * pipeline is bound the tile is inactive and a tap opens the app so the user can
 * configure it (privacy-first default — the tile does nothing until bound).
 */
@AndroidEntryPoint
class DutyPipelineTileService : TileService() {

    @Inject lateinit var launchTilePipeline: LaunchTilePipelineUseCase

    @Inject lateinit var resolveSurfacePipeline: ResolveLaunchableSurfacePipelineUseCase

    /** Service-scoped scope for the brief settings reads; cancelled in [onDestroy]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { refreshTileState() }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val result = try {
                launchTilePipeline(getString(R.string.tile_duty_prompt))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Quick Settings tile failed to launch the duty pipeline.")
                TileLaunchResult.NotConfigured
            }
            when (result) {
                TileLaunchResult.Launched ->
                    Toast.makeText(this@DutyPipelineTileService, getString(R.string.tile_running), Toast.LENGTH_SHORT)
                        .show()
                TileLaunchResult.NotConfigured -> openAppToConfigure()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Reflects the bound/unbound state onto the tile (active + ready, or inactive + prompt). */
    private suspend fun refreshTileState() {
        val bound = resolveSurfacePipeline(EntrySurface.QUICK_TILE) != null
        val tile = qsTile ?: return
        tile.state = if (bound) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = getString(
            if (bound) R.string.tile_subtitle_ready else R.string.tile_subtitle_unconfigured,
        )
        tile.updateTile()
    }

    /** Opens the app (collapsing the shade) so the user can bind a tile pipeline. */
    private fun openAppToConfigure() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        Toast.makeText(this, getString(R.string.tile_not_configured), Toast.LENGTH_LONG).show()
        startActivityAndCollapse(pending)
    }
}
