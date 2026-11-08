package dev.mule.api

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ModelsClientSpec : BehaviorSpec({

    given("a payload shaped as data[]") {
        val models = ModelsClient.parseModels(
            """{"object":"list","data":[{"id":"b-model"},{"id":"A-model","owned_by":"local","created":1794135998}]}"""
        )

        `when`("parsed") {
            then("ids are sorted without case getting in the way") {
                models.map { it.id } shouldBe listOf("A-model", "b-model")
            }
            then("absent metadata does not drop the model") {
                models.first { it.id == "b-model" }.ownedBy shouldBe null
                models.first { it.id == "A-model" }.created shouldBe 1794135998L
            }
        }
    }

    given("a server that calls the array models[] instead") {
        `when`("parsed") {
            then("the picker is still filled") {
                ModelsClient.parseModels("""{"models":[{"id":"only"}]}""").map { it.id } shouldBe listOf("only")
            }
        }
    }

    given("an entry with no id") {
        `when`("parsed") {
            then("it is skipped rather than offering a nameless model") {
                ModelsClient.parseModels("""{"data":[{"owned_by":"x"},{"id":"ok"}]}""").map { it.id } shouldBe
                    listOf("ok")
            }
        }
    }

    given("a well-formed answer carrying no models at all") {
        `when`("parsed") {
            then("the result is empty and not a crash") {
                ModelsClient.parseModels("""{"data":[]}""").size shouldBe 0
            }
        }
    }

    given("a body that is not a list response") {
        `when`("parsed") {
            then("it yields no models instead of throwing") {
                ModelsClient.parseModels("""{"error":{"message":"unauthorized"}}""").size shouldBe 0
            }
        }
    }
})
