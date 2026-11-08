package dev.mule.tool

import dev.mule.session.Msg

/**
 * The driver: model turn, then run, then feed the result back, then turn again,
 * until the model stops asking for tools or the cap stops it. It is an orchestrator
 * over three injected seams -- a [TurnSource] for the next reply, a [CallExtractor]
 * to see the calls, and a [ToolRunner] to run them -- so it holds no transport,
 * persistence or UI itself and every dependency is one a test can swap.
 *
 * It blocks on every turn and on every approval, mirroring the one-shot send this
 * loop replaces; the caller keeps running it off the event thread. [maxIterations]
 * is a hard stop against a model that would loop forever. [chainResponseId] mirrors
 * the caller's session policy: when on it threads each reply's id into the next call
 * to use a server-side session, when off it replays from the client every turn, which
 * is the safe default because a local backend that ignores previous_response_id then
 * still sees each tool observation.
 */
class ToolLoop(
    private val turnSource: TurnSource,
    private val registry: ToolRegistry,
    private val extractor: CallExtractor,
    private val maxIterations: Int,
    private val chainResponseId: Boolean = false
) {

    private val runner = ToolRunner(registry)

    fun run(
        history: List<Msg>,
        previousResponseId: String? = null,
        ctx: ToolContext,
        onDelta: (String) -> Unit = {},
        onReasoning: (String) -> Unit = {}
    ): LoopOutcome {
        val msgs = history.toMutableList()
        val added = mutableListOf<Msg>()
        val steps = mutableListOf<ToolStep>()
        val undos = mutableListOf<() -> Unit>()
        var prev = if (chainResponseId) previousResponseId else null

        repeat(maxIterations.coerceAtLeast(1)) {
            val turn = turnSource.nextTurn(msgs.toList(), prev, onDelta, onReasoning)
            if (chainResponseId && turn.responseId != null) prev = turn.responseId
            addAssistant(msgs, added, turn)
            val calls = extractor.extract(turn)

            if (calls.isEmpty()) {
                steps.add(ToolStep(turn.text, emptyList(), emptyList()))
                return LoopOutcome.Completed(
                    finalText = turn.text,
                    finalTurn = turn,
                    responseId = turn.responseId,
                    steps = steps.toList(),
                    messages = added.toList(),
                    undos = undos.toList()
                )
            }

            val results = calls.map { call ->
                runner.run(call, ctx).also { result ->
                    result.undo?.let { undos.add(it) }
                    addObservation(msgs, added, call, result)
                }
            }
            steps.add(ToolStep(turn.text, calls, results))
        }

        return LoopOutcome.CapReached(
            responseId = prev,
            steps = steps.toList(),
            messages = added.toList(),
            undos = undos.toList()
        )
    }

    private fun addAssistant(msgs: MutableList<Msg>, added: MutableList<Msg>, turn: dev.mule.api.Turn) {
        val msg = Msg("assistant", turn.text, turn.responseId, turn.usage?.prompt)
        msgs.add(msg)
        added.add(msg)
    }

    private fun addObservation(msgs: MutableList<Msg>, added: MutableList<Msg>, call: ToolCall, result: ToolResult) {
        val msg = Msg("user", observation(call, result))
        msgs.add(msg)
        added.add(msg)
    }

    /** The text a tool's outcome becomes when it goes back to the model; stays English. */
    private fun observation(call: ToolCall, result: ToolResult): String = buildString {
        append("tool `").append(call.tool).append(if (result.ok) "` succeeded: " else "` failed: ")
        append(result.output)
        result.diff?.let { append("\n```diff\n").append(it).append("```") }
    }
}
