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
/**
 * Tool-calls runnable at once inside one fan-out batch. Two, because this app targets a local box: a
 * couple of read-only calls overlap usefully, while a wider fleet only helps a hosted backend with
 * elastic workers. This bounds tool-calls inside a single turn, not whole turns -- that separate
 * bound is `AppState.MAX_SAFE_PARALLEL`.
 */
private const val MAX_PARALLEL_TOOL_CALLS = 2

/**
 * Small ceiling on consecutive blank, call-less turns. A reasoning model that parks its whole
 * answer in the reasoning channel and emits nothing on the answer channel is not done; one more
 * request is what moves it, which is exactly what a manual nudge did. Bounded so a model that
 * stays blank forever cannot spin the loop in place of answering.
 */
private const val MAX_BLANK_TURNS = 2

/**
 * Ceiling on the text one tool observation may put on the model channel, in characters. The full
 * output always stays in the transcript row -- the sink path that renders the card is untouched --
 * and only what the model is shown is bounded. A large diff or grep replayed on every later turn
 * otherwise (a) bloats the whole history, which is what makes a long session crawl, and (b) can
 * exceed the characters one summarization call carries, which used to strand compaction outright.
 * Kept head-biased so the model still reads how the call began; the human reads everything in the
 * card. It sits under the 48k a fold carries by default, so a normal model never strands on one.
 */
private const val MAX_OBSERVATION_CHARS = 32_000

/**
 * Bound [text] to about [maxChars] by keeping its first three fifths and its last two fifths around
 * an in-place truncation marker. Model-facing, so English. Under the ceiling it returns [text]
 * as-is; the finished string never exceeds [maxChars] by more than the marker's digits, so a
 * caller's own guard on that ceiling still holds.
 */
internal fun capModelText(text: String, maxChars: Int): String {
    if (maxChars <= 0 || text.length <= maxChars) return text
    // A fixed-shape skeleton reserves the marker's room, so head + marker + tail stays under the
    // ceiling; the real marker only swaps the zero-pads for actual figures of no greater width.
    val skeleton = "\n\n[... output truncated: keeping the first 0000 and last 0000 of 000000" +
        " characters; the full output stays in the transcript ...]\n\n"
    val budget = (maxChars - skeleton.length).coerceAtLeast(0)
    val head = budget * 3 / 5
    val tail = budget - head
    val marker = "\n\n[... output truncated: keeping the first $head and last $tail of ${text.length}" +
        " characters; the full output stays in the transcript ...]\n\n"
    return text.substring(0, head) + marker + text.substring(text.length - tail)
}

/**
 * A short in-flight instruction appended to the working conversation when the model parks a turn
 * blank, so the next request differs from the one that just returned nothing. A deterministic local
 * model replays identical messages into an identical blank reply -- the old bare retry changed
 * nothing -- so the only thing that ever moved it was input that differed, which is what a manual
 * nudge did. Kept out of the persisted history (never added to [added]), so the transcript stays clean.
 */
private const val BLANK_NUDGE =
    "Your previous turn returned neither an answer nor a tool call. If you intend to use a tool, " +
    "emit the call now; otherwise reply with your final answer."

/**
 * Whether a call-less turn carries nothing a reader could act on: blank, or only placeholder
 * punctuation such as the "..." a reasoning model sometimes leaves on the answer channel. Neither is
 * a real answer, so both earn one more request instead of ending the turn silently.
 */
