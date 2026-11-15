package dev.baudet.tool

import dev.baudet.api.str
import dev.baudet.session.Msg
import dev.baudet.session.SessionState
import dev.baudet.thread.FileChangeKind
import dev.baudet.thread.ItemKind
import dev.baudet.thread.ItemSink
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The driver: model turn, then run, then feed the result back, then turn again, until
 * the model stops asking for tools or the cap stops it. It is an orchestrator over three
 * injected seams -- a [TurnSource] for the next reply, a [CallExtractor] to see the
 * calls, and a [ToolRunner] to run them -- so it holds no transport, persistence or UI
 * itself and every dependency is one a test can swap.
 *
 * It blocks on every turn and on every approval, mirroring the one-shot send this loop
 * replaces; the caller keeps running it off the event thread. [maxIterations] is a hard
 * stop against a model that would loop forever; when the model finally answers only
 * after that budget, the outcome still carries the answer but flags capReached.
 * [chainResponseId] mirrors the caller's session policy: when on it threads each reply's
 * id into the next call to use a server-side session, when off it replays from the client
 * every turn, the safe default because a local backend that ignores previous_response_id
 * then still sees each tool observation. [ToolRegistry.definitions] is offered to every
 * turn so the backend can answer with a native function_call.
 */
class ToolLoop(
    private val turnSource: TurnSource,
    private val registry: ToolRegistry,
    private val extractor: CallExtractor,
    private val maxIterations: Int,
    private val chainResponseId: Boolean = false,
    /**
     * Hard ceiling on the token estimate of the running transcript, only enforced while chaining a
     * server session (where the backend replays and grows its own context, which the client cannot
     * bound by trimming what it sends). 0 disables it. When the estimate passes it, the loop stops
     * cleanly instead of firing a request the backend is certain to refuse.
     */
    private val contextCeiling: Long = 0L
) {

    private val runner = ToolRunner(registry)
    private var onMessage: (Msg) -> Unit = {}

    /** Three grave accents, built from a code point so the source never holds the run. */
    private val fenceChars = "\u0060\u0060\u0060"

    fun run(
        history: List<Msg>,
        previousResponseId: String? = null,
        ctx: ToolContext,
        onDelta: (String) -> Unit = {},
        onReasoning: (String) -> Unit = {},
        onMessage: (Msg) -> Unit = {},
        image: String? = null,
        /**
         * Records what the loop does as transcript rows. Last and defaulted on purpose: a
         * caller that does not watch the turn is untouched by it, while one that does sees
         * each step as the model took it instead of only the final text.
         */
        sink: ItemSink = ItemSink.None
    ): LoopOutcome {
        this.onMessage = onMessage
        // A chunk goes to both listeners. The sink records it as a row's content and the
        // callback still hands it to whoever watches the stream; neither replaces the other,
        // because the callbacks are what callers already pass and cannot be taken away.
        val emitText: (String) -> Unit = { chunk -> sink.textDelta(chunk); onDelta(chunk) }
        val emitReasoning: (String) -> Unit = { chunk -> sink.reasoningDelta(chunk); onReasoning(chunk) }
        val msgs = history.toMutableList()
        val added = mutableListOf<Msg>()
        val steps = mutableListOf<ToolStep>()
        val undos = mutableListOf<() -> Unit>()
        var prev = if (chainResponseId) previousResponseId else null
        val tools: String? = if (registry.isEmpty()) null else registry.definitions()
        var callSteps = 0
        // An image rides only the first model request of the turn; later iterations replay text.
        var pendingImage = image

        repeat(maxIterations.coerceAtLeast(1)) {
            if (contextCeiling > 0 && chainResponseId &&
                SessionState.estimateTokensOf(msgs) > contextCeiling
            ) {
                return LoopOutcome.CapReached(
                    prev, steps.toList(), added.toList(), undos.toList(),
                    incompleteReason = "context_limit"
                )
            }
            sink.replyStarted()
            val turn = turnSource.nextTurn(msgs.toList(), prev, tools, emitText, emitReasoning, image = pendingImage)
            pendingImage = null
            sink.replyCompleted(turn.text)
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
                    undos = undos.toList(),
                    capReached = callSteps >= maxIterations
                )
            }

            val results = calls.map { call ->
                // Opened before the run, not after it: a tool asks for approval from inside
                // execute(), so a card raised afterwards reports a decision the reader already
                // made, on a row they were not looking at yet.
                announce(call, sink)
                val result = runner.run(call, ctx)
                report(call, result, sink)
                result.undo?.let { undos.add(it) }
                addObservation(msgs, added, call, result)
                result
            }
            steps.add(ToolStep(turn.text, calls, results))
            callSteps++
        }

        // Budget spent while the model still wanted to act: give it one turn to answer in
        // prose so the user is not left without a reply; if it asks to act again, stop.
        sink.replyStarted()
        val settle = turnSource.nextTurn(msgs.toList(), prev, tools, emitText, emitReasoning, image = pendingImage)
        sink.replyCompleted(settle.text)
        if (chainResponseId && settle.responseId != null) prev = settle.responseId
        addAssistant(msgs, added, settle)
        if (extractor.extract(settle).isEmpty()) {
            steps.add(ToolStep(settle.text, emptyList(), emptyList()))
            return LoopOutcome.Completed(
                finalText = settle.text,
                finalTurn = settle,
                responseId = settle.responseId,
                steps = steps.toList(),
                messages = added.toList(),
                undos = undos.toList(),
                capReached = true
            )
        }
        return LoopOutcome.CapReached(prev, steps.toList(), added.toList(), undos.toList())
    }

    /**
     * Open the row a call will fill. The card comes from the tool's own [Tool.itemKind], so a
     * tool rename cannot quietly move a run into the wrong row. An unresolvable tool still gets
     * one: a call the loop could not dispatch is precisely the event worth showing, and since
     * the runner already turns it into a failed result, hiding it would leave the model seeing
     * a failure the human cannot account for.
     */
    private fun announce(call: ToolCall, sink: ItemSink) {
        when (kindOf(call)) {
            ItemKind.CommandExecution -> sink.commandStarted(
                command = call.arguments.str("command").orEmpty(),
                cwd = null,
                timeoutSeconds = call.arguments.intValue("timeout_seconds")
            )

            ItemKind.FileChange -> sink.fileChangeStarted(call.arguments.str("file").orEmpty())
            else -> sink.toolCallStarted(call.tool, call.arguments.detail())
        }
    }

    /** Close the row [announce] opened with what the run actually produced. */
    private fun report(call: ToolCall, result: ToolResult, sink: ItemSink) {
        when (kindOf(call)) {
            ItemKind.CommandExecution -> sink.commandCompleted(
                exitCode = result.exitCode,
                timedOut = result.timedOut,
                durationMs = result.durationMs,
                output = result.output,
                ok = result.ok,
                cwd = result.cwd
            )

            // Update, not the tool's kind: edit_file patches text in a file it first reads, so
            // the kinds agree, and a rejected write is carried by the item's status instead.
            ItemKind.FileChange -> sink.fileChangeCompleted(
                path = call.arguments.str("file").orEmpty(),
                change = FileChangeKind.Update,
                diff = result.diff,
                ok = result.ok
            )

            else -> sink.toolCallCompleted(result.ok, result.output)
        }
    }

    private fun kindOf(call: ToolCall): ItemKind = registry.get(call.tool)?.itemKind ?: ItemKind.ToolCall

    /** The argument worth naming on a card that has no card of its own. */
    private fun JsonObject.detail(): String = str("file") ?: str("command") ?: ""

    /**
     * An integer argument read tolerantly. It is only ever used to fill in a card's timeout, so
     * a value the model sent as a string still shows instead of silently blanking -- but a value
     * that is not a number at all stays absent, because a card may not invent a limit the tool
     * did not apply.
     */
    private fun JsonObject.intValue(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()

    private fun addAssistant(msgs: MutableList<Msg>, added: MutableList<Msg>, turn: dev.baudet.api.Turn) {
        val msg = Msg("assistant", turn.text, turn.responseId, turn.usage?.prompt)
        msgs.add(msg)
        added.add(msg)
        onMessage(msg)
    }

    private fun addObservation(msgs: MutableList<Msg>, added: MutableList<Msg>, call: ToolCall, result: ToolResult) {
        val msg = Msg("user", observation(call, result))
        msgs.add(msg)
        added.add(msg)
        onMessage(msg)
    }

    /**
     * The text a tool's outcome becomes when it goes back to the model; stays English.
     * It names the call id, the tool, an explicit ok flag and the diff, so the model can
     * self-correct: a failed call that does not say "do not repeat this" invites a model
     * to retry the identical wrong call, and the loop bounds the count but not the loop.
     */
    private fun observation(call: ToolCall, result: ToolResult): String = buildString {
        append("Tool observation")
        call.callId?.let { append(" for call ").append(it) }
        append(": `").append(call.tool).append("` ")
        append(if (result.ok) "succeeded (ok=true): " else "failed (ok=false): ")
        append(result.output)
        if (!result.ok) append(" Do not send the same call again unchanged; correct it or answer.")
        result.diff?.let { append("\n").append(fenceChars).append("diff\n").append(it).append("\n").append(fenceChars) }
    }
}
