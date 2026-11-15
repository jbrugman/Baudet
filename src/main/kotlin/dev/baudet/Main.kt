package dev.baudet

import com.formdev.flatlaf.FlatDarkLaf
import dev.baudet.api.CapabilityProbe
import dev.baudet.api.ModelsClient
import dev.baudet.app.AppState
import dev.baudet.config.AppConfig
import dev.baudet.i18n.Key
import dev.baudet.i18n.Texts
import dev.baudet.ui.AppIcon
import dev.baudet.ui.AboutDialog
import dev.baudet.ui.BaudetPalette
import dev.baudet.ui.HelpDialog
import dev.baudet.ui.ChatPanel
import dev.baudet.ui.FileTreePanel
import dev.baudet.ui.ProjectSidebar
import dev.baudet.ui.ModelsPanel
import dev.baudet.ui.SettingsPanel
import dev.baudet.ui.SidebarToggleIcon
import dev.baudet.ui.WindowLayout
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Insets
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
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
    BaudetPalette.install()

    SwingUtilities.invokeLater {
        val frame = JFrame("Baudet · local coding-assistant")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        applySavedSize(frame, state.config)
        frame.iconImages = AppIcon.images()

        var shown = Texts.language
        var layout = show(state, frame, null)

        // Settings is the only place the language changes, and only through its own
        // button, so this cannot loop: the panels are rebuilt exactly when the
        // persisted value actually differs from the language on screen. A rebuild keeps
        // the split widths the user just had instead of snapping them back to defaults.
        state.onConfigSaved = {
            if (Texts.language != shown) {
                shown = Texts.language
                val previous = layout
                SwingUtilities.invokeLater { layout = show(state, frame, previous) }
            }
        }

        // Remember the window and both split widths when the window closes. A collapsed
        // side stays out of the saved geometry, so reopening never restores a hidden panel.
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                state.updateWindowGeometry(
                    frame.x, frame.y, frame.width, frame.height,
                    clampedDivider(layout.left, state.config.leftDivider),
                    clampedDivider(layout.right, state.config.rightDivider),
                    clampedDivider(layout.files.changesSplit, state.config.filesDivider)
                )
            }
        })

        frame.isVisible = true
        // Position is restored only after the window is shown: on macOS a location set
        // before the native peer exists is ignored, so the window kept landing at the same
        // cascade point each launch even though its size remembered fine.
        applySavedLocation(frame, state.config)
    }
}

/**
 * The live split panes plus the file panel, kept so a rebuild can carry their current sizes
 * across. The file panel rides along for the sake of the bar inside it: the split between the
 * tree and the change list is the panel's own business, but its height belongs to the layout.
 */
private data class MainLayout(val left: JSplitPane, val right: JSplitPane, val files: FileTreePanel)

/** (Re)build every panel in the active language and put it on screen. */
private fun show(state: AppState, frame: JFrame, previous: MainLayout?): MainLayout {
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
    val files = FileTreePanel(state).apply {
        minimumSize = Dimension(0, 0)
        // The panel already resolved its own height from the stored value; a rebuild carries the
        // live position over it, exactly as the two window splits do.
        val chosen = changesSplit.dividerLocation
        changesSplit.dividerLocation = WindowLayout.resolveDivider(
            previous?.files?.changesSplit?.dividerLocation, 0, chosen
        )
    }

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
        dividerLocation = WindowLayout.resolveDivider(previous?.left?.dividerLocation, state.config.leftDivider, 280)
        resizeWeight = 0.0            // the sidebar keeps its width; the chat absorbs resizing,
                                     // so hiding the file tree never widens the sidebar
        isContinuousLayout = true
        isOneTouchExpandable = false   // replaced by the toolbar toggle, which stays put when collapsed
    }
    val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, files).apply {
        dividerLocation = WindowLayout.resolveDivider(previous?.right?.dividerLocation, state.config.rightDivider, 720)
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
    frame.jMenuBar = helpMenu(frame, tabs)
    chat.refresh()
    settings.reload()
    frame.contentPane.revalidate()
    frame.contentPane.repaint()
    return MainLayout(left, split, files)
}

/**
 * Size the frame from saved geometry before the panels are built, so the split dividers
 * lay out against the width the user left the window at. A zero size means nothing was
 * stored, which is the first launch, so it opens at a centered default. Placement is kept
 * separate and applied after the window is shown, in [applySavedLocation]. No maximized
 * state is kept: restoring one behaves inconsistently across window managers.
 */
private fun applySavedSize(frame: JFrame, config: AppConfig) {
    val width = config.windowWidth
    val height = config.windowHeight
    frame.size = if (width <= 0 || height <= 0) Dimension(1040, 720) else Dimension(width, height)
}

/**
 * Place the frame where the user left it, once it is displayable. Run after the window is
 * shown, because a location set before the native peer exists is ignored on macOS and the
 * window would otherwise reopen at the same default spot every launch. A remembered spot
 * that no longer lands inside a screen, or none stored at all, falls back to center.
 */
private fun applySavedLocation(frame: JFrame, config: AppConfig) {
    val width = config.windowWidth
    val height = config.windowHeight
    if (width <= 0 || height <= 0) {
        frame.setLocationRelativeTo(null)
        return
    }
    // Per-screen containment rather than the union of all screens: a union has no gaps, so a spot
    // a removed monitor left behind used to pass as visible and put the title bar out of reach.
    if (WindowLayout.rememberedOriginIsReachable(config.windowX, config.windowY, WindowLayout.screens()))
        frame.setLocation(config.windowX, config.windowY)
    else frame.setLocationRelativeTo(null)
}

/**
 * The extent worth saving for one split: its live divider, unless that divider is collapsed
 * or unset -- zero, or at or past the far edge -- in which case the previously stored value
 * is kept so a collapse never becomes the reopened default. A vertical split runs out of
 * height rather than width, which is what the change list under the file tree does.
 */
private fun clampedDivider(split: JSplitPane, stored: Int): Int {
    val extent = if (split.orientation == JSplitPane.VERTICAL_SPLIT) split.height else split.width
    val location = split.dividerLocation
    return if (location <= 0 || location >= extent) stored else location
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
 * A single Help menu carrying the per-tab help and the About action. The help item reads the
 * tabbed pane's selected index when clicked, so it opens guidance for whichever tab is in view;
 * rebuilding the menu with the panels makes the title and both items localize together on a
 * language switch.
 */
private fun helpMenu(owner: JFrame, tabs: JTabbedPane): JMenuBar {
    val guide = JMenuItem(Texts[Key.HelpGuide]).apply {
        addActionListener { HelpDialog.open(owner, HelpDialog.bodyKeyFor(tabs.selectedIndex)) }
    }
    val about = JMenuItem(Texts[Key.AboutTitle]).apply {
        addActionListener { AboutDialog.open(owner) }
    }
    val menu = JMenu(Texts[Key.MenuHelp]).apply { add(guide); add(about) }
    return JMenuBar().apply { add(menu) }
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
