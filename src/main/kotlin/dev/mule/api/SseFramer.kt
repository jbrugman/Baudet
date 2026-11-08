package dev.mule.api

/**
 * SSE framing per the spec: a blank line ends an event, consecutive data: lines
 * join with a newline, one optional leading space after the colon is stripped, a
 * colon starts a comment, and every other field (id, retry) is ignored.
 * Framing on the blank line rather than per data line matters: a payload that
 * spans several lines would otherwise read as several events and parse as
 * broken JSON. Pure over a line sequence, so it is unit-testable.
 */
object SseFramer {

    fun frame(lines: Sequence<String>): Sequence<SseEvent> = sequence {
        var event: String? = null
        val data = StringBuilder()
        var haveData = false

        for (line in lines) {
            when {
                line.isEmpty() -> {
                    if (isReal(data, haveData)) yield(SseEvent(event, data.toString()))
                    event = null
                    data.setLength(0)
                    haveData = false
                }
                // comment or keep-alive
                line.startsWith(":") -> Unit
                line.startsWith("data:") -> {
                    if (haveData) data.append('\n')
                    data.append(stripField(line.substring(5)))
                    haveData = true
                }
                line.startsWith("event:") -> event = stripField(line.substring(6))
                // id, retry, unknown field
                else -> Unit
            }
        }
        // A stream may end without a trailing blank line; that last event is real.
        if (isReal(data, haveData)) yield(SseEvent(event, data.toString()))
    }

    private fun stripField(v: String): String = if (v.startsWith(" ")) v.substring(1) else v

    private fun isReal(data: StringBuilder, haveData: Boolean): Boolean =
        haveData && data.isNotEmpty() && data.toString() != "[DONE]"
}
