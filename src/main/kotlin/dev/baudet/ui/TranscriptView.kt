package dev.baudet.ui

import dev.baudet.i18n.Key
import dev.baudet.i18n.Texts
import dev.baudet.session.Msg
import dev.baudet.thread.AgentMessageItem
import dev.baudet.thread.ApprovalDecision
import dev.baudet.thread.ApprovalRequest
import dev.baudet.thread.ApprovalRequested
import dev.baudet.thread.ApprovalResolved
import dev.baudet.thread.CommandExecutionItem
import dev.baudet.thread.ErrorItem
import dev.baudet.thread.FileChangeItem
import dev.baudet.thread.Item
import dev.baudet.thread.ItemCompleted
import dev.baudet.thread.ItemDelta
import dev.baudet.thread.ItemKind
import dev.baudet.thread.ItemStarted
import dev.baudet.thread.ItemStatus
import dev.baudet.thread.PlanEntryStatus
import dev.baudet.thread.PlanItem
import dev.baudet.thread.ReasoningItem
import dev.baudet.thread.ThreadEvent
import dev.baudet.thread.ToolCallItem
import dev.baudet.thread.UserMessageItem
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Insets
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextPane
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.View
import javax.swing.border.EmptyBorder

/** How one line of a unified diff reads, so a card can colour it without re-deriving the rules. */
enum class DiffLine { Added, Removed, Hunk, Header, Context }

/**
 * The words and counts a card shows, kept free of Swing so that they can be tested alone.
 *
 * These are the strings a reader judges the machine by, so the two rules here are rules about
 * honesty rather than about layout:
 *
 *  * a missing exit code is named as missing. A timeout and a command that never started are
 *    not exit code 0, and printing 0 for them would tell the reader their hardware finished
 *    something it did not finish;
 *  * the counts on a write card come from [dev.baudet.thread.DiffStats] through the item, and
 *    [diffKind] classifies a line by the same header rule, so the header and the body of a
 *    card can never disagree about how much was written.
 */
object RowText {

    /** A command reads as an action taken, not as prose the model wrote. */
    fun command(command: String): String = "$ $command"

    /**
     * A duration the reader can compare across cards. Spelled out by hand rather than through
     * [String.format], because the grouping must not depend on the JVM's default locale -- a
     * card that reads "1,2 s" on one machine and "1.2 s" on another is the same run.
     */
    fun duration(ms: Long): String = when {
        ms < 0 -> "0 ms"
        ms < 1_000 -> "$ms ms"
        ms < 60_000 -> "${ms / 1_000}.${ms % 1_000 / 100} s"
        else -> "${ms / 60_000} m ${ms % 60_000 / 1_000} s"
    }

    /**
     * The verdict on the right of a command card: what ended the run and how long it took.
     * A run that is still open says so, because a card that shows no verdict while the process
     * is still running reads as a command that produced nothing.
     */
    fun outcome(item: CommandExecutionItem): String {
        val verdict = when {
            item.status == ItemStatus.InProgress -> Texts[Key.RowRunning]
            item.status == ItemStatus.Interrupted -> Texts[Key.TurnStopped]
            item.timedOut -> Texts.fmt(Key.RowTimedOut, item.timeoutSeconds ?: 0)
            item.exitCode != null -> Texts.fmt(Key.RowExitCode, item.exitCode)
            else -> Texts[Key.RowNoExitCode]
        }
        return if (item.durationMs > 0L) "$verdict \u00b7 ${duration(item.durationMs)}" else verdict
    }

    /** Whether a command failed, which decides the colour of its verdict and nothing else. */
    fun commandFailed(item: CommandExecutionItem): Boolean =
        item.status == ItemStatus.Failed || item.status == ItemStatus.Interrupted ||
            item.timedOut || (item.exitCode ?: 0) != 0

    /** The head of a write card: the path, then what the write amounts to. */
    fun change(item: FileChangeItem): String {
        val mark = when {
            item.status == ItemStatus.InProgress -> Texts[Key.RowRunning]
            item.status == ItemStatus.Failed -> Texts[Key.RowRejected]
            item.status == ItemStatus.Interrupted -> Texts[Key.TurnStopped]
            else -> "+${item.additions} \u2212${item.deletions}"
        }
        return "${item.path}  $mark"
    }

    /** The head of a card for a tool with no card of its own: `read_file`, the read-only `git`. */
    fun tool(item: ToolCallItem): String {
        val head = if (item.detail.isEmpty()) item.tool else "${item.tool}  ${item.detail}"
        return when {
            item.status == ItemStatus.InProgress -> "$head  ${Texts[Key.RowRunning]}"
            item.ok -> head
            else -> "$head  ${Texts[Key.RowRejected]}"
        }
    }

    /** One checklist line of a plan, marked by its state. Reserved until a plan tool exists. */
    fun plan(status: PlanEntryStatus, content: String): String {
        val box = when (status) {
            PlanEntryStatus.Completed -> "[x] "
            PlanEntryStatus.InProgress -> "[~] "
            PlanEntryStatus.Pending -> "[ ] "
        }
        return box + content
    }

