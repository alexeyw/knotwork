package app.knotwork.android

import android.app.UiAutomation
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.knotwork.android.data.testing.AppFunctionsE2ETestEntryPoint
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.engine.executors.ToolInvocationGate
import app.knotwork.android.domain.engine.executors.ToolNodeExecutor
import app.knotwork.android.domain.engine.structured.CloudStructuredInferenceClientFactory
import app.knotwork.android.domain.engine.structured.StructuredOutputGate
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeOutput
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PendingDecision
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.services.ApprovalNotifier
import app.knotwork.android.domain.usecases.LoadModelUseCase
import app.knotwork.android.domain.usecases.RecordTriggerHitlEventUseCase
import app.knotwork.android.testing.DeviceOnlyInstrumentedTest
import dagger.hilt.android.EntryPointAccessors
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import app.knotwork.android.domain.models.Result as DomainResult

/**
 * End-to-end test harness for the AppFunctions surface.
 *
 * The four scenarios on this class exercise the agent's caller-side, HITL gate,
 * callee-side and risk-override paths against a second app installed alongside the test
 * APK (`:tools-probe`, applicationId `app.knotwork.android.toolsprobe`). The probe is
 * deployed via a Gradle task hook in `:app/build.gradle.kts` that makes
 * `:tools-probe:installDebug` a prerequisite of `installDebugAndroidTest`, so the
 * probe APK is on the device before any test in this class runs — both from the CLI
 * (`./gradlew :app:connectedDebugAndroidTest`) and from Android Studio's Run Test.
 * The probe declares a single `@AppFunction echo(message)` whose response is the
 * input verbatim — making it a deterministic remote target that does not depend on
 * network access or any non-trivial business logic on the probe side.
 *
 * Why instrumented and not Robolectric: the Android 16 `AppFunctionManager` is a
 * system-process service. The test class therefore requires API 36+ and a real device
 * or emulator; on lower API levels every test is skipped via [assumeTrue] so the
 * file is harmless in JVM-only check builds.
 *
 * Why it carries [DeviceOnlyInstrumentedTest]: an emulator can host the class but
 * cannot grant a developer-installed caller `EXECUTE_APP_FUNCTIONS`, which Android 16
 * declares `internal|privileged`, so every scenario would resolve to a skip. The
 * annotation excludes the class from the automated emulator runs by name instead of
 * letting it burn the
 * discovery poll to reach that skip; it stays part of the manual reference-device
 * pass, where the permission gate can actually apply.
 *
 * Why not `@HiltAndroidTest`: the agent's production application class is already
 * `@HiltAndroidApp`, so the SingletonComponent is bootstrapped automatically when the
 * instrumentation Application is created. Pulling dependencies from the production
 * graph through [AppFunctionsE2ETestEntryPoint] is cheaper than spinning up a parallel
 * Hilt test component and avoids the custom `AndroidJUnitRunner` / `HiltTestApplication`
 * scaffolding that would otherwise leak into the rest of the test suite.
 */
@RunWith(AndroidJUnit4::class)
@DeviceOnlyInstrumentedTest(
    reason = "EXECUTE_APP_FUNCTIONS is declared `internal|privileged` in the Android 16 framework " +
        "manifest (AOSP android-16.0.0_r3), so it is granted to privileged system apps only. Stock " +
        "emulator images refuse every grant route for a developer-installed caller — `pm grant` reports " +
        "the permission is not changeable and `appops set` does not recognise the op — so cross-package " +
        "discovery never returns the probe and all five scenarios degrade to Assume-skips while still " +
        "paying the discovery poll (measured: 78s of a 453s suite). A real verdict needs a privileged / " +
        "preinstalled agent build on the reference device, which is where these scenarios are exercised.",
)
class AppFunctionsEndToEndTest {

    private lateinit var entryPoint: AppFunctionsE2ETestEntryPoint
    private lateinit var qualifiedEchoName: String

    /**
     * Snapshot of the most recent tool catalogue seen by the discovery poll. Captured so
     * a discovery timeout's error message can show what the agent *did* observe — that
     * single line of context disambiguates "probe never installed" from "probe installed
     * but not indexed" from "EXECUTE_APP_FUNCTIONS appop missing", which is the actual
     * difference between very different fixes.
     */
    private var lastObservedTools: List<String> = emptyList()

