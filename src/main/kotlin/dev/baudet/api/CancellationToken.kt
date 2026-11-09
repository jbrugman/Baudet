package dev.baudet.api

/**
 * A single handle to abandon the turn now in flight. The worker running a turn consults
 * [isCancelled] at every event boundary, and [cancel] closes whatever response body is live so
 * a read blocked on the socket unwinds at once. Interrupting the worker thread does not do
 * this: a blocking socket read is not interruptible on the JVM, so the socket itself has to be
 * closed, which is why the live body is registered here through [attach].
 *
 * A token is single-use: it belongs to one turn, is created when that turn starts and is
 * dropped when it ends. [NONE] is a shared token nobody ever cancels, the default for callers
 * with nothing to stop, such as the capability probe and the compactor.
 */
class CancellationToken {

    @Volatile private var cancelled = false

    /** The live response body of the current request, closed by [cancel]; null between requests. */
    @Volatile private var body: AutoCloseable? = null

    val isCancelled: Boolean get() = cancelled

    /**
     * Register the body of the request about to stream. If a cancel already arrived, close it
     * immediately, so the ordering between the worker opening the stream and the UI pressing
     * Stop cannot leave a doomed socket open.
     */
    fun attach(closeable: AutoCloseable) {
        body = closeable
        if (cancelled) closeQuietly(closeable)
    }

    /** Ask to stop the turn: flag the worker and release the socket it may be waiting on. */
    fun cancel() {
        cancelled = true
        body?.let { closeQuietly(it) }
    }

    /** Closing an already-dead socket must not mask the cancellation we are trying to raise. */
    private fun closeQuietly(closeable: AutoCloseable) {
        runCatching { closeable.close() }
    }

    companion object {
        /** A token that is never cancelled and never holds a live body: the do-nothing default. */
        val NONE = CancellationToken()
    }
}
