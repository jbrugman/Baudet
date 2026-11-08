package dev.mule.ui

import dev.mule.app.AppState
import dev.mule.i18n.Key
import dev.mule.i18n.Texts
import dev.mule.project.FolderPermission
import dev.mule.project.Permissions
import dev.mule.project.Project
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Projects and their sessions as a tree, with a compact top row (Add project)
 * and a folder-permission panel below. Sessions
 * nest under their project; any without one sit under a built-in Unassigned node,
 * so existing conversations are neither hidden nor lost.
 *
 * Two ways to act on a node: the three top buttons cover the common project
 * actions, and a right-click context menu carries the rest -- a project offers New
 * session, Rename, Archive, Link/Unlink folder and Delete, a session offers Open, Rename and
 * Delete. Picking a project starts a fresh conversation there on the right
 * ([onSelectProject]); picking a session reloads it ([onSelectSession]).
 */
class ProjectSidebar(
    private val state: AppState,
    private val onSelectSession: (String) -> Unit,
    private val onAfterDelete: () -> Unit = {},
    private val onSelectProject: (String?) -> Unit = {},
    /** Reports the project the selection now implies (null = Unassigned), for the file tree. */
    private val onSelectionChanged: (String?) -> Unit = {}
) : JPanel(BorderLayout()) {

    // Rows fill the viewport width, so a right-click anywhere on a line hits it (the blue
    // selection spans the whole line too); long names clip with an ellipsis.
    private val tree = object : JTree() {
        override fun getScrollableTracksViewportWidth(): Boolean = true
    }.apply {
        isRootVisible = false       // the invisible container node should not read as "null"
        showsRootHandles = true
    }
    private val folderLabel = JLabel(Texts[Key.FolderNone])
    private val readBox = JCheckBox(Texts[Key.PermRead])
    private val writeBox = JCheckBox(Texts[Key.PermWrite])
    private val execBox = JCheckBox(Texts[Key.PermExecute])
    private val capLabel = JLabel(" ")

    private val addBtn = button(Key.AddProjectButton, Key.TipAdd) { addProject() }

    private var selectedProjectId: String? = null
    private var selectedSessionId: String? = null
    private var selectedSessionTitle: String = ""

    /** Guards the tree/checkbox listeners while the code itself rebuilds the view. */
    private var refreshing = false

    /** True once the current click has shown the popup, so press and release do not both fire it. */
    private var popupShown = false

    init {
        border = BorderFactory.createEmptyBorder(6, 6, 6, 6)

        val buttons = JPanel(WrapLayout(FlowLayout.LEFT, 4, 0))
        buttons.add(addBtn)

        val permsRow = JPanel(WrapLayout(FlowLayout.LEFT, 8, 0))
        permsRow.add(readBox); permsRow.add(writeBox); permsRow.add(execBox)
        readBox.addActionListener { onPermToggled() }
        writeBox.addActionListener { onPermToggled() }
        execBox.addActionListener { onPermToggled() }

        val top = JPanel(BorderLayout())
        top.add(JLabel(Texts[Key.FolderLabel] + " "), BorderLayout.WEST)
        top.add(folderLabel, BorderLayout.CENTER)
        val detail = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder(Texts[Key.FolderPermissionsTitle])
            toolTipText = Texts[Key.TipPerms]
            add(top, BorderLayout.NORTH)
            add(permsRow, BorderLayout.CENTER)
            add(capLabel, BorderLayout.SOUTH)
        }

        add(buttons, BorderLayout.NORTH)
        add(JScrollPane(tree), BorderLayout.CENTER)
        add(detail, BorderLayout.SOUTH)

        tree.addTreeSelectionListener { onTreeSelection() }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                popupShown = false
                if (e.isPopupTrigger) maybePopup(e) // the platform-native press side (Linux/Windows)
            }

            override fun mouseReleased(e: MouseEvent) {
                // macOS raises the popup on release, and a two-finger trackpad tap is a
                // secondary (right) button with no press-side trigger, so catch it here.
                if (!popupShown && (e.isPopupTrigger || SwingUtilities.isRightMouseButton(e))) maybePopup(e)
            }
        })
        reload()
    }

    private fun button(labelKey: Key, tipKey: Key, action: () -> Unit): JButton =
        JButton(Texts[labelKey]).apply { toolTipText = Texts[tipKey]; addActionListener { action() } }

    /** Rebuild the whole tree from the store; projects and sessions are few, so this is cheap. */
    fun reload() {
        val previous = selectedProjectId
        // The rebuild re-selects at most the previous project, so any prior session
        // selection is gone; clear it so a conversation action stays honest.
        selectedSessionId = null
        selectedSessionTitle = ""
        refreshing = true
        val root = DefaultMutableTreeNode(null)
        val projectNodes = HashMap<String, DefaultMutableTreeNode>()
        state.projects().forEach { p ->
            val node = DefaultMutableTreeNode(ProjectNode(p))
            state.sessionsIn(p.id).forEach { node.add(DefaultMutableTreeNode(SessionNode(it.id, it.title))) }
            root.add(node)
            projectNodes[p.id] = node
        }
        val inbox = DefaultMutableTreeNode(InboxNode)
        state.sessionsIn(null).forEach { inbox.add(DefaultMutableTreeNode(SessionNode(it.id, it.title))) }
        root.add(inbox)

        tree.model = DefaultTreeModel(root)
        var row = 0
        while (row < tree.rowCount) { tree.expandRow(row); row++ } // show sessions without a click

        // Re-selecting under the [refreshing] guard keeps this from firing the genuine
        // selection path, so a rebuild never starts a chat or reloads a conversation.
        previous?.let { id -> projectNodes[id]?.let { tree.selectionPath = TreePath(it.path) } }
        refreshing = false
        updateDetail()
    }

    /** A genuine selection: record it, then drive the chat -- new chat for a project, load for a session. */
    private fun onTreeSelection() {
        if (refreshing) return
        val node = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode) ?: return
        recordSelection(node)
        when (val user = node.userObject) {
            is ProjectNode -> onSelectProject(user.project.id)
            is SessionNode -> onSelectSession(user.sessionId)
            else -> onSelectProject(null) // the Unassigned node
        }
    }

    /** Record which project/session a node means, without touching the chat panel. */
    private fun recordSelection(node: DefaultMutableTreeNode) {
        when (val user = node.userObject) {
            is ProjectNode -> {
                selectedProjectId = user.project.id
                selectedSessionId = null
                selectedSessionTitle = ""
                state.currentProjectId = user.project.id
            }
            is SessionNode -> {
                val parent = (node.parent as? DefaultMutableTreeNode)?.userObject as? ProjectNode
                selectedProjectId = parent?.project?.id
                selectedSessionId = user.sessionId
                selectedSessionTitle = user.title
                state.currentProjectId = selectedProjectId
            }
            else -> { selectedProjectId = null; selectedSessionId = null; selectedSessionTitle = "" }
        }
        updateDetail()
        onSelectionChanged(selectedProjectId)
    }

    // ---- right-click context menu ---------------------------------------

    private fun maybePopup(e: MouseEvent) {
        // Swing only matches a point over the node text, so a click on the empty part of a
        // full-width row misses; fall back to the row whose vertical span holds the click.
        val path = tree.getPathForLocation(e.x, e.y) ?: rowPathByY(e.y) ?: return
        // Select quietly so the menu targets this node without the selection listener
        // starting a new chat or reloading a conversation behind the user's back.
        refreshing = true
        tree.selectionPath = path
        refreshing = false
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        recordSelection(node)
      buildPopup(node).show(tree, e.x, e.y)
        popupShown = true
        e.consume()
    }

    /** The row whose vertical span contains y, whatever its text width; null when y is off-tree. */
    private fun rowPathByY(y: Int): TreePath? {
        for (r in 0 until tree.rowCount) {
            val b = tree.getRowBounds(r) ?: continue
            if (y in b.y until b.y + b.height) return tree.getPathForRow(r)
        }
        return null
    }

    private fun buildPopup(node: DefaultMutableTreeNode): JPopupMenu {
        val menu = JPopupMenu()
        when (val user = node.userObject) {
            is ProjectNode -> {
                val p = user.project
                menu.add(item(Key.NewSessionButton) { addSession(p.id) })
                menu.add(item(Key.RenameButton) { renameProject() })
                menu.add(item(if (p.archived) Key.UnarchiveProjectButton else Key.ArchiveProjectButton) { toggleArchive() })
                menu.add(item(Key.LinkFolderButton) { linkFolder() })
                if (p.folderPath != null) menu.add(item(Key.UnlinkFolderButton) { unlinkFolder() })
                menu.addSeparator()
                menu.add(item(Key.DeleteProjectButton) { deleteProject() })
            }
            is SessionNode -> {
                menu.add(item(Key.OpenSessionButton) { onSelectSession(user.sessionId) })
                menu.add(item(Key.RenameButton) { renameConversation() })
                menu.add(item(Key.DeleteConversationButton) { deleteConversation() })
            }
            else -> menu.add(item(Key.NewSessionButton) { addSession(null) }) // the Unassigned node
        }
        return menu
    }

    private fun item(labelKey: Key, action: () -> Unit): JMenuItem =
        JMenuItem(Texts[labelKey]).apply { addActionListener { action() } }

    /** Start a conversation inside a project from the menu; persist it so it shows up in the tree. */
    private fun addSession(projectId: String?) {
        val s = state.newSession(projectId)
        state.currentProjectId = projectId
        reload()
        selectSessionNode(s.sessionId)
        onSelectSession(s.sessionId)
    }

    private fun selectedProject(): Project? =
        selectedProjectId?.let { id -> state.projects().firstOrNull { it.id == id } }

    private fun updateDetail() {
        val p = selectedProject()
        addBtn.isEnabled = true

        if (p == null) {
            folderLabel.text = Texts[Key.FolderNone]
            capLabel.text = " "
            setPermChecks(0, enabled = false)
            return
        }
        folderLabel.text = p.folderPath ?: Texts[Key.FolderNone]
        setPermChecks(p.permissions, enabled = p.folderPath != null)
        capLabel.text = p.folderPath?.let { diskCapability(it) } ?: " "
    }

    private fun setPermChecks(bits: Int, enabled: Boolean) {
        refreshing = true
        readBox.isEnabled = enabled; writeBox.isEnabled = enabled; execBox.isEnabled = enabled
        readBox.isSelected = Permissions.has(bits, FolderPermission.Read)
        writeBox.isSelected = Permissions.has(bits, FolderPermission.Write)
        execBox.isSelected = Permissions.has(bits, FolderPermission.Execute)
        refreshing = false
    }

    private fun onPermToggled() {
        if (refreshing) return
        val p = selectedProject() ?: return
        val path = p.folderPath ?: return
        var bits = 0
        if (readBox.isSelected) bits = Permissions.with(bits, FolderPermission.Read, true)
        if (writeBox.isSelected) bits = Permissions.with(bits, FolderPermission.Write, true)
        if (execBox.isSelected) bits = Permissions.with(bits, FolderPermission.Execute, true)
        state.linkFolder(p.id, path, bits)
        capLabel.text = diskCapability(path)
    }

    private fun diskCapability(path: String): String {
        val p = Paths.get(path)
        val exists = Files.exists(p)
        return Texts[Key.DiskCapPrefix] +
            capWord(Texts[Key.CapReadable], exists && Files.isReadable(p)) + "  " +
            capWord(Texts[Key.CapWritable], exists && Files.isWritable(p)) + "  " +
            capWord(Texts[Key.CapExecutable], exists && Files.isExecutable(p))
    }

    private fun capWord(name: String, ok: Boolean): String =
        "$name=${if (ok) Texts[Key.Yes] else Texts[Key.No]}"

    private fun addProject() {
        val name = JOptionPane.showInputDialog(this, Texts[Key.ProjectNamePrompt]) ?: return
        if (name.isBlank()) return
        val created = state.addProject(name)
        state.currentProjectId = created.id
        reload()
        selectProjectNode(created.id)
    }

    private fun renameProject() {
        val p = selectedProject() ?: return
        val name = JOptionPane.showInputDialog(this, Texts[Key.RenameProjectPrompt], p.name) ?: return
        if (name.isBlank()) return
        state.renameProject(p.id, name)
        reload()
        selectProjectNode(p.id)
    }

    private fun toggleArchive() {
        val p = selectedProject() ?: return
        state.setProjectArchived(p.id, !p.archived)
        reload()
        selectProjectNode(p.id)
    }

    /** Delete the project and every conversation inside it; files on disk are left untouched. */
    private fun deleteProject() {
        val p = selectedProject() ?: return
        val chats = state.sessionsIn(p.id).size
        val answer = JOptionPane.showConfirmDialog(
            this, Texts.fmt(Key.DeleteProjectConfirm, p.name, chats),
            Texts[Key.DeleteProjectButton], JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
        )
        if (answer != JOptionPane.YES_OPTION) return
        state.deleteProject(p.id)
        selectedProjectId = null
        reload()
        onAfterDelete()
    }

    /** Delete the selected conversation and its messages; the project and its folder are untouched. */
    private fun deleteConversation() {
        val id = selectedSessionId ?: return
        val answer = JOptionPane.showConfirmDialog(
            this, Texts.fmt(Key.DeleteConversationConfirm, selectedSessionTitle),
            Texts[Key.DeleteConversationButton], JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
        )
        if (answer != JOptionPane.YES_OPTION) return
        state.deleteSession(id)
        selectedSessionId = null
        reload()
        onAfterDelete()
    }

    /** Rename the selected conversation; a blank entry reverts to the automatic title. */
    private fun renameConversation() {
        val id = selectedSessionId ?: return
        val name = JOptionPane.showInputDialog(this, Texts[Key.RenameSessionPrompt], selectedSessionTitle) ?: return
        state.renameSession(id, name)
        reload()
        selectSessionNode(id)
    }

    private fun linkFolder() {
        val p = selectedProject() ?: return
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = Texts[Key.LinkFolderButton]
            p.folderPath?.let { val f = File(it); if (f.isDirectory) selectedFile = f }
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val path = chooser.selectedFile.absolutePath
        // A first link grants Read; later toggles keep whatever the user already chose.
        val bits = if (p.permissions == 0) Permissions.encode(setOf(FolderPermission.Read)) else p.permissions
        state.linkFolder(p.id, path, bits)
        reload()
        selectProjectNode(p.id)
    }

    private fun unlinkFolder() {
        val p = selectedProject() ?: return
        state.linkFolder(p.id, null, 0)
        reload()
        selectProjectNode(p.id)
    }

    // Quiet selection helpers: they move the highlight and record state without going
    // through the tree listener, so a programmatic re-select never starts a chat.

    private fun selectProjectNode(id: String) {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as DefaultMutableTreeNode
            if ((child.userObject as? ProjectNode)?.project?.id == id) {
                selectQuietly(child)
                return
            }
        }
    }

    private fun selectSessionNode(id: String) {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return
        for (i in 0 until root.childCount) {
            val parent = root.getChildAt(i) as DefaultMutableTreeNode
            for (j in 0 until parent.childCount) {
                val child = parent.getChildAt(j) as DefaultMutableTreeNode
                if ((child.userObject as? SessionNode)?.sessionId == id) {
                    selectQuietly(child)
                    return
                }
            }
        }
    }

    private fun selectQuietly(node: DefaultMutableTreeNode) {
        refreshing = true
        tree.selectionPath = TreePath(node.path)
        refreshing = false
        recordSelection(node)
    }

    private class ProjectNode(val project: Project) {
        override fun toString(): String =
            project.name + if (project.archived) " (" + Texts[Key.ArchivedSuffix] + ")" else ""
    }

    private class SessionNode(val sessionId: String, val title: String) {
        override fun toString(): String = title
    }

    private object InboxNode {
        override fun toString(): String = Texts[Key.InboxNode]
    }
}