    /**
     * Which line of a patch this is. The header test comes first and mirrors
     * [dev.baudet.thread.DiffStats]: `--- a/x` starts with a minus and must not be counted or
     * coloured as a deletion, which is the one way a card could invent a change.
     */
    fun diffKind(line: String): DiffLine = when {
        line.startsWith("---") || line.startsWith("+++") -> DiffLine.Header
        line.startsWith("@@") -> DiffLine.Hunk
        line.startsWith("+") -> DiffLine.Added
        line.startsWith("-") -> DiffLine.Removed
        else -> DiffLine.Context
    }

    /**
     * A short excerpt of a tool's output for a line that has no body of its own. Collapsed to a
     * single line and cut at [max], because a failure excerpt is a pointer, not the report.
     */
    fun excerpt(output: String, max: Int = 160): String {
        val one = output.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return ""
        return if (one.length <= max) one else one.substring(0, max).trimEnd() + "\u2026"
    }

    /**
     * The past-tense verdict a resolved card keeps in place of its buttons. Chosen so the
     * answer reads as what happened rather than a live prompt: "always allowed" is not the
     * same as "allowed once", and a stop reads as cancelled rather than as a refusal.
     */
    fun approval(decision: ApprovalDecision): String = when (decision) {
        ApprovalDecision.Accept -> Texts[Key.ApprovalAllowedOnce]
        ApprovalDecision.AcceptForSession -> Texts[Key.ApprovalAllowedAlways]
        ApprovalDecision.Decline -> Texts[Key.ApprovalDenied]
        ApprovalDecision.Cancel -> Texts[Key.ApprovalCancelled]
    }
}

/**
 * The transcript as a stack of rows, one component per [Item].
 *
 * This replaced a single flat text document. That document could colour a run of characters but
 * could not hold a component, so a step a local model took -- a command, a write -- had nowhere
 * to be drawn and left the transcript as prose only: the reader's own machine ran something and
 * the interface showed a sentence instead. A row per item is what lets a step be a card with its
 * own header, its own folded output and, in the next phase, its own decision buttons.
 *
 * Events are the only source of rows while a turn runs. That matters for more than tidiness: the
 * chunks that arrive over [ItemDelta] and the item that arrives over [ItemCompleted] are not the
 * same text -- the completion carries the backend's final answer -- so a renderer that kept both
 * would show one answer twice. [apply] therefore lets the completion win, by re-rendering the row
 * when the two disagree.
 *
 * Reasoning is deliberately absent. It stays in the dock above the composer, where a thinking
 * model can be as verbose as it likes without pushing the conversation away, and it is still
 * never stored, which is what keeps it out of the context sent back to the model.
 *
 * Events arrive on the worker thread that runs the turn; every row mutation is posted to the
 * event dispatch thread, and posting preserves order, so rows appear in the order the turn
 * produced them.
 */
class TranscriptView : JPanel(BorderLayout()) {

    companion object {
        /** How far above the bottom still counts as resting at the tail, in pixels. */
        private const val TAIL_SLACK = 24

        /** How far below the head still asks for an older page, in pixels. */
        private const val NEAR_TOP = 120

        /** Output lines a finished command shows before the rest is folded away. */
        private const val VISIBLE_LINES = 14

        private const val COLLAPSE_ARROW = "\u25b8"
        private const val EXPAND_ARROW = "\u25be"
    }

    /**
     * The rows, oldest on top. It is [Scrollable] so the viewport sizes it to its own width in the
     * wrap mode: a plain panel would keep its preferred width, so a narrower viewport would clip a
     * wrapped row instead of re-folding it -- the asymmetry that left a narrower window wrong until
     * the next rebuild. Tracking makes folding follow the viewport in both directions, live.
     */
    private val rows = object : JPanel(), Scrollable {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = EmptyBorder(8, 10, 8, 10)
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle?, orientation: Int, direction: Int): Int = 16
        override fun getScrollableBlockIncrement(r: Rectangle?, orientation: Int, direction: Int): Int =
            if (orientation == SwingConstants.VERTICAL) (r?.height ?: 0) else (r?.width ?: 0)
        // The wrap mode folds to the viewport width, so the view takes that width; the scroll mode
        // keeps the natural width so a wide row scrolls. Height is never tracked -- it needs a bar.
        override fun getScrollableTracksViewportWidth(): Boolean = wrap
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    /** The viewport, so the host can place this whole view in its own split. */
    val scroll = JScrollPane(rows, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER)

    /**
     * Whether a row's text folds to the pane width (true, the prose default) or keeps its length
     * and asks the pane for a horizontal scrollbar (false, for diffs and long paths). The host sets
     * it from [dev.baudet.config.AppConfig.transcriptWrap] and calls [applyWrap]; the text panes
     * read it live, so one flip re-lays the whole transcript without rebuilding it.
     */
    var wrap: Boolean = true

