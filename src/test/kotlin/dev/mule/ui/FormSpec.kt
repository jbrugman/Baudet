package dev.mule.ui

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
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
