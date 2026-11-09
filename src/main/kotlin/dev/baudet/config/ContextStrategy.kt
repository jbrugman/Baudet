package dev.baudet.config

/**
 * What happens when a conversation grows past the context window of the model.
 *
 * [RecentMessages] and [RollingWindow] are automatic and silent: the app bounds the window it
 * sends rather than refusing, so a long conversation simply keeps going -- the LMStudio behaviour.
 * The stored conversation is never edited; only the copy handed to the model is trimmed.
 *
 * [RecentMessages] sends only the last few turns -- the newest turn plus the [recent] most recent
 * ones before it -- which is the shape that keeps a local model on task: it always sees the whole
 * recent exchange and never a pinned oldest turn with the middle missing, an arrangement that made
 * the model drift back to long-finished subjects. [RollingWindow] instead fills the window as full
 * as the model can carry and rolls the oldest turns off the front. [StopAtLimit] does nothing on its
 * own and turns the oversized turn down before it is sent, so the user decides to compact or to start
 * fresh.
 *
 * The system prompt is never lost under the trimming strategies: it rides in the instructions of the
 * request rather than in this window, so it always leads and is never rolled off.
 *
 * Declaration order also drives the Settings combo index, so keep them in this order.
 */
enum class ContextStrategy {
    RecentMessages,
    RollingWindow,
    StopAtLimit;

    /**
     * Whether the server-side session can carry context under this strategy.
     *
     * Only [StopAtLimit] lets a server chain lead, because trimming edits what we send and so
     * cannot shrink a chain the backend already holds; the trimming strategies always replay from
     * the client and the send-response-id toggle is inert beneath them. Settings greys that toggle
     * out accordingly, and the live-session status reads the same rule so it cannot overstate a
     * chain that is never used.
     */
    val supportsServerSession: Boolean get() = this == StopAtLimit

    /**
     * Whether the window is bounded by a recent-turn count rather than by the model budget.
     *
     * Only [RecentMessages] sends a fixed count of recent turns, so only there does the
     * recent-turns field mean anything; under [RollingWindow] and [StopAtLimit] Settings greys it
     * out because nothing reads that count.
     */
    val trimsByTurnCount: Boolean get() = this == RecentMessages
}
