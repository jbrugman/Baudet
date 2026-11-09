package dev.mule.tool

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * A captured program output stream: the kept text (up to a cap), whether more was dropped,
 * and the true byte count seen. [section] renders it the way both process tools present it.
 */
class Captured(val text: String, val truncated: Boolean, val length: Int) {

    /** A labeled section for this stream, or empty when it captured nothing. */
    fun section(label: String): String {
        if (length == 0) return ""
        val marker = if (truncated) " (truncated, $length bytes read)" else ""
        return "\n--- $label$marker ---\n" + text
    }
}

/**
 * Runs a fixed argv in a folder, with a timeout and a capped, concurrent read of stdout and
 * stderr, and nothing else. Both the command tool and the git tool need exactly this, and a
 * subprocess must never block on a full pipe nor flood the context, so the two drains run on
 * their own threads and each keeps only a cap. It starts no shell of its own and applies no
 * policy: callers decide argv, render their own header line, and map a start failure to their
 * own reason. Pure of tool and UI concerns, so the drain and cap are defined once.
 */
object ProcessRunner {

    private const val JOIN_MILLIS = 1_000L
    private const val CAP_BYTES = 64 * 1024

    /** The outcome of a run: the exit code (null on timeout), the timeout flag and both streams. */
    class Result(
        val exitCode: Int?,
        val timedOut: Boolean,
        val stdout: Captured,
        val stderr: Captured
    ) {
        /** The two stream sections, with a no-output marker when both are empty. */
        fun body(): String = buildString {
            append(stdout.section("stdout"))
            append(stderr.section("stderr"))
            if (stdout.length == 0 && stderr.length == 0) append("\n(no output)")
        }
    }

    /**
     * Start and drain the process described by [argv], run in [dir], capped at
     * [timeoutSeconds]. [configure] can adjust the builder (for example its environment)
     * before it starts. Throws if the process cannot be started.
     */
    fun run(
        argv: List<String>,
        dir: File,
        timeoutSeconds: Long,
        configure: (ProcessBuilder) -> Unit = {}
    ): Result {
        val process = ProcessBuilder(argv)
            .directory(dir)
            .redirectErrorStream(false)
            .apply(configure)
            .start()

        var out = Captured("", false, 0)
        var err = Captured("", false, 0)
        val outThread = Thread { out = readCapped(process.inputStream) }
        val errThread = Thread { err = readCapped(process.errorStream) }
        outThread.start()
        errThread.start()

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        val timedOut = !finished
        if (timedOut) process.destroyForcibly()
        outThread.join(JOIN_MILLIS)
        errThread.join(JOIN_MILLIS)
        val exit = if (timedOut) null else process.exitValue()
        return Result(exit, timedOut, out, err)
    }

    /** Drains [input] fully while keeping only [CAP_BYTES], so a chatty process cannot hang or flood. */
    private fun readCapped(input: InputStream): Captured {
        val buffer = ByteArray(8192)
        val kept = ByteArrayOutputStream()
        var total = 0
        var truncated = false
        while (true) {
            val n = runCatching { input.read(buffer) }.getOrDefault(-1)
            if (n < 0) break
            total += n
            if (kept.size() < CAP_BYTES) {
                val room = CAP_BYTES - kept.size()
                kept.write(buffer, 0, minOf(n, room))
                if (total > CAP_BYTES) truncated = true
            } else {
                truncated = true
            }
        }
        return Captured(kept.toString(Charsets.UTF_8), truncated, total)
    }
}
