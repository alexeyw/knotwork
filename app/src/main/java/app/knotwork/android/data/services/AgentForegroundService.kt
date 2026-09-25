package app.knotwork.android.data.services

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.VisibleForTesting
import app.knotwork.android.R
import app.knotwork.android.domain.constants.NotificationChannels
import app.knotwork.android.domain.constants.NotificationIds
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.repositories.PowerStateRepository
import app.knotwork.android.domain.services.MemoryReembedScheduler
import app.knotwork.android.domain.usecases.AgentOrchestratorUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that keeps the Agent (and LiteRT-ML engine) alive in memory
 * while tasks are executing or while waiting for short periods.
 * Includes idle timeout logic to safely deallocate model resources if inactive.
 */
@AndroidEntryPoint
class AgentForegroundService : Service() {

    /**
     * Use case for managing the global state and execution flow of the agent.
     */
    @Inject
    lateinit var agentOrchestratorUseCase: AgentOrchestratorUseCase

    /**
     * The engine responsible for local LLM inference.
     */
    @Inject
    lateinit var llmEngine: LlmInferenceEngine

    /**
     * Repository for managing device power state and keeping the CPU awake.
     */
    @Inject
    lateinit var powerStateRepository: PowerStateRepository

    /**
     * Re-arms the background memory re-embed pass on startup when chunks remain
     * flagged — covers the case where the OS restarts this service without
     * `MainActivity` ever being created.
     */
    @Inject
    lateinit var memoryReembedScheduler: MemoryReembedScheduler

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var idleManager: AgentIdleManager
    private lateinit var powerManager: AgentPowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Whether the service currently holds the foreground status notification.
     * The notification is shown only while the agent is actively working and is
     * removed the moment it settles to idle, so this flag guards against
     * redundant [Service.startForeground] promotions and [stopForeground]
     * demotions on every state emission.
     */
    private var isForeground: Boolean = false

    /**
     * The status text currently posted to the notification, or `null` when no
     * notification is showing. Lets [promoteForeground] skip a redundant
     * `notify()` when the status is unchanged — during token streaming the state
     * text (e.g. "Answering...") is constant across every emission, so this
     * collapses thousands of per-token notification rebuilds into one.
     */
    private var lastNotifiedStatus: String? = null

    /** Lazily-resolved system notification manager used to update the status notification. */
    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /**
     * Content intent that opens the app's launcher activity, resolved once for
     * the service's lifetime. Cached because the target never changes and
     * rebuilding it per notification update would issue a PackageManager query
     * per streamed token.
     */
    private val contentPendingIntent: PendingIntent? by lazy {
        AgentForegroundNotification.launchContentIntent(this)
    }

    companion object {
        private const val WAKE_LOCK_TAG = "Knotwork:InferenceLock"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

        /**
         * `true` while an instance of this service is alive (between [onCreate]
         * and [onDestroy]).
         *
         * Lets headless background components — `AgentWorker` running in a
         * process the user never opened — decide who owns post-run engine
         * cleanup: when the service is alive its [AgentIdleManager] unloads the
         * model after the idle timeout, so the worker must leave the engine
         * alone; when it is not, nothing else would ever release the model
         * memory and the worker unloads eagerly after its run finishes.
         * `@Volatile` because the flag is written on the main thread and read
         * from worker threads. The setter is internal (not private) solely so
         * tests can reset the process-wide flag between Robolectric classes,
         * which share a classloader.
         */
        @Volatile
        var isRunning: Boolean = false
            @VisibleForTesting
            internal set
    }

