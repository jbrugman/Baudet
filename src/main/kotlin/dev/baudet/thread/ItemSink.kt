package dev.baudet.thread

/**
 * What a turn producer can report about the work it is doing, expressed as rows rather than
 * as a callback per byte.
 *
 * It is deliberately narrower than [ThreadEvent]: the tool loop knows a reply started, a
 * command is running and a file was written, and it never has to invent identifiers, decide
 * which method an update travels under, or know how many listeners there are. Those are the
 * emitter's job, which keeps `tool/` free of protocol concerns and lets the same loop drive
 * one conversation or, on a machine that can, one of several at once.
 *
 * Every method has a no-op default through [None], so a caller that does not watch a turn
 * keeps compiling and behaving exactly as before.
 */
interface ItemSink {

    /** A model reply begins. Deltas that follow belong to it until [replyCompleted]. */
    fun replyStarted() {}

    /** Text of the reply that is open; opens one when the producer forgot to announce it. */
    fun textDelta(chunk: String) {}

    /** Reasoning streamed with the reply that is open. Display-only, never replayed. */
    fun reasoningDelta(chunk: String) {}

    /** The open reply ended with [text]. */
    fun replyCompleted(text: String, status: ItemStatus = ItemStatus.Completed) {}

    /**
     * A command is about to run. Announcing it before the run is what lets a decision appear
     * on the card the reader is already looking at, instead of in a window that blocks the
     * turn and the transcript behind it.
     */
    fun commandStarted(command: String, cwd: String?, timeoutSeconds: Int? = null) {}

    /** More of the command's output. */
    fun commandDelta(chunk: String) {}

    /**
     * The command ended. A null [exitCode] means none was produced: a timeout is not code 0.
     *
     * [cwd] is the folder it really ran in, reported here rather than at [commandStarted]:
     * the tool resolves the real path, and for a project folder reached through a symlink that
     * path is not the one the caller handed over. A card that names the folder before the run
     * would be naming a guess.
     */
    fun commandCompleted(
        exitCode: Int?,
        timedOut: Boolean,
        durationMs: Long,
        output: String,
        ok: Boolean,
        approval: ApprovalDecision? = null,
        cwd: String? = null
    ) {
    }

    /** A write to [path] is about to be attempted. */
    fun fileChangeStarted(path: String, change: FileChangeKind = FileChangeKind.Update) {}

    /** The write ended, carrying the preview [diff] when the tool produced one. */
    fun fileChangeCompleted(path: String, change: FileChangeKind, diff: String?, ok: Boolean) {}

    /** A call with no card of its own started, e.g. a file read. */
    fun toolCallStarted(tool: String, detail: String = "") {}

    /** ... and ended. */
    fun toolCallCompleted(ok: Boolean, output: String = "") {}

    /**
     * A tool returned a picture (base64, with its MIME type), shown as its own row. The bytes are
     * reported here, never through a text delta, so they reach the transcript without ever being
     * folded into what the model reads.
     */
    fun imageCompleted(tool: String, mimeType: String, base64: String) {}

    /** Close every open row as interrupted: what a deliberate Stop must leave behind. */
    fun interrupted() {}

    /** A sink that listens to nothing, the default for every existing caller. */
    companion object { val None: ItemSink = object : ItemSink {} }
}
