package dev.mule.ui

import dev.mule.api.CapabilityProbe
import dev.mule.app.AppState
import dev.mule.config.AppConfig
import dev.mule.config.ContextStrategy
import dev.mule.config.ToolMode
import dev.mule.i18n.Key
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
    private val contextStrategy = JComboBox(
        arrayOf(Texts[Key.StrategyTruncate], Texts[Key.StrategyRolling], Texts[Key.StrategyStop])
    )
    private val temperature = JTextField(8)
    private val topP = JTextField(8)
    private val reasoning = JComboBox(arrayOf("", "minimal", "low", "medium", "high"))
    private val language = JComboBox(arrayOf("English", "Nederlands", "Français", "Deutsch"))
    private val languageCodes = arrayOf("en", "nl", "fr", "de")
    private val useResponses = JCheckBox(Texts[Key.UseResponsesApi])
    private val storeResp = JCheckBox(Texts[Key.StoreResponses])
    private val useServerSession = JCheckBox(Texts[Key.SendPreviousResponseId])
    private val autoApproveSafe = JCheckBox(Texts[Key.AutoApproveSafe])
    private val toolMode = JComboBox(arrayOf(Texts[Key.ToolModeOff], Texts[Key.ToolModeAsk], Texts[Key.ToolModeAuto]))
    private val maxIterations = JTextField(6)
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
        language.toolTipText = Texts[Key.TipLanguage]
        toolMode.toolTipText = Texts[Key.TipToolMode]
        baseUrl.toolTipText = Texts[Key.TipBaseUrl]
        apiKey.toolTipText = Texts[Key.TipApiKey]
        envVar.toolTipText = Texts[Key.TipEnvVar]
        model.toolTipText = Texts[Key.TipModel]
        maxTokens.toolTipText = Texts[Key.TipMaxTokens]
        contextBudget.toolTipText = Texts[Key.TipContextBudget]
        contextStrategy.toolTipText = Texts[Key.TipContextStrategy]
        temperature.toolTipText = Texts[Key.TipTemperature]
        topP.toolTipText = Texts[Key.TipTopP]
        reasoning.toolTipText = Texts[Key.TipReasoning]
        systemPrompt.toolTipText = Texts[Key.TipSystemPrompt]
        useResponses.toolTipText = Texts[Key.TipUseResponses]
        storeResp.toolTipText = Texts[Key.TipStoreResponses]
        useServerSession.toolTipText = Texts[Key.TipSendResponseId]
        autoApproveSafe.toolTipText = Texts[Key.TipAutoApproveSafe]
        maxIterations.toolTipText = Texts[Key.TipMaxIterations]
        val form = formPanel(
            listOf(
                Texts[Key.LanguageLabel] to language,
                Texts[Key.BaseUrlLabel] to baseUrl,
                Texts[Key.ApiKeyLabel] to apiKey,
                Texts[Key.OrEnvVar] to envVar,
                Texts[Key.ModelLabel] to model,
                Texts[Key.MaxTokensLabel] to maxTokens,
                Texts[Key.ContextBudgetLabel] to contextBudget,
                Texts[Key.ContextStrategyLabel] to contextStrategy,
                Texts[Key.TemperatureLabel] to temperature,
                Texts[Key.TopPLabel] to topP,
                Texts[Key.ReasoningEffortLabel] to reasoning,
                Texts[Key.ToolModeLabel] to toolMode,
                Texts[Key.MaxToolIterationsLabel] to maxIterations,
                "" to useResponses,
                "" to storeResp,
                "" to useServerSession,
                "" to autoApproveSafe,
                Texts[Key.SystemPromptLabel] to systemPrompt
            )
        )

        val buttons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
        buttons.add(JButton(Texts[Key.SaveButton]).apply { toolTipText = Texts[Key.TipSave]; addActionListener { save() } })
        buttons.add(JButton(Texts[Key.TestButton]).apply { toolTipText = Texts[Key.TipTest]; addActionListener { test() } })
        buttons.add(JButton(Texts[Key.ReloadButton]).apply { toolTipText = Texts[Key.TipReload]; addActionListener { reload() } })

        val north = JPanel(BorderLayout())
        north.add(form, BorderLayout.NORTH)
        north.add(buttons, BorderLayout.SOUTH)

        add(north, BorderLayout.NORTH)
        add(
            javax.swing.JScrollPane(probeOut).apply {
                border = BorderFactory.createTitledBorder(Texts[Key.Diagnostics])
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
        contextStrategy.selectedIndex = c.contextStrategy.ordinal
        temperature.text = c.temperature.toString()
        topP.text = c.topP?.toString() ?: ""
        reasoning.selectedItem = (c.reasoningEffort ?: "")
        useResponses.isSelected = c.useResponsesApi
        storeResp.isSelected = c.storeResponses
        useServerSession.isSelected = c.useServerSession
        autoApproveSafe.isSelected = c.autoApproveSafeCommands
        // Raw stored text: blank means "use the built-in default", which the
        // field label says. Showing the resolved text here would store it back
        // as user text and freeze it against later language changes.
        systemPrompt.text = c.systemPrompt
        language.selectedIndex = Texts.parse(c.language).ordinal
        toolMode.selectedIndex = c.toolMode.ordinal
        maxIterations.text = c.maxToolIterations.toString()
        status.foreground = Color(160, 160, 160)
        status.text = Texts.fmt(Key.LoadedFromDisk, AppState.dataDir().toAbsolutePath().toString())
    }

    private fun fail(m: String) {
        status.foreground = Color(235, 110, 110)
        status.text = m
    }

    private fun collect(): AppConfig? {
        // Zero is a real choice, not a missing one: a max-output of 0 omits the field so the
        // server decides, and a budget of 0 turns the context guard off. Only a non-number or a
        // negative is rejected, which is exactly what the error text already promises.
        val mt = maxTokens.text.trim().toIntOrNull()
        if (mt == null || mt < 0) { fail(Texts[Key.ErrMaxTokens]); return null }
        val cb = contextBudget.text.trim().toIntOrNull()
        if (cb == null || cb < 0) { fail(Texts[Key.ErrContextBudget]); return null }
        val temp = temperature.text.trim().toDoubleOrNull()
        if (temp == null || temp < 0.0 || temp > 2.0) { fail(Texts[Key.ErrTemperature]); return null }
        val tp = topP.text.trim()
        val tpVal = if (tp.isBlank()) null else tp.toDoubleOrNull()
        if (tp.isNotBlank() && (tpVal == null || tpVal <= 0.0 || tpVal > 1.0)) {
            fail(Texts[Key.ErrTopP]); return null
        }
        val url = baseUrl.text.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            fail(Texts[Key.ErrBaseUrl]); return null
        }
        val mi = maxIterations.text.trim().toIntOrNull()
        if (mi == null || mi <= 0) { fail(Texts[Key.ErrMaxIterations]); return null }
        return state.config.copy(
            language = languageCodes[language.selectedIndex],
            baseUrl = url,
            apiKey = String(apiKey.password).trim(),
            apiKeyEnvVar = envVar.text.trim().ifBlank { "OMLX_API_KEY" },
            model = model.text.trim(),
            maxOutputTokens = mt,
            contextBudgetTokens = cb,
            contextStrategy = ContextStrategy.entries[contextStrategy.selectedIndex],
            temperature = temp,
            topP = tpVal,
            reasoningEffort = reasoning.selectedItem?.toString()?.takeIf { it.isNotBlank() },
            useResponsesApi = useResponses.isSelected,
            storeResponses = storeResp.isSelected,
            useServerSession = useServerSession.isSelected,
            autoApproveSafeCommands = autoApproveSafe.isSelected,
            toolMode = ToolMode.entries[toolMode.selectedIndex],
            maxToolIterations = mi,
            systemPrompt = systemPrompt.text
        )
    }

    fun save(): Boolean {
        val next = collect() ?: return false
        state.saveConfig(next)
        status.foreground = Color(120, 200, 130)
        status.text = Texts.fmt(Key.SavedToDisk, next.model)
        return true
    }

    private fun test() {
        val next = collect() ?: return
        state.saveConfig(next)
        status.foreground = Color(160, 160, 160)
        status.text = Texts[Key.TestingProbe]
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
                    status.text = Texts[Key.DiagnosticsDone]
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { fail(Texts.fmt(Key.TestFailed, e.message ?: "?")) }
            }
        }.start()
    }
}
