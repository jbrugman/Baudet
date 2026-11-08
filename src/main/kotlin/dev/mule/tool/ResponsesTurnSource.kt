package dev.mule.tool

import dev.mule.api.ResponsesClient
import dev.mule.api.Turn
import dev.mule.session.Msg

/**
 * Turns a running transcript into one Responses API call. [Transcript] decides what is
 * replayed and what is the current input, and scrubs leakage on the way; how much the
 * backend re-reads is the caller's choice, carried by [previousResponseId]. [tools]
 * offers the enabled functions natively so a chat-tuned model can answer with a real
 * function_call. Deltas and reasoning stream straight through, so a tool loop reads as
 * one continuous reply.
 */
class ResponsesTurnSource(
    private val client: ResponsesClient,
    private val system: String
) : TurnSource {

    override fun nextTurn(
        history: List<Msg>,
        previousResponseId: String?,
        tools: String?,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): Turn {
        val (prior, _) = Transcript.forCall(history)
        val userText = Transcript.currentInput(history, previousResponseId != null)
        return client.turn(system, prior, userText, previousResponseId, tools, onDelta, onReasoning)
    }
}
