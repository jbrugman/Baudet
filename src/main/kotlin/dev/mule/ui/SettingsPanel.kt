package dev.mule.ui

import dev.mule.api.CapabilityProbe
import dev.mule.app.AppState
import dev.mule.config.AppConfig
import dev.mule.i18n.Key
import dev.mule.i18n.Language
import dev.mule.i18n.Texts
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JPasswordField
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

class SettingsPanel(private val state: AppState) : JPanel(BorderLayout()) {

    private val baseUrl = JTextField(34)
    private val apiKey = JPasswordField(34)
    private val envVar = JTextField(18)
    private val model = JTextField(24)
    private val maxTokens = JTextField(8)
    private val contextBudget = JTextField(10)
    private val temperature = JTextField(8)
    private val topP = JTextField(8)
    private val reasoning = JComboBox(arrayOf("", "minimal", "low", "medium", "high"))
    private val language = JComboBox(arrayOf("English", "Nederlands"))
    private val useResponses = JCheckBox(Texts[Key.useResponsesApi])
    private val storeResp = JCheckBox(Texts[Key.storeResponses])
    private val useServerSession = JCheckBox(Texts[Key.sendPreviousResponseId])
    private val systemPrompt = JTextArea(5, 34).apply {
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }

    private val probeOut = JTextArea(10, 34).apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }
    private val status = JTextField(" ")

    init {
        language.toolTipText = Texts[Key.tipLanguage]
        val form = formPanel(
            listOf(
                Texts[Key.languageLabel] to language,
                Texts[Key.baseUrlLabel] to baseUrl,
                Texts[Key.apiKeyLabel] to apiKey,
                Texts[Key.orEnvVar] to envVar,
                Texts[Key.modelLabel] to model,
                Texts[Key.maxTokensLabel] to maxTokens,
                Texts[Key.contextBudgetLabel] to contextBudget,
                Texts[Key.temperatureLabel] to temperature,
                Texts[Key.topPLabel] to topP,
                Texts[Key.reasoningEffortLabel] to reasoning,
                "" to useResponses,
                "" to storeResp,
                "" to useServerSession,
                Texts[Key.systemPromptLabel] to systemPrompt
            )
        )

        val buttons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
        buttons.add(JButton(Texts[Key.saveButton]).apply { toolTipText = Texts[Key.tipSave]; addActionListener { save() } })
        buttons.add(JButton(Texts[Key.testButton]).apply { toolTipText = Texts[Key.tipTest]; addActionListener { test() } })
        buttons.add(JButton(Texts[Key.reloadButton]).apply { toolTipText = Texts[Key.tipReload]; addActionListener { reload() } })

        val north = JPanel(BorderLayout())
        north.add(form, BorderLayout.NORTH)
        north.add(buttons, BorderLayout.SOUTH)

        add(north, BorderLayout.NORTH)
        add(
            javax.swing.JScrollPane(probeOut).apply {
                border = BorderFactory.createTitledBorder(Texts[Key.diagnostics])
            },
            BorderLayout.CENTER
        )
        status.isEditable = false
        add(status, BorderLayout.SOUTH)

        reload()
    }

    @Suppress("DEPRECATION")
    fun reload() {
        val c = state.config
        baseUrl.text = c.baseUrl
        apiKey.text = c.apiKey
        envVar.text = c.apiKeyEnvVar
        model.text = c.model
        maxTokens.text = c.maxOutputTokens.toString()
        contextBudget.text = c.contextBudgetTokens.toString()
        temperature.text = c.temperature.toString()
        topP.text = c.topP?.toString() ?: ""
        reasoning.selectedItem = (c.reasoningEffort ?: "")
        useResponses.isSelected = c.useResponsesApi
        storeResp.isSelected = c.storeResponses
        useServerSession.isSelected = c.useServerSession
        // Raw stored text: blank means "use the built-in default", which the
        // field label says. Showing the resolved text here would store it back
        // as user text and freeze it against later language changes.
        systemPrompt.text = c.systemPrompt
        language.selectedIndex = if (Texts.parse(c.language) == Language.NL) 1 else 0
        status.foreground = Color(160, 160, 160)
        status.text = Texts.fmt(Key.loadedFromDisk, AppState.dataDir().toAbsolutePath().toString())
    }

    private fun fail(m: String) {
        status.foreground = Color(235, 110, 110)
        status.text = m
    }

    private fun collect(): AppConfig? {
        val mt = maxTokens.text.trim().toIntOrNull()
        if (mt == null || mt <= 0) { fail(Texts[Key.errMaxTokens]); return null }
        val cb = contextBudget.text.trim().toIntOrNull()
        if (cb == null || cb <= 0) { fail(Texts[Key.errContextBudget]); return null }
        val temp = temperature.text.trim().toDoubleOrNull()
        if (temp == null || temp < 0.0 || temp > 2.0) { fail(Texts[Key.errTemperature]); return null }
        val tp = topP.text.trim()
        val tpVal = if (tp.isBlank()) null else tp.toDoubleOrNull()
        if (tp.isNotBlank() && (tpVal == null || tpVal <= 0.0 || tpVal > 1.0)) {
            fail(Texts[Key.errTopP]); return null
        }
        val url = baseUrl.text.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            fail(Texts[Key.errBaseUrl]); return null
        }
        return state.config.copy(
            language = if (language.selectedIndex == 1) "nl" else "en",
            baseUrl = url,
            apiKey = String(apiKey.password).trim(),
            apiKeyEnvVar = envVar.text.trim().ifBlank { "OMLX_API_KEY" },
            model = model.text.trim(),
            maxOutputTokens = mt,
            contextBudgetTokens = cb,
            temperature = temp,
            topP = tpVal,
            reasoningEffort = reasoning.selectedItem?.toString()?.takeIf { it.isNotBlank() },
            useResponsesApi = useResponses.isSelected,
            storeResponses = storeResp.isSelected,
            useServerSession = useServerSession.isSelected,
            systemPrompt = systemPrompt.text
        )
    }

    fun save(): Boolean {
        val next = collect() ?: return false
        state.saveConfig(next)
        status.foreground = Color(120, 200, 130)
        status.text = Texts.fmt(Key.savedToDisk, next.model)
        return true
    }

    private fun test() {
        val next = collect() ?: return
        state.saveConfig(next)
        status.foreground = Color(160, 160, 160)
        status.text = Texts[Key.testingProbe]
        probeOut.text = ""
        Thread {
            try {
                CapabilityProbe().run(state.config) { line ->
                    SwingUtilities.invokeLater {
                        probeOut.append(line + "\n")
                        probeOut.caretPosition = probeOut.document.length
                    }
                }
                SwingUtilities.invokeLater {
                    status.foreground = Color(120, 200, 130)
                    status.text = Texts[Key.diagnosticsDone]
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { fail(Texts.fmt(Key.testFailed, e.message ?: "?")) }
            }
        }.start()
    }
}
