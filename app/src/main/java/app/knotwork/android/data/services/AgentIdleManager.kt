package app.knotwork.android.data.services

import app.knotwork.android.domain.constants.TimeAndIdConstants
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.AgentOrchestratorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Manages the idle timeout logic for the AI Agent.
 * Listens to the [AgentOrchestratorState] and triggers a safe unload of the
 * [LlmInferenceEngine] if the agent remains idle for a specified duration.
 *
 * @property scope The [CoroutineScope] used for launching the idle timer and collecting state.
 * @property engine The [LlmInferenceEngine] to unload when idle.
 * @property agentState The [StateFlow] representing the current state of the agent.
 * @property idleTimeoutMs The duration in milliseconds before the agent is considered idle.
 */
class AgentIdleManager(
    private val scope: CoroutineScope,
    private val engine: LlmInferenceEngine,
    private val agentState: StateFlow<AgentOrchestratorState>,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MINUTES * TimeAndIdConstants.MS_PER_MINUTE,
) {
    private var idleJob: Job? = null

    /**
     * Starts observing the agent state to manage the idle timer.
     */
    fun startObserving() {
        scope.launch {
            agentState.collectLatest { state ->
                handleStateChange(state)
            }
        }
    }

    private fun handleStateChange(state: AgentOrchestratorState) {
        val isIdle = state is AgentOrchestratorState.Idle ||
            state is AgentOrchestratorState.Completed ||
            state is AgentOrchestratorState.Error

        if (isIdle) {
            startIdleTimer()
        } else {
            cancelIdleTimer()
        }
    }

    private fun startIdleTimer() {
        cancelIdleTimer()
        idleJob = scope.launch {
            delay(idleTimeoutMs)
            // [LlmInferenceEngine.unload] serialises on the engine's own
            // native-session mutex, so it never frees handles mid-generation.
            // Note the mutex alone does not make a *deferred* unload safe — it
            // makes it wait, and what it waits for may be a newer engine than
            // the one the unload was requested for. This
            // call is safe because the timer is cancelled the moment the
            // orchestrator leaves an idle state, so it only ever fires when
            // nothing is running.
            if (engine.isInitialized) {
                engine.unload()
            }
        }
    }

    private fun cancelIdleTimer() {
        idleJob?.cancel()
        idleJob = null
    }

    private companion object {
        /** Default number of minutes of inactivity before the LLM engine is unloaded from memory. */
        const val DEFAULT_IDLE_TIMEOUT_MINUTES: Long = 5L
    }
}
