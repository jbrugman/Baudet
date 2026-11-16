package dev.baudet.mcp

import dev.baudet.api.json
import dev.baudet.api.obj
import dev.baudet.api.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A scripted MCP peer over an in-memory stdio pair, so the real [McpJsonRpc] framing and the real
 * [McpClient] handshake run end-to-end with no process ever launched. The client end is a genuine
 * [Transport]; this end is a daemon thread that reads each request line and answers it from [respond].
 *
 * [respond] maps a request to its result object, or to [Fail] to answer with an error, or to null to
 * stay silent -- which is the correct answer for a notification (no id, so nothing awaits it). [calls]
 * records the methods seen, in order, so a test can prove a handshake happened or a server was reused.
 *
 * [noise] is an optional list of raw lines pushed once, just before the first real reply, to prove the
 * client reader skips server-initiated notifications and foreign frames. Because the client registers
 * its pending request before it writes, pending is always present by the time this thread can reply.
 *
 * Everything crosses on separate threads (the client's reader thread parks, this thread answers), which
 * is the only safe use of the JDK piped streams and exactly how a real subprocess is wired.
 */
class McpTestServer(
    private val noise: List<String> = emptyList(),
    private val respond: (method: String, params: JsonObject?) -> JsonObject?
) {

    /** Thrown from [respond] to make the peer reply with a JSON-RPC error instead of a result. */
    class Fail(val code: Int, override val message: String) : RuntimeException(message)

    val calls = CopyOnWriteArrayList<String>()

    // client -> server (this end reads)
    private val serverIn = PipedInputStream(64 * 1024)
    private val clientWrite = PipedOutputStream(serverIn)
    // server -> client (this end writes)
    private val clientIn = PipedInputStream(64 * 1024)
    private val serverWrite = PipedOutputStream(clientIn)

    private val ready = CountDownLatch(1)

    @Volatile private var stopped = false
    @Volatile private var noiseSent = false

    init {
        Thread { loop() }.apply {
            isDaemon = true
            name = "mcp-test-server"
        }.start()
    }

    /** Block until the server loop is reading, so first writes never race the loop's start. */
    fun awaitReady(): Boolean = ready.await(5, TimeUnit.SECONDS)

    /** The client-facing transport: it reads this end's replies and writes requests to this end. */
    fun clientTransport(): Transport =
        Transport(InputStreamReader(clientIn), OutputStreamWriter(clientWrite))

    fun stop() {
        stopped = true
        runCatching { serverWrite.close() }
        runCatching { serverIn.close() }
    }

    private fun loop() {
        val br = BufferedReader(InputStreamReader(serverIn))
        val bw = BufferedWriter(OutputStreamWriter(serverWrite))
        ready.countDown()
        try {
            while (!stopped) {
                val line = br.readLine() ?: break
                val obj = runCatching { json.parseToJsonElement(line.trim()) as? JsonObject }.getOrNull() ?: continue
                val id = (obj["id"] as? JsonPrimitive)?.content?.toLongOrNull()
                val method = obj.str("method").orEmpty()
                calls.add(method)
                if (id == null) continue // a notification: no reply is expected
                if (!noiseSent && noise.isNotEmpty()) {
                    noiseSent = true
                    noise.forEach { bw.write(it.replace("\n", " ")); bw.write("\n") }
                    bw.flush()
                }
                val reply = try {
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id)
                        put("result", respond(method, obj.obj("params")) ?: buildJsonObject { })
                    }
                } catch (f: Fail) {
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id)
                        putJsonObject("error") {
                            put("code", f.code)
                            put("message", f.message)
                        }
                    }
                }
                bw.write(reply.toString().replace("\n", " "))
                bw.write("\n")
                bw.flush()
            }
        } catch (_: Exception) {
            // peer closed; the loop simply ends
        } finally {
            runCatching { bw.close() }
        }
    }
}

/**
 * A [TransportFactory] that hands each config its own [McpTestServer], so [McpServers] can be driven
 * through its reconcile rules with no process. One server is built per call (so a restart after a config
 * change starts a fresh one), and [servers] lets a test read the instances back -- to count handshakes,
 * to answer the next call differently, or to stop one.
 */
class FakeTransportFactory(
    private val canStart: (McpServerConfig) -> Boolean = { true },
    private val build: (McpServerConfig) -> (String, JsonObject?) -> JsonObject?
) : TransportFactory {
    val servers = CopyOnWriteArrayList<McpTestServer>()

    override fun invoke(config: McpServerConfig): Transport? {
        if (!canStart(config)) return null
        val server = McpTestServer(respond = build(config))
        servers.add(server)
        return server.clientTransport()
    }
}

// ---- canned MCP payloads ---------------------------------------------------
// Shared by the client, manager and adapter specs so each builds the same wire shapes a real server
// would emit: a tools/list result, and tools/call results whose content parts the adapter must map.

/** A `tools/list` result advertising one tool per name, each with a minimal input schema. */
fun mcpToolList(vararg names: String): JsonObject = buildJsonObject {
    put("tools", kotlinx.serialization.json.buildJsonArray {
        names.forEach { name ->
            add(buildJsonObject {
                put("name", name)
                put("description", "the `$name` tool")
                putJsonObject("inputSchema") { put("type", "object") }
            })
        }
    })
}

/** A `tools/call` result whose content is these text parts, in order. */
fun mcpTextResult(vararg parts: String): JsonObject = buildJsonObject {
    put("content", kotlinx.serialization.json.buildJsonArray {
        parts.forEach { text ->
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        }
    })
}

/** A `tools/call` result whose content is one image part, as a vision/image tool would emit. */
fun mcpImageResult(data: String, mimeType: String = "image/png"): JsonObject = buildJsonObject {
    put("content", kotlinx.serialization.json.buildJsonArray {
        add(buildJsonObject {
            put("type", "image")
            put("mimeType", mimeType)
            put("data", data)
        })
    })
}

/** A `tools/call` result carrying a text part followed by an image part. */
fun mcpTextThenImageResult(text: String, data: String, mimeType: String = "image/png"): JsonObject = buildJsonObject {
    put("content", kotlinx.serialization.json.buildJsonArray {
        add(buildJsonObject {
            put("type", "text")
            put("text", text)
        })
        add(buildJsonObject {
            put("type", "image")
            put("mimeType", mimeType)
            put("data", data)
        })
    })
}

/** A `tools/call` result flagged as a tool-level error (isError), with one text part. */
fun mcpErrorResult(text: String): JsonObject = buildJsonObject {
    put("isError", true)
    put("content", kotlinx.serialization.json.buildJsonArray {
        add(buildJsonObject {
            put("type", "text")
            put("text", text)
        })
    })
}
