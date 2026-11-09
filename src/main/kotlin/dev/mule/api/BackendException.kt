package dev.mule.api

import dev.mule.i18n.Key
import dev.mule.i18n.Texts

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

    companion object {
        private val Overflow = Regex(
            "prompt is too long|context_length_exceeded|maximum context length|" +
                "context length exceeded|too many tokens|input is too long"
        )

        /** Whether [message] carries a context-window complaint, whatever backend wrote it. */
        fun looksLikeContextOverflow(message: String?): Boolean =
            message != null && Overflow.containsMatchIn(message.lowercase())
    }
}
