package dev.baudet.tool

import dev.baudet.api.Turn

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
        const val FENCE: String = "baudet-tool"

        /**
         * The instructions that define the prompted wire format, worded to advertise only
         * the tools this turn actually has. Kept beside the parser on purpose: the prompt
         * and the extractor that reads it live in one place, so the contract they share
         * cannot drift apart between slices. English always, and strict: a chat-tuned model
         * that is merely invited to use a format will invent its own, so this names the one
         * shape accepted and forbids any other. It lists only the enabled tools on purpose:
         * a protocol that mentions run_command or git while the registry omits them teaches
         * the model to call a tool that then fails as unknown, and one that hides a tool it
         * does have leaves the model unaware it may build, test or inspect git at all. A
         * backend that does native function calling ignores the fenced shape but still
         * benefits from the read-before-edit rule and the same accurate tool list.
         */
        fun protocolFor(enabled: Set<String>): String {
            val canEdit = "edit_file" in enabled
            val canRun = "run_command" in enabled
            val canGit = "git" in enabled
            val advertised = buildList {
                add("read_file")
                if (canEdit) add("edit_file")
                if (canRun) add("run_command")
                if (canGit) add("git")
            }
            return buildString {
                append("You can act on the project only through the tools below, and only with ")
                append("the exact format shown. The only tools available to you are: ")
                append(advertised.joinToString(", ") { "\"" + it + "\"" })
                append(". Do not describe a tool call or a patch in prose, and never invent ")
                append("another tool name or markup. To act, emit one fenced block tagged ")
                append(FENCE).append(" that contains a single JSON object, one object per call.\n\n")

                append("Read a file before you change it or reason about it, so you work from ")
                append("the current text:\n")
                append("  {\"tool\": \"read_file\", \"arguments\": {\"file\": \"relative/path\"}}\n\n")

                if (canEdit) {
                    append("Change a file by replacing text you saw in that read:\n")
                    append("  {\"tool\": \"edit_file\", \"arguments\": {\"file\": \"relative/path\", ")
                    append("\"edits\": [{\"old_text\": \"...\", \"new_text\": \"...\"}]}}\n\n")
                }
                if (canRun) {
                    append("Run a shell command in the project folder (the user approves it first), ")
                    append("for example to build or test:\n")
                    append("  {\"tool\": \"run_command\", \"arguments\": {\"command\": \"mvn -B test\"}}\n\n")
                }
                if (canGit) {
                    append("Inspect the repository with a read-only git command such as status, ")
                    append("diff or log:\n")
                    append("  {\"tool\": \"git\", \"arguments\": {\"command\": \"status\"}}\n\n")
                }

                append("Prose may come before or after a block. Each call returns an observation ")
                append("that says ok true or ok false; read it before acting again. On ok false, ")
                append("do not send the same call again unchanged; fix the arguments or answer ")
                append("without a block.")
                if (canEdit) {
                    append(" On ok true from read_file, choose old_text from the text it returned. ")
                    append("On ok true from edit_file, either answer or make the next call you still need.")
                } else {
                    append(" Otherwise either answer or make the next call you still need.")
                }
            }
        }
    }
}
