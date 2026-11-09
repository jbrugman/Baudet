package dev.baudet.api

import dev.baudet.i18n.Key
import dev.baudet.i18n.Texts

/**
 * A backend that answered with an error. [contextOverflow] singles out the one error the user
 * can act on without knowing anything about token maths: the prompt no longer fits the model.
 * Matching is on the words the two common backend families print, because the status code alone
 * does not separate "too long" from "bad field" and the raw body is not something to show.
 */
class BackendException(val status: Int, val detail: String) :
    RuntimeException(Texts.fmt(Key.HttpFromBackend, status, detail)) {

    /** Matched on [detail], the body as it arrived, never on the formatted message. */
    val contextOverflow: Boolean get() = looksLikeContextOverflow(detail)

    /**
     * The window this backend says it has, read out of the same complaint that makes
     * [contextOverflow] true, or null when the wording names no number. This is how the app
     * learns the size of a model it has never heard of: the refusal states the limit, so the
     * next turn is guarded against the truth instead of against a guess.
     */
    val claimedContextTokens: Int? get() = claimedContextTokensOf(detail)

    companion object {
        private val Overflow = Regex(
            "prompt is too long|context_length_exceeded|maximum context length|" +
                "context length exceeded|too many tokens|input is too long"
        )

        /**
         * The limit, sought only next to the words that name a limit. Reading every number in
         * the body instead would pick up the count that overflowed and the token breakdown some
         * backends print with it, and a limit learned from those is wrong in the one direction
         * that matters: too high, so the next request fails the same way.
         */
        private val Limit = listOf(
            // "maximum context length is 128072 tokens" / "...length of 128072"
            Regex("maximum context length (?:is|of)?\\s*(\\d[\\d,_ ]*)"),
            // "129232 tokens > 128000 maximum context length"
            Regex("(\\d[\\d,_ ]*)\\s*maximum context length"),
            // "context window of 131072" / "context length limit 32768"
            Regex("context (?:length|window) (?:limit|of|is)?\\s*(\\d[\\d,_ ]*)"),
            // "token limit of 128000" / "exceeds the 128000 token limit"
            Regex("(?:token )?limit (?:of )?(\\d[\\d,_ ]*)")
        )

        /** Below this a number is a token count in a breakdown, not a window; above it a typo. */
        private const val MIN_WINDOW = 512
        private const val MAX_WINDOW = 10_000_000

        /** Whether [message] carries a context-window complaint, whatever backend wrote it. */
        fun looksLikeContextOverflow(message: String?): Boolean =
            message != null && Overflow.containsMatchIn(message.lowercase())

        /**
         * The window named in [message], or null unless it says both "too long" and a plausible
         * limit. Null means "unknown", never "large": a caller that gets null keeps whatever it
         * already assumed rather than inventing room the backend may not have.
         */
        fun claimedContextTokensOf(message: String?): Int? {
            if (!looksLikeContextOverflow(message)) return null
            val text = message!!.lowercase()
            for (pattern in Limit) {
                val found = pattern.find(text)?.groupValues?.get(1)
                    ?.replace(",", "")?.replace("_", "")?.trim() ?: continue
                val value = found.toIntOrNull() ?: continue
                if (value in MIN_WINDOW..MAX_WINDOW) return value
            }
            return null
        }
    }
}
