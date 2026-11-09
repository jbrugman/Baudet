package dev.baudet.ui

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField

/**
 * The caption column is what the eye rests on while reading a form, so a hint has to
 * be reachable from there and not only from the control on the far side of the row.
 * These cases lock that mirroring: a hint attached solely to the field reads to the
 * reader as a missing feature, because hovering the description answers nothing.
 */
class FormSpec : BehaviorSpec({

    // Rows are added caption-first, so a row occupies two consecutive children and
    // the stretch filler follows every row.
    fun captionAt(form: JPanel, row: Int) = form.components[row * 2] as JLabel
    fun fieldAt(form: JPanel, row: Int) = form.components[row * 2 + 1] as JComponent

    given("a row whose field carries a hint") {
        val field = JTextField(6).apply { toolTipText = "Zero defers to the model server." }
        val form = formPanel(listOf("max output tokens" to field))
        `when`("the form is built") {
            then("the caption carries the same hint") {
                captionAt(form, 0).toolTipText shouldBe "Zero defers to the model server."
            }
            then("the field keeps its own hint") {
                fieldAt(form, 0).toolTipText shouldBe "Zero defers to the model server."
            }
        }
    }

    given("a row whose field has no hint") {
        val form = formPanel(listOf("model" to JTextField(6)))
        `when`("the form is built") {
            then("the caption stays bare instead of inventing a tooltip target") {
                captionAt(form, 0).toolTipText shouldBe null
            }
        }
    }

    given("a field whose hint is blank text") {
        val form = formPanel(listOf("top_p" to JTextField(6).apply { toolTipText = "" }))
        `when`("the form is built") {
            then("the caption is left alone") {
                captionAt(form, 0).toolTipText shouldBe null
            }
        }
    }

    given("a multi-line field handed to a row") {
        val area = JTextArea(5, 20).apply {
            lineWrap = true
            wrapStyleWord = true
            toolTipText = "Leave blank for the built-in default."
        }
        val row = scrollingField(area)
        val form = formPanel(listOf("system prompt" to row))
        val declaredHeight = row.preferredSize.height

        `when`("the form is built") {
            then("the row is a viewport holding the field") {
                fieldAt(form, 0) shouldBe row
                row.viewport.view shouldBe area
            }
            then("the caption carries the hint of the field living inside the viewport") {
                captionAt(form, 0).toolTipText shouldBe "Leave blank for the built-in default."
            }
            then("the field keeps its own hint as well") {
                area.toolTipText shouldBe "Leave blank for the built-in default."
            }
        }
    }

    given("a prompt that grew past the visible rows") {
        val area = JTextArea(5, 20).apply { lineWrap = true; wrapStyleWord = true }
        val row = scrollingField(area)
        val declaredHeight = row.preferredSize.height
        // Long enough to overflow: this is the state that used to push the rows below it,
        // and the buttons under the form, past the bottom edge of the window.
        area.text = (1..200).joinToString(" ") { "word$it" }
        row.setBounds(0, 0, 260, declaredHeight)
        row.doLayout()

        `when`("the text is measured again") {
            then("the row holds the height it was declared with instead of stretching") {
                row.preferredSize.height shouldBe declaredHeight
            }
            then("the surplus scrolls inside the row") {
                (row.verticalScrollBar.model.maximum > 0) shouldBe true
            }
            then("no horizontal bar claims width the wrapped text never needs") {
                row.horizontalScrollBarPolicy shouldBe JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            }
        }
    }

    given("a blank caption above a hinted control") {
        val box = JCheckBox("Responses API").apply { toolTipText = "Talk to the Responses API." }
        val form = formPanel(listOf("" to box))
        `when`("the form is built") {
            then("the empty caption is not turned into an invisible hover target") {
                captionAt(form, 0).toolTipText shouldBe null
            }
            then("the control still answers on its own") {
                fieldAt(form, 0).toolTipText shouldBe "Talk to the Responses API."
            }
        }
    }
})
