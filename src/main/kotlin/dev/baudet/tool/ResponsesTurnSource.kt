package dev.baudet.tool

import dev.baudet.api.CancellationToken
import dev.baudet.api.ModelGateway
import dev.baudet.api.Turn
import dev.baudet.config.ContextStrategy
import dev.baudet.session.Msg

/**
 * Turns a running transcript into one Responses API call. [Transcript] decides what is
 * replayed and what is the current input, and scrubs leakage on the way; how much the
 * backend re-reads is the caller's choice, carried by [previousResponseId]. [tools]
 * offers the enabled functions natively so a chat-tuned model can answer with a real
 * function_call. Deltas and reasoning stream straight through, so a tool loop reads as
 * one continuous reply.
 */
class ResponsesTurnSource(
    private val gateway: ModelGateway,
    private val system: String,
    /**
     * The caller's context policy, handed down so every turn this source builds is bounded by the
     * same budget that opened the turn rather than by the fixed replay ceiling alone. The default
     * keeps a directly-built source (and the tests) on the original whole-transcript path.
     */
    private val strategy: ContextStrategy = ContextStrategy.StopAtLimit,
    /** The token budget the window trims to; 0 means the window is unknown, which leaves it whole. */
    private val budgetTokens: Long = 0L,
    /** Turns before the current one that keep-last-N sends back; ignored by the other strategies. */
    private val keepRecent: Int = Int.MAX_VALUE,
    /** Cancellation handle for the turn this source drives, so a Stop closes its in-flight request. */
    private val token: CancellationToken = CancellationToken.NONE
) : TurnSource {

    override fun nextTurn(
        history: List<Msg>,
        previousResponseId: String?,
        tools: String?,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): Turn {
        // Bounded by the same policy that opened the turn: under a trimming strategy this caps each
        // request to the budget as observations pile up, not just the first one.
        val (prior, userText) = Transcript.forLoop(
            history, strategy, budgetTokens,
            underServerSession = previousResponseId != null, keepRecent = keepRecent
        )
        return gateway.turn(
            system, prior, userText, previousResponseId, tools, onDelta, onReasoning, token = token
        )
    }
}
