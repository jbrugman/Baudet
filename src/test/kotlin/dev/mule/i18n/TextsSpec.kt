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
            then("no key ships an empty French string") {
                Key.entries.forEach { it.fr.shouldNotBeBlank() }
            }
            then("no key ships an empty German string") {
                Key.entries.forEach { it.de.shouldNotBeBlank() }
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
                Texts[Key.SaveButton] shouldBe "Save"
            }
        }
    }

    given("Dutch selected") {
        `when`("reading a string") {
            Texts.language = Language.NL
            then("the same key yields the Dutch text") {
                Texts[Key.SaveButton] shouldBe "Opslaan"
            }
        }
        `when`("switching back") {
            Texts.language = Language.NL
            Texts.language = Language.EN
            then("the English text returns") {
                Texts[Key.SaveButton] shouldBe "Save"
            }
        }
    }

    given("French selected") {
        `when`("reading a string") {
            Texts.language = Language.FR
            then("the same key yields the French text") {
                Texts[Key.SaveButton] shouldBe "Enregistrer"
            }
        }
    }

    given("German selected") {
        `when`("reading a string") {
            Texts.language = Language.DE
            then("the same key yields the German text") {
                Texts[Key.SaveButton] shouldBe "Speichern"
            }
        }
    }

    given("a string with placeholders") {
        `when`("formatted") {
            then("arguments land in position") {
                Texts.fmt(Key.ModelsListed, 3, "http://h:1/v1") shouldBe "3 models via http://h:1/v1"
            }
            then("the Dutch form is used in Dutch") {
                Texts.language = Language.NL
                Texts.fmt(Key.ModelsListed, 3, "http://h:1/v1") shouldBe "3 modellen via http://h:1/v1"
            }
            then("a placeholder that is part of the text is left alone") {
                // probeReread carries a literal {id} naming an API path segment
                Texts.fmt(Key.ProbeReread, 200) shouldContain "{id}"
                Texts.fmt(Key.ProbeReread, 200) shouldContain "HTTP 200"
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
            then("fr selects French") {
                Texts.parse("fr") shouldBe Language.FR
            }
            then("de selects German") {
                Texts.parse("de") shouldBe Language.DE
            }
            then("anything unrecognized falls back to English instead of failing") {
                Texts.parse("") shouldBe Language.EN
                Texts.parse("es") shouldBe Language.EN
            }
        }
    }
})
