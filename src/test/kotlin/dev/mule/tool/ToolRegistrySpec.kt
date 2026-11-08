package dev.mule.tool

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The registry is the seam between the loop and the tools: the `tools` field it
 * renders is what the backend sees, and its dispatch is what routes a call back.
 * Both are pinned so a tool that is wired but never offered, or offered but never
 * reachable, fails here instead of at runtime.
 */
class ToolRegistrySpec : BehaviorSpec({

    given("an empty registry") {
        val reg = ToolRegistry(emptyList())
        `when`("asked") {
            then("it is empty and renders an empty tools array") {
                reg.isEmpty() shouldBe true
                reg.definitions() shouldBe "[]"
            }
        }
    }

    given("a registry holding the file-edit tool") {
        val reg = ToolRegistry(listOf(EditFileTool()))
        `when`("dispatching by name") {
            then("the known tool comes back and an unknown name is a clean miss") {
                (reg.get("edit_file") != null) shouldBe true
                (reg.get("nope") == null) shouldBe true
                reg.isEmpty() shouldBe false
            }
        }
        `when`("rendering definitions") {
            val json = reg.definitions()
            then("it is a function tool named edit_file") {
                json.contains("\"type\":\"function\"") shouldBe true
                json.contains("edit_file") shouldBe true
            }
        }
    }
})
