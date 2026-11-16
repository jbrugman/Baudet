package dev.baudet.mcp

import dev.baudet.tool.Tool

/**
 * Owns the live MCP clients for the servers the user opted into, and hands the loop a flat list of
 * [Tool]s to register alongside the built-ins. This is the only place a server is launched, kept alive,
 * restarted, or torn down -- so the lifecycle is in one head, not scattered across the UI and the loop.
 *
 * It is *lazy per launch* (design choice C): a client connects the first time a turn actually builds
 * its tools, not at app start, and the process stays up across turns so a burst of calls reuses it. It
 * reconciles against the current config on every call: a server whose line was removed or edited is
 * closed (and its successor started fresh), so editing a command in Settings takes effect next turn.
 * A server that will not start is simply absent from this turn's tools -- the model never sees a tool
 * it cannot call, and one failed server never breaks the others or the turn.
 */
class McpServers(
    private val transportFactory: TransportFactory = ::startStdioTransport
) {
    private class Holder(var config: McpServerConfig, var client: McpClient?, var defs: List<McpToolDef>?)

    private val holders = LinkedHashMap<String, Holder>()

    /**
     * The tools for the servers in [configs], in config order. Side effects: a changed/removed server is
     * torn down, a new/changed one is connected, a failed one is skipped. Safe to call every turn.
     */
    fun tools(configs: List<McpServerConfig>): List<Tool> {
        val enabled = configs.filter { it.enabled }
        val ids = enabled.map { it.id }.toSet()
        // Drop servers that are gone or switched off, so a stale process cannot outlive its config.
        holders.keys.filter { it !in ids }.forEach { id -> holders.remove(id)?.client?.close() }

        val out = ArrayList<Tool>()
        for (cfg in enabled) {
            val holder = holders.getOrPut(cfg.id) { Holder(cfg, null, null) }
            if (holder.config != cfg) {
                holder.client?.close()
                holder.client = null
                holder.defs = null
                holder.config = cfg
            }
            if (holder.client == null) {
                val client = McpClient(cfg, transportFactory)
                if (client.start()) holder.client = client else client.close()
            }
            val client = holder.client ?: continue
            if (holder.defs == null) {
                holder.defs = runCatching { client.listTools() }.getOrElse {
                    // The server was up for the handshake but its listing failed: drop it this turn.
                    holder.client = null
                    client.close()
                    continue
                }
            }
            for (def in holder.defs!!) out.add(McpToolAdapter(cfg.id, def, client))
        }
        return out
    }

    /** The ids of servers that are connected right now -- for the Settings panel to report on. */
    fun connected(): Set<String> = holders.entries.filter { it.value.client != null }.map { it.key }.toSet()

    /** Tear every client (and its process) down. Called once as the app closes. */
    fun closeAll() {
        holders.values.forEach { it.client?.close() }
        holders.clear()
    }
}
