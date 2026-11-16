package dev.baudet.mcp

import dev.baudet.api.json
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * A user writes one JSON line per MCP server in Settings, so the config must parse leniently: an old
 * line that predates the timeout field still gets the protocol default, and a longer timeout written
 * for a slow local image server round-trips so it survives a save and reload of the line.
 */
class McpServerConfigSpec : BehaviorSpec({
    val default = McpJsonRpc.CALL_TIMEOUT_SECONDS

    given("a config line with no timeout field") {
        val cfg = json.decodeFromString(
            McpServerConfig.serializer(),
            """{"id":"a","command":"p"}"""
        )
        `when`("it is parsed") {
            then("the protocol default applies and the line stays valid") {
                cfg.timeoutSeconds shouldBe default
                cfg.enabled shouldBe true
            }
        }
    }

    given("a config line that raises the timeout") {
        val cfg = json.decodeFromString(
            McpServerConfig.serializer(),
            """{"id":"img","command":"p","timeoutSeconds":900,"enabled":true}"""
        )
        `when`("it is written back out and parsed again") {
            val text = json.encodeToString(McpServerConfig.serializer(), cfg)
            val again = json.decodeFromString(McpServerConfig.serializer(), text)
            then("the longer ceiling survives the round trip") {
                cfg.timeoutSeconds shouldBe 900L
                again.timeoutSeconds shouldBe 900L
            }
        }
    }
})
