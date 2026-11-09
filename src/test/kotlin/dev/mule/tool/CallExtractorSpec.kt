package dev.mule.tool

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The advertised protocol must list exactly the tools the turn has, since it is what teaches the
 * model what it may call: a tool named here but absent from the registry fails as unknown, and a
 * tool the model has but never hears about leaves it unable to build, test or inspect git. These
 * pin both directions of that rule.
 */
class CallExtractorSpec : BehaviorSpec({

    given("a turn with only read and edit") {
        val protocol = CallExtractor.protocolFor(setOf("read_file", "edit_file"))
        `when`("reading the advertised tools") {
            then("it names read_file and edit_file") {
                protocol shouldContain "\"read_file\""
                protocol shouldContain "\"edit_file\""
            }
            then("it stays silent about run_command and git") {
                protocol shouldNotContain "run_command"
                protocol shouldNotContain "\"git\""
            }
        }
    }

    given("a turn with every tool enabled") {
        val protocol =
            CallExtractor.protocolFor(setOf("read_file", "edit_file", "run_command", "git"))
        `when`("reading the advertised tools") {
            then("it advertises run_command and git too") {
                protocol shouldContain "\"run_command\""
                protocol shouldContain "\"git\""
            }
        }
    }

    given("a read-only turn") {
        val protocol = CallExtractor.protocolFor(setOf("read_file"))
        `when`("reading the advertised tools") {
            then("it offers read_file but never edit_file") {
                protocol shouldContain "\"read_file\""
                protocol shouldNotContain "edit_file"
            }
        }
    }
})
