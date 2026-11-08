package dev.mule.tool

import dev.mule.api.ResponsesClient
import dev.mule.api.Turn
import dev.mule.session.Msg

/**
 * Turns a running transcript into one Responses API call. The last message is the
 * current input and the rest is history, which is exactly the shape ResponsesClient
 * already takes; how much of that history the backend re-reads is the caller's
 * choice, carried by [previousResponseId] -- set it to replay from the client, leave
 * it null to lean on a stored session. It streams deltas and reasoning straight
 * through, so a tool loop looks like one continuous reply to the reader.
 */
class ResponsesTurnSource(
    private val client: ResponsesClient,
    private val system: String
) : TurnSource {

    override fun nextTurn(
        history: List<Msg>,
        previousResponseId: String?,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): Turn {
        val userText = history.lastOrNull()?.content ?: ""
        val prior = if (history.isEmpty()) emptyList() else history.dropLast(1)
        return client.turn(system, prior, userText, previousResponseId, onDelta, onReasoning)
    }
}
