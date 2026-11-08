package dev.mule.i18n

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank

/**
 * The table is the only place a translation can live, so the real risk is a key
 * added with one language filled in. These tests make that a build failure.
 */
class TextsSpec : BehaviorSpec({

    afterEach { Texts.language = Language.EN }

    given("the table as a whole") {
        `when`("every key is inspected") {
            then("no key ships an empty English string") {
                Key.entries.forEach { it.en.shouldNotBeBlank() }
            }
            then("no key ships an empty Dutch string") {
                Key.entries.forEach { it.nl.shouldNotBeBlank() }
            }
            then("key names stay unique") {
                Key.entries.map { it.name }.distinct().size shouldBe Key.entries.size
            }
        }
    }

    given("a fresh install") {
        `when`("nothing chose a language") {
            then("the UI reads English") {
                Texts.language shouldBe Language.EN
                Texts[Key.saveButton] shouldBe "Save"
            }
        }
    }

    given("Dutch selected") {
        `when`("reading a string") {
            Texts.language = Language.NL
            then("the same key yields the Dutch text") {
                Texts[Key.saveButton] shouldBe "Opslaan"
            }
        }
        `when`("switching back") {
            Texts.language = Language.NL
            Texts.language = Language.EN
            then("the English text returns") {
                Texts[Key.saveButton] shouldBe "Save"
            }
        }
    }

    given("a string with placeholders") {
        `when`("formatted") {
            then("arguments land in position") {
                Texts.fmt(Key.modelsListed, 3, "http://h:1/v1") shouldBe "3 models via http://h:1/v1"
            }
            then("the Dutch form is used in Dutch") {
                Texts.language = Language.NL
                Texts.fmt(Key.modelsListed, 3, "http://h:1/v1") shouldBe "3 modellen via http://h:1/v1"
            }
            then("a placeholder that is part of the text is left alone") {
                // probeReread carries a literal {id} naming an API path segment
                Texts.fmt(Key.probeReread, 200) shouldContain "{id}"
                Texts.fmt(Key.probeReread, 200) shouldContain "HTTP 200"
            }
        }
    }

    given("a stored language value") {
        `when`("parsed") {
            then("nl selects Dutch") {
                Texts.parse("nl") shouldBe Language.NL
            }
            then("case and spacing do not matter") {
                Texts.parse(" NL ") shouldBe Language.NL
            }
            then("en selects English") {
                Texts.parse("en") shouldBe Language.EN
            }
            then("anything else falls back to English instead of failing") {
                Texts.parse("") shouldBe Language.EN
                Texts.parse("de") shouldBe Language.EN
                Texts.parse("fr") shouldBe Language.EN
            }
        }
    }
})
