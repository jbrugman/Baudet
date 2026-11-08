package dev.mule.ui

import dev.mule.app.AppState
import dev.mule.fs.FileEntry
import dev.mule.fs.FileLister
import dev.mule.i18n.Key
import dev.mule.i18n.Texts
import dev.mule.project.FolderPermission
import java.awt.BorderLayout
import java.awt.CardLayout
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
 * A right-click anywhere on a row offers [Key.CopyPath], which copies the path
 * relative to the project folder onto the clipboard. There is no editor yet, so the
 * panel stays a faithful read-only view of the structure.
 */
class FileTreePanel(private val state: AppState) : JPanel(BorderLayout()) {

    // Rows fill the viewport width, so a right-click anywhere on a line hits it (the blue
    // selection spans the whole line too); long names clip with an ellipsis.
    private val tree = object : JTree() {
        override fun getScrollableTracksViewportWidth(): Boolean = true
    }.apply {
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

    /** Rebuild the whole tree from the project root; only its first level is read. */
    private fun rebuild() {
        val path = rootPath ?: return
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
        if (entries.isEmpty()) {
            node.add(DefaultMutableTreeNode(FileNode(null, Texts[Key.FileTreeEmpty], false)))
        } else {
            entries.forEach { node.add(childNode(it)) }
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

    // ---- right-click context menu -----------------------------------------

    private fun maybePopup(e: MouseEvent) {
        // Swing only matches a point over the node text, so a click on the empty part of a
        // full-width row misses; fall back to the row whose vertical span holds the click.
        val path = tree.getPathForLocation(e.x, e.y) ?: rowPathByY(e.y) ?: return
        tree.selectionPath = path
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
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
    }
}
