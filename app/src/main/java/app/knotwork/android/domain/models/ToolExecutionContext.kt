package app.knotwork.android.domain.models

/**
 * Engine-side context that accompanies a tool invocation.
 *
 * Tool arguments themselves come from the LLM as a JSON string and therefore
 * can never be trusted to carry trustworthy identifiers. Values that the
 * execution environment knows authoritatively — such as which chat session
 * the enclosing pipeline run belongs to — travel through this context object
 * instead, so executors that need them (e.g. `schedule_task` binding a
 * scheduled run back to the originating session) read them from a source the
 * model cannot spoof.
 *
 * @property sessionId Id of the chat session whose pipeline run invoked the
 *   tool, or `null` when the invocation has no session affiliation (e.g.
 *   direct execution from a tool-detail debug surface).
 * @property gatedRisk The risk the HITL gate resolved for this call and decided
 *   on — to ask or not to ask. The repository re-checks it against the tool that
 *   is actually about to run and refuses the call when they differ, so a call is
 *   never run under a decision made for another tool. That can happen with MCP:
 *   the server serving a name is resolved again at dispatch, and a server that
 *   reconnected in between can take the name over. `null` when the caller did
 *   not go through the gate (tests only — the gate is the one production caller).
 */
data class ToolExecutionContext(val sessionId: String? = null, val gatedRisk: ToolRisk? = null) {
    /** Well-known context instances. */
    companion object {
        /** Context carrying no environment information. */
        val EMPTY: ToolExecutionContext = ToolExecutionContext()
    }
}