private fun isBlankish(text: String): Boolean =
    text.all { it.isWhitespace() || it == '.' || it == '\u2026' }

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
        var blanks = 0
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
            val calls = extractor.extract(turn)

            if (calls.isEmpty()) {
                // A reasoning model can spend a whole turn in its reasoning channel and leave the
                // answer channel blank (or a placeholder like "..."), with no call extracted. That is
                // not a final answer. A bare retry only helped under a server session: replaying the
                // same messages into a deterministic local model reproduces the same blank reply, so
                // nothing moved and a nudge was needed. The fix is to make the next request differ --
                // append a short in-flight nudge that is never persisted -- up to a small bound. Past
                // the bound it ends as before; the caller flags the empty turn, so a run is never a
                // silent no-op again.
                if (isBlankish(turn.text) && blanks < MAX_BLANK_TURNS) {
                    blanks++
                    msgs.add(Msg("user", BLANK_NUDGE))
                    return@repeat
                }
                addAssistant(msgs, added, turn)
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

            addAssistant(msgs, added, turn)
            val results = runCalls(calls, ctx, msgs, added, undos, sink)
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
     * Run one batch of the model's calls. By default they run one at a time, exactly as before. A
     * batch of more than one runs together only when every call is [Tool.canRunParallel] -- writes
     * nothing and waits for no person, e.g. the several reads or greps a local model asks for at
     * once -- and then the emitter is still driven only from this worker thread, in call order, so
     * no pool thread touches its open-row bookkeeping. A mixed batch, or any call that writes or
     * needs an approval, keeps the one-at-a-time path so an approval card still surfaces live.
     */
    private fun runCalls(
        calls: List<ToolCall>,
        ctx: ToolContext,
        msgs: MutableList<Msg>,
        added: MutableList<Msg>,
        undos: MutableList<() -> Unit>,
        sink: ItemSink
    ): List<ToolResult> {
        val parallel = calls.size > 1 &&
            calls.all { registry.get(it.tool)?.canRunParallel(it.arguments, ctx) == true }
        if (parallel) return runCallsInParallel(calls, ctx, msgs, added, undos, sink)
        return calls.map { call ->
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
    }

    /**
     * Run a side-effect-free batch together, then emit in order. The bodies may finish out of order
     * on the pool, but the cards and observations are rebuilt in the model's call order, so a fan-out
     * is indistinguishable from a serial run to the reader and to the next turn. A body cannot throw
     * -- [ToolRunner] turns every failure into a result -- but a null slot is still refused rather
     * than guessed, so a result the pool did not produce is a clean miss, not a crash.
     */
    private fun runCallsInParallel(
        calls: List<ToolCall>,
        ctx: ToolContext,
        msgs: MutableList<Msg>,
        added: MutableList<Msg>,
        undos: MutableList<() -> Unit>,
        sink: ItemSink
    ): List<ToolResult> {
        val results = arrayOfNulls<ToolResult>(calls.size)
        val done = java.util.concurrent.CountDownLatch(calls.size)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(MAX_PARALLEL_TOOL_CALLS, calls.size))
        try {
            calls.forEachIndexed { index, call ->
                pool.execute {
                    try { results[index] = runner.run(call, ctx) } finally { done.countDown() }
                }
            }
            done.await()
        } finally {
            pool.shutdown()
        }
        return calls.mapIndexed { index, call ->
            val result = results[index] ?: ToolResult(ok = false, output = "tool `${call.tool}` produced no result")
            announce(call, sink)
            report(call, result, sink)
            result.undo?.let { undos.add(it) }
            addObservation(msgs, added, call, result)
            result
        }
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

            else -> {
                sink.toolCallCompleted(result.ok, result.output)
                // A tool that returned a picture gets a row of its own after its one-line result, so
                // the image shows inline. The bytes stay off the model channel: only output went there.
                result.images.forEach { sink.imageCompleted(call.tool, it.mimeType, it.base64) }
            }
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
    private fun observation(call: ToolCall, result: ToolResult): String = capModelText(buildString {
        append("Tool observation")
        call.callId?.let { append(" for call ").append(it) }
        append(": `").append(call.tool).append("` ")
        append(if (result.ok) "succeeded (ok=true): " else "failed (ok=false): ")
        append(result.output)
        if (!result.ok) append(" Do not send the same call again unchanged; correct it or answer.")
        result.diff?.let { append("\n").append(fenceChars).append("diff\n").append(it).append("\n").append(fenceChars) }
    }, MAX_OBSERVATION_CHARS)
}
