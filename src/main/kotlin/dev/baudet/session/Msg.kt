package dev.baudet.session

import kotlinx.serialization.Serializable

@Serializable
data class Msg(
    val role: String,
    val content: String,
    val responseId: String? = null,
    val promptTokens: Long? = null
)
