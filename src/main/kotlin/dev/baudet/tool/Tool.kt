package dev.baudet.tool

import dev.baudet.thread.ItemKind
import kotlinx.serialization.json.JsonObject

/**
 * One capability the model can be offered. [description] and [parameters] are
 * model-facing and stay English; [parameters] is a JSON Schema object, the shape
 * the Responses API wants for a function tool. [execute] is pure of UI: any
 * approval is negotiated through [ToolContext], never by reaching into Swing.
 */
interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject

    /**
     * The card this tool's run is shown in.
     *
     * Declared here so the loop never decides that by matching on a tool's *name*: a rename
     * or a new tool would silently move a run into the wrong row, and nothing would fail.
     * The default is [ItemKind.ToolCall], the honest fallback for a tool the interface has no
     * dedicated card for -- and the one tag in the vocabulary that is named as Baudet's own
     * rather than borrowed from the app-server, because pretending otherwise would make the
     * transcript claim a wire item the backend never defined.
     */
    val itemKind: ItemKind get() = ItemKind.ToolCall

    /**
     * Whether this call may run alongside its siblings in the same batch. A tool says yes only when
     * the call both writes nothing and waits for no person, so a fan-out can never race a write or
     * strand an approval. The default refuses, so a new tool stays serial until it proves itself
     * side-effect-free; [ToolLoop] fans a batch out only when every call in it agrees.
     */
    fun canRunParallel(args: JsonObject, ctx: ToolContext): Boolean = false

    fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}
