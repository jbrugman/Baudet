package dev.mule.store

/** The read shape the sidebar renders under a project: enough to label and order it. */
data class SessionSummary(
    val id: String,
    val title: String,
    val messageCount: Int,
    val updatedAt: Long
)
