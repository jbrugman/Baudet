package dev.mule.ui

import dev.mule.api.ModelsClient
import dev.mule.app.AppState
import dev.mule.i18n.Key
import dev.mule.i18n.Texts
import java.awt.BorderLayout
import java.awt.Font
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

class ModelsPanel(private val state: AppState, private val onPicked: (String) -> Unit) :
    JPanel(BorderLayout()) {

    private val model = DefaultListModel<String>()
    private val list = JList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
    }
    private val status = javax.swing.JTextField(" ")

    init {
        val bar = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
        bar.add(JButton(Texts[Key.fetchModels]).apply { addActionListener { refresh() } })
        bar.add(JButton(Texts[Key.useThisModel]).apply { addActionListener { pick() } })
        add(bar, BorderLayout.NORTH)
        add(JScrollPane(list), BorderLayout.CENTER)
        status.isEditable = false
        add(status, BorderLayout.SOUTH)
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    }

    fun refresh() {
        status.text = Texts[Key.fetching]
        Thread {
            val result = try {
                ModelsClient.fetch(state.config)
            } catch (e: Exception) {
                SwingUtilities.invokeLater { status.text = Texts.fmt(Key.failedPrefix, e.message ?: "?") }
                return@Thread
            }
            SwingUtilities.invokeLater {
                model.clear()
                result.forEach { model.addElement(it.id) }
                status.text = Texts.fmt(Key.modelsListed, result.size, state.config.baseUrl)
                val cur = state.config.model
                if (cur.isNotBlank()) {
                    val i = result.indexOfFirst { it.id == cur }
                    if (i >= 0) list.selectedIndex = i
                }
            }
        }.start()
    }

    private fun pick() {
        val sel = list.selectedValue ?: run { status.text = Texts[Key.pickFirst]; return }
        state.saveConfig(state.config.copy(model = sel))
        status.text = Texts.fmt(Key.modelSaved, sel)
        onPicked(sel)
    }
}
