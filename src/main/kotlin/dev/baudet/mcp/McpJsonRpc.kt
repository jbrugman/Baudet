package dev.baudet.mcp

import dev.baudet.api.json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.Reader
import java.io.Writer

/**
 * One line-delimited JSON-RPC conversation, transport-agnostic on purpose. The stdio transport is a
 * reader and a writer over pipes; the protocol never touches a process, so the same framing runs over
 * a pair of in-memory pipes in a test and over a real subprocess in production.
 *
 * MCP frames each message as a single line of JSON (no embedded newlines), so a line is a message.
 * The writer locks so a request from the worker thread and a notification from elsewhere cannot
 * interleave half-written bytes onto one line; the reader runs on its own thread and routes each
 * reply back to the [request] call waiting on its id, ignoring server-initiated notifications.
 */
open class Transport(val reader: Reader, val writer: Writer) {
    private val br = BufferedReader(reader)
    private val bw = BufferedWriter(writer)
    private val writeLock = Any()

    /** One whole message, newline-terminated and flushed; any newlines inside the JSON are folded
     *  out so a pretty-printed value can never split a frame. */
    fun writeLine(line: String) = synchronized(writeLock) {
        bw.write(line.replace("\n", " ").replace("\r", " "))
        bw.write("\n")
        bw.flush()
    }

    /** The next whole line, or null once the peer has closed. */
    fun readLine(): String? = br.readLine()

    open fun close() {
        runCatching { bw.close() }
        runCatching { br.close() }
    }
}

/** A JSON-RPC `error` object the peer returned; its message is shown straight to the model. */
class McpRpcException(val code: Int, message: String) : Exception(message)

/**
 * A single in-flight request: its id is how the reader thread finds the call to hand the reply back
 * to, and its latch is what the worker parks on until the reply (or a close) releases it.
 */
private class Pending {
    val latch = java.util.concurrent.CountDownLatch(1)
    var result: JsonObject? = null
    var error: McpRpcException? = null
}

/**
 * Lines the [Transport] may not carry: an empty line is tolerated, a non-object or one with a string
 * id is silently skipped rather than completing some unrelated request.
 */
class McpJsonRpc(
    private val transport: Transport,
    /** How long one request may wait for its reply before the call fails; see [CALL_TIMEOUT_SECONDS]. */
    private val callTimeoutSeconds: Long = CALL_TIMEOUT_SECONDS
) {

    @Volatile private var running = true
    private var nextId = 0L
    private val pending = java.util.concurrent.ConcurrentHashMap<Long, Pending>()
    private val readerThread = Thread { readLoop() }.apply {
        isDaemon = true
        name = "mcp-jsonrpc-reader"
    }.also { it.start() }

    /**
     * Send one request and block for its result. A transport failure, a peer `error`, or a close while
     * waiting all surface as [McpRpcException] -- never a half-value -- so the caller's only success
     * path is a well-formed `result` object.
     */
    fun request(method: String, params: JsonObject? = null): JsonObject {
        val id = synchronized(this) { ++nextId }
        val entry = Pending()
        pending[id] = entry
        try {
            val paramsText = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                if (params != null) put("params", params)
            }
            transport.writeLine(paramsText.toString())
            if (!entry.latch.await(callTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                throw McpRpcException(-32000, "no response to `$method` within ${callTimeoutSeconds}s")
            }
            entry.error?.let { throw it }
            return entry.result ?: throw McpRpcException(-32000, "empty response to `$method`")
        } finally {
            pending.remove(id)
        }
    }

    /** A fire-and-forget message: written, with no reply awaited. */
    fun notify(method: String, params: JsonObject? = null) {
        val obj = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }
        runCatching { transport.writeLine(obj.toString()) }
    }

    private fun readLoop() {
        try {
            while (running) {
                val line = transport.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val obj = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: continue
                val id = (obj["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: continue
                val entry = pending[id] ?: continue
                val err = obj["error"] as? JsonObject
                if (err != null) {
                    entry.error = McpRpcException(
                        (err["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1,
                        (err["message"] as? JsonPrimitive)?.content ?: "rpc error"
                    )
                } else {
                    entry.result = obj["result"] as? JsonObject ?: buildJsonObject { }
                }
                entry.latch.countDown()
            }
        } catch (_: IOException) {
            // peer closed: fall through and fail everyone still waiting
        } finally {
            failAll("the MCP server closed the connection")
        }
    }

    private fun failAll(reason: String) {
        running = false
        pending.values.forEach {
            if (it.error == null && it.result == null) it.error = McpRpcException(-32000, reason)
            it.latch.countDown()
        }
    }

    fun close() {
        running = false
        runCatching { transport.close() }
    }

    companion object {
        /** A call may outlive a fast command (a remote build or fetch), but a stuck server must not
         *  park the turn forever; this is the default ceiling on one round trip. A server whose
         *  [McpServerConfig.timeoutSeconds] raises it does so per line, because a local image model can
         *  hold a single call open for minutes. */
        const val CALL_TIMEOUT_SECONDS = 120L
    }
}
