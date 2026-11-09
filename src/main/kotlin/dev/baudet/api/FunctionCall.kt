package dev.baudet.api

/**
 * A native function call the backend returned inside a response. [arguments] is kept as
 * the raw JSON string the backend produced rather than pre-parsed: a malformed or only
 * half-streamed argument object then degrades to "no usable call" at the extractor, not
 * a transport failure. [callId] pairs this call with the observation returned for it.
 */
data class FunctionCall(
    val callId: String?,
    val name: String,
    val arguments: String
)
