package dev.mule.store

import dev.mule.config.AppConfig
import dev.mule.session.Msg
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * Persistence is the one layer where a silent regression loses real conversations,
 * so it earns tests even though it is mostly JDBC glue. Each scenario gets its own
 * throwaway directory: H2 only shares state within a single file path, so separate
 * directories can never bleed into one another.
 */
class StoresSpec : BehaviorSpec({

    fun freshStore(): H2Store =
        H2Store(Files.createTempDirectory("mule-store").resolve("db"))

    given("an empty store") {
        val store = freshStore()
        `when`("reading the config on a first run") {
            then("there is nothing yet, rather than a fabricated default") {
                store.load() shouldBe null
            }
        }
    }

    given("a config that has been saved") {
        val store = freshStore()
        val saved = AppConfig(
            baseUrl = "http://example.test:1234/v1",
            apiKey = "secret",
            model = "qwen-flash",
            language = "nl",
            maxOutputTokens = 2048,
            contextBudgetTokens = 16384,
            temperature = 0.2,
            topP = 0.9,
            reasoningEffort = "low",
            useResponsesApi = false,
            storeResponses = false,
            useServerSession = false,
            systemPrompt = "custom prompt"
        )
        store.save(saved)
        `when`("read back") {
            then("every field survives the round-trip") {
                store.load() shouldBe saved
            }
        }
        `when`("saved a second time") {
            val newer = saved.copy(model = "other", temperature = 1.0, topP = null)
            store.save(newer)
            then("the single row holds only the latest config") {
                store.load() shouldBe newer
            }
        }
    }

    given("a session carrying metadata and two turns") {
        val store = freshStore()
        val id = "s-round"
        store.upsertSession(id, model = "qwen-flash", prevResponseId = "resp_2", lastSeq = 2)
        store.rewriteMessages(
            id,
            listOf(
                Msg("user", "hello", responseId = "resp_1", promptTokens = 11L),
                Msg("assistant", "hi there", responseId = "resp_2", promptTokens = 24L)
            )
        )
        `when`("reading the messages back in order") {
            then("role, content, response id and token count all return") {
                store.messages(id) shouldBe listOf(
                    Msg("user", "hello", "resp_1", 11L),
                    Msg("assistant", "hi there", "resp_2", 24L)
                )
            }
        }
        `when`("reading the session row") {
            then("the server-session pointer and model are kept") {
                store.previousResponseId(id) shouldBe "resp_2"
                store.model(id) shouldBe "qwen-flash"
            }
        }
        `when`("rewriting with fewer messages, as compaction does") {
            store.rewriteMessages(id, listOf(Msg("user", "summary seed")))
            then("the earlier rows are replaced, not appended") {
                store.messages(id) shouldBe listOf(Msg("user", "summary seed"))
            }
            then("a rewritten message with no ids reads back as null") {
                val only = store.messages(id).single()
                only.responseId shouldBe null
                only.promptTokens shouldBe null
            }
        }
    }

    given("a session that was never written") {
        val store = freshStore()
        `when`("probing it") {
            then("there are no messages and no pointers") {
                store.messages("nope") shouldBe emptyList()
                store.previousResponseId("nope") shouldBe null
                store.model("nope") shouldBe null
            }
        }
    }

    given("two sessions touched one after the other") {
        val store = freshStore()
        store.upsertSession("s-old", model = "m", prevResponseId = null, lastSeq = 1)
        // updated_at is millisecond-resolution; a pause keeps the two writes in
        // distinct milliseconds so newest-first ordering is deterministic to assert.
        Thread.sleep(5)
        store.upsertSession("s-new", model = "m", prevResponseId = null, lastSeq = 3)
        `when`("asking for the most recent") {
            then("the latest touch leads") {
                store.latestSessionId(1) shouldBe listOf("s-new")
            }
            then("the limit is honoured and the order is newest-first") {
                store.latestSessionId(10) shouldBe listOf("s-new", "s-old")
            }
        }
    }
})
