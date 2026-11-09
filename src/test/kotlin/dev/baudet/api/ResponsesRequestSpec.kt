package dev.baudet.api

import dev.baudet.config.AppConfig
import dev.baudet.session.Msg
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The request body is the last stop before the wire. These pin two guarantees the fix
 * relies on: the current prompt is added exactly once from userText (so the caller must
 * hand history without it, not twice), and any system row carried in the replayed history
 * is dropped rather than sent as text -- which is why a compaction summary rides in the
 * instructions, not the history.
 */
class ResponsesRequestSpec : BehaviorSpec({
    val client = ResponsesClient(AppConfig())

    fun rolesOf(req: JsonObject): List<String> =
        (req["input"] as JsonArray).map { (it as JsonObject)["role"]!!.jsonPrimitive.content }

    fun contentsOf(req: JsonObject, role: String): List<String> =
        (req["input"] as JsonArray).map { it as JsonObject }
            .filter { it["role"]!!.jsonPrimitive.content == role }
            .map { it["content"]!!.jsonPrimitive.content }

    given("a call whose history ends without the current prompt") {
        `when`("building the request") {
            val req = client.buildRequest(
                system = "sys",
                history = listOf(Msg("user", "earlier")),
                userText = "current",
                previousResponseId = null,
                tools = null
            )
            then("the prompt appears once, as the final user turn") {
                contentsOf(req, "user") shouldBe listOf("earlier", "current")
            }
        }
    }

    given("a system row carried inside the replayed history") {
        `when`("building the request") {
            val req = client.buildRequest(
                system = "sys",
                history = listOf(Msg("system", "summary"), Msg("user", "earlier")),
                userText = "current",
                previousResponseId = null,
                tools = null
            )
            then("the system row is dropped, never replayed as user text") {
                rolesOf(req) shouldBe listOf("user", "user")
            }
        }
    }

    given("a server-side session id is present") {
        `when`("building the request") {
            val req = client.buildRequest(
                system = "sys",
                history = listOf(Msg("user", "older"), Msg("user", "earlier")),
                userText = "current",
                previousResponseId = "resp-1",
                tools = null
            )
            then("only the current prompt goes on the wire") {
                rolesOf(req) shouldBe listOf("user")
            }
        }
    }
})
