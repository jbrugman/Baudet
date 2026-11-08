package dev.mule.tool

import dev.mule.api.Turn

/**
 * Reads the tool calls out of a finished [Turn]. It is the one seam between "the
 * model answered" and "the model asked to act", so swapping prompted-JSON for a
 * native function-calling backend is a new implementation here, never a loop edit.
 */
interface CallExtractor {

    /** The calls this turn asked for; empty means the turn is a final answer. */
    fun extract(turn: Turn): List<ToolCall>

    companion object {
        /** The fenced-code tag that marks a tool call in a prompted answer. */
        const val FENCE: String = "mule-tool"

        /**
         * The instructions that define the wire format. Kept beside the parser on
         * purpose: the prompt and the extractor that reads it live in one place, so
         * the contract they share cannot drift apart between slices.
         */
        val PROTOCOL: String = """
            When you want to change a file, do not print a patch in prose. Instead emit a
            fenced block tagged $FENCE whose body is one JSON object, or a JSON array of them:
              {"tool": "edit_file", "arguments": {"file": "relative/path", "edits": [{"old_text": "...", "new_text": "..."}]}}
            Put that block on its own, any prose before or after it. After the result of each
            call is returned to you, decide the next step: call again, or answer without a block.
        """.trimIndent()
    }
}
