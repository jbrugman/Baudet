package dev.baudet.api

/**
 * A single handle to abandon the turn now in flight. The worker running a turn consults
 * [isCancelled] at every event boundary, and [cancel] closes whatever response body is live so
 * a read blocked on the socket unwinds at once. Interrupting the worker thread does not do
 * this: a blocking socket read is not interruptible on the JVM, so the socket itself has to be
 * closed, which is why the live body is registered here through [attach].
 *
 * A second kind of wait exists that no socket-close reaches: a worker parked on an approval
 * latch, waiting for a human to answer an inline card. It registers through [onCancel] instead,
 * so a Stop releases it. Both registrations share one guarantee -- if a cancel already arrived,
 * the registered thing is released at once, so the ordering between the worker starting to wait
 * and the UI pressing Stop can never leave it waiting on a turn that is already gone.
 *
 * A token is single-use: it belongs to one turn, is created when that turn starts and is
 * dropped when it ends. [NONE] is a shared token nobody ever cancels, the default for callers
 * with nothing to stop, such as the capability probe and the compactor.
 */
class CancellationToken {

    @Volatile private var cancelled = false

    /** The live response body of the current request, closed by [cancel]; null between requests. */
    @Volatile private var body: AutoCloseable? = null

    /** Actions registered by [onCancel]; drained under [cancels] so a cancel and a register cannot race. */
    private val cancels = ArrayList<() -> Unit>()

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

    /**
     * Register an action to run when the turn is cancelled -- the approval-latch equivalent of
     * [attach]'s socket. A worker parked waiting for a human decision cannot be unwound by
     * closing a socket, so it parks a latch and releases it here. If a cancel already arrived,
     * [action] runs at once for the same ordering reason as [attach]. The action runs off the
     * lock and its failure is swallowed: releasing one waiter must not abort the others or mask
     * the cancellation.
     */
    fun onCancel(action: () -> Unit) {
        synchronized(cancels) {
            if (cancelled) { runQuietly(action); return }
            cancels.add(action)
        }
    }

    /** Ask to stop the turn: flag the worker, release the socket it may be waiting on, and free any parked waiter. */
    fun cancel() {
        val pending: List<() -> Unit>
        synchronized(cancels) {
            cancelled = true
            pending = cancels.toList()
            cancels.clear()
        }
        body?.let { closeQuietly(it) }
        pending.forEach { runQuietly(it) }
    }

    /** Closing an already-dead socket must not mask the cancellation we are trying to raise. */
    private fun closeQuietly(closeable: AutoCloseable) {
        runCatching { closeable.close() }
    }

    /** Likewise a waiter that throws must not stop the other waiters from being released. */
    private fun runQuietly(action: () -> Unit) {
        runCatching { action() }
    }

    companion object {
        /** A token that is never cancelled and never holds a live body: the do-nothing default. */
        val NONE = CancellationToken()
    }
}
