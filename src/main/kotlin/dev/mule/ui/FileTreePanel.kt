package dev.mule.ui

import dev.mule.app.AppState
import dev.mule.fs.FileEntry
import dev.mule.fs.FileLister
import dev.mule.git.FileChange
import dev.mule.git.GitInspector
import dev.mule.git.GitStatus
import dev.mule.i18n.Key
import dev.mule.i18n.Texts
import dev.mule.project.FolderPermission
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * The project file tree, shown on the right: the folder structure of the currently
 * selected project, mirroring the left sidebar. The listing is read live from disk --
 * nothing about it is stored -- so it always reflects the folder as it is now.
 *
 * Directories load lazily: a folder reports a single placeholder child to get an
 * expand handle and only reads its own contents when the user opens it. A large
 * project is therefore never walked in one pass. The tree follows the project of
 * whatever is selected on the left ([showProject]), and it only populates when that
 * project has a linked folder with Read granted -- the one right this app enforces.
 *
 * When the folder is a git repository each file row carries its change state: added or
 * changed files are green, deleted ones red (shown as a ghost row even though the file is
 * gone from disk), untracked ones blue, and the tooltip names the state. Double-clicking a
 * file opens its git patch in [DiffViewDialog]. A right-click anywhere on a row offers
 * [Key.CopyPath]. There is still no editor, so the panel stays a read-only view.
 */
class FileTreePanel(private val state: AppState) : JPanel(BorderLayout()) {

    private val git = GitInspector()

    /** The current change snapshot; refreshed on load and on Refresh. */
    private var gitStatus: GitStatus = GitStatus.NOT_A_REPO

    // Rows fill the viewport width, so a right-click anywhere on a line hits it (the blue
    // selection spans the whole line too); long names clip with an ellipsis. A tooltip names
    // a file's git change wherever the pointer is over the row.
    private val tree = MuleTree().apply {
        isRootVisible = true       // the root is the project folder itself, a useful label
        showsRootHandles = true
    }

    private val message = JLabel(" ")
    private val cards = JPanel(CardLayout())
    private val hiddenBox = JCheckBox(Texts[Key.FileTreeHidden])
    private val refreshBtn = JButton(Texts[Key.FileTreeRefreshButton])

    private var rootPath: Path? = null
    private var rootName: String = ""

    /** True once the current click has shown the popup, so press and release do not both fire it. */
    private var popupShown = false

