package dev.mule.api

/**
 * One SSE event: the optional event: field plus the joined data payload.
 * The event: field is kept on purpose. Some backends carry the event name only
 * there and repeat nothing of it inside data, so dropping it loses the name and
 * no delta branch can match anymore.
 */
class SseEvent(val event: String?, val data: String)
