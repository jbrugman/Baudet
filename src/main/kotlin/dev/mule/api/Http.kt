package dev.mule.api

import dev.mule.config.AppConfig
import dev.mule.i18n.Key
import dev.mule.i18n.Texts
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.stream.Collectors
import kotlin.streams.asSequence

class BackendException(status: Int, message: String) :
    RuntimeException(Texts.fmt(Key.httpFromBackend, status, message))

/**
 * One SSE event: the optional event: field plus the joined data payload.
 * The event: field is kept on purpose. Some backends carry the event name only
 * there and repeat nothing of it inside data, so dropping it loses the name and
 * no delta branch can match anymore.
 */
class SseEvent(val event: String?, val data: String)

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

/**
 * java.net.http over Ktor/OkHttp: built into the JDK, no reflection, so it is
 * the cheapest option for a later native-image build. HTTP/1.1 is forced
 * because SSE framing on local uvicorn backends is most reliable there.
 */
class Http(private val cfg: AppConfig) {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    private val base: String = cfg.baseUrl.trim().trimEnd('/')

    /** Upper bound for one exchange: 30 min, a large local generation can take that. */
    private val exchangeTimeout: Duration = Duration.ofMinutes(30)

    private fun request(path: String, body: String?, accept: String): HttpRequest {
        val b = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(exchangeTimeout)
            .header("Authorization", "Bearer " + cfg.resolvedApiKey())
            .header("Accept", accept)
        if (body == null) {
            b.GET()
        } else {
            b.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        }
        return b.build()
    }

    fun get(path: String): Pair<Int, String> {
        val resp = client.send(
            request(path, null, "application/json"),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        )
        return resp.statusCode() to resp.body()
    }

    /** Yields the events of an SSE stream, lazily, in order. */
    fun sse(path: String, body: String): Sequence<SseEvent> = sequence {
        val resp = client.send(
            request(path, body, "text/event-stream"),
            HttpResponse.BodyHandlers.ofLines()
        )
        if (resp.statusCode() >= 400) {
            val err = resp.body().collect(Collectors.joining("\n"))
            throw BackendException(resp.statusCode(), err.ifBlank { "empty body" })
        }
        for (ev in SseFramer.frame(resp.body().asSequence())) yield(ev)
    }
}