    /** Rows still open, by item id, because a delta arrives naming the row it belongs to. */
    private val open = LinkedHashMap<String, Row>()

    /**
     * Set when the composer staged an image, consumed by the next prompt row. The prompt row
     * arrives on an event after the composer has already shown the thumbnail, so the flag is how
     * the row learns it was sent with a picture -- the item itself carries no image.
     */
    private var promptImage = false

    /**
     * Where a click on an approval card's button is answered. The host wires this to the
     * state that parked a worker on the request; the view itself never decides anything, it
     * only reports which decision the human reached for which item.
     */
    var onApproval: (itemId: String, decision: ApprovalDecision) -> Unit = { _, _ -> }

    /**
     * Called when the reader reaches the head of what has been read and older rows remain in
     * the store. The view owns only the trigger; deciding and reading belong to the host, which
     * holds the store. Set by [ChatPanel] to a method that pages one older block in.
     */
    var onLoadOlder: (() -> Unit)? = null

    /** Whether older rows remain to hand out; mirrors the host session's pending-older state. */
    var hasMoreOlder = false

    /** Holds one older load in flight at a time, so a scroll burst asks the host only once. */
    private var awaitingOlder = false

    /** The bar value and row-block height just before an older load, to hold the viewport steady. */
    private var prevValue = 0
    private var prevHeight = 0

    init {
        add(scroll, BorderLayout.CENTER)
        scroll.verticalScrollBar.model.addChangeListener { maybeLoadOlder() }
    }

    /**
     * Ask the host for one older page once the reader rests near the head of a scrolling view.
     * The guard is deliberately loose -- a bar that scrolls at all and sits within [NEAR_TOP]
     * of the top -- because rows added above the fold grow the content the reader was looking
     * at, and [olderReady] re-adds that height so the view does not jump. [awaitingOlder] holds
     * until the host answers with [olderReady] or [olderLoadSkipped], so one page loads at a
     * time and a fresh open at the tail (value high) never fires it by accident.
     */
    private fun maybeLoadOlder() {
        val host = onLoadOlder ?: return
        if (!hasMoreOlder || awaitingOlder) return
        val model = scroll.verticalScrollBar.model
        if (model.maximum <= model.extent) return
        if (model.value > NEAR_TOP) return
        awaitingOlder = true
        prevValue = model.value
        prevHeight = rows.preferredSize.height
        host()
    }

    /**
     * Apply the current [wrap] mode to the viewport: the wrap mode hides the horizontal bar, the
     * scroll mode shows it on demand. Hiding the bar on its own folds nothing, so the real work
     * lives in [textPane] -- a wrapped pane asks for the viewport width and its folded height, an
     * unwrapped one asks for its natural single-line width. The revalidate lets the panes already
     * on screen re-measure under the new mode, so a flip lands without rebuilding the transcript.
     */
    fun applyWrap() {
        scroll.horizontalScrollBarPolicy =
            if (wrap) JScrollPane.HORIZONTAL_SCROLLBAR_NEVER else JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        rows.revalidate()
        rows.repaint()
    }

    // ---- styles --------------------------------------------------------------------------

    private val normal = SimpleAttributeSet().apply {
        StyleConstants.setFontFamily(this, Font.MONOSPACED)
        StyleConstants.setFontSize(this, 13)
    }
    private val dim = SimpleAttributeSet(normal).apply { StyleConstants.setForeground(this, BaudetPalette.dim) }
    private val error = SimpleAttributeSet(normal).apply { StyleConstants.setForeground(this, BaudetPalette.error) }
    private val bold = SimpleAttributeSet(normal).apply { StyleConstants.setBold(this, true) }
    private val italic = SimpleAttributeSet(normal).apply { StyleConstants.setItalic(this, true) }
    private val heading = SimpleAttributeSet(bold).apply { StyleConstants.setFontSize(this, 15) }
    /**
     * Inline code is the one run that needs a background to read as code, since every glyph in
     * this transcript is already monospaced. A fenced block only tints its lines: a background on
     * a whole line would paint one bar per line, and those bars end wherever the code does.
     */
    private val codeSpan = SimpleAttributeSet(normal).apply {
        StyleConstants.setBackground(this, BaudetPalette.codeBg)
        StyleConstants.setForeground(this, BaudetPalette.text)
    }
    private val codeBlock = SimpleAttributeSet(normal).apply {
        StyleConstants.setForeground(this, BaudetPalette.codeFg)
    }
    private val diffAdded = SimpleAttributeSet(normal).apply { StyleConstants.setForeground(this, BaudetPalette.diffAdded) }
    private val diffRemoved = SimpleAttributeSet(normal).apply { StyleConstants.setForeground(this, BaudetPalette.diffRemoved) }
    private val diffHunk = SimpleAttributeSet(normal).apply { StyleConstants.setForeground(this, BaudetPalette.diffHunk) }

