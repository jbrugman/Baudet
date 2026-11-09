package dev.mule.ui

import dev.mule.app.AppState
import dev.mule.fs.FileEntry
import dev.mule.fs.FileLister
import dev.mule.git.ChangeRow
import dev.mule.git.ChangesList
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
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
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
 *
 * The panel is split vertically: the tree keeps the top, so the project structure stays in
 * view, and a flat list of only the added, changed and deleted files sits below it behind a
 * draggable bar. Both halves read the same git snapshot and are refilled by the same Refresh,
 * so the list below can never disagree with the colors above it. Where that bar sits is
 * remembered in the stored config (filesDivider), alongside the two window split widths.
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

    // ---- the change list below the tree --------------------------------------

    private val changesHeader = JLabel(" ")
    private val changeRows = DefaultListModel<ChangeRow>()
    private val changeList = ChangeListView(changeRows)

    /** Tree on top, change list below. Public so the frame can read and remember this divider. */
    val changesSplit: JSplitPane = JSplitPane(
        JSplitPane.VERTICAL_SPLIT,
        JScrollPane(tree),
        JPanel(BorderLayout()).apply {
            add(changesHeader, BorderLayout.NORTH)
            add(JScrollPane(changeList), BorderLayout.CENTER)
        }
    )

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

        changesSplit.apply {
            isContinuousLayout = true
            isOneTouchExpandable = false    // the two little arrows add nothing here; the bar drags
            resizeWeight = 0.5
            // Both halves collapse to nothing on purpose: the panel as a whole is already
            // hideable from the toolbar, and a minimum here would fight that toggle.
            leftComponent.minimumSize = Dimension(0, 0)
            rightComponent.minimumSize = Dimension(0, 0)
            dividerLocation = WindowLayout.resolveDivider(null, state.config.filesDivider, CHANGES_HEIGHT)
        }
        changeList.cellRenderer = ChangeRenderer()
        changeList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        changeList.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                popupShown = false
                if (e.isPopupTrigger) maybePopupRows(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                // Same macOS rule as the tree: the popup arrives on release only.
                if (!popupShown && (e.isPopupTrigger || SwingUtilities.isRightMouseButton(e))) maybePopupRows(e)
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount < 2 || !SwingUtilities.isLeftMouseButton(e)) return
                val row = rowAt(e) ?: return
                openChange(row)
            }
        })
        changesHeader.toolTipText = Texts[Key.TipFileTreeChanges]

        cards.add(changesSplit, CARD_TREE)
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
        changeRows.clear()
        changesHeader.text = " "
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

    /** Rebuild both halves: re-read git status, then the first tree level and the change list. */
    private fun rebuild() {
        val path = rootPath ?: return
        gitStatus = git.status(path)
        val root = makeDirNode(rootName, path)
        tree.model = DefaultTreeModel(root)
        loadChildren(root, path)
        tree.expandRow(0)
        rebuildChanges()
    }

    /**
     * Refill the change list from the snapshot [rebuild] just read, so the list and the tree
     * colors are always derived from one git call. The header carries the count, or the reason
     * there is nothing to count.
     */
    private fun rebuildChanges() {
        val rows = ChangesList.rows(gitStatus)
        changeRows.clear()
        rows.forEach { changeRows.addElement(it) }
        changesHeader.text = when {
            !gitStatus.isRepository -> Texts[Key.FileTreeChangesNotGit]
            rows.isEmpty() -> Texts[Key.FileTreeChangesClean]
            else -> Texts.fmt(Key.FileTreeChangesHeader, rows.size)
        }
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

    /** The colour that carries a git state, or null for unchanged and for a directory. */
    private fun colorFor(change: FileChange?): Color? = when (change) {
        FileChange.Added, FileChange.Modified, FileChange.Renamed -> COLOR_ADDED
        FileChange.Deleted -> COLOR_DELETED
        FileChange.Untracked -> COLOR_UNTRACKED
        else -> null
    }

    /** The word naming a git state for a tooltip, or null when there is nothing to name. */
    private fun tagFor(change: FileChange?): String? = when (change) {
        FileChange.Added, FileChange.Renamed -> Texts[Key.GitTagAdded]
        FileChange.Modified -> Texts[Key.GitTagModified]
        FileChange.Deleted -> Texts[Key.GitTagDeleted]
        FileChange.Untracked -> Texts[Key.GitTagUntracked]
        else -> null
    }

    /** Open the git patch for a tree row, or the reason there is nothing to show. */
    private fun openDiff(file: FileNode) {
        val path = file.path ?: return
        val rel = gitRel(path)
        if (rel.isEmpty()) return
        openPatch(rel, path, changeFor(path))
    }

    /**
     * Open the git patch for a row of the change list. The path is taken from git rather than
     * resolved back through the tree on purpose: relativizing it again would turn git's slashes
     * into backslashes on Windows and then miss the very change this row stands for.
     */
    private fun openChange(row: ChangeRow) {
        val root = rootPath ?: return
        openPatch(row.relPath, root.resolve(row.relPath), row.change)
    }

    /** Show the patch for [rel], or why there is none: no repository, untracked, or unchanged. */
    private fun openPatch(rel: String, path: Path, change: FileChange?) {
        val root = rootPath ?: return
        if (!gitStatus.isRepository) {
            DiffViewDialog.open(
                tree, Texts.fmt(Key.DiffTitle, rel), Texts[Key.DiffNoGit],
                state.config.diffPopupX, state.config.diffPopupY,
                state.config.diffPopupWidth, state.config.diffPopupHeight,
                onClosed = { x, y, w, h -> state.updateDiffPopupBounds(x, y, w, h) }
            )
            return
        }
        val patch = git.diffPatch(root, rel)
        var plain: String? = null
        val body = when {
            patch.isNotEmpty() -> patch
            change == FileChange.Untracked -> newFileBody(rel, path)
            else -> {
                // Nothing changed, so there is no patch: the message says so and the file itself
                // follows below in the aside gray, which is what makes the dialog worth opening.
                plain = NewFileDiff.plain(readBytesOrNull(path))
                Texts.fmt(Key.DiffNoChanges, rel)
            }
        }
        DiffViewDialog.open(
            tree, Texts.fmt(Key.DiffTitle, rel), body,
            state.config.diffPopupX, state.config.diffPopupY,
            state.config.diffPopupWidth, state.config.diffPopupHeight,
            plainBody = plain,
            onClosed = { x, y, w, h -> state.updateDiffPopupBounds(x, y, w, h) }
        )
    }

    /** The stand-in for an untracked file, which has no committed version to diff against. */
    private fun newFileBody(rel: String, path: Path): String =
        NewFileDiff.body(rel, Texts.fmt(Key.DiffUntracked, rel), readBytesOrNull(path))

    /** The bytes of [path] when it is a regular file, else null so [NewFileDiff] keeps just the message. */
    private fun readBytesOrNull(path: Path): ByteArray? =
        if (Files.isRegularFile(path)) runCatching { Files.readAllBytes(path) }.getOrNull() else null

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

    /**
     * The change row under the pointer, or null on the empty area below the last row --
     * locationToIndex happily answers with the nearest index there, so the cell bounds settle it.
     */
    private fun rowAt(e: MouseEvent): ChangeRow? {
        val i = changeList.locationToIndex(e.point)
        if (i < 0 || i >= changeList.model.size) return null
        val bounds = changeList.getCellBounds(i, i) ?: return null
        if (!bounds.contains(e.point)) return null
        return changeList.model.getElementAt(i)
    }

    // ---- right-click context menu -----------------------------------------

    private fun maybePopup(e: MouseEvent) {
        val path = resolvePath(e) ?: return
        tree.selectionPath = path
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        copyPathPopup((node.userObject as? FileNode)?.path).show(tree, e.x, e.y)
        popupShown = true
        e.consume()
    }

    /** The same menu for the change list, whose rows are paths from git rather than tree nodes. */
    private fun maybePopupRows(e: MouseEvent) {
        val row = rowAt(e) ?: return
        changeList.selectedIndex = changeList.locationToIndex(e.point)
        val root = rootPath ?: return
        copyPathPopup(root.resolve(row.relPath)).show(changeList, e.x, e.y)
        popupShown = true
        e.consume()
    }

    /** The one-item menu; it copies nothing rather than an empty string when there is no path. */
    private fun copyPathPopup(path: Path?): JPopupMenu {
        val menu = JPopupMenu()
        val item = JMenuItem(Texts[Key.CopyPath])
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
            return tagFor(changeFor(path))
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
                    colorFor(changeFor(file.path))?.let { foreground = it }
                }
            }
            return this
        }
    }

    /**
     * The change list proper. It spells its rows with a porcelain letter and the relative path,
     * so it reads like `git status` in a terminal, and the tooltip names the state -- the same
     * rule the tree above uses. A JList has no tooltip of its own, so the point decides it.
     */
    private inner class ChangeListView(model: DefaultListModel<ChangeRow>) : JList<ChangeRow>(model) {
        override fun getToolTipText(e: MouseEvent): String? {
            val row = rowAt(e) ?: return null
            return tagFor(row.change)?.let { "${ChangesList.letter(row.change)}  $it" }
        }
    }

    /** Monospaced so the letter column lines up, colored by the same table as the tree. */
    private inner class ChangeRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
            val row = value as? ChangeRow ?: return label
            label.text = "${ChangesList.letter(row.change)}  ${row.relPath}"
            label.font = Font(Font.MONOSPACED, Font.PLAIN, label.font.size)
            if (!isSelected) colorFor(row.change)?.let { label.foreground = it }
            return label
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

        /** How much room the change list gets until the user drags the bar or stores another. */
        const val CHANGES_HEIGHT = 200

        // GitHub-ish: additions green, deletions red, untracked a soft blue.
        val COLOR_ADDED = Color(46, 160, 67)
        val COLOR_DELETED = Color(210, 70, 70)
        val COLOR_UNTRACKED = Color(130, 170, 220)
    }
}
