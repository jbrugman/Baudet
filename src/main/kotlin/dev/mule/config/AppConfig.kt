package dev.mule.config

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
     * Reasoning models spend part of this budget on the summary before the
     * answer starts, so a chat-sized default truncates them before any answer
     * exists; the surplus is not paid for unless the model reasons.
     */
    var maxOutputTokens: Int = 4096,
    /** Rough context budget; drives the warning bar and the compaction trigger. */
    var contextBudgetTokens: Int = 32768,
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
     * The size the user last left the diff viewer at, so a patch reopens at a size they
     * chose rather than the small default. Zero means never stored, which lets the viewer
     * fall back to its own default on the first open.
     */
    var diffPopupWidth: Int = 0,
    var diffPopupHeight: Int = 0
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
            "You are Mule, an accurate, critical coding assistant. Answer briefly and factually."
    }
}
