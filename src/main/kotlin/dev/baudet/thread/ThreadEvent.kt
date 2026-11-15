package dev.baudet.thread

import dev.baudet.api.Usage

/**
 * Everything that happens in a conversation, as a stream of facts rather than as text the
 * interface has to reassemble.
 *
 * Two decisions make this the seam a real app-server can be built on later, and both come
 * from what a machine with room to spare actually needs:
 *
 *  * every event carries its [ThreadEvent.threadId], because one conversation's events may
 *    not be assumed to be the only ones in the process -- a studio that runs three tasks at
 *    once produces three interleaved streams, and a listener must be able to tell them apart
 *    without guessing from the text;
 *  * every event carries its [ThreadEvent.turnId], so a listener that started watching
 *    mid-turn, or one that still holds an older turn's card, can ignore what it is not
 *    looking at.
 *
 * [wireMethod] is the app-server method each event corresponds to, using that protocol's own
 * spelling. It is documentation that is checked by a test rather than a comment: when this
 * process eventually answers a JSON-RPC request over stdio, it publishes these methods.
 */
sealed class ThreadEvent {
    abstract val threadId: String
    abstract val turnId: String

    /** The Codex app-server method this event maps to. */
    abstract val wireMethod: String
}

/** A turn began, so the interface can open its card and time it. */
data class TurnStarted(
    override val threadId: String,
    override val turnId: String
) : ThreadEvent() {
    override val wireMethod: String get() = "turn/started"
}

/** A new row. The [item] is the row's initial state, usually in progress with no content. */
data class ItemStarted(
    override val threadId: String,
    override val turnId: String,
    val item: Item
) : ThreadEvent() {
    override val wireMethod: String get() = "item/started"
}

/**
 * Part of an item's content. The kind decides the method, exactly as the protocol splits
 * one stream per content type: an answer delta and a command's output delta are different
 * notifications even though both are "more text".
 */
data class ItemDelta(
    override val threadId: String,
    override val turnId: String,
    val itemId: String,
    val kind: ItemKind,
    val delta: String
) : ThreadEvent() {
    override val wireMethod: String get() = deltaMethod(kind)
}

/** An item reached its final state. The [item] carries it, so a renderer never keeps its own. */
data class ItemCompleted(
    override val threadId: String,
    override val turnId: String,
    val item: Item
) : ThreadEvent() {
    override val wireMethod: String get() = "item/completed"
}

/** The plan checklist changed. Reserved with the plan tool. */
data class PlanUpdated(
    override val threadId: String,
    override val turnId: String,
    val entries: List<PlanEntry>
) : ThreadEvent() {
    override val wireMethod: String get() = "turn/plan/updated"
}

/**
 * The whole turn's accumulated file changes. This is what lets an interface offer one
 * "review this turn" action instead of asking the reader to remember which cards wrote what.
 */
data class TurnDiffUpdated(
    override val threadId: String,
    override val turnId: String,
    val files: List<FileChangeItem>,
    val diff: String,
    val additions: Int,
    val deletions: Int
) : ThreadEvent() {
    override val wireMethod: String get() = "turn/diff/updated"
}

/** The turn's token accounting, emitted as its own event so a footer can update before the turn closes. */
data class TokenUsageUpdated(
    override val threadId: String,
    override val turnId: String,
    val usage: Usage
) : ThreadEvent() {
    override val wireMethod: String get() = "thread/tokenUsage/updated"
}

/**
 * A turn finished, cleanly or not. [finalEvent] and [incompleteReason] are the backend's own
 * signals, kept verbatim: "no answer" and "budget exhausted before the answer started" have
 * to stay distinguishable here, because nowhere downstream can they be recovered.
 */
data class TurnCompleted(
    override val threadId: String,
    override val turnId: String,
    val responseId: String? = null,
    val usage: Usage? = null,
    val finalEvent: String? = null,
    val incompleteReason: String? = null
) : ThreadEvent() {
    override val wireMethod: String get() = "turn/completed"
}

/** What the interface must ask the human before the turn may continue. */
sealed class ApprovalRequest {
    abstract val wireMethod: String

    /** A shell command waiting for a decision, with the folder it would run in. */
    data class RunCommand(
        val command: String,
        val cwd: String?,
        val timeoutSeconds: Int?
    ) : ApprovalRequest() {
        override val wireMethod: String get() = "item/commandExecution/requestApproval"
    }

    /** A patch waiting for a decision. */
    data class ApplyPatch(
        val path: String,
        val diff: String?
    ) : ApprovalRequest() {
        override val wireMethod: String get() = "item/fileChange/requestApproval"
    }
}

/** The turn is blocked on [request]; the answer arrives as an [ApprovalResolved]. */
data class ApprovalRequested(
    override val threadId: String,
    override val turnId: String,
    val itemId: String,
    val request: ApprovalRequest
) : ThreadEvent() {
    override val wireMethod: String get() = request.wireMethod
}

/** A pending approval was answered, by the human or by a stored grant. */
data class ApprovalResolved(
    override val threadId: String,
    override val turnId: String,
    val itemId: String,
    val decision: ApprovalDecision
) : ThreadEvent() {
    override val wireMethod: String get() = "serverRequest/resolved"
}

/**
 * The method a content delta of [kind] travels under. An item kind with no delta method of
 * its own -- a plan line, an error -- reports its state through [ItemCompleted] instead, so
 * the fallback is spelled as ours rather than borrowed.
 */
fun deltaMethod(kind: ItemKind): String = when (kind) {
    ItemKind.AgentMessage, ItemKind.UserMessage -> "item/agentMessage/delta"
    ItemKind.Reasoning -> "item/reasoning/textDelta"
    ItemKind.CommandExecution -> "item/commandExecution/outputDelta"
    ItemKind.FileChange -> "item/fileChange/outputDelta"
    ItemKind.Plan -> "item/plan/delta"
    else -> "baudet/item/delta"
}
