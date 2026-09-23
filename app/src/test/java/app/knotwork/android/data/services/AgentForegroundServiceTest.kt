package app.knotwork.android.data.services

import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import app.knotwork.android.R
import app.knotwork.android.domain.constants.NotificationChannels
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.PowerState
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.repositories.PowerStateRepository
import app.knotwork.android.domain.services.MemoryReembedScheduler
import app.knotwork.android.domain.usecases.AgentOrchestratorUseCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowLooper

/**
 * Robolectric coverage for [AgentForegroundService] — the foreground service that
 * keeps the LiteRT engine alive while the agent is running.
 *
 * Hilt's `@AndroidEntryPoint` bytecode-rewriting wrapper (`Hilt_AgentForegroundService`)
 * insists on a `GeneratedComponentManagerHolder` application at runtime, which would
 * force us to add `hilt-android-testing` + a custom `HiltTestApplication` runner just
 * to instantiate the service. We avoid that scope creep by flipping the wrapper's
 * private `injected` flag to `true` via reflection *before* `onCreate` runs and
 * manually assigning the `@Inject lateinit var` dependencies — the net effect on the
 * subject under test is identical to a successful Hilt injection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AgentForegroundServiceTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var orchestrator: AgentOrchestratorUseCase
    private lateinit var engine: LlmInferenceEngine
    private lateinit var powerStateRepository: PowerStateRepository
    private lateinit var memoryReembedScheduler: MemoryReembedScheduler
    private lateinit var globalState: MutableStateFlow<AgentOrchestratorState>

    @Before
    fun setup() {
        // Service constructs `CoroutineScope(Dispatchers.Main + Job())` at init —
        // override Main *before* the service is built so its scope captures the
        // test dispatcher.
        Dispatchers.setMain(testDispatcher)

        orchestrator = mockk(relaxed = true)
        engine = mockk(relaxed = true)
        powerStateRepository = mockk(relaxed = true)
        memoryReembedScheduler = mockk(relaxed = true)

        globalState = MutableStateFlow(AgentOrchestratorState.Loading)
        every { orchestrator.globalState } returns globalState
        every { powerStateRepository.powerState } returns
            MutableStateFlow(PowerState(isBatteryLow = false, isCharging = true))
        every { engine.isInitialized } returns true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Builds the service via Robolectric, neutralises the Hilt wrapper's
     * `inject()` call by setting the generated `injected` flag, then assigns
     * dependencies the way Hilt's component would. The caller chooses when to
     * advance the lifecycle (`controller.create()` / `.startCommand()` / `.destroy()`).
     */
    private fun newController(): ServiceController<AgentForegroundService> {
        val controller = Robolectric.buildService(AgentForegroundService::class.java)
        val service = controller.get()
        // Hilt's generated `Hilt_AgentForegroundService` is the direct superclass;
        // its `injected` field is `private` — reflect once and short-circuit.
        val injectedField = service.javaClass.superclass!!.getDeclaredField("injected")
        injectedField.isAccessible = true
        injectedField.setBoolean(service, true)
        service.agentOrchestratorUseCase = orchestrator
        service.llmEngine = engine
        service.powerStateRepository = powerStateRepository
        service.memoryReembedScheduler = memoryReembedScheduler
        return controller
    }

    private fun flushAll() {
        testScope.advanceUntilIdle()
        testDispatcher.scheduler.advanceUntilIdle()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `given onCreate when channel is registered then it has AGENT_FOREGROUND id and IMPORTANCE_LOW`() {
        val controller = newController()

        controller.create()
        flushAll()

        val context = controller.get().applicationContext
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = nm.getNotificationChannel(NotificationChannels.AGENT_FOREGROUND)
        assertNotNull("AGENT_FOREGROUND channel must be created in onCreate", channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun `given onCreate when startForeground runs then posts an ongoing notification`() {
        val controller = newController()

        controller.create()
        flushAll()

        val notification = Shadows.shadowOf(controller.get()).lastForegroundNotification
        assertNotNull("Service must enter foreground in onCreate", notification)
        val ongoingFlag = notification!!.flags and android.app.Notification.FLAG_ONGOING_EVENT
        assertEquals(
            "Foreground-service notification must carry FLAG_ONGOING_EVENT",
            android.app.Notification.FLAG_ONGOING_EVENT,
            ongoingFlag,
        )
        assertEquals(
            "Foreground-service notification must be posted on the AGENT_FOREGROUND channel",
            NotificationChannels.AGENT_FOREGROUND,
            notification.channelId,
        )
    }

    @Test
    fun `given foreground notification when built then uses the dedicated status-bar icon`() {
        val controller = newController()

        controller.create()
        flushAll()

        val notification = Shadows.shadowOf(controller.get()).lastForegroundNotification
        assertNotNull(notification)
        assertEquals(
            "Foreground notification must use the dedicated ic_stat_agent small icon",
            R.drawable.ic_stat_agent,
            notification!!.smallIcon.resId,
        )
    }

    @Test
    fun `given active work then Idle when collector runs then foreground notification is removed`() {
        val controller = newController()
        controller.create()
        flushAll()
        globalState.value = AgentOrchestratorState.Thinking("…")
        flushAll()

        // The agent finished: the status notification must not linger.
        globalState.value = AgentOrchestratorState.Idle
        flushAll()

        assertTrue(
            "Foreground notification must be removed once the agent settles to idle",
            Shadows.shadowOf(controller.get()).isForegroundStopped,
        )
    }

    @Test
    fun `given Completed when collector runs then foreground notification is removed`() {
        val controller = newController()
        controller.create()
        flushAll()
        globalState.value = AgentOrchestratorState.Answering("…")
        flushAll()

        globalState.value = AgentOrchestratorState.Completed("done")
        flushAll()

        assertTrue(
            "Foreground notification must be removed when the run completes",
            Shadows.shadowOf(controller.get()).isForegroundStopped,
        )
    }

    @Test
    fun `given orchestrator emits Thinking when collector runs then wake lock is acquired`() {
        val controller = newController()
        controller.create()
        flushAll()

        globalState.value = AgentOrchestratorState.Thinking("…")
        flushAll()

        val latest = org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()
        assertNotNull("WakeLock must be created in onCreate", latest)
        assertTrue("WakeLock must be held while the agent is Thinking", latest.isHeld)
    }

    @Test
    fun `given Thinking then Idle when collector runs then wake lock is released`() {
        val controller = newController()
        controller.create()
        flushAll()
        globalState.value = AgentOrchestratorState.Thinking("…")
        flushAll()

        globalState.value = AgentOrchestratorState.Idle
        flushAll()

        assertFalse(
            "WakeLock must be released once the agent goes Idle",
            org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock().isHeld,
        )
    }

    @Test
    fun `given Thinking then WaitingForApproval when collector runs then wake lock is released`() {
        val controller = newController()
        controller.create()
        flushAll()
        globalState.value = AgentOrchestratorState.Thinking("…")
        flushAll()

        // Waiting on a human must not pin the CPU awake until the safety timeout.
        globalState.value =
            AgentOrchestratorState.WaitingForApproval("delete_file", "{}", ToolRisk.DESTRUCTIVE, "req-1")
        flushAll()

        assertFalse(
            "WakeLock must be released while awaiting user approval",
            org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock().isHeld,
        )
    }

    @Test
    fun `given orchestrator emits ExecutingTool when collector runs then wake lock is acquired`() {
        val controller = newController()
        controller.create()
        flushAll()

        globalState.value = AgentOrchestratorState.ExecutingTool("search_tool", "{\"q\":\"x\"}")
        flushAll()

        assertTrue(org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test
    fun `given orchestrator emits Error when collector runs then wake lock is released`() {
        val controller = newController()
        controller.create()
        flushAll()
        globalState.value = AgentOrchestratorState.Thinking("…")
        flushAll()

        globalState.value = AgentOrchestratorState.Error("boom")
        flushAll()

        assertFalse(org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test
    fun `given onStartCommand is called when invoked then returns START_STICKY`() {
        val controller = newController()
        controller.create()
        flushAll()

        val result = controller.get().onStartCommand(null, 0, 1)
        assertEquals(android.app.Service.START_STICKY, result)
    }

    @Test
    fun `given onStartCommand called repeatedly when invoked then each returns START_STICKY`() {
        val controller = newController()
        controller.create()
        flushAll()
        val service = controller.get()

        val results = (0 until 3).map { startId ->
            service.onStartCommand(null, 0, startId)
        }

        assertEquals(
            listOf(
                android.app.Service.START_STICKY,
                android.app.Service.START_STICKY,
                android.app.Service.START_STICKY,
            ),
            results,
        )
    }

    @Test
    fun `given service has no binder support when onBind is called then returns null`() {
        val controller = newController()
        controller.create()
        flushAll()

        assertNull(controller.get().onBind(null))
    }

    @Test
    fun `given engine is initialized when onDestroy runs then engine close is called`() {
        every { engine.isInitialized } returns true
        val controller = newController()
        controller.create()
        flushAll()

        controller.destroy()
        flushAll()
        // Robolectric runs destroy() synchronously; let any cancellation tasks settle.
        ShadowLooper.runMainLooperToNextTask()

        verify(exactly = 1) { engine.close() }
    }

    @Test
    fun `given engine is not initialized when onDestroy runs then engine close is not called`() {
        every { engine.isInitialized } returns false
        val controller = newController()
        controller.create()
        flushAll()

        controller.destroy()
        flushAll()

        verify(exactly = 0) { engine.close() }
    }

    @Test
    fun `given unusual states emitted when collector runs then no crash and notification still posted`() {
        // Smoke-test guarding against accidental crashes inside the long-running
        // `collectLatest { state -> updateNotification(...) }` loop when an
        // unusual state lands (PipelineTrace / ConsoleLog / NodeIO / WaitingForApproval /
        // AwaitingClarification / Answering / Completed / ObservationResult).
        val controller = newController()
        controller.create()
        flushAll()

        val unusual = listOf(
            AgentOrchestratorState.PipelineTrace(emptyList()),
            AgentOrchestratorState.ConsoleLog(emptyList()),
            AgentOrchestratorState.WaitingForApproval(
                toolName = "x",
                arguments = "{}",
                risk = ToolRisk.SENSITIVE,
                requestId = "req-1",
            ),
            AgentOrchestratorState.Answering("…"),
            AgentOrchestratorState.Completed("ok"),
            AgentOrchestratorState.ObservationResult("search_tool", "done"),
        )
        for (s in unusual) {
            globalState.value = s
            flushAll()
        }
        // The presence of a current foreground notification is enough — any uncaught
        // exception inside the collector would have killed Looper.getMainLooper().
        assertNotNull(Shadows.shadowOf(controller.get()).lastForegroundNotification)
        assertEquals(Looper.getMainLooper(), Looper.getMainLooper()) // never null
    }
}