    /**
     * Called by the system when the service is first created.
     * Initializes the notification channel, WakeLock, idle manager, power manager,
     * and starts observing agent state.
     */
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        // Promote immediately to satisfy the startForegroundService contract
        // (a notification must appear within a few seconds of the start). The
        // state collector below demotes as soon as the agent settles to idle,
        // so the notification is only present while there is active work.
        promoteForeground("Initializing…")

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)

        idleManager = AgentIdleManager(
            scope = serviceScope,
            engine = llmEngine,
            agentState = agentOrchestratorUseCase.globalState,
        )
        idleManager.startObserving()

        powerManager = AgentPowerManager(
            scope = serviceScope,
            powerStateRepository = powerStateRepository,
            engine = llmEngine,
        )
        powerManager.startObserving()

        // Self-heal the import re-embed pass even when the process is brought up
        // via the service rather than MainActivity (e.g. an OS service restart).
        serviceScope.launch(Dispatchers.IO) { memoryReembedScheduler.rearmIfPending() }

        serviceScope.launch {
            agentOrchestratorUseCase.globalState.collectLatest { state ->
                updateForegroundPresence(state)
                when (state) {
                    is AgentOrchestratorState.Loading,
                    is AgentOrchestratorState.Thinking,
                    is AgentOrchestratorState.ExecutingTool,
                    // Streaming the final answer is active CPU work too. Acquired
                    // explicitly because a run resumed from a HITL/clarification
                    // suspension can jump straight to Answering without passing
                    // through Loading/Thinking, which would otherwise leave the
                    // wake lock down (released on the suspension) during the decode.
                    is AgentOrchestratorState.Answering,
                    -> acquireWakeLock()
                    is AgentOrchestratorState.Idle,
                    is AgentOrchestratorState.Completed,
                    is AgentOrchestratorState.Error,
                    // Suspension states wait on a human (HITL approval,
                    // clarification, durable background park) that may never come
                    // soon — release the CPU wake lock instead of pinning it
                    // awake until the 10-minute safety timeout; the next active
                    // state re-acquires it when the user responds.
                    is AgentOrchestratorState.WaitingForApproval,
                    is AgentOrchestratorState.AwaitingClarification,
                    is AgentOrchestratorState.SuspendedInBackground,
                    -> releaseWakeLock()
                    else -> Unit
                }
            }
        }
    }

    /**
     * Acquires [wakeLock] if it is not already held, preventing the CPU from sleeping
     * during active inference or tool execution.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    /**
     * Releases [wakeLock] if it is currently held, allowing the CPU to sleep
     * once inference completes or an error occurs.
     */
    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun getStatusTextForState(state: AgentOrchestratorState): String = when (state) {
        is AgentOrchestratorState.Idle -> "Agent is waiting"
        is AgentOrchestratorState.Loading -> "Loading context..."
        is AgentOrchestratorState.Queued -> "Queued behind another run"
        is AgentOrchestratorState.Thinking -> "Agent is thinking..."
        is AgentOrchestratorState.ExecutingTool -> "Using tool: ${state.toolName}..."
        is AgentOrchestratorState.WaitingForApproval -> "Awaiting user confirmation..."
        is AgentOrchestratorState.AwaitingClarification -> "Awaiting user clarification..."
        is AgentOrchestratorState.WaitingForCeilingRaise -> "Waiting: the run reached a limit"
        is AgentOrchestratorState.SuspendedInBackground -> "Waiting for user response in background"
        is AgentOrchestratorState.ObservationResult -> "Processing tool result..."
        is AgentOrchestratorState.Answering -> "Answering..."
        is AgentOrchestratorState.Completed -> "Task completed"
        // Unreachable in practice, and deliberately says nothing about the
        // cause. `Error` is not active work, so `updateForegroundPresence`
        // demotes the notification rather than promoting it with this text.
        // Even if that changed, the cause does not belong here: `state.message`
        // is the diagnostic for a typed stop, and the sentence a person reads
        // is resolved once, in the presentation layer, for the chat and the
        // background-run notification. A foreground status line must not be a
        // third place to word it — and reaching into presentation from `data`
        // to do so would invert the dependency direction.
        is AgentOrchestratorState.Error -> "Run stopped"
        is AgentOrchestratorState.PipelineStage -> "Pipeline stage: ${state.stepInfo.nodeName}"
        is AgentOrchestratorState.PipelineTrace -> "Pipeline trace updated"
        is AgentOrchestratorState.ConsoleLog -> "Pipeline running..."
        is AgentOrchestratorState.NodeIO -> "Pipeline running..."
        // An advisory is not a change of activity: the run carries on, and the
        // strip above the composer is where the notice is worded.
        is AgentOrchestratorState.RunNotice -> "Pipeline running..."
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NotificationChannels.AGENT_FOREGROUND,
            getString(R.string.notifications_agent_foreground_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = getString(R.string.notifications_agent_foreground_channel_description)
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Reconciles the foreground notification with the agent [state]: shows it
     * (promoting the service to the foreground) while the agent is actively
     * working, and removes it once the work settles. Idle / terminal states and
     * human-wait states drop the notification so it never lingers after the
     * agent has finished — the latter are surfaced by their own dedicated
     * approval / clarification notifications instead of this generic one.
     *
     * @param state The latest global agent state.
     */
    private fun updateForegroundPresence(state: AgentOrchestratorState) {
        if (state.isActiveWork()) {
            promoteForeground(getStatusTextForState(state))
        } else {
            demoteForeground()
        }
    }

    /**
     * Whether this state represents active, user-relevant agent work that
     * warrants the foreground status notification. Exhaustive so a newly added
     * [AgentOrchestratorState] must consciously choose a side.
     *
     * @return `true` for in-flight work (loading / thinking / tool / streaming /
     *   pipeline progress); `false` for idle, terminal and human-wait states.
     */
    private fun AgentOrchestratorState.isActiveWork(): Boolean = when (this) {
        is AgentOrchestratorState.Loading,
        is AgentOrchestratorState.Thinking,
        is AgentOrchestratorState.ExecutingTool,
        is AgentOrchestratorState.ObservationResult,
        is AgentOrchestratorState.Answering,
        is AgentOrchestratorState.PipelineStage,
        is AgentOrchestratorState.PipelineTrace,
        is AgentOrchestratorState.ConsoleLog,
        is AgentOrchestratorState.NodeIO,
        // A notice is only ever raised mid-run, so the work is by definition
        // still in flight and the status notification stays up. It does not
        // carry the advisory's words: this line describes what the agent is
        // doing, and the advisory is worded once, in the chat.
        is AgentOrchestratorState.RunNotice,
        -> true
        is AgentOrchestratorState.Idle,
        is AgentOrchestratorState.Completed,
        is AgentOrchestratorState.Error,
        is AgentOrchestratorState.WaitingForApproval,
        is AgentOrchestratorState.AwaitingClarification,
        is AgentOrchestratorState.WaitingForCeilingRaise,
        is AgentOrchestratorState.SuspendedInBackground,
        // Never the global state (it describes the run that holds the worker), and a
        // waiting task does no work of its own.
        is AgentOrchestratorState.Queued,
        -> false
    }

    /**
     * Shows / updates the foreground status notification with [status]. On the
     * first call it promotes the service to the foreground; subsequent calls
     * only refresh the notification content. A foreground promotion forbidden
     * from the background (a headless run driving the global state, which owns
     * its own [AgentWorker] notification) is caught rather than crashing.
     *
     * @param status The status line rendered as the notification content text.
     */
    private fun promoteForeground(status: String) {
        if (isForeground) {
            // Already foreground: only re-post when the status text actually
            // changed, so a long stream of identical states does not flood the
            // NotificationManager with no visible difference.
            if (status == lastNotifiedStatus) return
            notificationManager.notify(NotificationIds.AGENT_FOREGROUND, buildNotification(status))
            lastNotifiedStatus = status
            return
        }
        try {
            startForeground(
                NotificationIds.AGENT_FOREGROUND,
                buildNotification(status),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            isForeground = true
            lastNotifiedStatus = status
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // The app is in the background and the OS forbids a foreground start
            // from here — a headless/scheduled run driving the global state owns
            // its own notification via AgentWorker's foreground. Skip, don't crash.
            Timber.w(e, "Foreground promotion not allowed from background; skipping notification")
        }
    }

    /** Removes the foreground status notification, demoting the service (which stays alive). */
    private fun demoteForeground() {
        if (!isForeground) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForeground = false
        lastNotifiedStatus = null
    }

    private fun buildNotification(status: String): Notification = AgentForegroundNotification.build(
        context = this,
        contentTitle = getString(R.string.notifications_agent_foreground_title),
        contentText = status,
        contentIntent = contentPendingIntent,
    )

    /**
     * Called by the system every time a client explicitly starts the service.
     *
     * @param intent The Intent supplied to [android.content.Context.startService].
     * @param flags Additional data about this start request.
     * @param startId A unique integer representing this specific request to start.
     * @return The return value indicates what semantics the system should use for the service's current started state.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * Return the communication channel to the service. This service does not support binding.
     *
     * @param intent The Intent that was used to bind to this service.
     * @return null as binding is not supported.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Called by the system to notify a Service that it is no longer used and is being removed.
     * Cleans up coroutines and the LLM engine.
     */
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        releaseWakeLock()
        serviceScope.cancel()
        if (llmEngine.isInitialized) {
            llmEngine.close()
        }
    }
}
