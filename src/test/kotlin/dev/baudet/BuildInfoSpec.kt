package dev.baudet

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The About box now reports its real version, so the resource Maven filters at build time must be
 * present and substituted on the classpath: the test classpath is the same target/classes the app
 * ships, so a filtered value here is proof the About box will show a true version, not "dev".
 */
class BuildInfoSpec : BehaviorSpec({
    given("the build-info resource Maven filtered into target/classes") {
        `when`("reading it from the classpath") {
            then("it carries a substituted version, not the dev fallback or an unsubstituted token") {
                (BuildInfo.version != "dev") shouldBe true
                BuildInfo.version.matches(Regex("\\d+\\.\\d+\\.\\d+.*")) shouldBe true
            }
        }
    }
})
