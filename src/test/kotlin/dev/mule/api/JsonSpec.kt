package dev.mule.api

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The whole API layer reads back-end payloads through these helpers, because local
 * servers deviate from the documented shape. The behaviour worth locking is the
 * tolerance itself: absent keys, JSON null (a JsonPrimitive singleton that prim()
 * rejects on purpose), and a number arriving where text or a number is expected.
 */
class JsonSpec : BehaviorSpec({

    fun parse(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    given("an object reader over a tolerant payload") {
        val o = parse("""{"id":"resp_1","absent":null,"count":"42","flag":7}""")
        `when`("reading string fields") {
            then("a present string returns its content") { o.str("id") shouldBe "resp_1" }
            then("an absent key yields null rather than throwing") { o.str("missing") shouldBe null }
            then("a JSON null reads as absent, not as the text null") { o.str("absent") shouldBe null }
            then("a number is read back as its text") { o.str("flag") shouldBe "7" }
        }
        `when`("reading a numeric field") {
            then("a quoted integer still parses") { o.long("count") shouldBe 42L }
            then("a bare number parses") { o.long("flag") shouldBe 7L }
            then("an absent or null numeric field yields null") {
                o.long("absent") shouldBe null
                o.long("missing") shouldBe null
            }
        }
    }

    given("nested shapes that are not always what they claim") {
        val o = parse("""{"meta":{"a":"x"},"list":[1,2],"scalar":"just a string"}""")
        `when`("reading objects and arrays") {
            then("obj returns a real object and reaches into it") { o.obj("meta")?.str("a") shouldBe "x" }
            then("obj yields null for a scalar") { o.obj("scalar") shouldBe null }
            then("obj yields null for an array") { o.obj("list") shouldBe null }
            then("arr returns a real array") { o.arr("list")?.size shouldBe 2 }
            then("arr yields null for an object or a scalar") {
                o.arr("meta") shouldBe null
                o.arr("scalar") shouldBe null
            }
        }
    }

    given("a field whose value is blank rather than missing") {
        val o = parse("""{"e":""}""")
        `when`("reading it") {
            then("the empty string is kept as itself") { o.str("e") shouldBe "" }
        }
    }
})
