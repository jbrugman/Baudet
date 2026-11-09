package dev.baudet.api

/**
 * What one payload carried. Answer text and reasoning stay separate: reasoning
 * is shown to the user but must never re-enter the prompt or the transcript.
 */
class Delta(val text: String? = null, val reasoning: String? = null)
