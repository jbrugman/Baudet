package dev.baudet.thread

import dev.baudet.api.Turn
import dev.baudet.api.Usage

/**
 * Turns "a turn producer did something" ([ItemSink]) into the event stream an interface
 * renders and a future protocol publishes.
 *
 * The emitter owns the three things a producer must not guess at: identifiers, the current
 * turn, and which row a chunk of text belongs to. It is also the only place that decides an
 * item's final state, so a producer that returns early -- a Stop, a refusal, a tool that
 * threw -- cannot leave a row spinning forever: [turnInterrupted] and [turnFailed] close
 * whatever is still open.
 *
 * One emitter belongs to one conversation and is driven from the one worker thread that runs
 * that conversation's turn, which is how a turn is run today. Parallelism is therefore not
 * shared state inside this class but one emitter per conversation, each scoped by its own
 * [threadId]: on a machine with room to spare, three conversations running at once is three
 * emitters whose streams a listener can separate without looking at the text.
 */
class ThreadEmitter(
    val threadId: String,
    private val publish: (ThreadEvent) -> Unit
) : ItemSink {

    /** The turn the emitted events are scoped to; null until a turn is opened. */
    @Volatile
    var turnId: String? = null
        private set

    private var itemSeq = 0
    private var turnSeq = 0

    private var agent: AgentMessageItem? = null

    /**
     * Whether the open reply was already announced. A reply is announced by its first content,
     * so this is what tells a completion there is a row to complete: announcing a row that was
     * never opened, or never completing one that was, are the two ways a transcript breaks.
     */
    private var agentAnnounced = false
    private var reasoning: ReasoningItem? = null
    private var command: CommandExecutionItem? = null
    private var change: FileChangeItem? = null
    private var toolCall: ToolCallItem? = null

    /** The turn's writes, kept so the turn can be reviewed as one diff. */
    private val changes = mutableListOf<FileChangeItem>()

    /** Open a turn. Callers must open it before the prompt row so the row lands inside it. */
    fun turnStarted() {
        turnId = "$threadId-t${++turnSeq}"
        publish(TurnStarted(threadId, requireNotNull(turnId)))
    }

    /**
     * The human's instruction as a row of its own. A prompt is never implied by the reply that
     * follows it: a transcript has to be rebuildable from rows alone.
     */
    fun userMessage(text: String) {
        val item = UserMessageItem(nextId(), text)
        started(item)
        completed(item.copy(status = ItemStatus.Completed))
    }

    /**
     * A reply is expected. The row itself is announced by the first content that lands in it:
     * a model that answers a turn by asking to run a command produces no prose, and announcing
     * the reply up front would leave an empty assistant row between every step card.
     */
    override fun replyStarted() {
        closeReasoning()
        val open = agent
        if (open != null) completeAgent(open.text)
        agent = AgentMessageItem(nextId())
        agentAnnounced = false
    }

    override fun textDelta(chunk: String) {
        if (chunk.isEmpty()) return
        // Reasoning arrives before the answer on the backends this app targets, so the
        // reasoning row is closed by the first answer chunk rather than by the turn's end.
        closeReasoning()
        var open = agent ?: AgentMessageItem(nextId()).also { agent = it }
        announceAgent(open)
        open = open.copy(text = open.text + chunk)
        agent = open
        publish(ItemDelta(threadId, turn(), open.itemId, open.kind, chunk))
    }

    override fun reasoningDelta(chunk: String) {
        if (chunk.isEmpty()) return
        var open = reasoning ?: run {
            val fresh = ReasoningItem(nextId())
            reasoning = fresh
            started(fresh)
            fresh
        }
        open = open.copy(text = open.text + chunk)
        reasoning = open
        publish(ItemDelta(threadId, turn(), open.itemId, open.kind, chunk))
    }

    override fun replyCompleted(text: String, status: ItemStatus) {
        closeReasoning()
        completeAgent(text, status)
    }

    override fun commandStarted(command: String, cwd: String?, timeoutSeconds: Int?) {
        closeReasoning()
        val open = agent
        if (open != null) completeAgent(open.text)
        val item = CommandExecutionItem(nextId(), command, cwd, timeoutSeconds)
        this.command = item
        started(item)
    }

    override fun commandDelta(chunk: String) {
        val open = command ?: return
        if (chunk.isEmpty()) return
        command = open.copy(output = open.output + chunk)
        publish(ItemDelta(threadId, turn(), open.itemId, open.kind, chunk))
    }

    override fun commandCompleted(
        exitCode: Int?,
        timedOut: Boolean,
        durationMs: Long,
        output: String,
        ok: Boolean,
        approval: ApprovalDecision?,
        cwd: String?
    ) {
        val open = command ?: return
        command = null
        completed(
            open.copy(
                status = if (ok) ItemStatus.Completed else ItemStatus.Failed,
                exitCode = exitCode,
                timedOut = timedOut,
                durationMs = durationMs,
                output = if (output.isNotEmpty()) output else open.output,
                cwd = cwd ?: open.cwd,
                approval = approval
            )
        )
    }

    override fun fileChangeStarted(path: String, change: FileChangeKind) {
        closeReasoning()
        val open = agent
        if (open != null) completeAgent(open.text)
        val item = FileChangeItem(nextId(), path, change)
        this.change = item
        started(item)
    }

    override fun fileChangeCompleted(path: String, change: FileChangeKind, diff: String?, ok: Boolean) {
        val open = this.change ?: return
        this.change = null
        val (added, removed) = DiffStats.of(diff)
        val done = open.copy(
            status = if (ok) ItemStatus.Completed else ItemStatus.Failed,
            diff = diff,
            additions = added,
            deletions = removed
        )
        if (ok && diff != null) changes.add(done)
        completed(done)
    }

    override fun toolCallStarted(tool: String, detail: String) {
        closeReasoning()
        val open = agent
        if (open != null) completeAgent(open.text)
        val item = ToolCallItem(nextId(), tool, detail)
        toolCall = item
        started(item)
    }

    override fun toolCallCompleted(ok: Boolean, output: String) {
        val open = toolCall ?: return
        toolCall = null
        completed(open.copy(status = if (ok) ItemStatus.Completed else ItemStatus.Failed, ok = ok, output = output))
    }

    override fun imageCompleted(tool: String, mimeType: String, base64: String) {
        // An image is a one-shot row: there is nothing to stream into it, so it starts and finishes
        // in one beat, like the prompt row. It does not touch the open agent/command/change/tool,
        // because the picture belongs to the tool that produced it, already closed above.
        val item = ImageItem(nextId(), tool, mimeType, base64)
        started(item)
        completed(item)
    }

    override fun interrupted() {
        closeReasoning()
        agent?.let { if (agentAnnounced) completed(it.copy(status = ItemStatus.Interrupted)) }
        agent = null
        agentAnnounced = false
        command?.let { completed(it.copy(status = ItemStatus.Interrupted)) }
        command = null
        change?.let { completed(it.copy(status = ItemStatus.Interrupted)) }
        change = null
        toolCall?.let { completed(it.copy(status = ItemStatus.Interrupted)) }
        toolCall = null
    }

    /** A Stop: the rows keep what they reached, and the turn says why it did not finish. */
    fun turnInterrupted() {
        interrupted()
        publish(TurnCompleted(threadId, turn(), incompleteReason = "interrupted"))
    }

    /** A failure that ended the turn, shown as a row where it happened. */
    fun turnFailed(message: String) {
        interrupted()
        val item = ErrorItem(nextId(), message)
        started(item)
        completed(item)
        publish(TurnCompleted(threadId, turn(), incompleteReason = "failed"))
    }

    /**
     * Close a turn that ran. The accounting goes out before the completion, because it is the
     * evidence a reader needs to judge the answer: a truncated reply with its token counts on
     * screen is a different event from a short answer, and both look like a short answer once
     * the turn has closed.
     */
    fun turnCompleted(turn: Turn) {
        interrupted()
        turn.usage?.let { publish(TokenUsageUpdated(threadId, turn(), it)) }
        publishDiff()
        publish(
            TurnCompleted(
                threadId = threadId,
                turnId = turn(),
                responseId = turn.responseId,
                usage = turn.usage,
                finalEvent = turn.finalEvent,
                incompleteReason = turn.incompleteReason
            )
        )
    }

    /** The file changes this turn wrote, for a caller that keeps its own view of them. */
    fun turnChanges(): List<FileChangeItem> = changes.toList()

    /**
     * Announce that the human must decide on a step, and hand back the identifier a listener waits
     * on. The request is raised against the step card the producer opened just before it began to
     * run, so one card carries the step, its preview and the buttons -- this is how the app-server
     * routes an approval to the item that asked for it. Only one card is ever open at a time: the
     * loop opens a card, asks, then closes it before the next, so whichever of the command, the
     * file change or the tool call is open when a request arrives is the one that must carry its
     * buttons. A git write borrows the command approval path but opened a tool-call card, which is
     * why the home is the open card rather than a card matched by request type. A request that finds
     * no open card at all falls back to a row of its own rather than being dropped. The answer is not
     * taken here: this only publishes the question, so the class never blocks; the caller owns the wait.
     */
    fun requestApproval(request: ApprovalRequest): String {
        val itemId = command?.itemId ?: change?.itemId ?: toolCall?.itemId ?: nextId()
        publish(ApprovalRequested(threadId, turn(), itemId, request))
        return itemId
    }

    /** A pending approval was answered: close its buttons and record the decision on its row. */
    fun resolveApproval(itemId: String, decision: ApprovalDecision) {
        publish(ApprovalResolved(threadId, turn(), itemId, decision))
    }

    private fun publishDiff() {
        if (changes.isEmpty()) return
        publish(
            TurnDiffUpdated(
                threadId = threadId,
                turnId = turn(),
                files = changes.toList(),
                diff = changes.joinToString("\n") { it.diff.orEmpty() },
                additions = changes.sumOf { it.additions },
                deletions = changes.sumOf { it.deletions }
            )
        )
    }

    /**
     * Close the open reply. A reply that was announced but stayed empty emits nothing at all:
     * it has no content to render, and a row carried only by its identifier is a row the
     * transcript pretends happened. Once anything reached it, the completion carries the whole
     * text, which is the backend's final answer rather than what happened to stream.
     */
    private fun completeAgent(text: String, status: ItemStatus = ItemStatus.Completed) {
        val open = agent ?: return
        val wasAnnounced = agentAnnounced
        agent = null
        agentAnnounced = false
        if (!wasAnnounced) return
        completed(open.copy(text = if (text.isNotEmpty()) text else open.text, status = status))
    }

    /** Announce the reply row on the first chunk that gives it content, once per item. */
    private fun announceAgent(item: AgentMessageItem) {
        if (agentAnnounced) return
        agentAnnounced = true
        started(item)
    }

    private fun closeReasoning() {
        val open = reasoning ?: return
        reasoning = null
        completed(open.copy(status = ItemStatus.Completed))
    }

    private fun started(item: Item) = publish(ItemStarted(threadId, turn(), item))

    private fun completed(item: Item) = publish(ItemCompleted(threadId, turn(), item))

    /**
     * Identifiers are prefixed with the conversation, so an id from one thread can never be
     * mistaken for an id from another once several conversations run side by side.
     */
    private fun nextId(): String = "$threadId-i${++itemSeq}"

    /** A turn opened lazily if a producer reported work without announcing one. */
    private fun turn(): String = turnId ?: run {
        turnStarted()
        requireNotNull(turnId)
    }
}
