package dev.baudet.mcp

import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * How a transport is built from one server's config. Returning null means it could not be established.
 * Production uses [startStdioTransport] (a real subprocess); a test hands in a factory that wires an
 * in-memory pipe pair to a scripted responder, so the client and the manager run end-to-end with no
 * process ever launched.
 */
typealias TransportFactory = (config: McpServerConfig) -> Transport?

/**
 * Launch [config.command] [config.args] as a subprocess and use its stdin/stdout as the transport.
 * stderr stays separate so a misbehaving server's complaints reach the app log instead of jamming a
 * full pipe; the child's environment is the parent's plus [config.env]. Native-image safe: only
 * ProcessBuilder and pipes, no reflection. Closing the transport destroys the child.
 */
fun startStdioTransport(config: McpServerConfig): Transport? = try {
    val pb = ProcessBuilder(buildList { add(config.command); addAll(config.args) })
    pb.environment().putAll(config.env)
    pb.redirectErrorStream(false)
    val process = pb.start()
    val reader = InputStreamReader(process.inputStream, StandardCharsets.UTF_8)
    val writer = OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8)
    object : Transport(reader, writer) {
        override fun close() {
            super.close()
            runCatching { if (process.isAlive) process.destroy() }
            runCatching { if (process.isAlive) process.destroyForcibly() }
        }
    }
} catch (e: Exception) {
    null
}
