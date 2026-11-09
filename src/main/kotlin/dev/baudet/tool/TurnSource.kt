package dev.baudet.tool

import dev.baudet.api.Turn
import dev.baudet.session.Msg

/**
 * Where the next assistant turn comes from. The loop is deliberately ignorant of
 * transport and persistence: it only asks for the next reply to a transcript. That
 * single seam is what lets the loop be tested against a scripted source, and lets a
 * later native function-calling backend replace prompted-JSON without touching the
 * loop body. [history] is the whole running transcript, newest message last;
 * [previousResponseId] chains a server-side session when the caller opted in; [tools]
 * is the enabled-function definitions to offer the backend, or null when none apply.
 */
interface TurnSource {
    fun nextTurn(
        history: List<Msg>,
        previousResponseId: String?,
        tools: String? = null,
        onDelta: (String) -> Unit = {},
        onReasoning: (String) -> Unit = {}
    ): Turn
}
