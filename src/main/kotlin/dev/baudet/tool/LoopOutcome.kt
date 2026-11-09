package dev.baudet.tool

import dev.baudet.api.Turn
import dev.baudet.session.Msg

/**
 * What a whole loop run ends as. Two endings only: the model stopped asking for tools
 * (Completed) or the iteration cap cut it off (CapReached). Neither is an error -- a
 * refused or failed tool is fed back to the model as an observation and it can keep
 * going, so tool trouble shows up in [steps], not in the outcome type.
 *
 * [messages] are only the messages this run appended (assistant turns plus the tool
 * observations), never the caller's own new user message: that lets the caller persist
 * just the new turn. [undos] collects the revert handles of every write that landed, and
 * [responseId] is the last reply's id so a caller can chain or store it.
 */
sealed class LoopOutcome {
    abstract val steps: List<ToolStep>
    abstract val messages: List<Msg>
    abstract val undos: List<() -> Unit>
    abstract val responseId: String?

    /** The model gave a final answer with no further tool calls. */
    data class Completed(
        val finalText: String,
        /** The reply that ended the run, kept whole so the caller keeps usage and the finish signal. */
        val finalTurn: Turn,
        override val responseId: String?,
        override val steps: List<ToolStep>,
        override val messages: List<Msg>,
        override val undos: List<() -> Unit>,
        /** true when answers came only after the iteration cap, so the caller can note it. */
        val capReached: Boolean = false
    ) : LoopOutcome()

    /**
     * The run stopped while the model still wanted to act, before a final answer. [incompleteReason]
     * says which: the iteration cap, or the context ceiling that would only send a doomed request.
     */
    data class CapReached(
        override val responseId: String?,
        override val steps: List<ToolStep>,
        override val messages: List<Msg>,
        override val undos: List<() -> Unit>,
        val incompleteReason: String = "max_tool_iterations"
    ) : LoopOutcome()
}
