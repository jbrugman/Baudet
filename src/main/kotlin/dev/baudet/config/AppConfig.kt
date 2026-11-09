package dev.baudet.config

import kotlinx.serialization.Serializable

/**
 * Everything the user can set in the app, persisted as one JSON blob in H2.
 * Deliberately not one column per field: this table has a single row and
 * schema migrations cost more here than they buy.
 */
@Serializable
data class AppConfig(
    /**
     * Loopback by default: this is a local-first app, so the common case is a
     * server on the same machine. Any other host belongs in Settings, which
     * persists it, not in a default that would ship someone else's network layout.
     */
    var baseUrl: String = "http://127.0.0.1:8000/v1",
    /** Blank = read from env var [apiKeyEnvVar]; preferred over storing on disk. */
    var apiKey: String = "",
    var apiKeyEnvVar: String = "OMLX_API_KEY",
    var model: String = "",
    /** UI language: "en", "nl", "fr" or "de". Anything else reads back as English. */
    var language: String = "en",

    /**
     * The ceiling on the reply a turn may produce. 0 -- the default -- omits the field so the
     * server and the model decide, which is what a local backend should do unless the user has a
     * reason to cap it. A reasoning model spends part of any positive cap on its summary before the
     * answer starts, so a small cap truncates it before an answer exists; leaving it at 0 avoids
     * paying that cost unless the user asks for a hard bound.
     */
    var maxOutputTokens: Int = 0,
    /**
     * The ceiling a turn is guarded against, in tokens. 0 -- the default -- lets the app derive it
     * from the model's context window: trim the sent window at three quarters of it, and under
     * [ContextStrategy.StopAtLimit] refuse at its edge. A typed number acts as a ceiling on top of
     * that, never as a floor, so a small value keeps a conversation deliberately cheap while a large
     * one cannot push a request past the model.
     */
    var contextBudgetTokens: Int = 0,
    /**
     * What the app does when a conversation reaches that ceiling. Trimming is the default so a long
     * conversation simply keeps going instead of being refused; see [ContextStrategy].
     */
    var contextStrategy: ContextStrategy = ContextStrategy.TruncateAtMiddle,
    var temperature: Double = 0.7,
    var topP: Double? = null,
    /** null = do not send. Values like minimal|low|medium|high, model dependent. */
    var reasoningEffort: String? = null,

    /** Responses API by default: this backend advertises previous_response_id. */
    var useResponsesApi: Boolean = true,
    var storeResponses: Boolean = true,
    /** Send previous_response_id. Off for backends that only swallow the field. */
    var useServerSession: Boolean = true,

    /**
     * Whether the assistant may call tools in a turn. Ask previews each change,
     * Auto writes directly, Off never enters the loop. Ask by default: writing to
     * a project folder should not happen behind the user's back on a fresh install.
     */
    var toolMode: ToolMode = ToolMode.Ask,
    /** Upper bound on model<->tool round trips in one turn; a guard against a runaway loop. */
    var maxToolIterations: Int = 8,

    /**
     * Exact commands the user chose to always allow from the run dialog. Matching is on the whole
     * command string, never a prefix or pattern, so approving "ls" cannot later let "ls && rm -rf"
     * through. Off by empty; nothing runs without an explicit entry here or a per-turn approval.
     */
    var autoAllowCommands: MutableList<String> = mutableListOf(),
    /**
     * Opt-in: auto-approve commands [dev.baudet.tool.SafeCommands] recognises as plainly read-only
     * (a single simple command like ls, cat or git status). Default off, so a fresh install still
     * asks for every command; the user turns it on deliberately to stop being nagged over harmless ones.
     */
    var autoApproveSafeCommands: Boolean = false,

    /** Blank = the built-in English default (model-facing, never translated); typed text always wins. */
    var systemPrompt: String = "",

    /**
     * Last window position and size, remembered so the app reopens where the user left it.
     * Zero means never stored, which lets the first launch fall back to a default size. This
     * rides in the same single-row JSON blob as every other setting, so no schema is touched.
     */
    var windowX: Int = 0,
    var windowY: Int = 0,
    var windowWidth: Int = 0,
    var windowHeight: Int = 0,
    /** Remembered split widths so a rebuild, such as a language change, keeps the user layout. */
    var leftDivider: Int = 0,
    var rightDivider: Int = 0,
    /**
     * The height of the change list under the file tree. A third split, but the same rule: it
     * rides in the single-row JSON blob, so remembering it touches no schema.
     */
    var filesDivider: Int = 0,
    /**
     * Where and how big the user last left the diff viewer, so a patch reopens there rather
     * than re-centering at the small default. Zero size means never stored, which lets the
     * viewer fall back to its own default on the first open; the position is trusted only once
     * a size was stored, mirroring the main window.
     */
    var diffPopupX: Int = 0,
    var diffPopupY: Int = 0,
    var diffPopupWidth: Int = 0,
    var diffPopupHeight: Int = 0,
    /**
     * The context window a refusal taught us, kept with the [learnedContextKey] it was learned
     * under so a model or server switch cannot leave a stale number behind. This is not a setting:
     * the user never types it. It exists because the app otherwise has to guard a turn against a
     * size nobody told it, which is how a conversation gets sent to a backend that can only
     * reject it. Zero means never learned.
     */
    var learnedContextWindow: Int = 0,
    var learnedContextKey: String = ""
) {
    /** Key resolution: UI input wins, then the environment. Never hardcoded. */
    fun resolvedApiKey(): String =
        apiKey.ifBlank { System.getenv(apiKeyEnvVar.ifBlank { "OMLX_API_KEY" }) ?: "" }

    fun hasKey(): Boolean = resolvedApiKey().isNotBlank()

    /** Same rule as the key: stored text wins, otherwise the built-in default. */
    fun resolvedSystemPrompt(): String = systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT }

    companion object {
        /**
         * Model-facing, so English whatever the UI speaks. A prompt is not part of
         * the interface: translating it silently changes how the model behaves.
         */
        const val DEFAULT_SYSTEM_PROMPT =
            "You are Baudet, an accurate, critical coding assistant. Answer briefly and factually."
    }
}
