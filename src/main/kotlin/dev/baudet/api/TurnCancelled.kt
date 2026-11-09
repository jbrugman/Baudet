package dev.baudet.api

/**
 * Raised when the user stops the turn in flight, detected at a streaming boundary. It unwinds
 * the worker the same way a socket close does when Stop lands mid-read, so a stopped turn takes
 * one path instead of persisting a half answer. The UI recognises a stopped turn by its own
 * cancelled flag, so the type only carries the intent.
 */
class TurnCancelled : Exception("the turn was cancelled by the user")
