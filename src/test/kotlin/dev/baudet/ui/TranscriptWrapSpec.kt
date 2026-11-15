package dev.baudet.ui

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.awt.Container
import javax.swing.JScrollPane
import javax.swing.Scrollable
import javax.swing.JTextPane

/**
 * The transcript either folds a long line to the pane or keeps it whole behind a horizontal
 * scrollbar. The policy the viewport carries is tested first, because it is the setting the user
 * flips: wrap hides the bar, scroll shows it on demand. The fold itself is then measured, because
 * hiding the bar alone does not fold a line -- the pane has to ask for the viewport width and the
 * folded height, which is the bug a policy-only toggle gets wrong. Folding is finally checked on a
 * narrower viewport too, because shrinking the window must re-fold, not clip, without a rebuild.
 */
class TranscriptWrapSpec : BehaviorSpec({

    given("a transcript in its default mode") {
        val view = TranscriptView()
        `when`("it is built") {
            then("long lines wrap and no horizontal bar is offered") {
                view.wrap shouldBe true
                view.scroll.horizontalScrollBarPolicy shouldBe JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            }
        }
        `when`("wrap is switched off and applied") {
            view.wrap = false
            view.applyWrap()
            then("the viewport offers a horizontal bar so a wide row keeps its shape") {
                view.scroll.horizontalScrollBarPolicy shouldBe JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            }
        }
        `when`("wrap is switched back on and applied") {
            view.wrap = true
            view.applyWrap()
            then("the horizontal bar is hidden again") {
                view.scroll.horizontalScrollBarPolicy shouldBe JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            }
        }

        // Give the viewport a real width, which headless layout will not do on its own, so the
        // pane's wrap decision has a width to fold to. One row is then measured in both modes.
        `when`("a long line meets a laid-out viewport") {
            view.scroll.viewport.setSize(400, 200)
            view.prompt("The quick brown fox jumps over the lazy dog. ".repeat(40), false)
            val pane = firstTextPane(view)
            val folded = pane.preferredSize
            view.wrap = false
            view.applyWrap()
            val straight = pane.preferredSize
            view.wrap = true
            view.applyWrap()
            then("the wrap mode folds the line to the viewport width and grows the row") {
                folded.width shouldBe 400
                folded.height shouldBeGreaterThan straight.height
            }
            then("the scroll mode keeps the line whole, wider than the viewport") {
                straight.width shouldBeGreaterThan 400
            }
            then("the view tracks the viewport width so folding can follow a resize") {
                (view.scroll.viewport.view as Scrollable).getScrollableTracksViewportWidth() shouldBe true
            }
        }

        // Dragging the window narrower must re-fold to the narrower width, not keep the old fold and
        // clip it -- the asymmetry that only corrected itself after a refresh. The viewport is made
        // narrower in place and the same row re-measured.
        `when`("the viewport is then narrowed") {
            view.scroll.viewport.setSize(220, 200)
            val pane = firstTextPane(view)
            val narrower = pane.preferredSize
            then("the line re-folds to the narrower width and grows taller again") {
                narrower.width shouldBe 220
                narrower.height shouldBeGreaterThan 0
            }
        }
    }
})

/** The one text pane a prompt row adds, found by walking the view; nothing else adds one here. */
private fun firstTextPane(c: Container): JTextPane {
    for (child in c.components) {
        if (child is JTextPane) return child
        if (child is Container) {
            val found = runCatching { firstTextPane(child) }.getOrNull()
            if (found != null) return found
        }
    }
    throw AssertionError("expected a text pane in the transcript")
}
