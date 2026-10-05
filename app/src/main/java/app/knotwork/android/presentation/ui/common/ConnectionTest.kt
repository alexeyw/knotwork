package app.knotwork.android.presentation.ui.common

import app.knotwork.android.domain.connection.ConnectionCheckResult
import app.knotwork.android.domain.connection.ConnectionFailure
import app.knotwork.android.domain.connection.ConnectionRefusal
import app.knotwork.android.domain.engine.CloudErrorSanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource

/**
 * The Test connection row of a settings form: whether it can run, its run, and what it found.
 *
 * Shared by the provider screen and the MCP server form, which differ only in what they check.
 * A ViewModel owns one, tells it whenever the values the check reads change ([onInputs]), and
 * forwards the two buttons ([start], [cancel]).
 *
 * Two rules hold the state to the form:
 * - **A result describes the values it was run with.** When those change, a running check is
 *   cancelled and a result is dropped: the row goes back to [ConnectionTestState.Idle], or to
 *   [ConnectionTestState.Disabled] with the new reason.
 * - **Nothing outlives the form.** The check runs in the ViewModel's scope, so leaving the screen
 *   cancels it, and the next visit starts idle.
 *
 * @property scope Where checks run — the owning ViewModel's scope.
 * @property timeSource The clock a running check's start is marked on.
 */
class ConnectionTest(private val scope: CoroutineScope, private val timeSource: TimeSource.WithComparableMarks) {
    private val _state = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)

    /** The row's state. */
    val state: StateFlow<ConnectionTestState> = _state.asStateFlow()

    private var job: Job? = null
    private var inputs: Any? = null

    /**
     * Takes the values the check would read now, and what stops it, if anything.
     *
     * The same [inputs] as last time change nothing — a flow re-emitting an unchanged value must
     * not drop a result. Different ones cancel a running check and drop a result.
     *
     * @param inputs Everything the check reads, comparable by equality.
     * @param refusal Why the check cannot run with these inputs, or `null` when it can.
     */
    fun onInputs(inputs: Any, refusal: ConnectionRefusal?) {
        if (inputs == this.inputs) return
        this.inputs = inputs
        job?.cancel()
        job = null
        _state.value = refusal?.let(ConnectionTestState::Disabled) ?: ConnectionTestState.Idle
    }

    /**
     * Runs [check] unless the row is disabled or a check is already running. Pressed again after a
     * result, it runs again.
     *
     * @param host The host the check asks, for the running line.
     * @param check The check; total by contract, but an exception it throws anyway ends the run as
     *   a failure rather than in the ViewModel's scope.
     */
    fun start(host: String, check: suspend () -> ConnectionCheckResult) {
        val current = _state.value
        if (current is ConnectionTestState.Disabled || current is ConnectionTestState.Running) return
        _state.value = ConnectionTestState.Running(host, timeSource.markNow())
        job = scope.launch {
            val result = try {
                check()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ConnectionCheckResult.Failed(host, ConnectionFailure.Other(CloudErrorSanitizer.sanitize(e)))
            }
            // A check cancelled while it was finishing must not overwrite what replaced it.
            ensureActive()
            _state.value = ConnectionTestState.Finished(result)
        }
    }

    /** Stops a running check; the row goes back to idle. Nothing happens when none is running. */
    fun cancel() {
        if (_state.value !is ConnectionTestState.Running) return
        job?.cancel()
        job = null
        _state.value = ConnectionTestState.Idle
    }
}

/** The states of a Test connection row. */
sealed interface ConnectionTestState {

    /**
     * The check cannot run with the values in the form.
     *
     * @property reason What is missing or refused.
     */
    data class Disabled(val reason: ConnectionRefusal) : ConnectionTestState

    /** The check can run and has not, or its result was dropped. */
    data object Idle : ConnectionTestState

    /**
     * A check is running.
     *
     * @property host The host it asks.
     * @property startedAt When it started, for the elapsed seconds the row shows.
     */
    data class Running(val host: String, val startedAt: ComparableTimeMark) : ConnectionTestState

    /**
     * A check ran.
     *
     * @property result What it found.
     */
    data class Finished(val result: ConnectionCheckResult) : ConnectionTestState
}
