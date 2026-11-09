package dev.baudet.tool

/**
 * One exact search-and-replace inside a file. Exact text is chosen over line
 * numbers because a local model counts lines poorly and drifts on every edit:
 * the match either lands or it does not, which is trivially verifiable, and the
 * caller gets an exact reason when it does not. [oldText] must appear verbatim;
 * [replaceAll] only when the caller means to change every occurrence.
 */
data class Edit(
    val oldText: String,
    val newText: String,
    val replaceAll: Boolean = false
)
