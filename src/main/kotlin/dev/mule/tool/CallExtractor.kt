package dev.mule.tool

import dev.mule.api.Turn

/**
 * Reads the tool calls out of a finished [Turn]. It is the one seam between "the
 * model answered" and "the model asked to act", so swapping prompted-JSON for a
 * native function-calling backend is a new implementation here, never a loop edit.
 */
fun interface CallExtractor {

    /** The calls this turn asked for; empty means the turn is a final answer. */
    fun extract(turn: Turn): List<ToolCall>

    companion object {
        /** The fenced-code tag that marks a tool call in a prompted answer. */
        const val FENCE: String = "mule-tool"

        /**
         * The instructions that define the prompted wire format. Kept beside the parser
         * on purpose: the prompt and the extractor that reads it live in one place, so
         * the contract they share cannot drift apart between slices. English always, and
         * strict: a chat-tuned model that is merely invited to use a format will invent
         * its own, so this says exactly the one shape accepted and forbids any other.
         * A backend that does native function calling ignores the fenced shape but still
         * benefits from the read-before-edit rule, so it is stated once here for both.
         */
        val PROTOCOL: String = """
            You can read and change project files only through the two tools below, and only
            with the exact format shown. Do not describe a tool call or a patch in prose, and
            never invent another tool name or markup. To act, emit one fenced block tagged
            $FENCE that contains a single JSON object, one object per call.

            Read a file before you change it, so your replacement matches the current text:
              {"tool": "read_file", "arguments": {"file": "relative/path"}}

            Then change it by replacing text you saw in that read:
              {"tool": "edit_file", "arguments": {"file": "relative/path", "edits": [{"old_text": "...", "new_text": "..."}]}}

            Prose may come before or after a block. Each call returns an observation that
            says ok true or ok false; read it before acting again. On ok false, do not send
            the same call again unchanged; fix the arguments or answer without a block. On
            ok true from read_file, choose old_text from the text it returned. On ok true
            from edit_file, either answer or make the next call you still need.
        """.trimIndent()
    }
}
