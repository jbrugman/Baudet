package dev.baudet.mcp

import dev.baudet.tool.ToolContext
import dev.baudet.tool.ToolImage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The adapter is the safety valve and the result translator for one remote tool. It must namespace the
 * name so a remote tool cannot shadow a built-in, refuse to touch the peer before a human or a standing
 * grant lets it through, and turn an MCP content payload into exactly what the model should read --
 * including an error flag and parts it cannot show. These pin all three, with no real server.
 */
class McpToolAdapterSpec : BehaviorSpec({

    val def = McpToolDef("do", "the doer", buildJsonObject { put("type", "object") })

    fun ctx(allow: Set<String>, approve: (String, String, String) -> Boolean) =
        ToolContext(null, writeGranted = false, approve = { _, _ -> true },
            mcpAllowServers = { allow }, approveMcp = approve)

    /** Build an adapter over a connected client whose tools/call answers with [callResult]. */
    fun wired(callResult: JsonObject): Triple<McpToolAdapter, McpTestServer, McpClient> {
        val srv = McpTestServer { method, _ -> if (method == "tools/call") callResult else null }
        srv.awaitReady() shouldBe true
        val client = McpClient(McpServerConfig(id = "srv", command = "noop")) { srv.clientTransport() }
        client.start()
        return Triple(McpToolAdapter("srv", def, client), srv, client)
    }

    given("names with characters a function-call name cannot carry") {
        `when`("an adapter is named") {
            val srv = McpTestServer { _, _ -> null }
            val client = McpClient(McpServerConfig("srv", "noop")) { srv.clientTransport() }
            val spaced = McpToolAdapter("my server", McpToolDef("do.it", "", def.inputSchema), client)
            then("each segment collapses to the safe charset under the mcp__ namespace") {
                spaced.name shouldBe "mcp__my_server__do_it"
            }
            then("an empty segment still yields a usable name") {
                McpToolAdapter.sanitize("") shouldBe "tool"
            }
            client.close(); srv.stop()
        }
    }

    given("a human who declines the call") {
        val (adapter, srv, client) = wired(mcpTextResult("secret"))
        `when`("it executes") {
            val r = adapter.execute(buildJsonObject { }, ctx(allow = emptySet(), approve = { _, _, _ -> false }))
            then("it refuses and never reaches the peer") {
                r.ok shouldBe false
                r.output shouldContain "declined"
                srv.calls.contains("tools/call") shouldBe false
            }
        }
        client.close(); srv.stop()
    }

    given("a server under a standing grant") {
        val (adapter, srv, client) = wired(mcpTextResult("ok-output"))
        var asked = 0
        `when`("it executes") {
            val r = adapter.execute(buildJsonObject { },
                ctx(allow = setOf("srv"), approve = { _, _, _ -> asked++; false }))
            then("it runs without asking the human, mapping the text part") {
                r.ok shouldBe true
                r.output shouldBe "ok-output"
                asked shouldBe 0
                srv.calls.contains("tools/call") shouldBe true
            }
        }
        client.close(); srv.stop()
    }

    given("a one-off human approval") {
        val (adapter, srv, client) = wired(mcpTextResult("ran"))
        var asked = 0
        `when`("it executes") {
            val r = adapter.execute(buildJsonObject { },
                ctx(allow = emptySet(), approve = { _, _, _ -> asked++; true }))
            then("the human is asked once and the call goes through") {
                r.ok shouldBe true
                r.output shouldBe "ran"
                asked shouldBe 1
            }
        }
        client.close(); srv.stop()
    }

    given("a tool result with several text parts") {
        val (adapter, _, client) = wired(mcpTextResult("one", "two"))
        `when`("it executes") {
            val r = adapter.execute(buildJsonObject { }, ctx(setOf("srv")) { _, _, _ -> true })
            then("the text parts join in order") {
                r.output shouldBe "one\ntwo"
            }
        }
        client.close()
    }

    given("a tool result flagged as an error") {
        val (adapter, _, client) = wired(mcpErrorResult("bad thing"))
        `when`("it executes") {
            val r = adapter.execute(buildJsonObject { }, ctx(setOf("srv")) { _, _, _ -> true })
            then("ok is false and the error text is still surfaced") {
                r.ok shouldBe false
                r.output shouldBe "bad thing"
            }
        }
        client.close()
    }

    given("a result carrying a part the model cannot read at all") {
        val mixed = buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "see the resource") })
                add(buildJsonObject { put("type", "resource"); put("resource", "...") })
            })
        }
        val (adapter, _, client) = wired(mixed)
        `when`("it executes") {
            val r = adapter.execute(buildJsonObject { }, ctx(setOf("srv")) { _, _, _ -> true })
            then("the text is kept and the unreadable part is counted, never dropped silently") {
                r.output shouldContain "see the resource"
                r.output shouldContain "1 non-text part(s) not shown here"
                r.images shouldBe emptyList()
            }
        }
        client.close()
    }

    given("a result carrying only an image part") {
        val (adapter, _, client) = wired(mcpImageResult("aGVsbG8=", mimeType = "image/png"))
        `when`("it executes") {
            val r = adapter.execute(buildJsonObject { }, ctx(setOf("srv")) { _, _, _ -> true })
            then("the bytes go to images with their mime type, and never into the model-facing output") {
                r.ok shouldBe true
                r.images shouldBe listOf(ToolImage("image/png", "aGVsbG8="))
                r.output shouldNotContain "aGVsbG8="
                r.output shouldContain "1 image(s) returned"
            }
        }
        client.close()
    }

    given("a result carrying a text part and an image part") {
        val (adapter, _, client) = wired(mcpTextThenImageResult("here it is", "aGk="))
        `when`("it executes") {
            val r = adapter.execute(buildJsonObject { }, ctx(setOf("srv")) { _, _, _ -> true })
            then("the text reads to the model, the image rides along in images only") {
                r.ok shouldBe true
                r.output shouldContain "here it is"
                r.output shouldContain "1 image(s) returned"
                r.output shouldNotContain "aGk="
                r.images shouldBe listOf(ToolImage("image/png", "aGk="))
            }
        }
        client.close()
    }
})