    init {
        border = BorderFactory.createEmptyBorder(6, 6, 6, 6)

        val controls = JPanel(WrapLayout(FlowLayout.LEFT, 8, 0))
        refreshBtn.toolTipText = Texts[Key.TipFileTreeRefresh]
        refreshBtn.isFocusable = false
        refreshBtn.addActionListener { rebuild() }
        hiddenBox.addActionListener { rebuild() }
        controls.add(refreshBtn)
        controls.add(hiddenBox)

        cards.add(JScrollPane(tree), CARD_TREE)
        cards.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(message) }, CARD_MESSAGE)

        add(controls, BorderLayout.NORTH)
        add(cards, BorderLayout.CENTER)

        tree.cellRenderer = CellRenderer()
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(e: TreeExpansionEvent) = loadOnExpand(e.path)
            override fun treeCollapsed(e: TreeExpansionEvent) {}
        })
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

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount < 2 || !SwingUtilities.isLeftMouseButton(e)) return
                val node = fileNodeAt(e) ?: return
                if (node.path != null && !node.isDirectory) openDiff(node)
            }
        })

        // Start empty and on the message card: a bare JTree ships a sample model (the
        // "colors / sports / food" nodes), which must never flash, and no project is
        // selected until the sidebar reports one.
        tree.model = DefaultTreeModel(DefaultMutableTreeNode(""))
        showMessage(Texts[Key.FileTreeNoFolder])
    }

    /** Point the tree at a project's folder, or explain why it cannot: no folder, or no Read. */
    fun showProject(projectId: String?) {
        val project = projectId?.let { id -> state.projects().firstOrNull { it.id == id } }
        val folder = project?.folderPath
        when {
            project == null || folder == null -> showMessage(Texts[Key.FileTreeNoFolder])
            !project.isGranted(FolderPermission.Read) -> showMessage(Texts[Key.FileTreeNoRead])
            else -> loadRoot(project.name, Paths.get(folder))
        }
    }

    private fun showMessage(text: String) {
        rootPath = null
        gitStatus = GitStatus.NOT_A_REPO
        message.text = "<html><body style='width:200px'>${escape(text)}</body></html>"
        refreshBtn.isEnabled = false
        hiddenBox.isEnabled = false
        (cards.layout as CardLayout).show(cards, CARD_MESSAGE)
    }

    private fun loadRoot(name: String, path: Path) {
        rootName = name
        rootPath = path
        refreshBtn.isEnabled = true
        hiddenBox.isEnabled = true
        rebuild()
        (cards.layout as CardLayout).show(cards, CARD_TREE)
    }

    /** Rebuild the whole tree from the project root; re-reads git status, then reads the first level. */
    private fun rebuild() {
        val path = rootPath ?: return
        gitStatus = git.status(path)
        val root = makeDirNode(rootName, path)
        tree.model = DefaultTreeModel(root)
        loadChildren(root, path)
        tree.expandRow(0)
    }

    // ---- lazy directory loading -------------------------------------------

    private fun loadOnExpand(path: TreePath) {
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val node0 = node.userObject as? FileNode ?: return
        if (!node0.isDirectory || node0.path == null) return
        if (!isUnloaded(node)) return // already populated
        loadChildren(node, node0.path)
    }

    private fun loadChildren(node: DefaultMutableTreeNode, dir: Path) {
        node.removeAllChildren()
        val entries = FileLister.list(dir, hiddenBox.isSelected)
        val listed = entries.filterNot { it.isDirectory }.map { it.name }.toSet()
        // Show files git reports deleted here but that are no longer on disk, so the change is visible.
        val deleted = gitStatus.deletedUnder(gitRel(dir)).filterNot { it in listed }
        if (entries.isEmpty() && deleted.isEmpty()) {
            node.add(DefaultMutableTreeNode(FileNode(null, Texts[Key.FileTreeEmpty], false)))
        } else {
            entries.forEach { node.add(childNode(it)) }
            deleted.forEach { node.add(DefaultMutableTreeNode(FileNode(dir.resolve(it), it, false))) }
        }
        (tree.model as? DefaultTreeModel)?.nodeStructureChanged(node)
    }

    private fun childNode(entry: FileEntry): DefaultMutableTreeNode =
        if (entry.isDirectory) makeDirNode(entry.name, entry.path)
        else DefaultMutableTreeNode(FileNode(entry.path, entry.name, false))

    /** A directory node that shows an expand handle by carrying one placeholder child. */
    private fun makeDirNode(name: String, path: Path): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(FileNode(path, name, true))
        node.add(placeholder())
        return node
    }

    private fun placeholder(): DefaultMutableTreeNode =
        DefaultMutableTreeNode(FileNode(null, "", false))

    /** True when the node still holds only the placeholder, so it has not been read yet. */
    private fun isUnloaded(node: DefaultMutableTreeNode): Boolean =
        node.childCount == 1 && (node.getChildAt(0) as? DefaultMutableTreeNode)?.userObject?.let {
            (it as? FileNode)?.path == null
        } == true

    // ---- git helpers -------------------------------------------------------

    /** The path relative to the project folder as git and the tree both use it; empty for the root. */
    private fun gitRel(path: Path): String {
        val root = rootPath ?: return ""
        if (path == root) return ""
        return root.relativize(path).toString()
    }

    /** The change git sees for a file path, or null when it is the root, has no root, or is unchanged. */
    private fun changeFor(path: Path): FileChange? {
        val rel = gitRel(path)
        if (rel.isEmpty()) return null
        return gitStatus.changeOf(rel)
    }

    /** Open the git patch for a file, or an explanation of why there is nothing to show. */
    private fun openDiff(file: FileNode) {
        val path = file.path ?: return
        val root = rootPath ?: return
        val rel = gitRel(path)
        if (rel.isEmpty()) return
        if (!gitStatus.isRepository) {
            DiffViewDialog.open(tree, Texts.fmt(Key.DiffTitle, rel), Texts[Key.DiffNoGit])
            return
        }
        val patch = git.diffPatch(root, rel)
        val body = when {
            patch.isNotEmpty() -> patch
            changeFor(path) == FileChange.Untracked -> Texts.fmt(Key.DiffUntracked, rel)
            else -> Texts.fmt(Key.DiffNoChanges, rel)
        }
        DiffViewDialog.open(tree, Texts.fmt(Key.DiffTitle, rel), body)
    }

    // ---- pointer resolution ------------------------------------------------

    /** The row at the pointer; Swing misses the empty part of a full-width row, so fall back to y. */
    private fun resolvePath(e: MouseEvent): TreePath? =
        tree.getPathForLocation(e.x, e.y) ?: rowPathByY(e.y)

    private fun fileNodeAt(e: MouseEvent): FileNode? {
        val p = resolvePath(e) ?: return null
        val node = p.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return node.userObject as? FileNode
    }

    /** The row whose vertical span contains y, whatever its text width; null when y is off-tree. */
    private fun rowPathByY(y: Int): TreePath? {
        for (r in 0 until tree.rowCount) {
            val b = tree.getRowBounds(r) ?: continue
            if (y in b.y until b.y + b.height) return tree.getPathForRow(r)
        }
        return null
    }

    // ---- right-click context menu -----------------------------------------

    private fun maybePopup(e: MouseEvent) {
        val path = resolvePath(e) ?: return
        tree.selectionPath = path
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        buildPopup(node).show(tree, e.x, e.y)
        popupShown = true
        e.consume()
    }

    private fun buildPopup(node: DefaultMutableTreeNode): JPopupMenu {
        val menu = JPopupMenu()
        val item = JMenuItem(Texts[Key.CopyPath])
        val path = (node.userObject as? FileNode)?.path
        item.isEnabled = path != null
        item.addActionListener { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(displayPath(path)), null) }
        menu.add(item)
        return menu
    }

    /** The path relative to the project folder; the folder itself copies as ".". */
    private fun displayPath(path: Path?): String {
        val root = rootPath ?: return path?.toString().orEmpty()
        if (path == root) return "."
        return root.relativize(path).toString()
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ---- rendering ----------------------------------------------------------

    /** The tree proper: full-width rows plus a tooltip naming a file's git change under the pointer. */
    private inner class MuleTree : JTree() {
        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getToolTipText(e: MouseEvent): String? {
            val node = fileNodeAt(e) ?: return null
            val path = node.path ?: return null
            if (node.isDirectory) return null
            return when (changeFor(path)) {
                FileChange.Added, FileChange.Renamed -> Texts[Key.GitTagAdded]
                FileChange.Modified -> Texts[Key.GitTagModified]
                FileChange.Deleted -> Texts[Key.GitTagDeleted]
                FileChange.Untracked -> Texts[Key.GitTagUntracked]
                else -> null
            }
        }
    }

    /** The default tree look, with only the text colour changed to carry the git state of a file. */
    private inner class CellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree?,
            value: Any?,
            isSelected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ): Component {
            super.getTreeCellRendererComponent(tree, value, isSelected, expanded, leaf, row, hasFocus)
            if (!isSelected) {
                val node = value as? DefaultMutableTreeNode
                val file = node?.userObject as? FileNode
                if (file != null && file.path != null && !file.isDirectory) {
                    when (changeFor(file.path)) {
                        FileChange.Added, FileChange.Modified, FileChange.Renamed -> foreground = COLOR_ADDED
                        FileChange.Deleted -> foreground = COLOR_DELETED
                        FileChange.Untracked -> foreground = COLOR_UNTRACKED
                        else -> Unit
                    }
                }
            }
            return this
        }
    }

    private class FileNode(
        val path: Path?,        // null only for the placeholder and the "empty folder" marker
        val name: String,
        val isDirectory: Boolean
    ) {
        override fun toString(): String = name
    }

    private companion object {
        const val CARD_TREE = "tree"
        const val CARD_MESSAGE = "message"

        // GitHub-ish: additions green, deletions red, untracked a soft blue.
        val COLOR_ADDED = Color(46, 160, 67)
        val COLOR_DELETED = Color(210, 70, 70)
        val COLOR_UNTRACKED = Color(130, 170, 220)
    }
}
