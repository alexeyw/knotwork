package app.knotwork.android.domain.engine.executors

import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.NodeExecutionResult
import app.knotwork.android.domain.models.NodeOutput
import app.knotwork.android.domain.models.PendingDecision
import app.knotwork.android.domain.models.PendingInteraction
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.models.ToolExecutionContext
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.models.TriggerHitlEvent
import app.knotwork.android.domain.models.TriggerHitlResolution
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.ToolRepository
import app.knotwork.android.domain.services.ApprovalNotifier
import app.knotwork.android.domain.usecases.RecordTriggerHitlEventUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared Human-in-the-Loop (HITL) tool-dispatch gate. Owns the single seam
 * through which any node that wants to *run* a resolved tool call must pass —
 * currently [ToolNodeExecutor] (which resolves the tool via LLM selection) and
 * [SkillNodeExecutor] (which dispatches a tool a skill initiated, after an
 * allowlist pre-check).
 *
 * Extracting the gate keeps the risk / approval / parking / reattach
 * semantics in one place so the two callers cannot diverge: a SKILL node can
 * never weaken the tool risk / confirmation contract, because it goes through
 * exactly the same code path as a TOOL node.
 *
 * Given an already-resolved `(toolName, arguments)` pair the gate:
 *
 *  1. consumes the one-shot decision a resumed run recorded on its parked
 *     request, if there is one — before anything can end the gate early;
 *  2. resolves the effective [ToolRisk] (canonical source: [ToolRepository.getRisk]);
 *  3. hard-denies the call when it is [ToolRisk.DESTRUCTIVE] and the user has
 *     blocked destructive tools in Settings;
 *  4. applies the recorded decision whatever the current policy says — or, when
 *     there is none, raises a HITL approval gate when
 *     `ToolApprovalPolicy.requiresApproval` (or the node's `alwaysConfirm`) says
 *     so, using the two-phase live-deferred → durable-park protocol;
 *  5. executes the tool through [ToolRepository] and emits the observation.
 *
 * Every gate it raises is also reported to the trigger-evaluation journal via
 * [RecordTriggerHitlEventUseCase] — raise, park and settlement alike — so that a
 * background run which stopped to ask the user is visible as such afterwards
 * instead of settling into an anonymous success. The reports are no-ops for runs
 * that own no journal row (every interactive one).
 *
 * Every gate it raises gets an identity of its own — a token minted here and
 * carried by the request state, the chat card, both notifications and the
 * parked record — and an answer settles a request only when it names that
 * token. A session is not an address: a parked request and a live one coexist
 * in one session whenever a second run starts there, so "complete whatever
 * this session waits on" would let the answer given for one request authorise
 * another.
 *
 * Marked `@Singleton` because [activeApprovalDeferreds] holds per-session
 * pending approval requests that must outlive any individual node execution
 * and be reachable from the UI / notification resume path regardless of which
 * executor raised the gate.
 */
@Singleton
class ToolInvocationGate @Inject constructor(
    private val toolRepository: ToolRepository,
    private val settingsRepository: SettingsRepository,
    private val approvalNotifier: ApprovalNotifier,
    private val chatRepository: ChatRepository,
    private val pendingInteractionRepository: PendingInteractionRepository,
    private val recordTriggerHitlEvent: RecordTriggerHitlEventUseCase,
) {

    private val activeApprovalDeferreds = ConcurrentHashMap<String, PendingApprovalHolder>()

    /**
     * Pairs the suspension primitive of one pending approval with the request
     * snapshot it was raised for, so a UI re-attaching to a suspended run can
     * re-render the confirmation card from the authoritative source instead of
     * hoping the per-session state flow's replay cache still holds the
     * [AgentOrchestratorState.WaitingForApproval] emission (console events
     * emitted while the run waits overwrite it — the replay depth is 1).
     *
     * @property deferred completes with the user's decision via [resumeWithApproval].
     * @property request the exact approval request published to the UI.
     */
    private data class PendingApprovalHolder(
        val deferred: CompletableDeferred<Boolean>,
        val request: AgentOrchestratorState.WaitingForApproval,
    )

    /**
     * Completes the live approval request [requestId] of [sessionId] with the
     * user's decision — and nothing else.
     *
     * Reached only through `SubmitApprovalDecisionUseCase` (the chat card and
     * the notification receiver both route there). The request must be the one
     * the session is suspended on right now: a decision for any other request
     * — a parked one, an earlier one of the same run, one whose notification
     * outlived it — leaves the live gate waiting and returns `false`, so the
     * caller can look for the parked record that request names instead. The
     * match and the removal are one atomic step, so a duplicate dispatch cannot
     * settle a request twice.
     *
     * The approval notification is not touched here: the gate removes it itself
     * when the wait ends, on this path and on every other one.
     *
     * @param sessionId chat session id used as the lookup key in [activeApprovalDeferreds].
     * @param requestId identity of the request the decision was given for.
     * @param isApproved `true` if the user approved tool execution, `false` to deny it.
     * @return `true` when the decision settled the live request it names;
     *   `false` when no live request of [sessionId] carries [requestId].
     */
    fun resumeWithApproval(sessionId: String, requestId: String, isApproved: Boolean): Boolean {
        val holder = activeApprovalDeferreds[sessionId]?.takeIf { it.request.requestId == requestId } ?: return false
        if (!activeApprovalDeferreds.remove(sessionId, holder)) return false
        return holder.deferred.complete(isApproved)
    }

    /**
     * Returns the approval request the run of [sessionId] is currently suspended
     * on, or `null` when no approval gate is active for that session.
     *
     * Backs the chat reattach protocol: when a session is reopened while its
     * persistent run record reads `WAITING_APPROVAL`, the UI restores the HITL
     * confirmation card from this snapshot — the per-session state flow cannot
     * be relied on for it because its replay cache (depth 1) is overwritten by
     * console events emitted after the suspension.
     *
     * @param sessionId chat session id used as the lookup key.
     * @return the pending [AgentOrchestratorState.WaitingForApproval], or `null`.
     */
    fun pendingApprovalFor(sessionId: String): AgentOrchestratorState.WaitingForApproval? =
        activeApprovalDeferreds[sessionId]?.request

    /**
     * Test-only readiness probe: returns `true` once the gate has registered a
     * `CompletableDeferred` for [sessionId] and is suspended awaiting the user's
     * decision. Instrumented tests use this to synchronise their `resumeWithApproval`
     * call deterministically — without it the test would have to rely on a fixed
     * `delay(...)`, which races the deferred registration on slow / overloaded
     * emulators.
     */
    @androidx.annotation.VisibleForTesting
    internal fun hasPendingApproval(sessionId: String): Boolean = activeApprovalDeferreds.containsKey(sessionId)

    /**
     * Runs a resolved tool call through the full HITL gate and emits the outcome
     * onto [collector].
     *
     * Emits intermediate [NodeOutput.State] progress and terminates with exactly
     * one [NodeOutput.Result] — except when the run parks in its persistent
     * waiting phase, in which case the flow ends after a
     * [AgentOrchestratorState.SuspendedInBackground] state without a result
     * (the engine then stops the walk and the run record stays `WAITING_APPROVAL`).
     *
     * @param collector the executor's flow collector to emit states / results into.
     * @param nodeType node type name, used only for error log attribution.
     * @param nodeId node id, used only for error log attribution.
     * @param sessionId chat session the run belongs to.
     * @param runId persistent run id, or `null` for non-persisted (editor test) runs.
     * @param resolvedToolName the tool to run.
     * @param resolvedToolArgs the tool's argument JSON string.
     * @param alwaysConfirm the dispatching node's own "always ask" switch; `true`
     *   adds an approval the policy would not have raised, and can never remove
     *   one. Deliberately without a default: every node type that dispatches
     *   tools must pass its switch, so a new caller cannot silently drop it.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    suspend fun dispatch(
        collector: FlowCollector<NodeOutput>,
        nodeType: String,
        nodeId: String,
        sessionId: String,
        runId: String?,
        resolvedToolName: String,
        resolvedToolArgs: String,
        alwaysConfirm: Boolean,
    ) = with(collector) {
        // Consume the parked record first, before anything that can end the
        // gate early (a failed risk lookup, the destructive hard block). The
        // record is one-shot: an early return that skipped the consumption
        // would leave an answer behind that outlives the gate it belonged to.
        val parkedDecision = consumeParkedDecision(runId, resolvedToolName, resolvedToolArgs)
        if (parkedDecision != null) {
            // Settles the gate this run parked on in an earlier process (which
            // counted itself then). Written here, before the risk and the policy
            // are even read, because the answer was given and the gate ended on
            // every path below — including the ones that refuse the call anyway.
            recordTriggerHitlEvent(
                runId,
                TriggerHitlEvent.Resolved(
                    if (parkedDecision == PendingDecision.APPROVED) {
                        TriggerHitlResolution.APPROVED
                    } else {
                        TriggerHitlResolution.DENIED
                    },
                ),
            )
        }

        // `getRisk` throws `IllegalArgumentException` when the tool isn't in the
        // catalogue — this is reachable if the LLM hallucinates a tool name, or
        // if a tool was unregistered between discovery and execution. Surface a
        // structured `NodeExecutionResult` error instead of letting the
        // exception terminate the pipeline.
        val risk = try {
            toolRepository.getRisk(resolvedToolName, resolvedToolArgs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("PipelineDebug").e(e, "Risk lookup failed for tool '%s'", resolvedToolName)
            val errorMsg = "Risk lookup failed for tool $resolvedToolName: ${e.message}"
            emit(NodeOutput.State(AgentOrchestratorState.Error(errorMsg)))
            emit(NodeOutput.Result(NodeExecutionResult(error = errorMsg, resolvedToolName = resolvedToolName)))
            return@with
        }
        // Hard-deny gate: when the user has opted to block destructive tools
        // outright, refuse the call before even staging the HITL prompt.
        //
        // The denial is surfaced as a `NodeExecutionResult.error` (NOT
        // `outputText`) so the orchestrator treats the node as failed and
        // the upstream planner sees a tool-failure signal rather than a
        // successful observation. Without that distinction the LLM would
        // hallucinate that the destructive action succeeded and could
        // loop back to retry the same call.
        if (risk == ToolRisk.DESTRUCTIVE && settingsRepository.blockDestructiveTools.first()) {
            val message = "Destructive tools are blocked by Settings — $resolvedToolName was not executed."
            chatRepository.saveMessage(
                ChatMessage(
                    sessionId = sessionId,
                    role = Role.SYSTEM,
                    content = message,
                    timestamp = System.currentTimeMillis(),
                    isFinal = false,
                ),
            )
            emit(NodeOutput.State(AgentOrchestratorState.Error(message)))
            emit(
                NodeOutput.Result(
                    NodeExecutionResult(
                        error = message,
                        resolvedToolName = resolvedToolName,
                    ),
                ),
            )
            return@with
        }
        // Which risks ask is the policy's rule, written once in
        // `ToolApprovalPolicy.requiresApproval` — a destructive call asks under
        // every policy. The node's own switch can only ADD a prompt, never
        // remove one — which is why it ORs into the policy instead of replacing
        // it. A pipeline file is a document that can be shared, and a node able
        // to declare "do not ask about this destructive call" would let
        // somebody else's document walk straight past the gate.
        val needsApproval = alwaysConfirm || settingsRepository.toolApprovalPolicy.first().requiresApproval(risk)
        var isApproved = true

        if (parkedDecision != null) {
            // A resumed run carries the user's one-shot decision for this exact
            // request snapshot — apply it without raising a new gate, and apply
            // it whatever [needsApproval] says now. The policy and the risk are
            // re-read on resume (the user may have relaxed either while the run
            // was parked), but they only decide whether a NEW question must be
            // asked; a recorded answer is the answer to one that was asked, and
            // a later setting cannot un-ask it. A denial stays a denial.
            isApproved = parkedDecision == PendingDecision.APPROVED
        } else if (needsApproval) {
            // Journal the gate the moment it is raised, not when (or if) it
            // parks: the live waiting phase is a full minute by default, so a
            // background approval answered promptly from the notification
            // never parks — and recording only parks would leave exactly that
            // case looking like "this run never asked for anything".
            recordTriggerHitlEvent(runId, TriggerHitlEvent.Raised(PendingInteractionKind.APPROVAL))
            val requestId = UUID.randomUUID().toString()
            val approvalRequest =
                AgentOrchestratorState.WaitingForApproval(resolvedToolName, resolvedToolArgs, risk, requestId)
            emit(NodeOutput.State(approvalRequest))
            approvalNotifier.sendApprovalRequest(sessionId, requestId, resolvedToolName, resolvedToolArgs, risk)

            // Register deferred before any suspension point so a fast approval is not dropped
            val deferred = CompletableDeferred<Boolean>()
            val holder = PendingApprovalHolder(deferred, approvalRequest)
            activeApprovalDeferreds[sessionId] = holder
            val timeoutMs = settingsRepository.toolCallTimeoutMs.first()
            var parked = false
            isApproved = try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                // Retire the live gate BEFORE parking, not in the `finally`
                // below. `parkRun` suspends on storage, and while the durable
                // record already exists but the holder is still registered the
                // two states disagree: a notification approval arriving in that
                // window takes `SubmitApprovalDecisionUseCase`'s live
                // short-circuit and completes a deferred whose `withTimeout`
                // has already given up — the decision is silently swallowed and
                // the run stays parked. Removing first makes the transition
                // atomic from an observer's point of view: the gate is either
                // live or durable, never both. The `finally` remove stays for
                // every other exit path (it is a no-op once removed here).
                if (!activeApprovalDeferreds.remove(sessionId, holder)) {
                    // Lost the removal: an answer claimed this very request in
                    // the moment the wait ran out. `resumeWithApproval` removes
                    // and then completes, and it told its caller the request is
                    // settled — so that answer is the answer, and parking now
                    // would drop it. It is completed, or about to be.
                    deferred.await()
                } else {
                    Timber.tag("PipelineDebug").w("Live approval phase timed out for session: %s", sessionId)
                    val request = ParkedApprovalRequest(requestId, resolvedToolName, resolvedToolArgs, risk)
                    if (runId != null && parkRun(runId, sessionId, request)) {
                        // Two-phase wait, second phase: the run parks on its
                        // durable pending record instead of failing. No
                        // NodeOutput.Result on purpose — the engine stops the
                        // walk and the run record stays WAITING_APPROVAL.
                        // Flagged before the emit: a collector that stops at
                        // this state aborts the flow inside it, and the
                        // `finally` must still know the ongoing notification
                        // now holds the request's slot.
                        parked = true
                        recordTriggerHitlEvent(runId, TriggerHitlEvent.Parked)
                        emit(
                            NodeOutput.State(
                                AgentOrchestratorState.SuspendedInBackground(PendingInteractionKind.APPROVAL),
                            ),
                        )
                    } else {
                        abandonTimedOutGate(runId)
                    }
                    return@with
                }
            } finally {
                // Covers every exit: timeout, resume (already removed — the
                // two-arg remove is then a no-op), and plain cancellation of
                // the suspended gate (scope teardown, an abandoned editor
                // test run). Without this, a leaked holder would keep
                // serving pendingApprovalFor a request no coroutine can
                // ever settle. remove(key, value) leaves a newer
                // registration for the same session untouched.
                activeApprovalDeferreds.remove(sessionId, holder)
                // The live notification goes with the wait, however it ended —
                // answered, stopped, timed out without a park. Left behind it is
                // an Approve button for a request nothing waits on any more.
                // Not after a park: the ongoing notification took its slot.
                if (!parked) approvalNotifier.cancelApprovalNotification(requestId)
            }
            // Reached only when the live phase settled with the user's
            // decision (the timeout path returns above), so the gate ends
            // without ever having parked.
            recordTriggerHitlEvent(
                runId,
                TriggerHitlEvent.Resolved(
                    if (isApproved) TriggerHitlResolution.APPROVED else TriggerHitlResolution.DENIED,
                ),
            )
        }

        if (!isApproved) {
            chatRepository.saveMessage(
                ChatMessage(
                    sessionId = sessionId,
                    role = Role.SYSTEM,
                    content = "User denied execution of tool: $resolvedToolName",
                    timestamp = System.currentTimeMillis(),
                    isFinal = false,
                ),
            )
            emit(
                NodeOutput.State(
                    AgentOrchestratorState.ObservationResult(resolvedToolName, "Execution denied by user"),
                ),
            )
            emit(
                NodeOutput.Result(
                    NodeExecutionResult(
                        outputText = "Execution denied by user",
                        resolvedToolName = resolvedToolName,
                    ),
                ),
            )
            return@with
        }

        emit(NodeOutput.State(AgentOrchestratorState.ExecutingTool(resolvedToolName, resolvedToolArgs)))
        val result = try {
            // The context carries the engine-known session id so tools that
            // bind follow-up work to the conversation (schedule_task) get it
            // from a source the LLM-emitted arguments cannot spoof — and the
            // risk this gate decided on, so the repository can refuse a call
            // whose serving tool changed since (an MCP name taken over by a
            // server that reconnected while the card was up).
            toolRepository.executeTool(
                resolvedToolName,
                resolvedToolArgs,
                ToolExecutionContext(sessionId = sessionId, gatedRisk = risk),
            )
        } catch (e: CancellationException) {
            // Preserve structured-concurrency cancellation: tool execution may suspend,
            // and a broad `catch (Exception)` would silently swallow the cancel.
            throw e
        } catch (e: Exception) {
            Timber.tag(
                "PipelineDebug",
            ).e(e, "[NODE_ERR] type=%s id=%s error executing tool: %s", nodeType, nodeId, resolvedToolName)
            "Error executing $resolvedToolName: ${e.message}"
        }

        emit(NodeOutput.State(AgentOrchestratorState.ObservationResult(resolvedToolName, result)))
        chatRepository.saveMessage(
            ChatMessage(
                sessionId = sessionId,
                role = Role.SYSTEM,
                content = "Observation from $resolvedToolName: $result",
                timestamp = System.currentTimeMillis(),
                isFinal = false,
            ),
        )
        emit(NodeOutput.Result(NodeExecutionResult(outputText = result, resolvedToolName = resolvedToolName)))
    }

    /**
     * Consumes the parked approval record of a resumed run, one-shot.
     *
     * The record never survives its first consumption attempt: whatever the
     * outcome, it is deleted so a stale decision can never authorise a later
     * call. The recorded decision applies only under the TOCTOU guard — the
     * re-resolved tool name and arguments must match the parked snapshot
     * exactly; the auto-select / argument-generation LLM passes are not
     * deterministic, and a decision the user gave for one concrete call must
     * not leak onto a different one.
     *
     * @param runId Id of the executing run, or `null` for non-persisted runs.
     * @param resolvedToolName Tool name resolved by this execution.
     * @param resolvedToolArgs Argument string resolved by this execution.
     * @return The user's decision when it may be applied, or `null` when a
     *   fresh approval gate must be raised (no record, undecided record, or
     *   TOCTOU mismatch).
     */
    private suspend fun consumeParkedDecision(
        runId: String?,
        resolvedToolName: String,
        resolvedToolArgs: String,
    ): PendingDecision? {
        if (runId == null) return null
        val parked = pendingInteractionRepository.getForRun(runId) ?: return null
        if (parked.kind != PendingInteractionKind.APPROVAL) return null
        pendingInteractionRepository.delete(runId)
        val argsMatch = parked.toolName == resolvedToolName && parked.toolArgs == resolvedToolArgs
        if (!argsMatch) {
            Timber.tag("PipelineDebug").w(
                "Parked approval of run %s resolved to a different call (%s) — raising a fresh gate",
                runId,
                resolvedToolName,
            )
        }
        return parked.decision?.takeIf { argsMatch }
    }

    /**
     * Fails a call whose live wait ran out and that could not park.
     *
     * Non-persisted runs (editor test runs) and storage failures keep the
     * legacy fail-fast semantics: a park without a durable record would be
     * unrecoverable. The gate then ends without the user ever getting the
     * chance to answer it — ABANDONED, not TIMED_OUT.
     *
     * @param runId Id of the run, or `null` for a non-persisted run.
     */
    private suspend fun FlowCollector<NodeOutput>.abandonTimedOutGate(runId: String?) {
        recordTriggerHitlEvent(runId, TriggerHitlEvent.Resolved(TriggerHitlResolution.ABANDONED))
        emit(NodeOutput.State(AgentOrchestratorState.Error("Approval request timed out")))
        emit(NodeOutput.Result(NodeExecutionResult(error = "Approval request timed out")))
    }

    /**
     * Parks the run in its persistent waiting phase: persists the approval
     * request snapshot as a [PendingInteraction] and, when durable, replaces
     * the transient approval notification with the persistent one.
     *
     * The record keeps the request's identity, so the live-phase card and
     * notification of this request go on answering it after the park — and
     * the ongoing notification lands in the live one's slot.
     *
     * @param runId Id of the persisted run being parked.
     * @param sessionId Id of the owning chat session.
     * @param request The staged call being parked.
     * @return `true` when the park is durable; `false` when the caller must
     *   fall back to failing the run.
     */
    private suspend fun parkRun(runId: String, sessionId: String, request: ParkedApprovalRequest): Boolean {
        val saved = pendingInteractionRepository.save(
            PendingInteraction(
                runId = runId,
                sessionId = sessionId,
                kind = PendingInteractionKind.APPROVAL,
                toolName = request.toolName,
                toolArgs = request.arguments,
                risk = request.risk,
                requestedAt = System.currentTimeMillis(),
                requestId = request.requestId,
            ),
        )
        if (saved) {
            approvalNotifier.sendPersistentApprovalRequest(
                runId = runId,
                sessionId = sessionId,
                requestId = request.requestId,
                toolName = request.toolName,
                arguments = request.arguments,
                risk = request.risk,
            )
        }
        return saved
    }

    /**
     * The staged call a live gate parks, bundled so [parkRun] takes the request
     * as one value rather than four loose parameters.
     *
     * @property requestId Identity of the request, minted when the gate was raised.
     * @property toolName Tool name of the staged call.
     * @property arguments Argument string of the staged call.
     * @property risk Risk classification of the staged call.
     */
    private data class ParkedApprovalRequest(
        val requestId: String,
        val toolName: String,
        val arguments: String,
        val risk: ToolRisk,
    )
}
