package dev.mule.api

data class Turn(
    val text: String,
    val responseId: String?,
    val usage: Usage?,
    /** the finish signal the backend actually sent, for diagnostics */
    val finalEvent: String?,
    /** payloads seen per event type, in first-seen order */
    val events: Map<String, Int> = emptyMap(),
    /** first raw payload per event type, truncated: what the wire really carried */
    val samples: Map<String, String> = emptyMap(),
    /** reasoning summary streamed alongside the answer, kept out of the prompt */
    val reasoning: String = "",
    /** why the backend stopped early, e.g. max_output_tokens; null when it finished */
    val incompleteReason: String? = null
)
