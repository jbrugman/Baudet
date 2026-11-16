package dev.baudet.mcp

import dev.baudet.api.arr
import dev.baudet.api.json
import dev.baudet.api.obj
import dev.baudet.api.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** One tool a server advertised, already reduced to the three fields Baudet acts on. */
data class McpToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

/** The MCP wire protocol version Baudet declares. The only one every current server negotiates. */
private const val PROTOCOL_VERSION = "2024-11-05"

/**
 * The MCP client for one server: it owns the transport, runs the handshake, and turns `tools/list` and
 * `tools/call` into typed calls. It is deliberately a thin transport -- the safety of *whether* a tool
 * may run lives in [McpToolAdapter] (which asks the human), not here; this class only speaks the wire.
 *
 * Every method is blocking, which is exactly what the synchronous [dev.baudet.tool.Tool.execute] and the
 * stdio round trip want; nothing here is on the event thread, because the tool loop runs off it.
 */
class McpClient(
    private val config: McpServerConfig,
    private val transportFactory: TransportFactory = ::startStdioTransport
) {
    @Volatile private var rpc: McpJsonRpc? = null
    private var transport: Transport? = null

    /** Why the last start failed, so a failed server shows a real reason to the model rather than a blank. */
    var lastError: String? = null
        private set

    /**
     * Establish the transport and complete the MCP handshake. Idempotent: a second call on a live
     * client is a no-op. Returns false (and records [lastError]) if the server never came up, so the
     * manager can skip it this turn instead of offering tools that cannot run.
     */
    fun start(): Boolean {
        if (rpc != null) return true
        val t = try { transportFactory(config) } catch (e: Exception) { lastError = e.message; null }
        if (t == null) {
            if (lastError == null) lastError = "could not launch `${config.command}`"
            return false
        }
        transport = t
        val client = McpJsonRpc(t)
        try {
            client.request(
                "initialize",
                buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    putJsonObject("capabilities") { }
                    putJsonObject("clientInfo") {
                        put("name", "Baudet")
                        put("version", "1")
                    }
                }
            )
            client.notify("notifications/initialized")
            rpc = client
            lastError = null
            return true
        } catch (e: McpRpcException) {
            lastError = e.message
            runCatching { client.close() }
            runCatching { t.close() }
            transport = null
            return false
        }
    }

    /** The tools the server exposes. Throws [McpRpcException] if the live call fails; the manager catches. */
    fun listTools(): List<McpToolDef> {
        val result = live().request("tools/list")
        val tools = result.arr("tools") ?: JsonArray(emptyList())
        return tools.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.str("name") ?: return@mapNotNull null
            McpToolDef(
                name = name,
                description = obj.str("description").orEmpty(),
                inputSchema = obj.obj("inputSchema") ?: buildJsonObject { put("type", "object") }
            )
        }
    }

    /** Invoke one tool by its bare server-side name; the raw `result` object comes back for the adapter to map. */
    fun callTool(name: String, arguments: JsonObject): JsonObject =
        live().request(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            }
        )

    /** Tear down the transport and the process it owns. Safe to call more than once or before a start. */
    fun close() {
        runCatching { rpc?.close() }
        runCatching { transport?.close() }
        rpc = null
        transport = null
    }

    private fun live(): McpJsonRpc =
        rpc ?: throw McpRpcException(-32000, "the MCP server `${config.id}` is not connected")
}
