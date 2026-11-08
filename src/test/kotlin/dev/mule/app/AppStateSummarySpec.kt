package dev.mule.app

import dev.mule.session.Msg
import dev.mule.store.H2Store
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Compaction drops the older turns and keeps only a summary, so that summary must reach
 * the store or it is lost for good once the app restarts. This drives a real store to pin
 * that persist and reopen keep the summary alongside the surviving turns.
 */
class AppStateSummarySpec : BehaviorSpec({

    given("a conversation carrying a compaction summary") {
        val db = Paths.get(Files.createTempDirectory("mule-summary").toString(), "db")
        val state = AppState(H2Store(db))
        val id = state.newSession().sessionId
        state.session.summary = "they chose option two"
        state.session.messages.add(Msg("user", "after the cut"))
        state.persist()
        `when`("a fresh AppState opens the same store") {
            val reopened = AppState(H2Store(db))
            then("the summary survives the restart") {
                reopened.session.sessionId shouldBe id
                reopened.session.summary shouldBe "they chose option two"
                reopened.session.messages.map { it.content } shouldBe listOf("after the cut")
            }
        }
    }
})