    /** The parser names an intent; this is where that intent becomes a Swing attribute. */
    private fun attrsFor(kind: SpanKind) = when (kind) {
        SpanKind.Plain -> normal
        SpanKind.Bold -> bold
        SpanKind.Italic -> italic
        SpanKind.Heading -> heading
        SpanKind.Code -> codeSpan
        SpanKind.CodeBlock -> codeBlock
        SpanKind.Marker -> dim
    }

    /** The colour a diff line gets, chosen by the same reading that counts the card's numbers. */
    private fun attrsFor(line: DiffLine) = when (line) {
        DiffLine.Added -> diffAdded
        DiffLine.Removed -> diffRemoved
        DiffLine.Hunk -> diffHunk
        DiffLine.Header -> dim
        DiffLine.Context -> normal
    }

    // ---- the public surface the host uses ------------------------------------------------

    /** Take one fact about a turn. Safe to call from any thread. */
    fun accept(event: ThreadEvent) {
        if (SwingUtilities.isEventDispatchThread()) apply(event) else SwingUtilities.invokeLater { apply(event) }
    }

    /** Say that the prompt about to arrive was sent with an image. One row, then cleared. */
    fun expectImageOnNextPrompt() {
        promptImage = true
    }

    /**
     * Rebuild the transcript from what the store holds. Only the conversation's messages are
     * stored, so a reload shows the prompts and the answers; the step cards lived in one turn's
     * event stream and are gone with it. That is stated rather than papered over, because a card
     * that survived a reload by being re-derived from prose would be a card that claims a step
     * it cannot evidence. Rows are persisted now, so a conversation that keeps them reloads
     * through [rebuildItems]; this prose path only serves one stored before rows were ever kept.
     */
    fun rebuild(summary: String?, messages: List<Msg>) {
        open.clear()
        rows.removeAll()
        summary?.let { rows.add(noteRow(Texts.fmt(Key.SummaryBanner, it.length))) }
        messages.forEach { m ->
            if (m.role == "assistant") {
                val row = AnswerRow()
                rows.add(row)
                row.render(m.content)
            } else {
                rows.add(promptRow(m.content, false))
            }
        }
        rows.revalidate()
        rows.repaint()
        SwingUtilities.invokeLater { scrollToTail() }
    }

    /**
     * Rebuild the transcript from the stored rows, replaying each through [started] then
     * [completed] so every card -- command, file change, tool call, error -- returns as it was
     * shown. This is the reload path now that rows persist; [rebuild] is the fallback for a
     * conversation stored before rows were kept, which can only show prose.
     */
    fun rebuildItems(summary: String?, items: List<Item>) = rebuildInto(summary, items, followTail = true)

    /**
     * Replay stored rows into an empty transcript. [followTail] decides where the reader lands:
     * true on an ordinary open, so the guard for an older load resets and the newest row shows;
     * false when an older page was just prepended, where the host holds the viewport where it
     * was instead (see [olderReady]).
     */
    private fun rebuildInto(summary: String?, items: List<Item>, followTail: Boolean) {
        // A fresh open or a session switch begins a clean transcript: the older-load guard goes
        // with the conversation it belonged to. The followTail=false path keeps it held, since
        // it is mid-older-load and its deferred layout must not re-trigger before [olderReady]
        // finishes placing the bar.
        if (followTail) awaitingOlder = false
        open.clear()
        rows.removeAll()
        promptImage = false
        summary?.let { rows.add(noteRow(Texts.fmt(Key.SummaryBanner, it.length))) }
        items.forEach { replayItem(it) }
        rows.revalidate()
        rows.repaint()
        if (followTail) SwingUtilities.invokeLater { scrollToTail() }
    }

    /** Replay one stored row through its start and completion, restoring a recorded verdict. */
    private fun replayItem(item: Item) {
        started(item)
        // A command card that recorded its decision replays it, so the verdict line returns.
        if (item is CommandExecutionItem) item.approval?.let {
            (open[item.itemId] as? ApprovalCapable)?.resolveApproval(it)
        }
        completed(item)
    }

    /**
     * Finish an older-page load: replay the now-longer list without following the tail, then
     * push the bar down by the height that appeared above, so the row the reader was looking at
     * stays put. [moreOlder] tells the view whether to keep offering older pages. It clears
     * [awaitingOlder] only after placing the bar, so the deferred layout the rebuild schedules
     * cannot fire another load with the bar still parked at the head.
     */
    fun olderReady(summary: String?, items: List<Item>, moreOlder: Boolean) {
        rebuildInto(summary, items, followTail = false)
        val delta = rows.preferredSize.height - prevHeight
        hasMoreOlder = moreOlder
        SwingUtilities.invokeLater {
            val bar = scroll.verticalScrollBar
            bar.value = (prevValue + delta).coerceAtMost(bar.maximum)
            awaitingOlder = false
        }
    }

    /** The host had nothing to load after this view asked; release the guard so a later scroll retries. */
    fun olderLoadSkipped() {
        awaitingOlder = false
    }

