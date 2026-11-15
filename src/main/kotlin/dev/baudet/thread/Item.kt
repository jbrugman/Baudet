package dev.baudet.thread

/**
 * The lifecycle of one transcript item.
 *
 * The spellings here are the ones the Codex app-server uses for the same states, taken off
 * the 0.160.0 binary rather than from memory, and that match is the point of this package:
 * the item vocabulary is Baudet's internal seam, so a later phase that speaks the protocol
 * out loud over stdio serializes these names instead of translating them. Read the tag off
 * [wireName]; never invent a second spelling at a call site.
 */
enum class ItemStatus(val wireName: String) {
    InProgress("inProgress"),
    Completed("completed"),
    Failed("failed"),
    Interrupted("interrupted")
}

/**
 * What an item is.
 *
 * Every [wireName] except [ToolCall] is a real app-server item tag. [ToolCall] is named as
 * ours on purpose: Baudet's `read_file` and its read-only `git` are actions the model really
 * took and deserve a row of their own, but dressing them up in a Codex tag would make the
 * seam lie about what the wire would carry, and honest accounting is the reason this app
 * exists. The prefix marks it as an extension a protocol mapper must map or drop, never pass
 * through as if the backend had invented it.
 */
enum class ItemKind(val wireName: String) {
    UserMessage("userMessage"),
    AgentMessage("agentMessage"),
    Reasoning("reasoning"),
    CommandExecution("commandExecution"),
    FileChange("fileChange"),
    Plan("plan"),
    Error("error"),
    ToolCall("baudet/toolCall")
}

/**
 * What the human decided about a pending approval. The four spellings are the app-server's
 * own decision tags for a command and a patch request. [AcceptForSession] is what Baudet's
 * "Always" pin already is in spirit -- a grant that outlives the turn -- so the decision a
 * card records and the pin the store keeps can be reconciled in one place later.
 */
enum class ApprovalDecision(val wireName: String) {
    Accept("accept"),
    AcceptForSession("acceptForSession"),
    Decline("decline"),
    Cancel("cancel")
}

/** The kind of write a file-change item stands for. */
enum class FileChangeKind(val wireName: String) {
    Add("add"),
    Update("update"),
    Delete("delete")
}

/** One line of a plan. The plan tool does not exist yet; the vocabulary does, so a card can. */
enum class PlanEntryStatus(val wireName: String) {
    Pending("pending"),
    InProgress("inProgress"),
    Completed("completed")
}

/** One checklist line of a plan item. */
data class PlanEntry(val content: String, val status: PlanEntryStatus)

/**
 * One row in a conversation, as the interface renders it and as a future protocol frame
 * would carry it. An item is always identified by [itemId] and always scoped by the event
 * that announces it ([ItemStarted], [ItemDelta], [ItemCompleted]), never by position in a
 * list: a position only means something while one task runs, and several tasks at once is
 * exactly what the studio hardware makes possible.
 */
sealed class Item {
    abstract val itemId: String
    abstract val kind: ItemKind
    abstract val status: ItemStatus
}

/** The instruction as the human sent it. */
data class UserMessageItem(
    override val itemId: String,
    val text: String,
    override val status: ItemStatus = ItemStatus.Completed
) : Item() {
    override val kind: ItemKind get() = ItemKind.UserMessage
}

/**
 * One model reply. A tool turn produces one of these per iteration, which is why the text
 * is not simply "the answer": the row the model wrote before it asked to run a command is
 * its own item, exactly as the app-server shows a step before a command card.
 */
data class AgentMessageItem(
    override val itemId: String,
    val text: String = "",
    override val status: ItemStatus = ItemStatus.InProgress
) : Item() {
    override val kind: ItemKind get() = ItemKind.AgentMessage
}

/**
 * Reasoning streamed with a reply. Display-only: it is never replayed into a prompt, so an
 * item here records what was shown, not what was sent.
 */
data class ReasoningItem(
    override val itemId: String,
    val text: String = "",
    override val status: ItemStatus = ItemStatus.InProgress
) : Item() {
    override val kind: ItemKind get() = ItemKind.Reasoning
}

/**
 * A shell command the model asked for and the human let run. The fields carry what a
 * command card shows -- the command, the folder it ran in, the exit code, how long it took,
 * its output and the decision that admitted it -- so the card needs no second source and a
 * transcript reloaded from the store can rebuild itself.
 *
 * [exitCode] stays null for a run that never produced one: a timeout and a command that was
 * never started are not exit code 0, and reporting them as one is the exact dishonesty this
 * app was written against.
 */
data class CommandExecutionItem(
    override val itemId: String,
    val command: String,
    val cwd: String? = null,
    val timeoutSeconds: Int? = null,
    override val status: ItemStatus = ItemStatus.InProgress,
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val durationMs: Long = 0L,
    val output: String = "",
    val approval: ApprovalDecision? = null
) : Item() {
    override val kind: ItemKind get() = ItemKind.CommandExecution
}

/**
 * A write to a project file. [diff] is the human-facing unified preview already produced by
 * the edit tool, and [additions]/[deletions] are counted from it so a card header and a
 * turn's diff summary agree on one number.
 */
data class FileChangeItem(
    override val itemId: String,
    val path: String,
    val change: FileChangeKind = FileChangeKind.Update,
    override val status: ItemStatus = ItemStatus.InProgress,
    val diff: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0
) : Item() {
    override val kind: ItemKind get() = ItemKind.FileChange
}

/** A tool the interface has no dedicated card for yet: `read_file`, and the read-only `git`. */
data class ToolCallItem(
    override val itemId: String,
    val tool: String,
    val detail: String = "",
    override val status: ItemStatus = ItemStatus.InProgress,
    val ok: Boolean = true,
    val output: String = ""
) : Item() {
    override val kind: ItemKind get() = ItemKind.ToolCall
}

/** The plan checklist. Reserved: nothing emits one until Baudet has a plan tool. */
data class PlanItem(
    override val itemId: String,
    val entries: List<PlanEntry> = emptyList(),
    override val status: ItemStatus = ItemStatus.InProgress
) : Item() {
    override val kind: ItemKind get() = ItemKind.Plan
}

/** A turn-level failure, shown where it happened instead of only in a status line. */
data class ErrorItem(
    override val itemId: String,
    val message: String,
    override val status: ItemStatus = ItemStatus.Failed
) : Item() {
    override val kind: ItemKind get() = ItemKind.Error
}

/**
 * The add and delete counts of a unified patch, counted the one way every consumer may use.
 * A diff header line starts with `---` or `+++` and a hunk header with `@@`, so those are
 * skipped before the sign is read; counting them would turn two file paths into two adds.
 */
object DiffStats {
    fun of(diff: String?): Pair<Int, Int> {
        if (diff.isNullOrEmpty()) return 0 to 0
        var added = 0
        var removed = 0
        diff.lineSequence().forEach { line ->
            when {
                line.startsWith("---") || line.startsWith("+++") || line.startsWith("@@") -> Unit
                line.startsWith("+") -> added++
                line.startsWith("-") -> removed++
            }
        }
        return added to removed
    }
}
