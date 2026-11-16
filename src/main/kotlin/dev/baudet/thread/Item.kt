package dev.baudet.thread

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The lifecycle of one transcript item.
 *
 * The spellings here are the ones the Codex app-server uses for the same states, taken off
 * the 0.160.0 binary rather than from memory, and that match is the point of this package:
 * the item vocabulary is Baudet's internal seam, so a later phase that speaks the protocol
 * out loud over stdio serializes these names instead of translating them. Read the tag off
 * [wireName]; never invent a second spelling at a call site.
 */
@Serializable
enum class ItemStatus(val wireName: String) {
    @SerialName("inProgress") InProgress("inProgress"),
    @SerialName("completed") Completed("completed"),
    @SerialName("failed") Failed("failed"),
    @SerialName("interrupted") Interrupted("interrupted")
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
@Serializable
enum class ItemKind(val wireName: String) {
    @SerialName("userMessage") UserMessage("userMessage"),
    @SerialName("agentMessage") AgentMessage("agentMessage"),
    @SerialName("reasoning") Reasoning("reasoning"),
    @SerialName("commandExecution") CommandExecution("commandExecution"),
    @SerialName("fileChange") FileChange("fileChange"),
    @SerialName("plan") Plan("plan"),
    @SerialName("error") Error("error"),
    @SerialName("baudet/toolCall") ToolCall("baudet/toolCall"),
    @SerialName("baudet/image") Image("baudet/image")
}

/**
 * What the human decided about a pending approval. The four spellings are the app-server's
 * own decision tags for a command and a patch request. [AcceptForSession] is what Baudet's
 * "Always" pin already is in spirit -- a grant that outlives the turn -- so the decision a
 * card records and the pin the store keeps can be reconciled in one place later.
 */
@Serializable
enum class ApprovalDecision(val wireName: String) {
    @SerialName("accept") Accept("accept"),
    @SerialName("acceptForSession") AcceptForSession("acceptForSession"),
    @SerialName("decline") Decline("decline"),
    @SerialName("cancel") Cancel("cancel")
}

/** The kind of write a file-change item stands for. */
@Serializable
enum class FileChangeKind(val wireName: String) {
    @SerialName("add") Add("add"),
    @SerialName("update") Update("update"),
    @SerialName("delete") Delete("delete")
}

/** One line of a plan. The plan tool does not exist yet; the vocabulary does, so a card can. */
@Serializable
enum class PlanEntryStatus(val wireName: String) {
    @SerialName("pending") Pending("pending"),
    @SerialName("inProgress") InProgress("inProgress"),
    @SerialName("completed") Completed("completed")
}

/** One checklist line of a plan item. */
@Serializable
data class PlanEntry(val content: String, val status: PlanEntryStatus)

/**
 * One row in a conversation, as the interface renders it and as a future protocol frame
 * would carry it. An item is always identified by [itemId] and always scoped by the event
 * that announces it ([ItemStarted], [ItemDelta], [ItemCompleted]), never by position in a
 * list: a position only means something while one task runs, and several tasks at once is
 * exactly what the studio hardware makes possible.
 */
@Serializable
sealed class Item {
    abstract val itemId: String
    abstract val kind: ItemKind
    abstract val status: ItemStatus
}

/** The instruction as the human sent it. */
@Serializable
@SerialName("userMessage")
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
@Serializable
@SerialName("agentMessage")
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
@Serializable
@SerialName("reasoning")
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
@Serializable
@SerialName("commandExecution")
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
@Serializable
@SerialName("fileChange")
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
@Serializable
@SerialName("baudet/toolCall")
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

/**
 * An image a tool produced, shown inline the way the app-server shows a returned image. The
 * bytes live here and nowhere else: [base64] is never folded into a model-facing observation, so
 * a transcript that carries a picture does not spend the context window on it. Named as ours with
 * the `baudet/` prefix like [ToolCallItem], because the app-server has no item tag for a tool's
 * image yet -- dressing it in a Codex tag would make the seam lie about what the wire would carry.
 */
@Serializable
@SerialName("baudet/image")
data class ImageItem(
    override val itemId: String,
    val tool: String,
    val mimeType: String,
    val base64: String,
    override val status: ItemStatus = ItemStatus.Completed
) : Item() {
    override val kind: ItemKind get() = ItemKind.Image
}

/** The plan checklist. Reserved: nothing emits one until Baudet has a plan tool. */
@Serializable
@SerialName("plan")
data class PlanItem(
    override val itemId: String,
    val entries: List<PlanEntry> = emptyList(),
    override val status: ItemStatus = ItemStatus.InProgress
) : Item() {
    override val kind: ItemKind get() = ItemKind.Plan
}

/** A turn-level failure, shown where it happened instead of only in a status line. */
@Serializable
@SerialName("error")
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
