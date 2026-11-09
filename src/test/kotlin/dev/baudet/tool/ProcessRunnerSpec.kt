package dev.baudet.tool

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createTempDirectory

/**
 * Locks the shared process run that both the command tool and the git tool now delegate to,
 * so the capped, concurrent drain and the timeout kill are proven once against the real shell
 * rather than only through the two tools. Portability over a fixed interpreter: every run goes
 * through sh -c, which is present on both macOS and Linux, so the assertions hold on CI too.
 */
class ProcessRunnerSpec : FunSpec({

    val dir = createTempDirectory("baudet-proc-").toFile()
    afterSpec { dir.delete() }

    fun sh(script: String, timeoutSeconds: Long = 15): ProcessRunner.Result =
        ProcessRunner.run(listOf("sh", "-c", script), dir, timeoutSeconds)

    test("a successful command reports a zero exit and keeps its stdout") {
        val result = sh("echo hello")
        result.exitCode shouldBe 0
        result.timedOut shouldBe false
        result.stdout.text shouldContain "hello"
        result.body() shouldContain "hello"
    }

    test("a non-zero exit is reported rather than thrown") {
        val result = sh("exit 3")
        result.exitCode shouldBe 3
        result.timedOut shouldBe false
    }

    test("stderr is captured as its own labeled section") {
        val result = sh("echo oops 1>&2")
        result.stderr.text shouldContain "oops"
        result.body() shouldContain "--- stderr"
    }

    test("a chatty stream keeps its head and tail, marks the middle, and is still drained") {
        val result = sh("{ printf HEAD; yes M | head -c 200000; printf TAIL; }")
        result.stdout.truncated shouldBe true
        (result.stdout.length >= 200000) shouldBe true
        // The very start and the very end survive the cap; only the middle is dropped.
        result.stdout.text.startsWith("HEAD") shouldBe true
        result.stdout.text.endsWith("TAIL") shouldBe true
        // The dropped middle is named, and the kept text stays small.
        result.stdout.text shouldContain "bytes omitted"
        (result.stdout.text.length < 100_000) shouldBe true
    }

    test("a run past its timeout is killed and reported as timed out") {
        val result = sh("sleep 5", timeoutSeconds = 1)
        result.timedOut shouldBe true
        result.exitCode shouldBe null
    }
})