    /**
     * Echo a prompt the composer already cleared, for the one case where no event stream will
     * carry it: a refusal raised before the turn opened. The normal path never calls this, because
     * there the prompt arrives as an item of the turn like every other fact.
     */
    fun prompt(text: String, image: Boolean) = addRow(promptRow(text, image))

    /** A failure shown where it happened, in the transcript rather than only in the status line. */
    fun failure(text: String) = addRow(errorRow(text))

    // ---- turning events into rows --------------------------------------------------------

    private fun apply(event: ThreadEvent) {
        when (event) {
            is ItemStarted -> started(event.item)
            is ItemDelta -> delta(event)
            is ItemCompleted -> completed(event.item)
            is ApprovalRequested -> (open[event.itemId] as? ApprovalCapable)?.showApproval(event.request)
            is ApprovalResolved -> (open[event.itemId] as? ApprovalCapable)?.resolveApproval(event.decision)
            else -> Unit
        }
    }

    private fun started(item: Item) {
        when (item) {
            is UserMessageItem -> {
                val row = promptRow(item.text, promptImage)
                promptImage = false
                addRow(row)
            }
            is AgentMessageItem -> put(item.itemId, AnswerRow())
            is CommandExecutionItem -> put(item.itemId, CommandRow(item))
            is FileChangeItem -> put(item.itemId, ChangeRow(item))
            is ToolCallItem -> put(item.itemId, ToolRow(item))
            is PlanItem -> put(item.itemId, PlanRow(item))
            is ErrorItem -> put(item.itemId, errorRow(item.message))
            // Reasoning has a dock of its own above the composer; it is not a row on purpose.
            is ReasoningItem -> Unit
        }
    }

    private fun delta(event: ItemDelta) {
        val row = open[event.itemId] ?: return
        when (event.kind) {
            ItemKind.AgentMessage -> (row as? AnswerRow)?.write(event.delta)
            ItemKind.CommandExecution -> (row as? CommandRow)?.appendOutput(event.delta)
            ItemKind.FileChange -> (row as? ChangeRow)?.appendDiff(event.delta)
            else -> Unit
        }
    }

    private fun completed(item: Item) {
        when (item) {
            is AgentMessageItem -> close(item.itemId)?.let { (it as AnswerRow).finish(item) }
            is CommandExecutionItem -> close(item.itemId)?.let { (it as CommandRow).finish(item) }
            is FileChangeItem -> close(item.itemId)?.let { (it as ChangeRow).finish(item) }
            is ToolCallItem -> close(item.itemId)?.let { (it as ToolRow).finish(item) }
            is PlanItem -> close(item.itemId)?.let { (it as PlanRow).finish(item) }
            // A prompt row is complete as it is written, and an error row carries its one message.
            is UserMessageItem, is ErrorItem -> Unit
            is ReasoningItem -> Unit
        }
    }

    private fun put(id: String, row: Row) {
        open[id] = row
        addRow(row)
    }

    private fun close(id: String): Row? = open.remove(id)

    // ---- row plumbing --------------------------------------------------------------------

    /**
     * Whether the reader is resting at the tail. The bounds come from the scroll bar model, which
     * owns all three of them: a bar queried field by field mixes the visible amount with the model
     * and drifts after a resize.
     */
    private fun atTail(): Boolean {
        val model = scroll.verticalScrollBar.model
        return ThinkingLayout.followsTail(model.value, model.extent, model.maximum, TAIL_SLACK)
    }

    private fun scrollToTail() {
        val bar = scroll.verticalScrollBar
        bar.value = bar.maximum
    }

    private fun addRow(row: JComponent) {
        val follow = atTail()
        rows.add(row)
        rows.revalidate()
        rows.repaint()
        // After the layout pass, so the bar's maximum already includes the row that was just added.
        if (follow) SwingUtilities.invokeLater { scrollToTail() }
    }

    /** Append to a row's own document, following the tail only when the reader was already there. */
    private fun write(doc: DefaultStyledDocument, text: String, attrs: SimpleAttributeSet) {
        val follow = atTail()
        doc.insertString(doc.length, text, attrs)
        rows.revalidate()
        rows.repaint()
        if (follow) SwingUtilities.invokeLater { scrollToTail() }
    }

    /**
     * A text surface for one row: selectable and never its own visible box. Whether a long line
     * folds is decided by the pane itself, not by the viewport, because these panes are nested --
     * text pane, row, rows panel, viewport -- so a pane is never the viewport's direct view and the
     * viewport cannot clip it into wrapping. In the wrap mode the pane therefore asks for the
     * viewport width as its own width and for the height the folded text needs there, which pulls
     * the row and the wrapped line onto the pane width; in the scroll mode it asks for its natural
     * single-line width, so a wide row keeps its shape and [scroll]'s horizontal bar scrolls it.
     */
    private fun textPane(): JTextPane = object : JTextPane() {
        init {
            isEditable = false
            isOpaque = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 13)
            margin = Insets(0, 0, 0, 0)
        }