    @Before
    fun setUp() = runBlocking {
        // The platform AppFunctions framework is only available on Android 16+. CI runners
        // and developer JVM-only check loops will reach this guard before any of the test
        // logic, so the file is a no-op there rather than a hard failure.
        assumeTrue("Requires Android 16+ for AppFunctionManager", Build.VERSION.SDK_INT >= 36)

        // Declaring `EXECUTE_APP_FUNCTIONS` in the agent's manifest is not enough: Android 16
        // declares it `internal|privileged` (AOSP android-16.0.0_r3), which no install-time or
        // runtime grant can satisfy for a developer-installed app, and it has no appop
        // counterpart to relax either. The shell attempts below are therefore best-effort across
        // platform images rather than the mechanism that makes discovery work — a privileged or
        // preinstalled agent build is. They are still issued because the *shape* of each refusal
        // is what [platformDeniesExecuteAppFunctionsGrant] reads to tell "this image closes the
        // door" apart from a real install/discovery regression, which otherwise surfaces as a
        // "not discovered within Nms" timeout that is easy to misread as an install problem.
        grantExecuteAppFunctionsAppOp()

        // Verify the probe APK is actually present on the device. AGP's
        // `connectedDebugAndroidTest` does its own SUT/test APK install internally and
        // does not run sibling `installDebug*` tasks — without the Gradle task hook
        // attaching `:tools-probe:installDebug` directly to `connectedDebugAndroidTest`
        // the probe would be missing and discovery would silently return zero entries.
        // Failing fast here points the next reader at the right fix instead of letting
        // them spend 45s watching the discovery poll spin.
        check(isPackageInstalled(PROBE_PACKAGE)) {
            "Probe package $PROBE_PACKAGE is not installed on the device. The Gradle " +
                "hook in `:app/build.gradle.kts` must wire `:tools-probe:installDebug` " +
                "into `connectedDebugAndroidTest` (and `installDebugAndroidTest` for IDE " +
                "Run-Test flows). Re-run `./gradlew :app:connectedDebugAndroidTest`."
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AppFunctionsE2ETestEntryPoint::class.java,
        )

        // The platform indexer needs a moment after install before it surfaces a freshly
        // deployed AppFunction. The probe is installed by the
        // `:app:installDebugAndroidTest` → `:tools-probe:installDebug` hook before the test
        // class is loaded, so the very first discovery call from a cold device may race
        // the indexer; poll briefly until the qualified name appears.
        //
        // If discovery still fails, distinguish two outcomes:
        //   (a) The Android 16 emulator restricts `EXECUTE_APP_FUNCTIONS` to privileged
        //       system apps — `pm grant` returns "not a changeable
        //       permission type" and `appops set` returns "Unknown operation". In that
        //       case the test is fundamentally un-runnable on this image regardless of
        //       how the test code is written, so we convert the failure to a JUnit
        //       skip via `Assume.assumeTrue(false, …)`. On a real device with a
        //       privileged / preinstalled agent this path is not taken.
        //   (b) Anything else — a true regression in install / discovery / queries — is
        //       still escalated to a hard failure with the full diagnostic report.
        val discovered = waitForProbeEchoDiscovery()
        if (discovered == null) {
            val report = buildDiscoveryFailureReport(context.applicationContext)
            if (platformDeniesExecuteAppFunctionsGrant()) {
                assumeTrue(
                    "Skipping: Android 16 emulator image keeps EXECUTE_APP_FUNCTIONS at " +
                        "`internal|privileged` — `pm grant` reports it is not a " +
                        "changeable permission type and `appops set` does not recognise the " +
                        "op. Cross-package AppFunctions discovery from a developer-installed agent " +
                        "is not exercisable on this image; run on a device with a privileged " +
                        "agent build to execute these scenarios.\n\n$report",
                    false,
                )
            }
            error(report)
        }
        qualifiedEchoName = discovered

        // Each test owns its session id so HITL deferred state cannot leak between
        // scenarios. The pre-test cleanup also drops any override the probe id might still
        // carry from a previous run that crashed before the @After cleanup ran.
        val settings = entryPoint.settingsRepository()
        val existing = settings.toolRiskOverrides.first()
        if (qualifiedEchoName in existing) {
            settings.setToolRiskOverride(qualifiedEchoName, ToolRisk.SENSITIVE)
        }
    }

    /**
     * Scenario 1 — Caller-side end-to-end.
     *
     * Asserts that the agent app sees the probe's `echo` AppFunction in the merged tool
     * catalogue and that dispatching through [ToolRepository.executeTool] returns the
     * argument verbatim. This is the cheapest possible round-trip — it bypasses the
     * LLM-driven argument generation by hand-crafting the JSON arguments, so a failure
     * here points squarely at the caller-side wiring (discovery, codec encode, IPC,
     * codec decode) rather than at the executor's prompt construction.
     */
    @Test
    fun caller_side_executeTool_returns_echo_message() = runBlocking {
        val toolRepo = entryPoint.toolRepository()

        val tools = toolRepo.getAvailableTools()
        assertTrue(
            "Probe echo `$qualifiedEchoName` should be present in the available tools catalogue",
            tools.any { it.name == qualifiedEchoName },
        )

        val response = toolRepo.executeTool(qualifiedEchoName, """{"message":"hi"}""")
        val parsed = JSONObject(response)
        assertEquals(
            "Echo round-trip should reflect the `message` argument verbatim",
            "hi",
            parsed.optString("result"),
        )
    }

