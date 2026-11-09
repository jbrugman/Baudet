package dev.baudet.api

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The handle behind the Stop button. Its whole job is to turn a press into two effects the JVM
 * cannot get from an interrupt alone: a flag the worker checks at every stream boundary, and the
 * closing of the live socket so a read already blocked on the backend unwinds now. Both orderings
 * matter, because Stop can land before the worker has opened the request or while it is reading.
 */
class CancellationTokenSpec : BehaviorSpec({

    /** A body that records how often it was closed, standing in for the live SSE stream. */
    class FakeBody : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }

    given("a fresh token") {
        val token = CancellationToken()
        `when`("nothing has asked to stop yet") {
            then("it is not cancelled") {
                token.isCancelled shouldBe false
            }
        }
        `when`("the user stops") {
            token.cancel()
            then("it reports itself cancelled") {
                token.isCancelled shouldBe true
            }
        }
    }

    given("a token with a request already streaming") {
        val token = CancellationToken()
        val body = FakeBody()
        token.attach(body)
        `when`("the user stops mid-stream") {
            token.cancel()
            then("the live body is closed so the blocked read releases") {
                body.closes shouldBe 1
            }
        }
    }

    given("a token stopped before the worker opened its request") {
        val token = CancellationToken()
        token.cancel()
        val body = FakeBody()
        `when`("the worker then attaches its body") {
            token.attach(body)
            then("the doomed body is closed at once rather than left to stream") {
                body.closes shouldBe 1
            }
        }
    }

    given("a body whose close throws, as a dead socket can") {
        val token = CancellationToken()
        token.attach(object : AutoCloseable {
            override fun close() = throw java.io.IOException("already gone")
        })
        `when`("the user stops") {
            then("the failing close is swallowed so the cancellation still stands") {
                shouldNotThrowAny { token.cancel() }
                token.isCancelled shouldBe true
            }
        }
    }

    given("a token that is never stopped") {
        `when`("a body is attached and no stop follows") {
            val body = FakeBody()
            CancellationToken().attach(body)
            then("the body is left open; closing belongs only to a stop") {
                body.closes shouldBe 0
            }
        }
    }

    given("the shared token used by callers with nothing to stop") {
        `when`("nothing cancels it") {
            then("it reports itself uncancelled, so a default request streams on") {
                CancellationToken.NONE.isCancelled shouldBe false
            }
        }
    }
})
