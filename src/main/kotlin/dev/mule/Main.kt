package dev.mule

import com.formdev.flatlaf.FlatDarkLaf
import dev.mule.api.CapabilityProbe
import dev.mule.api.ModelsClient
import dev.mule.app.AppState
import dev.mule.i18n.Key
import dev.mule.i18n.Texts
import dev.mule.ui.AppIcon
import dev.mule.ui.ChatPanel
import dev.mule.ui.ModelsPanel
import dev.mule.ui.SettingsPanel
import java.awt.Dimension
import javax.swing.JFrame
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.system.exitProcess

/**
 * --smoke        : no GUI, fetch the model list and exit (costs no GPU time)
 * --smoke --probe: additionally run the capability probe (two mini-calls)
 */
fun main(args: Array<String>) {
    val state = AppState.open()
    if (args.contains("--smoke")) exitProcess(smoke(state, args))

    FlatDarkLaf.setup()
    UIManager.put("Button.arc", 8)
    UIManager.put("Component.arc", 8)
    UIManager.put("TextComponent.arc", 8)

    SwingUtilities.invokeLater {
        val frame = JFrame("Mule · local coding-assistant")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.size = Dimension(1040, 720)
        frame.setLocationRelativeTo(null)
        frame.iconImages = AppIcon.images()

        var shown = Texts.language
        show(state, frame)

        // Settings is the only place the language changes, and only through its own
        // button, so this cannot loop: the panels are rebuilt exactly when the
        // persisted value actually differs from the language on screen.
        state.onConfigSaved = {
            if (Texts.language != shown) {
                shown = Texts.language
                SwingUtilities.invokeLater { show(state, frame) }
            }
        }

        frame.isVisible = true
    }
}

/** (Re)build every panel in the active language and put it on screen. */
private fun show(state: AppState, frame: JFrame) {
    val chat = ChatPanel(state)
    val settings = SettingsPanel(state)
    val models = ModelsPanel(state) {
        settings.reload()
        chat.refresh()
    }

    val tabs = JTabbedPane()
    tabs.addTab(Texts[Key.tabChat], chat)
    tabs.addTab(Texts[Key.tabModels], models)
    tabs.addTab(Texts[Key.tabSettings], settings)

    frame.contentPane = tabs
    chat.refresh()
    settings.reload()
    frame.contentPane.revalidate()
    frame.contentPane.repaint()
}

private fun smoke(state: AppState, args: Array<String>): Int {
    println(Texts.fmt(Key.smokeDataDir, AppState.dataDir().toAbsolutePath().toString()))

    // --model=<id> makes the smoke run scriptable for a specific model
    val wanted = args.firstOrNull { it.startsWith("--model=") }?.substringAfter('=')?.ifBlank { null }
    if (wanted != null) {
        state.saveConfig(state.config.copy(model = wanted))
        println(Texts.fmt(Key.smokeModelSet, wanted))
    }

    val cfg = state.config
    println("base      = ${cfg.baseUrl}")
    println(Texts[Key.smokeKeyLabel] + if (cfg.hasKey()) Texts[Key.keyPresent] else Texts[Key.keyMissing])
    println("model     = '${cfg.model}'")
    println(Texts.fmt(Key.smokeSession, state.session.sessionId, state.session.messages.size))

    val models = try {
        ModelsClient.fetch(cfg)
    } catch (e: Exception) {
        println(Texts.fmt(Key.smokeModelsFailed, e.message ?: "?"))
        return 1
    }
    println(Texts.fmt(Key.smokeModels, models.size))
    models.take(30).forEach { println("   - ${it.id}") }
    if (models.isEmpty()) return 1

    if (args.contains("--probe")) {
        println("--- probe ---")
        CapabilityProbe().run(cfg) { println("   $it") }
    }
    return 0
}