    /**
     * Scenario 2 — HITL gate at the SENSITIVE default.
     *
     * Constructs a `ToolNodeExecutor` with mock `LlmInferenceEngine` / `LoadModelUseCase`
     * (the LLM is irrelevant to the gate behaviour and unloading a real model on every
     * instrumented test run is prohibitively expensive) and real `ToolRepository` /
     * `SettingsRepository`. Drives `execute(...)` against a TOOL node configured to
     * call the probe's qualified echo, observes the [AgentOrchestratorState.WaitingForApproval]
     * emission, and then calls `resumeWithApproval(sessionId, requestId, true)` to release the
     * suspended invocation. The final [NodeOutput.Result] must carry the echoed message.
     */
    @Test
    fun hitl_executor_emits_waiting_then_resumes_on_approval() = runBlocking {
        val executor = newExecutor(
            llmResponse = """{"arguments":{"message":"hi"}}""",
        )

        val sessionId = "test-${UUID.randomUUID()}"
        val node = newToolNode(toolName = qualifiedEchoName)

        val outputs = mutableListOf<NodeOutput>()
        val sawWaiting = java.util.concurrent.atomic.AtomicBoolean(false)
        val collectorScope = CoroutineScope(Dispatchers.Default)

        val collection = collectorScope.async {
            withTimeout(EXECUTOR_TIMEOUT_MS) {
                executor.execute(node, inputText = "echo hi", sessionId = sessionId, originalPrompt = "echo hi")
                    .onEach { output ->
                        outputs.add(output)
                        val state = (output as? NodeOutput.State)?.state
                        if (state is AgentOrchestratorState.WaitingForApproval &&
                            sawWaiting.compareAndSet(false, true)
                        ) {
                            // The executor registers its CompletableDeferred *after* this emit
                            // resumes its producer (see `ToolNodeExecutor.execute`). Calling
                            // `resumeWithApproval` before that write is observable on the test
                            // thread would be a no-op — the map is empty, the deferred is
                            // dropped, and the gate would time out. Instead of guessing a
                            // delay that's "probably long enough" we poll the executor's
                            // `hasPendingApproval` probe until the registration is visible;
                            // this is deterministic regardless of emulator load.
                            launch {
                                awaitPendingApproval(executor, sessionId)
                                // Answer the request the gate is waiting on, as the card would.
                                val requestId = requireNotNull(executor.pendingApprovalFor(sessionId)).requestId
                                executor.resumeWithApproval(sessionId, requestId, true)
                            }
                        }
                    }
                    .toList()
            }
        }
        collection.await()

        val waitingStates = outputs.filterIsInstance<NodeOutput.State>()
            .map { it.state }
            .filterIsInstance<AgentOrchestratorState.WaitingForApproval>()
        assertTrue(
            "Executor must emit WaitingForApproval for the SENSITIVE-by-default AppFunction",
            waitingStates.isNotEmpty(),
        )
        assertEquals(
            "WaitingForApproval risk should reflect the default AppFunction risk classification",
            ToolRisk.SENSITIVE,
            waitingStates.first().risk,
        )

        val finalResult = outputs.filterIsInstance<NodeOutput.Result>().first()
        val parsed =
            JSONObject(checkNotNull(finalResult.result.outputText) { "Executor must return a non-null outputText" })
        assertEquals(
            "After approval, the executor must surface the echo payload from the probe",
            "hi",
            parsed.optString("result"),
        )
    }

    /**
     * Scenario 3 — Callee-side: the agent exposes `search_tool` to outside callers.
     *
     * Builds an [ExecuteAppFunctionRequest] for the KSP-generated id of
     * `AgentAppFunctionService.search` (see [SEARCH_TOOL_ID]) and dispatches it through the
     * system [AppFunctionManager], exercising the same code path the probe's MainActivity
     * uses for manual smoke checks. The response is parsed back through the platform
     * [GenericDocument]; the test asserts only that the call succeeded and the return
     * value is non-blank — content correctness depends on the Wikipedia network round-trip
     * and is not what this scenario covers.
     */
    @Test
    fun callee_side_search_tool_is_invocable_via_appFunctionManager() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = AppFunctionManager.getInstance(context)
            ?: error("AppFunctionManager not available — Android 16+ required.")

        val published = withTimeoutOrNull(PROBE_DISCOVERY_TIMEOUT_MS) {
            manager.searchAppFunctions(AppFunctionSearchSpec(packageNames = setOf(AGENT_PACKAGE)))
        } ?: error("AppFunctionManager.searchAppFunctions timed out for agent package")
        val metadata = published
            .firstOrNull { it.id == SEARCH_TOOL_ID }
            ?: error(
                "Agent app does not expose `$SEARCH_TOOL_ID` — check that " +
                    "`AgentAppFunctionService.search` is still annotated with `@AppFunction` " +
                    "so the KSP compiler publishes it through `knotwork_app_functions.xml`.",
            )

