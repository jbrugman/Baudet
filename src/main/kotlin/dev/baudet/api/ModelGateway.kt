package dev.baudet.api

import dev.baudet.session.Msg

/**
 * The transport seam the orchestrator talks to. AppState and its turn sources depend on this
 * interface rather than on a concrete client, so the run, stop and persist decision at the heart
 * of a turn can be driven against a scripted backend in tests. [ResponsesGateway] is the
 * production implementation and forwards one to one to ResponsesClient, so wiring it in removes
 * the inline client construction from the orchestration path without changing a live request.
 *
 * The signatures match ResponsesClient.turn and completeOnce exactly, so a forwarding wrapper is
 * behaviour-preserving: what leaves the process is the same bytes it sent before.
 */
interface ModelGateway {

    /** One streamed chat turn; mirrors ResponsesClient.turn. */
    fun turn(
        system: String,
        history: List<Msg>,
        userText: String,
        previousResponseId: String?,
        tools: String? = null,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit = {},
        token: CancellationToken = CancellationToken.NONE
    ): Turn

    /** A one-off call that is not stored, used by the compactor; mirrors ResponsesClient.completeOnce. */
    fun completeOnce(system: String, userText: String, maxTokens: Int, onDelta: (String) -> Unit): Turn
}
