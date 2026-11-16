package dev.baudet.mcp

import dev.baudet.api.json
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.StringReader

/**
 * The framing layer is the single place bytes become JSON-RPC messages, so a bug here corrupts every
 * call: a reply handed to the wrong caller, a peer error swallowed into a blank success, or a half
 * frame that hangs the turn forever. These drive the real reader thread and writer against an
 * in-memory peer to pin the round trip, error propagation, notifications, foreign-frame skipping, and
 * the newline folding that keeps a pretty-printed value from splitting a frame.
 */
class McpJsonRpcSpec : BehaviorSpec({

    fun client(srv: McpTestServer): McpJsonRpc {
        srv.awaitReady() shouldBe true
        return McpJsonRpc(srv.clientTransport())
    }

    given("a live JSON-RPC peer") {
        val srv = McpTestServer { method, _ ->
            when (method) {
                "echo" -> buildJsonObject { put("value", "x") }
                "boom" -> throw McpTestServer.Fail(42, "kaboom")
                else -> null
            }
        }
        val rpc = client(srv)
        `when`("a request is sent") {
            val result = rpc.request("echo")
            then("the peer's result object comes back intact") {
                (result["value"]?.toString()) shouldBe "\"x\""
            }
            then("the method actually reached the peer") {
                srv.calls.contains("echo") shouldBe true
            }
        }
        `when`("the peer answers with an error") {
            val e = shouldThrow<McpRpcException> { rpc.request("boom") }
            then("the code and message surface, never a blank success") {
                e.code shouldBe 42
                e.message shouldBe "kaboom"
            }
        }
        `when`("a notification is sent") {
            then("it returns without awaiting a reply") {
                rpc.notify("notifications/cancelled") // no reply expected; must not block or throw
                // and a following request still round-trips, proving the writer stayed healthy
                (rpc.request("echo")["value"]?.toString()) shouldBe "\"x\""
            }
        }
        rpc.close(); srv.stop()
    }

    given("a peer that first pushes foreign frames") {
        // A blank line, a server-initiated notification (no id) and a string-id frame all precede the
        // one real reply; only the matching numeric id may complete the pending request.
        val noise = listOf(
            "",
            """{"jsonrpc":"2.0","method":"notifications/progress","params":{}}""",
            """{"jsonrpc":"2.0","id":"not-a-number","result":{"trap":true}}"""
        )
        val srv = McpTestServer(
            respond = { _, _ -> buildJsonObject { put("ok", true) } },
            noise = noise
        )
        val rpc = client(srv)
        `when`("the one true reply finally lands") {
            val result = rpc.request("probe")
            then("the noise is skipped and the correct reply completes it") {
                (result["ok"]?.toString()) shouldBe "true"
            }
        }
        rpc.close(); srv.stop()
    }

    given("a writer asked to emit a value containing newlines") {
        val inPipe = PipedInputStream()
        val outPipe = PipedOutputStream(inPipe)
        val t = Transport(StringReader(""), OutputStreamWriter(outPipe))
        val serverSide = BufferedReader(InputStreamReader(inPipe))
        `when`("the line is written") {
            t.writeLine("{\n  \"a\": 1 }")
            then("it reaches the peer as exactly one folded line") {
                serverSide.readLine() shouldBe "{   \"a\": 1 }"
            }
        }
        t.close()
    }

    given("a peer that never answers and never closes") {
        // The reader blocks forever -- a server that accepts a call and hangs -- so only the per-call
        // ceiling can free the turn. A reader parked on a gate models that without raw-pipe cross-thread
        // quirks; close() opens the gate so the parked reader wakes, throws and exits instead of lingering,
        // and it skips the base pipe close because BufferedReader still holds its read lock during read.
        val gate = java.util.concurrent.CountDownLatch(1)
        val peer = object : java.io.Reader() {
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                gate.await()
                throw java.io.EOFException("blocked peer")
            }
            override fun close() = gate.countDown()
        }
        val t = object : Transport(peer, java.io.StringWriter()) {
            override fun close() { gate.countDown() } // in-memory transport: waking the reader is all it takes
        }
        val rpc = McpJsonRpc(t, callTimeoutSeconds = 1)
        `when`("a request is sent") {
            val e = shouldThrow<McpRpcException> { rpc.request("probe") }
            then("it fails on the configured ceiling rather than blocking forever") {
                e.message shouldBe "no response to `probe` within 1s"
            }
        }
        rpc.close()
    }
})
