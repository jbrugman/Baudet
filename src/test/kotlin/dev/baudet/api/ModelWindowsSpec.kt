package dev.baudet.api

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The table behind the context guard. Two properties matter more than the exact numbers: an id
 * resolves through the most specific fragment that matches, so a long-context variant is not
 * mistaken for its small sibling; and an id nobody knows yields null, meaning "unknown", never a
 * number invented to keep a turn going. An entry that is too low only folds a conversation early,
 * while one that is too high lets through the request the backend will refuse -- so the pessimistic
 * reading is the correct one, and these cases pin it.
 */
class ModelWindowsSpec : BehaviorSpec({

    given("an id from a known family") {
        `when`("looked up") {
            then("each resolves to its window, whatever the case or the size suffix") {
                ModelWindows.known("gpt-4o") shouldBe 128_000
                ModelWindows.known("GPT-4O-mini") shouldBe 128_000
                ModelWindows.known("gpt-4.1-mini") shouldBe 1_047_576
                ModelWindows.known("gpt-oss-20b") shouldBe 131_072
                ModelWindows.known("llama-3.1-8b-instruct") shouldBe 131_072
                ModelWindows.known("mistral-nemo-12b") shouldBe 128_000
                ModelWindows.known("command-r-plus") shouldBe 128_000
                ModelWindows.known("qwen2.5-coder-32b") shouldBe 32_768
            }
        }
    }

    given("an id whose family ships both a small and a long variant") {
        `when`("the shorter id matches") {
            then("the more specific fragment wins, so 3.1 is not read as 3") {
                ModelWindows.known("llama-3.1-8b") shouldBe 131_072
                ModelWindows.known("llama-3-8b") shouldBe 8_192
            }
            then("and a plain gpt-4 stays far below the family's largest entry") {
                ModelWindows.known("gpt-4") shouldBe 8_192
                ModelWindows.known("gpt-4o") shouldBe 128_000
                (ModelWindows.known("gpt-4")!! < ModelWindows.known("gpt-4o")!!) shouldBe true
            }
            then("mistral-nemo is the exception, mistral itself the smaller rule") {
                ModelWindows.known("mistral-large") shouldBe 32_768
                ModelWindows.known("mistral-nemo") shouldBe 128_000
            }
        }
    }

    given("an id nobody knows") {
        `when`("looked up") {
            then("the answer is unknown, never an invented window") {
                ModelWindows.known("baudetx-9b-instruct-q4") shouldBe null
                ModelWindows.known("") shouldBe null
                ModelWindows.known("   ") shouldBe null
            }
        }
    }
})
