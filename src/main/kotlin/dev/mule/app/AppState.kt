package dev.mule.app

import dev.mule.api.ResponsesClient
import dev.mule.api.Turn
import dev.mule.config.AppConfig
import dev.mule.i18n.Key
import dev.mule.i18n.Texts
import dev.mule.session.Compactor
import dev.mule.session.Msg
import dev.mule.session.SessionIds
import dev.mule.session.SessionState
import dev.mule.store.H2Store
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The single place that ties config, session and persistence together.
 * Everything here blocks: the UI must call it from a worker thread.
 */
class AppState(val store: H2Store) {

    var config: AppConfig = store.load() ?: AppConfig()
    var session: SessionState = SessionState(SessionIds.new(), config.model, null)

    init {
        Texts.language = Texts.parse(config.language)
        restoreLatest()
    }

    /** Loads the most recent conversation so context survives a restart. */
    private fun restoreLatest() {
        val id = store.latestSessionId(1).firstOrNull() ?: return
        val msgs = store.messages(id)
        if (msgs.isEmpty()) return
        session = SessionState(
            sessionId = id,
            model = store.model(id) ?: config.model,
            previousResponseId = store.previousResponseId(id),
            messages = msgs.toMutableList()
        )
    }

    /**
     * Notified after a config is persisted. The UI uses it for the one change it
     * cannot absorb in place: the language, which needs the panels rebuilt.
     */
    var onConfigSaved: (AppConfig) -> Unit = {}

    fun saveConfig(next: AppConfig) {
        config = next
        store.save(next)
        session.model = next.model
        Texts.language = Texts.parse(next.language)
        onConfigSaved(next)
    }

    fun newSession(): SessionState {
        session = SessionState(SessionIds.new(), config.model, null)
        store.upsertSession(session.sessionId, session.model, null, 0)
        return session
    }

    fun serverSessionActive(): Boolean =
        config.useServerSession && session.previousResponseId != null

    /**
     * Blocking. Call off the EDT; both callbacks fire on that worker thread.
     *
     * Reasoning is display-only and deliberately never stored in a Msg: the backend
     * keeps it behind the response id we send back, so storing it client-side too only
     * inflates every later turn.
     */
    fun send(
        userText: String,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit = {}
    ): Turn {
        session.messages.add(Msg("user", userText))
        val prev = if (config.useServerSession) session.previousResponseId else null
        val turn: Turn
        try {
            turn = ResponsesClient(config).turn(
                system = config.resolvedSystemPrompt(),
                history = session.promptHistory(),
                userText = userText,
                previousResponseId = prev,
                onDelta = onDelta,
                onReasoning = onReasoning
            )
        } catch (e: Exception) {
            session.messages.removeAt(session.messages.lastIndex) // do not keep a half-finished turn
            throw e
        }
        session.messages.add(Msg("assistant", turn.text, turn.responseId, turn.usage?.prompt))
        if (turn.responseId != null) session.previousResponseId = turn.responseId
        persist()
        return turn
    }

    fun compact(keepLast: Int, onDelta: (String) -> Unit): String? {
        val s = Compactor(config).compact(session, keepLast, onDelta)
        persist()
        return s
    }

    fun persist() {
        store.rewriteMessages(session.sessionId, session.messages)
        store.upsertSession(
            session.sessionId, session.model, session.previousResponseId, session.messages.size
        )
    }

    fun contextWarning(): String {
        val used = session.estimateTokens()
        val budget = config.contextBudgetTokens
        val pct = if (budget <= 0) 0 else (used * 100 / budget)
        return "~${used} / $budget tokens (${pct}%)" +
            if (pct > 80) Texts[Key.compactRecommended] else ""
    }

    companion object {
    /** Development default: inside the repo. Override with -Dmule.home. */
        fun dataDir(): Path = Paths.get(System.getProperty("mule.home") ?: "mule-data")
        fun open(): AppState = AppState(H2Store(dataDir()))
    }
}
