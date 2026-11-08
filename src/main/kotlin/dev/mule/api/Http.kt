package dev.mule.api

import dev.mule.config.AppConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.stream.Collectors
import kotlin.streams.asSequence

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
