package dev.baudet.session

/**
 * What a fold actually did, so the status line can say so instead of guessing. A nullable summary
 * used to stand in for this and collapsed four different truths into the one string "compaction
 * returned nothing", which was untrue for two of them: it reads as an outage when nothing was ever
 * sent, and it hides the reason when a summary did arrive and could not be trusted.
 *
 * An outage is deliberately absent here. The first summarization call that fails still goes out as
 * an exception -- see [Compactor.compact] -- because a backend that is down has to say so, so a
 * refusal reaching the user is always a refusal and never a crash report in disguise.
 *
 * Every case other than [Compacted] means the lossless guarantee held: the session is byte for byte
 * what it was, because a fold only ever clears turns a complete summary provably replaced.
 */
sealed class CompactionOutcome {

    /** Older turns were folded; [summary] is the record that replaced them. */
    data class Compacted(val summary: String) : CompactionOutcome()

    /** Nothing preceded the kept tail, so there was nothing to fold and nothing was changed. */
    data object NothingToFold : CompactionOutcome()

    /**
     * The oldest turn to fold is by itself larger than one summarization call may carry, so the only
     * way to send it is to cut its tail -- and a tail the model never read must never be cleared
     * behind a summary. The fold therefore stopped before sending anything and kept that turn
     * verbatim. [maxChars] is the ceiling it stopped at, because that is the number the user can act
     * on: a smaller budget, or a model with a bigger window, is what unlocks the fold.
     */
    data class OversizedTurn(val maxChars: Int) : CompactionOutcome()

    /**
     * A call was made and its answer could not be trusted, so nothing was cleared. [reason] is the
     * backend's own signal, kept untranslated for the same reason [dev.baudet.i18n.Key.CompactionFailed]
     * keeps an exception message raw: it names what the wire carried, which is the part worth reading.
     */
    data class SummaryRefused(val reason: String) : CompactionOutcome()
}
