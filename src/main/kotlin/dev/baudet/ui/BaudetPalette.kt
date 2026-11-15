package dev.baudet.ui

import java.awt.Color

/**
 * The one place a colour is named.
 *
 * The palette is deliberately the Codex palette rather than a generic dark theme: a near-black
 * canvas, a hairline panel lift, neutral grey text with one dimmed level for what the reader
 * reads second, and a single muted blue that only ever marks focus -- never a fill. A loud
 * accent would put the interface ahead of the work, which is the opposite of what a transcript
 * of a machine doing things on the reader's own hardware should do. The diff and git colours are
 * the colours a reader already knows from a patch, kept consistent so a card and a file tree
 * agree on what green and red mean.
 *
 * [install] pushes these into [javax.swing.UIManager] just after the look and feel is set up, so
 * whole-widget defaults -- panels, scroll areas, trees, tables -- pick the palette up without
 * every view having to set its own background. Unknown keys are ignored by the look and feel,
 * which is why the list can name more than is strictly guaranteed.
 */
object BaudetPalette {

    private fun rgb(r: Int, g: Int, b: Int) = Color(r, g, b)

    /** The deepest surface: behind a scroll area, the transcript's own background. */
    val canvas = rgb(20, 20, 23)
    /** A container lifted one step above the canvas. */
    val panel = rgb(27, 28, 31)
    /** A card: one more step, so a step the machine took reads as an object on the page. */
    val card = rgb(34, 35, 39)
    /** A visible edge between surfaces. */
    val border = rgb(48, 50, 56)
    /** A near-invisible rule inside a widget, such as a tree's guide line. */
    val hairline = rgb(40, 41, 46)

    /** Primary text. */
    val text = rgb(206, 206, 211)
    /** The text a reader reads second: card headers, counts, secondary labels. */
    val dim = rgb(150, 150, 156)
    /** The faintest text that still reads. */
    val faint = rgb(120, 122, 128)

    /** A neutral selection block -- never blue, so a selection cannot be mistaken for a link. */
    val selection = rgb(56, 58, 64)
    /** The one blue: focus only. */
    val accent = rgb(102, 133, 199)

    /** A step that went right. */
    val ok = rgb(124, 196, 132)
    /** A step that needs a decision or carries a caution. */
    val warn = rgb(222, 178, 86)
    /** A step that went wrong. */
    val error = rgb(231, 116, 116)

    /** Unified-diff lines, fixed to the colours a patch already uses. */
    val diffAdded = rgb(140, 200, 150)
    val diffRemoved = rgb(225, 130, 130)
    val diffHunk = rgb(115, 170, 215)

    /** Inline code: a background is the only way a run reads as code in an all-mono transcript. */
    val codeBg = rgb(42, 43, 48)
    val codeFg = rgb(198, 208, 224)

    /** The git states a file tree marks, kept to the same red/green as the diff. */
    val gitAdded = rgb(46, 160, 67)
    val gitDeleted = rgb(210, 70, 70)
    val gitUntracked = rgb(130, 170, 220)

    /**
     * Push the palette into the look and feel's defaults. Call once, right after the look and
     * feel is installed and before any window is built, so every widget inherits the same chrome.
     */
    fun install() {
        // Chrome: containers one step above the deepest surface, scroll areas at the deepest.
        put("Panel.background", panel)
        put("RootPane.background", panel)
        put("LayeredPane.background", panel)
        put("Viewport.background", canvas)
        put("ScrollPane.background", canvas)
        put("EditorPane.background", canvas)
        put("TextPane.background", canvas)
        put("TextArea.background", canvas)
        put("TextArea.foreground", text)
        put("TextArea.caretColor", text)
        put("TextField.background", panel)
        put("FormattedTextField.background", panel)
        put("PasswordField.background", panel)

        // Trees and lists: the project tree and the model list ride the panel, select in neutral.
        put("Tree.background", panel)
        put("Tree.textForeground", text)
        put("Tree.selectionBackground", selection)
        put("Tree.selectionForeground", text)
        put("Tree.hash", hairline)
        put("List.background", panel)
        put("List.foreground", text)
        put("List.selectionBackground", selection)
        put("List.selectionForeground", text)

        // Tables: grid in the border colour, selection neutral.
        put("Table.background", panel)
        put("Table.foreground", text)
        put("Table.gridColor", border)
        put("Table.selectionBackground", selection)
        put("Table.selectionForeground", text)

        // Structure: dividers blend into the canvas, separators are a plain border.
        put("SplitPane.background", panel)
        put("SplitPane.dividerColor", canvas)
        put("Separator.color", border)
        put("Border.borderColor", border)

        // Buttons and focus: the default button is a lifted neutral, focus is the single blue.
        put("Button.default.background", selection)
        put("Button.default.foreground", text)
        put("Component.focusColor", accent)
    }

    private fun put(key: String, value: Color) = javax.swing.UIManager.put(key, value)
}
