package dev.baudet.mcp

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The client owns the MCP handshake and turns list/call into typed calls. These pin the guarantees the
 * manager and adapter rely on: the exact initialize -> initialized -> tools/list order, that a tools
 * list survives parsing, that a failed start reports a real reason instead of a silent nothing, and
 * that a start never handshakes twice.
 */
class McpClientSpec : BehaviorSpec({

    val cfg = McpServerConfig(id = "s", command = "noop")

    given("a server that advertises two tools") {
        val srv = McpTestServer { method, _ ->
            when (method) {
                "tools/list" -> mcpToolList("alpha", "beta")
                else -> null
            }
        }
        srv.awaitReady() shouldBe true
        val client = McpClient(cfg) { srv.clientTransport() }
        `when`("it starts and lists") {
            val started = client.start()
            val defs = client.listTools()
            then("start succeeds") { started shouldBe true }
            then("both tools come back with their names and a schema") {
                defs.map { it.name } shouldBe listOf("alpha", "beta")
                defs.first().description shouldBe "the `alpha` tool"
                defs.first().inputSchema["type"].shouldNotBeNull()
            }
            then("the handshake ran before the listing") {
                srv.calls.take(3) shouldBe listOf("initialize", "notifications/initialized", "tools/list")
            }
        }
        client.close(); srv.stop()
    }

    given("a server that answers a tool call") {
        val srv = McpTestServer { method, _ ->
            when (method) {
                "tools/call" -> mcpTextResult("hello")
                else -> null
            }
        }
        srv.awaitReady() shouldBe true
        val client = McpClient(cfg) { srv.clientTransport() }
        client.start()
        `when`("calling a tool") {
            val result = client.callTool("alpha", buildJsonObject { put("k", "v") })
            then("the raw result object is handed back untouched") {
                result["content"].shouldNotBeNull()
            }
        }
        client.close(); srv.stop()
    }

    given("a client already connected") {
        val srv = McpTestServer { _, _ -> null }
        srv.awaitReady() shouldBe true
        val client = McpClient(cfg) { srv.clientTransport() }
        `when`("start is called twice") {
            client.start()
            val again = client.start()
            then("the second start is a no-op that does not re-handshake") {
                again shouldBe true
                srv.calls.count { it == "initialize" } shouldBe 1
            }
        }
        client.close(); srv.stop()
    }

    given("a server that cannot be launched") {
        val client = McpClient(cfg) { null }
        `when`("starting") {
            val started = client.start()
            then("it reports failure with a reason naming the command") {
                started shouldBe false
                client.lastError.shouldNotBeNull()
                (client.lastError!!.contains("noop")) shouldBe true
            }
        }
        client.close()
    }

    given("a server that rejects the handshake") {
        val srv = McpTestServer { method, _ ->
            if (method == "initialize") throw McpTestServer.Fail(-32600, "refused") else null
        }
        srv.awaitReady() shouldBe true
        val client = McpClient(cfg) { srv.clientTransport() }
        `when`("starting") {
            val started = client.start()
            then("the handshake error becomes the recorded reason") {
                started shouldBe false
                client.lastError shouldBe "refused"
            }
        }
        client.close(); srv.stop()
    }
})
