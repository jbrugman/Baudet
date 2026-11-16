package dev.baudet.mcp

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * The manager is the only place a server is launched, kept, restarted or torn down, and it runs every
 * turn. These pin its reconcile contract: a server connects once and is reused, an edited config restarts
 * it, a removed one is closed, a server that will not start contributes nothing, and a disabled one is
 * never connected -- all without a real process.
 */
class McpServersSpec : BehaviorSpec({

    fun lister(): (String, kotlinx.serialization.json.JsonObject?) -> kotlinx.serialization.json.JsonObject? =
        { method, _ -> if (method == "tools/list") mcpToolList("t") else null }

    val srv1 = McpServerConfig(id = "srv1", command = "prog-a")

    given("one enabled server") {
        val factory = FakeTransportFactory { lister() }
        val mgr = McpServers(factory)
        `when`("tools are built for the first time") {
            val tools = mgr.tools(listOf(srv1))
            then("one namespaced adapter is returned and the server is connected") {
                tools.map { it.name } shouldContainExactly listOf("mcp__srv1__t")
                mgr.connected() shouldBe setOf("srv1")
                factory.servers.size shouldBe 1
            }
            then("listing reused the live client without a second handshake") {
                mgr.tools(listOf(srv1))
                factory.servers.size shouldBe 1
                factory.servers[0].calls.count { it == "initialize" } shouldBe 1
            }
        }
        mgr.closeAll()
    }

    given("a server whose command is edited") {
        val factory = FakeTransportFactory { lister() }
        val mgr = McpServers(factory)
        `when`("a config with the same id but a different command appears") {
            mgr.tools(listOf(srv1))
            val tools = mgr.tools(listOf(srv1.copy(command = "prog-b")))
            then("the old client is replaced by a fresh handshake") {
                factory.servers.size shouldBe 2
                mgr.connected() shouldBe setOf("srv1")
                tools.map { it.name } shouldContainExactly listOf("mcp__srv1__t")
            }
        }
        mgr.closeAll()
    }

    given("a server that was connected and is then removed") {
        val factory = FakeTransportFactory { lister() }
        val mgr = McpServers(factory)
        `when`("a later turn offers no configs") {
            mgr.tools(listOf(srv1))
            val tools = mgr.tools(emptyList())
            then("it is torn down and reports nothing") {
                tools.shouldBeEmpty()
                mgr.connected().shouldBeEmpty()
            }
        }
        mgr.closeAll()
    }

    given("a server that will not launch") {
        val factory = FakeTransportFactory(build = { lister() }, canStart = { false })
        val mgr = McpServers(factory)
        `when`("tools are built") {
            val tools = mgr.tools(listOf(srv1))
            then("it is simply absent, with no connected server") {
                tools.shouldBeEmpty()
                mgr.connected().shouldBeEmpty()
            }
        }
        mgr.closeAll()
    }

    given("a disabled server") {
        val factory = FakeTransportFactory { lister() }
        val mgr = McpServers(factory)
        `when`("tools are built") {
            val tools = mgr.tools(listOf(srv1.copy(enabled = false)))
            then("it is skipped before anything is launched") {
                tools.shouldBeEmpty()
                factory.servers.size shouldBe 0
                mgr.connected().shouldBeEmpty()
            }
        }
        mgr.closeAll()
    }
})
