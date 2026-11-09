package dev.baudet.tool

import dev.baudet.config.ContextStrategy
import dev.baudet.session.ContextTrimmer
import dev.baudet.session.Msg

/**
 * Prepares a running transcript for one backend call. The newest message is the current
 * input; what is replayed before it is scrubbed so that the control tokens a chat-tuned
 * model sometimes leaks can never be imitated from history and never reach the panel.
 * Every prior turn is replayed, assistant turns included, so a run keeps its working
 * context when previous_response_id is off; scrubbing covers assistant text too, so
 * replaying it cannot re-feed the leakage this stops.
 */
object Transcript {

    /**
     * Ceiling on the text replayed as prior turns, in characters (~3 chars per token, so about
     * sixteen thousand tokens). Deliberately far below a 128k window: the instructions, the tools,
     * the current input and the answer's own budget all still need room, and a local backend
     * counts its chat template on top.
     */
    private const val MAX_REPLAY_CHARS = 48_000

    /**
     * One model's control token, e.g. an end-of-turn marker wrapped in the special-tuple
     * form. Built from code points so the literal token never appears in this source.
     */
    private val special = Regex("\u003c\u005c\u007c[^\u007c\u003e]+\u005c\u007c\u003e")

    /** Removes leaked control tokens from any text; leaves ordinary prose untouched. */
    fun scrub(text: String): String = special.replace(text, "")

    /**
     * Split a running tool-loop transcript for one request, bounded by the caller's context
     * policy so the SAME budget that opened the turn caps every later request too, not a fixed
     * ceiling. A tool loop appends one or more observations each round trip, so without this the
     * later requests would fall back to the fixed [forCall] clamp and ignore the budget the user
     * set. Under [ContextStrategy.StopAtLimit] the transcript goes whole and the fixed clamp still
     * holds, with [underServerSession] deciding the current input; under a trimming strategy the
     * window is bounded by [ContextTrimmer] first and then split without that clamp, so trimming
     * decides the size. The newest message is the current input whenever the loop replays client
     * side, which is what it does under a trimming strategy.
     */
    fun forLoop(
        history: List<Msg>,
        strategy: ContextStrategy,
        budgetTokens: Long,
        underServerSession: Boolean
    ): Pair<List<Msg>, String> {
        if (strategy == ContextStrategy.StopAtLimit) {
            val (prior, _) = forCall(history)
            return prior to currentInput(history, underServerSession)
        }
        return splitForWindow(ContextTrimmer.bound(history, strategy, budgetTokens))
    }

    /** @return the sanitized prior messages to replay, and the current user input. */
    fun forCall(history: List<Msg>): Pair<List<Msg>, String> {
        val userText = scrub(history.lastOrNull()?.content ?: "")
        val prior = history.dropLast(1)
            .map { it.copy(content = scrub(it.content)) }
        return fit(prior, MAX_REPLAY_CHARS) to userText
    }

    /**
     * Split for a window the caller has already bounded. Same scrubbing as [forCall] but no length
     * clamp, because [dev.baudet.session.ContextTrimmer] already cut the list to the size the model can
     * carry for this strategy; re-fitting it here to the fixed replay ceiling would undo that and put
     * the effective limit back to a guess instead of the window.
     */
    fun splitForWindow(history: List<Msg>): Pair<List<Msg>, String> {
        val userText = scrub(history.lastOrNull()?.content ?: "")
        val prior = history.dropLast(1).map { it.copy(content = scrub(it.content)) }
        return prior to userText
    }

    /**
     * The newest prior turns that fit into one call, oldest first, with a marker standing in for
     * whatever had to go. Without this a tool run grows one observation per round trip and
     * re-sends all of them every iteration, so a long run dies partway through with a context
     * error and the user loses the run at its most expensive moment. Dropping the oldest first
     * keeps what the model needs to act now: its own last steps and the observations they
     * produced. The kept part is always a contiguous tail, so the run still reads in order.
     */
    internal fun fit(prior: List<Msg>, maxChars: Int): List<Msg> {
        if (maxChars <= 0) return prior
        var used = 0
        var first = prior.size
        while (first > 0) {
            val m = prior[first - 1]
            // Same per-message allowance as SessionState.estimateTokens, so the two agree.
            val cost = m.content.length + m.role.length + 8
            if (used + cost > maxChars) break
            used += cost
            first--
        }
        val kept = prior.subList(first, prior.size).toList()
        if (first == 0) return kept
        // Model-facing note, so English whatever the UI speaks: it goes into the prompt.
        val note = "[$first earlier messages were dropped from this request to keep it inside " +
            "the model's context window. The oldest context is gone; the turns below are intact.]"
        return listOf(Msg("user", note)) + kept
    }

    /**
     * The current input for one backend call. Without a server session the newest message
     * alone is the input and the rest replays. With a server session the backend already
     * holds everything up to the last assistant turn, so the input is every message the loop
     * appended since then -- usually several tool observations from one model turn -- joined so
     * none is dropped; before any assistant turn the newest stands alone.
     */
    fun currentInput(history: List<Msg>, underServerSession: Boolean): String {
        if (!underServerSession) return scrub(history.lastOrNull()?.content ?: "")
        val lastAssistant = history.indexOfLast { it.role == "assistant" }
        val tail = when {
            lastAssistant >= 0 -> history.subList(lastAssistant + 1, history.size)
            history.isNotEmpty() -> listOf(history.last())
            else -> emptyList()
        }
        val joined = tail.joinToString("\n\n") { scrub(it.content) }
        return joined.ifBlank { scrub(history.lastOrNull()?.content ?: "") }
    }
}
