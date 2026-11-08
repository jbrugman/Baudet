package dev.mule.tool

import dev.mule.api.Turn
import dev.mule.session.Msg

/**
 * Where the next assistant turn comes from. The loop is deliberately ignorant of
 * transport and persistence: it only asks for the next reply to a transcript. That
 * single seam is what lets the loop be tested against a scripted source, and lets a
 * later native function-calling backend replace prompted-JSON without touching the
 * loop body. [history] is the whole running transcript, newest message last;
 * [previousResponseId] chains a server-side session when the caller opted in.
 */
interface TurnSource {
    fun nextTurn(
        history: List<Msg>,
        previousResponseId: String?,
        onDelta: (String) -> Unit = {},
        onReasoning: (String) -> Unit = {}
    ): Turn
}
