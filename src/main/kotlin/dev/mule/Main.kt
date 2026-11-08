package dev.mule

import com.formdev.flatlaf.FlatDarkLaf
import dev.mule.api.CapabilityProbe
import dev.mule.api.ModelsClient
import dev.mule.app.AppState
import dev.mule.i18n.Key
import dev.mule.i18n.Texts
import dev.mule.ui.AppIcon
import dev.mule.ui.ChatPanel
import dev.mule.ui.FileTreePanel
import dev.mule.ui.ProjectSidebar
import dev.mule.ui.ModelsPanel
import dev.mule.ui.SettingsPanel
import dev.mule.ui.SidebarToggleIcon
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Insets
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.system.exitProcess

/**
 * --export-icons=<dir>: headless, write the macOS .iconset and exit (a build step)
 * --smoke             : no GUI, fetch the model list and exit (costs no GPU time)
 * --smoke --probe     : additionally run the capability probe (two mini-calls)
 */
fun main(args: Array<String>) {
    // Icon export is a build step the packaging script calls. It runs before the
    // database is opened or a window exists, so it stays headless and writes no
    // state -- the only thing it touches is the iconset directory it is given.
    args.indexOfFirst { it == "--export-icons" || it.startsWith("--export-icons=") }
        .takeIf { it >= 0 }
        ?.let { i ->
            val dir = args[i].substringAfter('=', "").ifBlank { args.getOrNull(i + 1).orEmpty() }
            exitProcess(exportIcons(dir))
        }

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
    tabs.addTab(Texts[Key.TabChat], chat)
    tabs.addTab(Texts[Key.TabModels], models)
    tabs.addTab(Texts[Key.TabSettings], settings)
    // The split's right side is this tabbed pane; a JTabbedPane takes the largest minimum
    // of its tabs (Settings holds a wide multi-line field), which is what clamped the divider.
    tabs.minimumSize = Dimension(0, 0)

    // The file tree follows the project of whatever the sidebar selects; declared before the
    // sidebar so the selection callback can point straight at it.
    val files = FileTreePanel(state).apply { minimumSize = Dimension(0, 0) }

    val sidebar = ProjectSidebar(
        state,
        onSelectSession = { sessionId -> chat.loadSession(sessionId) },
        onAfterDelete = { chat.refresh() },
        onSelectProject = { projectId -> chat.startProjectChat(projectId) },
        onSelectionChanged = { projectId -> files.showProject(projectId) }
    ).apply { minimumSize = Dimension(0, 0) } // fully hideable via its toolbar toggle
    // A new or updated conversation in the chat must appear in the tree immediately.
    chat.onSessionsChanged = { sidebar.reload() }

    // Two splits, three panels: [sidebar | tabs] on the left, those as a whole beside the
    // file tree. Each toolbar toggle owns one divider, so either side panel collapses on its own.
    val left = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, tabs).apply {
        dividerLocation = 280
        resizeWeight = 0.2
        isContinuousLayout = true
        isOneTouchExpandable = false   // replaced by the toolbar toggle, which stays put when collapsed
    }
    val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, files).apply {
        dividerLocation = 720
        resizeWeight = 1.0             // the file tree keeps its width; the chat absorbs resizing
        isContinuousLayout = true
        isOneTouchExpandable = false
    }

    // Both toggles live in the frame, not their panel, so they survive a collapse and can bring
    // the panel back at its remembered width: the left one hides at 0, the right one at the far end.
    val toolbar = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(4, 4, 0, 4)
        add(sidebarToggle(left, left.dividerLocation, Key.ToggleSidebar, rightSide = false), BorderLayout.WEST)
        add(sidebarToggle(split, split.dividerLocation, Key.ToggleFileTree, rightSide = true), BorderLayout.EAST)
    }
    val root = JPanel(BorderLayout())
    root.add(toolbar, BorderLayout.NORTH)
    root.add(split, BorderLayout.CENTER)

    frame.contentPane = root
    chat.refresh()
    settings.reload()
    frame.contentPane.revalidate()
    frame.contentPane.repaint()
}

/**
 * A frame-level toggle that hides one side of a split pane completely and brings it back at
 * its remembered width. A left panel hides at divider 0; a right panel hides at the far end,
 * so both directions share one remembered-width toggle.
 */
private fun sidebarToggle(
    split: JSplitPane,
    expandedWidth: Int,
    tipKey: Key,
    rightSide: Boolean
): JButton {
    var shown = expandedWidth
    var hidden = false
    return JButton(SidebarToggleIcon(rightSide = rightSide)).apply {
        toolTipText = Texts[tipKey]
        isFocusable = false
        margin = Insets(4, 6, 4, 6)
        addActionListener {
            if (!hidden) {
                shown = split.dividerLocation
                split.dividerLocation = if (rightSide) Int.MAX_VALUE else 0
            } else {
                split.dividerLocation = shown
            }
            hidden = !hidden
        }
    }
}

/**
 * Write the macOS .iconset into the directory named by --export-icons. Exits 0 on
 * success, 2 on a usage or IO error -- distinct from the smoke codes so a build
 * failure is never mistaken for a backend being unreachable.
 */
private fun exportIcons(dir: String): Int {
    if (dir.isBlank()) {
        System.err.println("usage: --export-icons=<dir>")
        return 2
    }
    return try {
        AppIcon.writeIconSet(Path.of(dir)).forEach { println("wrote $it") }
        0
    } catch (e: Exception) {
        System.err.println("icon export failed: ${e.message}")
        2
    }
}

private fun smoke(state: AppState, args: Array<String>): Int {
    println(Texts.fmt(Key.SmokeDataDir, AppState.dataDir().toAbsolutePath().toString()))

    // --model=<id> makes the smoke run scriptable for a specific model
    val wanted = args.firstOrNull { it.startsWith("--model=") }?.substringAfter('=')?.ifBlank { null }
    if (wanted != null) {
        state.saveConfig(state.config.copy(model = wanted))
        println(Texts.fmt(Key.SmokeModelSet, wanted))
    }

    val cfg = state.config
    println("base      = ${cfg.baseUrl}")
    println(Texts[Key.SmokeKeyLabel] + if (cfg.hasKey()) Texts[Key.KeyPresent] else Texts[Key.KeyMissing])
    println("model     = '${cfg.model}'")
    println(Texts.fmt(Key.SmokeSession, state.session.sessionId, state.session.messages.size))

    val models = try {
        ModelsClient.fetch(cfg)
    } catch (e: Exception) {
        println(Texts.fmt(Key.SmokeModelsFailed, e.message ?: "?"))
        return 1
    }
    println(Texts.fmt(Key.SmokeModels, models.size))
    models.take(30).forEach { println("   - ${it.id}") }
    if (models.isEmpty()) return 1

    if (args.contains("--probe")) {
        println("--- probe ---")
        CapabilityProbe().run(cfg) { println("   $it") }
    }
    return 0
}