        /**
         * In the wrap mode a long line folds to the viewport width, so the pane must claim that
         * width and the taller height the folded text needs. [super.getPreferredSize] reports the
         * width a single line wants and a one-line height, which is what left the fold clipped
         * before. With the bar shown (the scroll mode), or before the viewport has a width yet, the
         * natural size is kept so a wide row keeps its shape and scrolls.
         */
        override fun getPreferredSize(): Dimension {
            val natural = super.getPreferredSize()
            val available = if (wrap) scroll.viewport.width else 0
            if (available <= 0 || natural.width <= available) return natural
            val root = getUI().getRootView(this)
            root.setSize(available.toFloat(), Short.MAX_VALUE.toFloat())
            val folded = kotlin.math.ceil(root.getPreferredSpan(View.Y_AXIS).toDouble()).toInt()
            val edge = insets
            return Dimension(available, folded + edge.top + edge.bottom)
        }
    }

    private fun noteRow(text: String): Row = Row().apply {
        val body = textPane()
        add(body, BorderLayout.CENTER)
        write(body.styledDocument as DefaultStyledDocument, text + "\n", dim)
    }

    private fun errorRow(text: String): Row = Row().apply {
        val body = textPane()
        add(body, BorderLayout.CENTER)
        write(body.styledDocument as DefaultStyledDocument, text + "\n", error)
    }

    /** The prompt as the human sent it: kept raw, because it is an instruction, not an answer. */
    private fun promptRow(text: String, image: Boolean): Row = Row().apply {
        val body = textPane()
        add(body, BorderLayout.CENTER)
        val doc = body.styledDocument as DefaultStyledDocument
        write(doc, Texts[Key.YouLabel], dim)
        write(doc, text + "\n", normal)
        if (image) write(doc, Texts[Key.ImageAttached], dim)
    }

    /** A row. Laid out on its own, and full width: BoxLayout would otherwise size it to its preferred width. */
    /** A row that can raise approval buttons on the step it stands for. */
    private interface ApprovalCapable {
        fun showApproval(request: ApprovalRequest)
        fun resolveApproval(decision: ApprovalDecision)
    }

    /**
     * The button strip for a pending decision. A command offers run-once, run-always and
     * refuse -- the three answers the old modal gave; a patch offers apply or refuse, since
     * there is no per-file grant to pin. A click reports the decision through [onApproval],
     * which is how the worker parked on the request is released.
     */
    private fun buildApprovalBar(itemId: String, request: ApprovalRequest): JPanel {
        val choices: List<Pair<Key, ApprovalDecision>> = when (request) {
            is ApprovalRequest.RunCommand -> listOf(
                Key.AllowOnce to ApprovalDecision.Accept,
                Key.AlwaysAllow to ApprovalDecision.AcceptForSession,
                Key.DenyButton to ApprovalDecision.Decline
            )
            is ApprovalRequest.ApplyPatch -> listOf(
                Key.ApplyPatch to ApprovalDecision.Accept,
                Key.DenyButton to ApprovalDecision.Decline
            )
            // An external MCP tool can be granted for this call or for its whole server, exactly as a
            // command can be run once or pinned; there is no middle answer, so the bar mirrors RunCommand.
            is ApprovalRequest.McpTool -> listOf(
                Key.AllowOnce to ApprovalDecision.Accept,
                Key.AlwaysAllow to ApprovalDecision.AcceptForSession,
                Key.DenyButton to ApprovalDecision.Decline
            )
        }
        return JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            isOpaque = false
            // A command and a patch already name themselves in their row; an MCP tool does not, so its
            // card says which server and tool it is before the human decides.
            if (request is ApprovalRequest.McpTool) {
                add(JLabel(Texts.fmt(Key.ApprovalMcpHeading, request.serverId, request.tool)).apply {
                    foreground = BaudetPalette.warn
                    font = Font(Font.MONOSPACED, Font.PLAIN, 11)
                })
            }
            add(JLabel(Texts[Key.ApprovalWaiting]).apply {
                foreground = BaudetPalette.warn
                font = Font(Font.MONOSPACED, Font.PLAIN, 11)
            })
            choices.forEach { (key, decision) ->
                add(JButton(Texts[key]).apply {
                    font = Font(Font.MONOSPACED, Font.PLAIN, 11)
                    addActionListener { onApproval(itemId, decision) }
                })
            }
        }
    }

    /** The line a resolved decision keeps in place of its buttons, so the answer stays readable. */
    private fun approvalRecord(decision: ApprovalDecision): JLabel {
        val mark = if (decision == ApprovalDecision.Accept || decision == ApprovalDecision.AcceptForSession)
            "\u2713 " else ""
        return JLabel(mark + RowText.approval(decision)).apply {
            foreground = when (decision) {
                ApprovalDecision.Accept, ApprovalDecision.AcceptForSession -> BaudetPalette.ok
                ApprovalDecision.Decline -> BaudetPalette.error
                ApprovalDecision.Cancel -> BaudetPalette.dim
            }
            font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        }
    }

    private open inner class Row(card: Boolean = false) : JPanel(BorderLayout()) {
        init {
            alignmentX = Component.LEFT_ALIGNMENT
            if (card) {
                isOpaque = true
                background = BaudetPalette.card
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(BaudetPalette.border),
                    EmptyBorder(4, 8, 4, 8)
                )
            } else {
                isOpaque = false
                border = EmptyBorder(2, 0, 6, 0)
            }
        }

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    /**
     * One answer. The label goes into the same document as the text, so the reply wraps under it
     * the way a paragraph does, rather than starting every line at the left edge of a column.
     */
    private inner class AnswerRow : Row() {
        private val body = textPane()
        private val doc = body.styledDocument as DefaultStyledDocument
        private val stream = MarkdownStream { text, kind -> write(doc, text, attrsFor(kind)) }

        /** What actually reached the screen, so the completion can tell whether it says more. */
        private val shown = StringBuilder()

        init {
            add(body, BorderLayout.CENTER)
            write(doc, Texts[Key.BaudetLabel], dim)
        }

        fun write(chunk: String) {
            shown.append(chunk)
            stream.write(chunk)
        }

        /**
         * The backend's final text wins. It is not always the sum of what streamed: a backend can
         * return a normalised answer, and a card that quietly kept both would show one answer
         * twice. Nothing reached the screen yet means only that the line is still held by the
         * stream, which is why this flushes before it compares.
         */
        fun finish(item: AgentMessageItem) {
            stream.flush()
            if (item.text != shown.toString()) render(item.text)
            if (item.status == ItemStatus.Interrupted) write(doc, Texts[Key.TurnStopped] + "\n", dim)
            else write(doc, "\n", normal)
        }

        /** Throw away what is drawn and render [text] once, used by a reload and by a correction. */
        fun render(text: String) {
            doc.remove(0, doc.length)
            shown.setLength(0)
            write(doc, Texts[Key.BaudetLabel], dim)
            val fresh = MarkdownStream { chunk, kind -> write(doc, chunk, attrsFor(kind)) }
            fresh.write(text)
            fresh.flush()
            write(doc, "\n", normal)
        }
    }

    /**
     * A command the human let run. The header names the command, the verdict names how it ended,
     * and the output folds: a `git diff` of three hundred lines must not push the conversation off
     * screen, and folding is the reason the row is a component rather than a run of characters.
     */
    private inner class CommandRow(private var item: CommandExecutionItem) : Row(card = true), ApprovalCapable {
        private val head = JLabel(RowText.command(item.command)).apply {
            foreground = BaudetPalette.dim
            font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        }
        private val verdict = JLabel().apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            border = EmptyBorder(0, 8, 0, 0)
        }
        private val body = textPane()
        private val doc = body.styledDocument as DefaultStyledDocument
        private val fold = JButton().apply {
            isVisible = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 11)
            foreground = BaudetPalette.dim
            border = EmptyBorder(0, 0, 0, 0)
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            horizontalAlignment = SwingConstants.LEFT
            addActionListener { setShowAll(!showAll) }
        }
        private val output = StringBuilder()
        private var showAll = false
        private var folding = false
        private var approvalBar: JPanel? = null

        init {
            val header = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(head, BorderLayout.WEST)
                add(verdict, BorderLayout.EAST)
            }
            val center = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(body, BorderLayout.CENTER)
                add(fold, BorderLayout.SOUTH)
            }
            add(header, BorderLayout.NORTH)
            add(center, BorderLayout.CENTER)
            renderVerdict()
        }

        fun appendOutput(chunk: String) {
            output.append(chunk)
            renderOutput()
        }

        fun finish(final: CommandExecutionItem) {
            item = final
            if (final.output.isNotEmpty()) {
                output.setLength(0)
                output.append(final.output)
            }
            renderVerdict()
            folding = output.lineSequence().count { it.isNotEmpty() } > VISIBLE_LINES
            fold.isVisible = folding
            renderOutput()
        }

        private fun setShowAll(all: Boolean) {
            showAll = all
            renderOutput()
        }

        private fun renderVerdict() {
            verdict.text = RowText.outcome(item)
            verdict.foreground = if (RowText.commandFailed(item)) BaudetPalette.error else BaudetPalette.ok
        }

        /**
         * Draw the output, or its tail when it folds. The fold keeps the end, because for a
         * command that failed the end is where the reason is; the button says how much is folded
         * away, so the reader knows the card is not the whole report.
         */
        private fun renderOutput() {
            doc.remove(0, doc.length)
            val lines = output.toString().split("\n")
            if (!folding || showAll) {
                write(doc, output.toString().removeSuffix("\n") + "\n", normal)
            } else {
                write(doc, Texts.fmt(Key.RowLinesHidden, lines.size - VISIBLE_LINES) + "\n", dim)
                write(doc, lines.takeLast(VISIBLE_LINES).joinToString("\n"), normal)
                write(doc, "\n", normal)
            }
            fold.text = (if (showAll) EXPAND_ARROW else COLLAPSE_ARROW) +
                " " + Texts[if (showAll) Key.RowHideOutput else Key.RowShowOutput]
        }

        override fun showApproval(request: ApprovalRequest) {
            if (approvalBar == null) {
                approvalBar = buildApprovalBar(item.itemId, request)
                add(approvalBar, BorderLayout.SOUTH)
                revalidate()
                repaint()
            }
        }

        override fun resolveApproval(decision: ApprovalDecision) {
            approvalBar?.let { remove(it) }
            approvalBar = null
            add(approvalRecord(decision), BorderLayout.SOUTH)
            revalidate()
            repaint()
        }
    }

    /**
     * A write to a project file. The diff is the preview the edit tool really produced, coloured
     * line by line, and the counts in the header are the ones [dev.baudet.thread.DiffStats] took
     * from that same text -- so a card cannot advertise more than the patch contains.
     */
    private inner class ChangeRow(private var item: FileChangeItem) : Row(card = true), ApprovalCapable {
        private val head = JLabel(RowText.change(item)).apply {
            foreground = BaudetPalette.dim
            font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        }
        private val body = textPane()
        private val doc = body.styledDocument as DefaultStyledDocument

        /** A delta can split a line anywhere, so only whole lines are coloured as they arrive. */
        private val partial = StringBuilder()
        private var approvalBar: JPanel? = null

        init {
            add(head, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }

        fun appendDiff(chunk: String) {
            for (ch in chunk) {
                if (ch != '\n') {
                    partial.append(ch)
                    continue
                }
                drawLine(partial.toString())
                partial.setLength(0)
            }
            if (partial.isNotEmpty()) write(doc, partial.toString(), normal)
        }

        fun finish(final: FileChangeItem) {
            item = final
            head.text = RowText.change(final)
            head.foreground = if (final.status == ItemStatus.Failed) BaudetPalette.error else BaudetPalette.dim
            val diff = final.diff
            if (diff != null && doc.length == 0) diff.lineSequence().forEach { drawLine(it) }
            if (diff == null && final.status == ItemStatus.Failed)
                write(doc, Texts[Key.RowRejected] + "\n", error)
        }

        private fun drawLine(line: String) {
            write(doc, line + "\n", attrsFor(RowText.diffKind(line)))
        }

        override fun showApproval(request: ApprovalRequest) {
            if (approvalBar != null) return
            // The preview the edit tool really produced, drawn before the human commits to it,
            // so a decision is made on the patch rather than on a path alone.
            if (request is ApprovalRequest.ApplyPatch && doc.length == 0) {
                request.diff?.lineSequence()?.forEach { drawLine(it) }
            }
            approvalBar = buildApprovalBar(item.itemId, request)
            add(approvalBar, BorderLayout.SOUTH)
            revalidate()
            repaint()
        }

        override fun resolveApproval(decision: ApprovalDecision) {
            approvalBar?.let { remove(it) }
            approvalBar = null
            add(approvalRecord(decision), BorderLayout.SOUTH)
            revalidate()
            repaint()
        }
    }

    /** A tool with no card of its own: one line, because a read is a step but not an event. */
    private inner class ToolRow(private var item: ToolCallItem) : Row(), ApprovalCapable {
        private var approvalBar: JPanel? = null
        private val head = JLabel(RowText.tool(item)).apply {
            foreground = BaudetPalette.dim
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        }

        init {
            add(head, BorderLayout.WEST)
        }

        fun finish(final: ToolCallItem) {
            item = final
            head.text = RowText.tool(final)
            head.foreground = if (final.ok) BaudetPalette.dim else BaudetPalette.error
            if (!final.ok && final.output.isNotBlank()) {
                val body = textPane()
                add(body, BorderLayout.CENTER)
                write(
                    body.styledDocument as DefaultStyledDocument,
                    "  " + RowText.excerpt(final.output) + "\n",
                    error
                )
            }
        }

        /** A pending decision on a git write: the buttons go on this tool's own card. */
        override fun showApproval(request: ApprovalRequest) {
            if (approvalBar != null) return
            approvalBar = buildApprovalBar(item.itemId, request)
            add(approvalBar, BorderLayout.SOUTH)
            revalidate()
            repaint()
        }

        override fun resolveApproval(decision: ApprovalDecision) {
            approvalBar?.let { remove(it) }
            approvalBar = null
            add(approvalRecord(decision), BorderLayout.SOUTH)
            revalidate()
            repaint()
        }
    }

    /** The plan checklist. Reserved: nothing emits one until Baudet has a plan tool. */
    private inner class PlanRow(item: PlanItem) : Row(card = true) {
        private val body = textPane()
        private val doc = body.styledDocument as DefaultStyledDocument

        init {
            add(body, BorderLayout.CENTER)
            render(item)
        }

        fun finish(final: PlanItem) = render(final)

        private fun render(plan: PlanItem) {
            doc.remove(0, doc.length)
            plan.entries.forEach { write(doc, RowText.plan(it.status, it.content) + "\n", normal) }
        }
    }
}
