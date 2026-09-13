package app.knotwork.android.presentation.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import app.knotwork.android.data.services.AgentForegroundService
import app.knotwork.android.data.services.AttachmentOrphanCleanupScheduler
import app.knotwork.android.data.services.MemoryCompactionScheduler
import app.knotwork.android.data.services.PendingInteractionMaintenanceScheduler
import app.knotwork.android.data.services.RunRetentionScheduler
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.MemoryReembedScheduler
import app.knotwork.android.domain.usecases.SyncTriggersUseCase
import app.knotwork.android.presentation.shortcuts.AppShortcutPublisher
import app.knotwork.android.presentation.state.ChatEntryRequestRelay
import app.knotwork.android.presentation.state.TransientMessageRelay
import app.knotwork.android.presentation.theme.KnotworkAppTheme
import app.knotwork.android.presentation.ui.navigation.AppNavGraph
import app.knotwork.android.presentation.ui.navigation.AppShellScaffold
import app.knotwork.android.presentation.ui.navigation.NavRoutes
import app.knotwork.android.presentation.ui.navigation.navigateToDeepLink
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The main activity of the application, serving as the entry point.
 * It sets up the platform splash, edge-to-edge insets, foreground service,
 * and the Compose nav graph hosted inside [AppShellScaffold].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var transientMessageRelay: TransientMessageRelay

    @Inject lateinit var memoryCompactionScheduler: MemoryCompactionScheduler

    @Inject lateinit var memoryReembedScheduler: MemoryReembedScheduler

    @Inject lateinit var pendingInteractionMaintenanceScheduler: PendingInteractionMaintenanceScheduler

    @Inject lateinit var runRetentionScheduler: RunRetentionScheduler

    @Inject lateinit var attachmentOrphanCleanupScheduler: AttachmentOrphanCleanupScheduler

    @Inject lateinit var appShortcutPublisher: AppShortcutPublisher

    @Inject lateinit var syncTriggersUseCase: SyncTriggersUseCase

    @Inject lateinit var chatEntryRequestRelay: ChatEntryRequestRelay

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _: Boolean ->
        // Permission outcome is observed lazily by features that need it.
    }

    /**
     * Bridges [onNewIntent] (a non-Compose Activity callback) into the Compose
     * NavController. Because [MainActivity] is `singleTask`, a deep link that
     * arrives while the app is already running reuses this instance and lands
     * here instead of a fresh `onCreate`; the collector in `setContent` routes
     * it. Buffered (capacity 1) so an emit that races composition is not lost.
     */
    private val deepLinkIntents = MutableSharedFlow<Intent>(extraBufferCapacity = 1)

    /**
     * Forwards a deep link delivered to the already-running single-task instance
     * (launcher shortcut / share / notification) to the live NavController. The
     * `onCreate` intent is auto-handled by the NavHost; this covers every
     * *subsequent* intent so a warm tap navigates in place instead of stacking a
     * second chat.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkIntents.tryEmit(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Platform splash (Android 12+) — must be installed BEFORE
        // `super.onCreate` so the system swaps the activity-theme splash
        // window for the SplashScreen-managed window before any content
        // attempts to draw. `Theme.App.Splash` supplies the accent-500
        // background and the brand-mark foreground; the post-splash theme
        // declared there takes over as soon as the first Compose frame
        // commits, so the platform splash hides exactly when the in-app
        // [SplashScreen][app.knotwork.android.presentation.ui.splash.SplashScreen]
        // composable becomes visible — no blank-frame flash.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Start the background agent service. Application init (incl. the
        // first-launch defaults that used to live here) is now driven by
        // the splash screen via `AppInitializationUseCase`.
        //
        // Only start it when it is not already running. The service shows its
        // foreground notification only while the agent is actively working and
        // drops to the background (demoted) when idle; calling
        // startForegroundService() again on an already-running, demoted service
        // would re-arm the 5-second startForeground() obligation that cannot be
        // met while idle, crashing with ForegroundServiceDidNotStartInTime — so
        // a plain Activity recreate (e.g. rotation) while idle must not re-issue
        // the start.
        if (!AgentForegroundService.isRunning) {
            val serviceIntent = Intent(this, AgentForegroundService::class.java)
            startForegroundService(serviceIntent)
        }

        // Schedule background long-term-memory maintenance off the main thread:
        // a daily charging + idle compaction pass plus an out-of-schedule watch
        // that drains the table when it grows past the configured hard limit.
        // Both calls are idempotent, so re-running them on activity recreation
        // is harmless.
        lifecycleScope.launch(Dispatchers.Default) {
            memoryCompactionScheduler.schedulePeriodic()
            memoryCompactionScheduler.startHardLimitWatch()
            // Expiry pass for runs parked on an unanswered background HITL
            // request — fails them once the approval window elapses.
            pendingInteractionMaintenanceScheduler.schedulePeriodic()
            // Retention pass over persisted pipeline runs and their traces —
            // same daily charging + idle window as memory compaction.
            runRetentionScheduler.schedulePeriodic()
            // Backstop sweep for orphaned image-attachment files (eager
            // delete-with-message handles the common case) — same window.
            attachmentOrphanCleanupScheduler.schedulePeriodic()
            // Self-heal: re-arm the import re-embed pass if a prior one-off was
            // lost or exhausted its retries. The check lives in the scheduler so
            // recovery isn't tied to this one entry point (the foreground service
            // re-arms too).
            memoryReembedScheduler.rearmIfPending()
            // Register the background watches for every enabled, pipeline-bound
            // automation trigger. Idempotent (replace/cancel keyed by trigger
            // id), so re-running on activity recreation is harmless; WorkManager
            // persists the watches across reboot, so this cold-start sync is the
            // only re-registration entry point needed.
            syncTriggersUseCase()
            // Refresh the dynamic launcher shortcuts (recent sessions) off the
            // main thread, as ShortcutManagerCompat requires. Only on a fresh
            // start (not a config-change recreation, which keeps savedInstanceState)
            // so rotation/theme changes don't re-query + re-publish needlessly.
            if (savedInstanceState == null) appShortcutPublisher.refresh()
        }

        // Pin transparent status- and navigation-bar
        // scrims so the Knotwork design system can paint surfaces all the
        // way to the device edges. `SystemBarStyle.auto(...)` flips between
        // the light- and dark-content variants based on the current theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        // The `knotwork://` deep link this cold launch carried (launcher
        // shortcut / share / notification), or null for a normal launch. The
        // NavHost no longer auto-handles it; the splash handler applies it
        // explicitly after landing on the chat home so the back stack is a
        // deterministic [CHAT_TAB, target]. Warm deep links (the activity is
        // `singleTask`) arrive via onNewIntent and are routed by the collector
        // below instead.
        val pendingDeepLink = intent?.takeIf { it.data?.scheme == NavRoutes.DEEP_LINK_SCHEME }

        setContent {
            KnotworkAppTheme {
                val navController = rememberNavController()

                // Route deep links that arrive on the already-running single-task
                // instance (onNewIntent) into the live NavController. The launch
                // intent itself is auto-handled by the NavHost, so this only
                // fires for warm shortcut / share / notification taps.
                LaunchedEffect(navController) {
                    deepLinkIntents.collect { newIntent ->
                        navController.navigateToDeepLink(newIntent, chatEntryRequestRelay)
                    }
                }
                // Onboarding gate: invert `hasCompletedOnboarding` instead
                // of reusing `isFirstLaunch` — the latter is cleared by
                // `InitializeAppUseCase` during cold-start init (which
                // runs while the splash screen is still in front), so by
                // the time `SplashScreen.onInitialized` fires it has
                // already been flipped to `false`. The dedicated
                // `hasCompletedOnboarding` flag survives initialization
                // and is the right gate for the UI surface. Re-emits if
                // the user resets onboarding from Settings.
                // Default `initial = false` (i.e. "treat as
                // returning user until DataStore confirms otherwise") so
                // we never flash onboarding for a returning user during
                // the brief read window; on a fresh install, the splash
                // screen blocks long enough for DataStore to emit the
                // real `false` value before the navigation decision.
                val hasCompletedOnboarding by settingsRepository.hasCompletedOnboarding
                    .collectAsState(initial = false)

                AppShellScaffold(
                    navController = navController,
                    transientMessageRelay = transientMessageRelay,
                ) { innerPadding ->
                    AppNavGraph(
                        navController = navController,
                        showOnboarding = !hasCompletedOnboarding,
                        pendingDeepLink = pendingDeepLink,
                        chatEntryRequestRelay = chatEntryRequestRelay,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
