package dev.mule.config

/**
 * What happens when a conversation grows past the context window of the model.
 *
 * [TruncateAtMiddle] and [RollingWindow] are automatic and silent: the app bounds the window it
 * sends rather than refusing, so a long conversation simply keeps going -- the LMStudio behaviour.
 * The stored conversation is never edited; only the copy handed to the model is trimmed.
 * [StopAtLimit] does nothing on its own and turns the oversized turn down before it is sent, so the
 * user decides to compact or to start fresh.
 *
 * Declaration order also drives the Settings combo index, so keep them in this order.
 */
enum class ContextStrategy { TruncateAtMiddle, RollingWindow, StopAtLimit }
