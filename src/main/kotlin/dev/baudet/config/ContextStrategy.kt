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
enum class ContextStrategy { RecentMessages, RollingWindow, StopAtLimit }