        val parameters = AppFunctionData.Builder(metadata.parameters, metadata.components)
            .setString("query", "Knotwork")
            .setString("lang", "en")
            .build()
        val request = ExecuteAppFunctionRequest(
            targetPackageName = AGENT_PACKAGE,
            functionIdentifier = SEARCH_TOOL_ID,
            functionParameters = parameters,
        )

        val response = manager.executeAppFunction(request)
        when (response) {
            is ExecuteAppFunctionResponse.Success -> {
                val payload = response.returnValue
                    .getString(ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE)
                    .orEmpty()
                assertTrue(
                    "search_tool response must be non-blank; got an empty payload",
                    payload.isNotBlank(),
                )
            }
            is ExecuteAppFunctionResponse.Error -> error(
                "search_tool returned an Error response: ${response.error.message ?: response.error::class.java.simpleName}",
            )
        }
    }

    /**
     * Scenario 4 — Risk override flow.
     *
     * Persists a [ToolRisk.READ_ONLY] override for the probe's echo through
     * [SettingsRepository.setToolRiskOverride] and verifies that
     * [ToolRepository.getRisk] resolves the same name to READ_ONLY on the next call.
     * The HITL gate then short-circuits to a direct execution because READ_ONLY tools
     * skip the approval flow (see `ToolNodeExecutor.execute`'s `needsApproval` branch).
     */
    @Test
    fun risk_override_downgrades_echo_to_read_only_and_skips_hitl() = runBlocking {
        val toolRepo = entryPoint.toolRepository()
        val settings = entryPoint.settingsRepository()

        assertEquals(
            "Echo must default to SENSITIVE before any override is set",
            ToolRisk.SENSITIVE,
            toolRepo.getRisk(qualifiedEchoName),
        )

        settings.setToolRiskOverride(qualifiedEchoName, ToolRisk.READ_ONLY)
        try {
            assertEquals(
                "Persisted override must be reflected by ToolRepository.getRisk",
                ToolRisk.READ_ONLY,
                toolRepo.getRisk(qualifiedEchoName),
            )

            val executor = newExecutor(llmResponse = """{"arguments":{"message":"hi"}}""")
            val sessionId = "test-readonly-${UUID.randomUUID()}"
            val node = newToolNode(toolName = qualifiedEchoName)

            val outputs = withTimeout(EXECUTOR_TIMEOUT_MS) {
                executor.execute(
                    node,
                    inputText = "echo hi",
                    sessionId = sessionId,
                    originalPrompt = "echo hi",
                ).toList()
            }
            val waitingStates = outputs.filterIsInstance<NodeOutput.State>()
                .map { it.state }
                .filterIsInstance<AgentOrchestratorState.WaitingForApproval>()
            assertTrue(
                "READ_ONLY override must short-circuit the HITL gate — no WaitingForApproval expected",
                waitingStates.isEmpty(),
            )

            val finalResult = outputs.filterIsInstance<NodeOutput.Result>().first()
            assertNotNull("Executor must complete and return a result", finalResult.result.outputText)
        } finally {
            // Restore the SENSITIVE default so a subsequent run of the same test class
            // starts from the production-default state regardless of prior failure
            // boundary.
            settings.setToolRiskOverride(qualifiedEchoName, ToolRisk.SENSITIVE)
        }
    }

    /**
     * Builds a multi-line diagnostic report dumped into the test's `IllegalStateException`
     * message when discovery times out. Collecting every potentially-relevant piece of
     * device state in one shot turns the failure from "echo not discovered" into a
     * directly-actionable transcript — without forcing the next reader to dig through
     * Logcat or re-run with a debugger. The report intentionally bypasses
     * `PackageManager.canPackageQuery` for the probe-installed and queries-resolved
     * checks (using `pm` / `dumpsys` shell commands) so a missing `<queries>` entry on
     * the agent cannot mask any of the upstream causes.
     */
    private suspend fun buildDiscoveryFailureReport(applicationContext: android.content.Context): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val probeInstalled = runShell(automation, "pm list packages $PROBE_PACKAGE")
        val agentPermLine = dumpPackagePermissionLine(automation, AGENT_PACKAGE, "EXECUTE_APP_FUNCTIONS")
        val probePermLine = dumpPackagePermissionLine(automation, PROBE_PACKAGE, "EXECUTE_APP_FUNCTIONS")
        val agentAppOp = runShell(
            automation,
            "appops get $AGENT_PACKAGE android:execute_app_functions",
        ).ifBlank { runShell(automation, "appops get $AGENT_PACKAGE EXECUTE_APP_FUNCTIONS") }
        val probeAppOp = runShell(
            automation,
            "appops get $PROBE_PACKAGE android:execute_app_functions",
        ).ifBlank { runShell(automation, "appops get $PROBE_PACKAGE EXECUTE_APP_FUNCTIONS") }
        val rawPlatformResult = runCatching {
            val manager = AppFunctionManager.getInstance(applicationContext)
                ?: return@runCatching "AppFunctionManager.getInstance returned null"
            manager.searchAppFunctions(AppFunctionSearchSpec())
                .groupBy({ it.packageName }, { it.id })
                .entries
                .joinToString { (pkg, ids) -> "$pkg:$ids" }
        }.getOrElse { "AppFunctionManager.searchAppFunctions threw: ${it::class.java.simpleName}: ${it.message}" }
        val permissionDump = runShell(automation, "pm list permissions -g -f android.permission.EXECUTE_APP_FUNCTIONS")
            .lineSequence().filter { it.isNotBlank() }.take(20).joinToString("\n      ")
        return buildString {
            appendLine("Probe AppFunction `echo` was not discovered within ${PROBE_DISCOVERY_TIMEOUT_MS}ms.")
            appendLine("  Last observed (agent) catalogue: $lastObservedTools")
            appendLine("  Raw AppFunctionManager.searchAppFunctions: [$rawPlatformResult]")
            appendLine("  pm list packages $PROBE_PACKAGE: '${probeInstalled.trim()}'")
            appendLine("  $AGENT_PACKAGE EXECUTE_APP_FUNCTIONS (dumpsys package): '$agentPermLine'")
            appendLine("  $PROBE_PACKAGE EXECUTE_APP_FUNCTIONS (dumpsys package): '$probePermLine'")
            appendLine("  appops get $AGENT_PACKAGE …: '${agentAppOp.trim()}'")
            appendLine("  appops get $PROBE_PACKAGE …: '${probeAppOp.trim()}'")
            appendLine("  pm list permissions … EXECUTE_APP_FUNCTIONS:")
            appendLine("      $permissionDump")
            appendLine("  Grant attempts (stdout+stderr):")
            lastGrantAttempts.forEach { attempt ->
                appendLine("    - ${attempt.command}")
                appendLine("      → ${attempt.output.ifBlank { "(no output)" }}")
            }
        }
    }

    /**
     * Extracts the single `dumpsys package <pkg>` line that mentions [permissionFragment].
     * Useful for confirming the install-time permission grant state without dumping the
     * entire `dumpsys package` output (which is several pages and would drown the actual
     * signal in noise).
     */
    private fun dumpPackagePermissionLine(automation: UiAutomation, pkg: String, permissionFragment: String): String =
        runShell(automation, "dumpsys package $pkg")
            .lineSequence()
            .firstOrNull { it.contains(permissionFragment) }
            ?.trim()
            .orEmpty()

    /**
     * Deterministic readiness wait for the HITL gate. The executor registers its
     * `CompletableDeferred` for [sessionId] *after* emitting the
     * [AgentOrchestratorState.WaitingForApproval] state but before suspending on the
     * approval. The test must not call `resumeWithApproval` until that registration is
     * observable — otherwise the resume is silently dropped and the gate runs to its
     * configured timeout. Polling [ToolNodeExecutor.hasPendingApproval] removes the
     * dependency on any fixed delay constant and keeps the test stable across slow
     * emulators and overloaded CI runners.
     */
    private suspend fun awaitPendingApproval(executor: ToolNodeExecutor, sessionId: String) {
        withTimeout(PENDING_APPROVAL_TIMEOUT_MS) {
            while (!executor.hasPendingApproval(sessionId)) {
                delay(PENDING_APPROVAL_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Polls [LocalAppFunctionManager.getAvailableFunctions] (transitively via
     * [ToolRepository.getAvailableTools]) until a tool whose qualified name starts with
     * the probe's package shows up, or the poll budget expires. Returns the qualified
     * name on success and `null` on timeout — the latter is escalated to a hard failure
     * by [setUp].
     *
     * Each polled catalogue is also captured in [lastObservedTools] and emitted to
     * Logcat under the [LOG_TAG] tag, so a discovery timeout can be diagnosed from the
     * test output without re-running with a debugger attached.
     */
    private suspend fun waitForProbeEchoDiscovery(): String? {
        val deadline = System.currentTimeMillis() + PROBE_DISCOVERY_TIMEOUT_MS
        val toolRepo = entryPoint.toolRepository()
        while (System.currentTimeMillis() < deadline) {
            val tools = toolRepo.getAvailableTools()
            lastObservedTools = tools.map { it.name }
            Log.d(LOG_TAG, "Discovered tools: $lastObservedTools")
            val match = tools.firstOrNull { tool ->
                tool.name.startsWith("$PROBE_PACKAGE/") && tool.name.endsWith("echo")
            }
            if (match != null) return match.name
            delay(PROBE_POLL_INTERVAL_MS)
        }
        return null
    }

    /**
     * Attempts to enable EXECUTE_APP_FUNCTIONS access for both the agent and the probe
     * packages. Android 16 declares the permission `internal|privileged`, so none of these
     * routes can succeed for a developer-installed app on a stock image; they are issued
     * anyway because the shape of each refusal is the signal
     * [platformDeniesExecuteAppFunctionsGrant] reads. Three routes rather than one,
     * because the wire identifier varies across platform images: the shell tool accepts
     * both the all-caps Java constant (`EXECUTE_APP_FUNCTIONS`) and the canonical
     * lowercase form (`android:execute_app_functions`), and `pm grant` works on some
     * images where the permission is exposed as a runtime-style grant.
     * Issuing all three is harmless — the first one the platform recognises wins, the
     * others return an "Unknown operation" line that we deliberately drop on the
     * floor (and log to Logcat for after-the-fact triage).
     *
     * The grant persists for the package lifetime, so re-running the test class is
     * fine, but a fresh emulator wipe drops it — which is why we re-issue every
     * `setUp`.
     */
    private fun grantExecuteAppFunctionsAppOp() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        lastGrantAttempts.clear()
        listOf(AGENT_PACKAGE, PROBE_PACKAGE).forEach { pkg ->
            for (cmd in grantCommandsFor(pkg)) {
                val output = runShell(automation, cmd)
                lastGrantAttempts += GrantAttempt(cmd, output)
                Log.d(LOG_TAG, "$cmd → '$output'")
            }
        }
    }

    /**
     * Captures every grant command issued during `setUp` together with its
     * stdout+stderr response, so a discovery timeout's error message can show whether
     * `pm grant` reported "permission cannot be granted" or `appops set` reported
     * "Unknown operation" — that one detail discriminates between "we used the wrong op
     * name" and "the platform reserves this permission for privileged system
     * apps."
     */
    private val lastGrantAttempts: MutableList<GrantAttempt> = mutableListOf()

    private data class GrantAttempt(val command: String, val output: String)

    /**
     * Inspects [lastGrantAttempts] (populated by [grantExecuteAppFunctionsAppOp]) and
     * returns `true` when every attempt to grant `EXECUTE_APP_FUNCTIONS` to a
     * third-party app was rejected by the platform with one of the two well-known
     * signatures:
     *
     *  - `pm grant` returns "not a changeable permission type" → the permission is
     *    declared `internal|privileged` (Android 16) and no runtime grant is allowed.
     *  - `appops set` returns "Unknown operation string" → the permission has no
     *    appop counterpart in the platform's `AppOpsManager`, so it cannot be relaxed
     *    via the appop wire either.
     *
     * Both responses together mean this Android 16 emulator image fundamentally does
     * not expose any path for a developer-installed agent to invoke another app's
     * AppFunctions, and the cross-package scenarios should be skipped (not failed).
     */
    private fun platformDeniesExecuteAppFunctionsGrant(): Boolean {
        if (lastGrantAttempts.isEmpty()) return false
        return lastGrantAttempts.all { attempt ->
            attempt.output.contains("not a changeable permission type", ignoreCase = true) ||
                attempt.output.contains("Unknown operation string", ignoreCase = true)
        }
    }

    /**
     * Shell commands tried (in order) to allow the EXECUTE_APP_FUNCTIONS permission for
     * [pkg]. Different Android 16 builds expose different combinations; running all
     * three is variant-tolerant.
     */
    private fun grantCommandsFor(pkg: String): List<String> = listOf(
        "pm grant $pkg android.permission.EXECUTE_APP_FUNCTIONS",
        "appops set $pkg android:execute_app_functions allow",
        "appops set $pkg EXECUTE_APP_FUNCTIONS allow",
    )

    /**
     * Cheap presence check for [packageName] on the device. Uses `pm list packages
     * <package>` rather than `PackageManager.getPackageInfo` because the latter is
     * sensitive to the calling app's `<queries>` declaration, which is precisely what
     * the test is supposed to verify is set up correctly — using a shell command gives
     * a queries-independent ground truth so a missing `<queries>` entry never
     * masquerades as a missing install.
     */
    private fun isPackageInstalled(packageName: String): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val output = runShell(automation, "pm list packages $packageName")
        Log.d(LOG_TAG, "pm list packages $packageName → '$output'")
        return output.lineSequence().any { it.trim() == "package:$packageName" }
    }

    /**
     * Runs [command] through the [UiAutomation] shell channel and returns the combined
     * stdout/stderr as a string. `executeShellCommandRwe` returns three file
     * descriptors — [stdout, stdin, stderr] — and we drain both stdout and stderr so
     * the "Permission cannot be granted" / "Unknown operation" lines that `pm grant`
     * and `appops set` emit on the failure path show up in the diagnostic report.
     *
     * Note: a previous version of this helper wrapped the command in
     * `sh -c '<cmd> 2>&1'`, but `executeShellCommand` does not run through a shell —
     * it splits on whitespace and execs the binary directly, so the single quotes
     * become literal characters and the command never starts. The Rwe variant is
     * the supported way to capture stderr.
     */
    private fun runShell(automation: UiAutomation, command: String): String {
        val pfds = automation.executeShellCommandRwe(command)
        // Layout: [stdout, stdin, stderr]
        val stdout = pfds[0]
        val stdin = pfds[1]
        val stderr = pfds[2]
        // Close stdin immediately — the commands we run don't read it and leaving the
        // descriptor open can leak the pipe on some platform builds.
        ParcelFileDescriptor.AutoCloseInputStream(stdin).close()
        val out = ParcelFileDescriptor.AutoCloseInputStream(stdout).use {
            it.bufferedReader().readText().trim()
        }
        val err = ParcelFileDescriptor.AutoCloseInputStream(stderr).use {
            it.bufferedReader().readText().trim()
        }
        return when {
            out.isBlank() && err.isBlank() -> ""
            err.isBlank() -> out
            out.isBlank() -> err
            else -> "$out\n$err"
        }
    }

    /**
     * Scenario 5 — background approval consumed after process recreation.
     *
     * The two-phase HITL protocol across its process-death seam, at the
     * executor level (the notification-receiver routing and the checkpoint
     * re-enqueue around it are covered by JVM tests):
     *
     *  1. the live gate of a persisted run times out → the run parks: the flow
     *     ends with [AgentOrchestratorState.SuspendedInBackground] and the
     *     request snapshot is durable in Room;
     *  2. the user's decision is recorded onto the durable record — exactly
     *     what the notification action does after the original process died;
     *  3. a *fresh* executor instance (its in-memory deferred map empty — the
     *     recreated-process analog) re-executes the TOOL node with the same
     *     run id: it must consume the one-shot APPROVED decision without
     *     raising a new gate, execute the real `echo` AppFunction, and delete
     *     the record.
     */
    @Test
    fun background_approval_parks_and_fresh_executor_consumes_the_recorded_decision() = runBlocking {
        val settings = entryPoint.settingsRepository()
        val pendingRepo = entryPoint.pendingInteractionRepository()
        val originalTimeout = settings.toolCallTimeoutMs.first()
        settings.setToolCallTimeoutMs(1_500L)
        try {
            val sessionId = "test-${UUID.randomUUID()}"
            val runId = "run-${UUID.randomUUID()}"
            val node = newToolNode(toolName = qualifiedEchoName)
            val llmResponse = """{"arguments":{"message":"hi"}}"""

            // Phase 1 — the live window elapses with no decision: the run parks.
            val executorBeforeDeath = newExecutor(llmResponse = llmResponse)
            val parkedOutputs = withTimeout(EXECUTOR_TIMEOUT_MS) {
                executorBeforeDeath
                    .execute(
                        node,
                        inputText = "echo hi",
                        sessionId = sessionId,
                        originalPrompt = "echo hi",
                        runId = runId,
                    )
                    .toList()
            }
            val lastState = (parkedOutputs.last() as NodeOutput.State).state
            assertTrue(
                "Timed-out gate of a persisted run must park, got $lastState",
                lastState is AgentOrchestratorState.SuspendedInBackground,
            )
            val parked = pendingRepo.getForRun(runId)
            assertNotNull("Park must be durable in Room", parked)
            assertEquals(PendingInteractionKind.APPROVAL, parked!!.kind)

            // The decision lands on the durable record — the in-memory gate died
            // with the "process".
            assertTrue(pendingRepo.recordDecision(runId, PendingDecision.APPROVED))

            // Phase 2 — a fresh executor (empty deferred map) re-executes the node.
            val executorAfterDeath = newExecutor(llmResponse = llmResponse)
            val outputs = withTimeout(EXECUTOR_TIMEOUT_MS) {
                executorAfterDeath
                    .execute(
                        node,
                        inputText = "echo hi",
                        sessionId = sessionId,
                        originalPrompt = "echo hi",
                        runId = runId,
                    )
                    .toList()
            }

            val waitingStates = outputs.filterIsInstance<NodeOutput.State>()
                .map { it.state }
                .filterIsInstance<AgentOrchestratorState.WaitingForApproval>()
            assertTrue("The recorded decision must not raise a new gate", waitingStates.isEmpty())
            val result = outputs.filterIsInstance<NodeOutput.Result>().single().result
            assertNotNull("Tool must execute with the confirmed decision", result.outputText)
            assertTrue(
                "Echo result expected, got: ${result.outputText}",
                result.outputText!!.contains("hi"),
            )
            assertEquals(null, pendingRepo.getForRun(runId))
        } finally {
            settings.setToolCallTimeoutMs(originalTimeout)
        }
    }

    /**
     * Builds a fresh [ToolNodeExecutor] with real `ToolRepository`, `SettingsRepository`
     * and `ChatRepository` (so the AppFunctions IPC path and the persisted approval
     * settings are exercised end-to-end) and mocked `LlmInferenceEngine` /
     * `LoadModelUseCase` (the LLM has no role in the HITL gate verification and loading
     * a real LiteRT model on every test would dwarf the test runtime).
     */
    private fun newExecutor(llmResponse: String): ToolNodeExecutor {
        val fakeEngine = mockk<LlmInferenceEngine>()
        every { fakeEngine.generateResponseStream(any()) } returns flowOf(llmResponse)

        val fakeLoadModel = mockk<LoadModelUseCase>()
        coEvery { fakeLoadModel.invoke(any()) } returns DomainResult.Success(Unit)
        coEvery { fakeLoadModel.invoke() } returns DomainResult.Success(Unit)

        val silentNotifier = object : ApprovalNotifier {
            override fun sendApprovalRequest(
                sessionId: String,
                requestId: String,
                toolName: String,
                arguments: String,
                risk: ToolRisk,
            ) {
                // Notification side effects are not part of this test's contract; the gate
                // suspension is observed through the WaitingForApproval state emission, not
                // via a system notification assertion. Swallowing here also keeps the test
                // independent of the POST_NOTIFICATIONS runtime permission.
            }

            override fun sendPersistentApprovalRequest(
                runId: String,
                sessionId: String,
                requestId: String,
                toolName: String,
                arguments: String,
                risk: ToolRisk,
            ) {
                // Same rationale: the park is asserted through the durable record.
            }

            override fun cancelApprovalNotification(requestId: String) {
                // No notification was posted, nothing to cancel.
            }

            override fun cancelPreUpdateNotification(sessionId: String) {
                // No earlier release posted anything on the test device either.
            }
        }

        return ToolNodeExecutor(
            llmEngine = fakeEngine,
            loadModelUseCase = fakeLoadModel,
            toolRepository = entryPoint.toolRepository(),
            toolInvocationGate = ToolInvocationGate(
                toolRepository = entryPoint.toolRepository(),
                settingsRepository = entryPoint.settingsRepository(),
                approvalNotifier = silentNotifier,
                chatRepository = entryPoint.chatRepository(),
                pendingInteractionRepository = entryPoint.pendingInteractionRepository(),
                recordTriggerHitlEvent = mockk<RecordTriggerHitlEventUseCase>(relaxed = true),
            ),
            structuredOutputGate = StructuredOutputGate(),
            settingsRepository = entryPoint.settingsRepository(),
            // This E2E covers a fixed on-device TOOL node; no cloud structured
            // client is needed, so the factory always yields null.
            cloudStructuredFactory = CloudStructuredInferenceClientFactory { _, _ -> null },
        )
    }

    private fun newToolNode(toolName: String): NodeModel = NodeModel(
        id = "test-tool-node",
        type = NodeType.TOOL,
        x = 0f,
        y = 0f,
        label = "TOOL",
        toolName = toolName,
        modelPath = "test-fake-model.bin",
        contextConfig = NodeContextConfig.ALL_ENABLED,
    )

    private companion object {
        /**
         * This app's own package, read from the build rather than written out:
         * the debug build carries an `applicationIdSuffix`, and a literal would
         * silently address the release install (or nothing at all) on the very
         * variant these instrumented tests run against.
         */
        val AGENT_PACKAGE: String = BuildConfig.APPLICATION_ID
        const val PROBE_PACKAGE = "app.knotwork.android.toolsprobe"

        /**
         * Canonical AppFunction wire id generated by the KSP compiler from
         * `@AppFunction`-annotated `AgentAppFunctionService.search`. The literal embeds the
         * backticks around `data` because the AppFunctions compiler emits Kotlin
         * source-level package escaping into the id string when a package segment
         * collides with a soft keyword — see the generated
         * `KnotworkAppFunctionService.FUNCTION_ID_SEARCH` constant. Written out rather than
         * referenced: it is the string an outside caller has to send, so a change to it is
         * a change to the published contract and should fail here. The platform indexer
         * treats the id as an opaque string, so the backticks must travel with every request.
         */
        const val SEARCH_TOOL_ID =
            "app.knotwork.android.`data`.tools.local.appfunctions.AgentAppFunctionService#search"

        const val LOG_TAG = "AppFunctionsE2E"
        const val PROBE_DISCOVERY_TIMEOUT_MS = 15_000L
        const val PROBE_POLL_INTERVAL_MS = 500L
        const val EXECUTOR_TIMEOUT_MS = 30_000L
        const val PENDING_APPROVAL_TIMEOUT_MS = 5_000L
        const val PENDING_APPROVAL_POLL_INTERVAL_MS = 20L
    }
}
