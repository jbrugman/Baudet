package dev.baudet.api

import dev.baudet.config.AppConfig
import dev.baudet.session.Msg

/**
 * The production [ModelGateway]. It owns one ResponsesClient and forwards both calls unchanged,
 * so replacing the inline ResponsesClient call sites on the orchestration path with this gateway
 * changes nothing about how a live request is built or streamed -- it only makes the seam injectable.
 */
class ResponsesGateway(cfg: AppConfig) : ModelGateway {

    private val client = ResponsesClient(cfg)

    override fun turn(
        system: String,
        history: List<Msg>,
        userText: String,
        previousResponseId: String?,
        tools: String?,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit,
        token: CancellationToken
    ): Turn = client.turn(system, history, userText, previousResponseId, tools, onDelta, onReasoning, token)

    override fun completeOnce(system: String, userText: String, maxTokens: Int, onDelta: (String) -> Unit): Turn =
        client.completeOnce(system, userText, maxTokens, onDelta)
}
