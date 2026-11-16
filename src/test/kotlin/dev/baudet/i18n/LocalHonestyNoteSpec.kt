package dev.baudet.i18n

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain

/**
 * The honesty line is the whole point of the feature: it must actually name a local model and ask
 * the reader to verify, not merely be non-empty. [TextsSpec] already fails any key shipped with a
 * blank language, so what this guards is the meaning of the words -- that the reminder keeps its
 * warning across the two languages the project targets, rather than drifting into decoration.
 */
class LocalHonestyNoteSpec : BehaviorSpec({

    given("the honesty footer text") {
        `when`("read in English") {
            then("it names a local model and asks the reader to verify") {
                val en = Key.LocalHonestyNote.en.lowercase()
                en shouldContain "local model"
                en shouldContain "verif"
            }
        }
        `when`("read in Dutch") {
            then("it carries the same warning in Dutch") {
                val nl = Key.LocalHonestyNote.nl.lowercase()
                nl shouldContain "lokaal model"
                nl shouldContain "controleer"
            }
        }
    }
})
