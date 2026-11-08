package dev.mule.config

import dev.mule.api.json
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class AppConfigSpec : BehaviorSpec({

    given("a key typed into Settings") {
        val cfg = AppConfig(apiKey = "typed-key", apiKeyEnvVar = "OMLX_API_KEY")

        `when`("resolving the key") {
            then("the typed key wins over the environment") {
                cfg.resolvedApiKey() shouldBe "typed-key"
            }
        }
    }

    given("no typed key") {
        `when`("resolving") {
            then("a named environment variable is used") {
                // HOME exists on any POSIX host, so this exercises the fallback
                // without depending on a project-specific variable being set.
                AppConfig(apiKey = "", apiKeyEnvVar = "HOME")
                    .resolvedApiKey() shouldBe (System.getenv("HOME") ?: "")
            }
            then("a variable that does not exist resolves to empty, not to the literal name") {
                AppConfig(apiKey = "", apiKeyEnvVar = "MULE_DEFINITELY_NOT_SET_XY")
                    .resolvedApiKey() shouldBe ""
            }
            then("having no key at all is reported as such") {
                AppConfig(apiKey = "", apiKeyEnvVar = "MULE_DEFINITELY_NOT_SET_XY").hasKey() shouldBe false
            }
        }
    }

    given("a settings blob written by an older build") {
        val blob = """{"baseUrl":"http://example.test:1234/v1","model":"m1","maxOutputTokens":256}"""

        `when`("reading it back") {
            val cfg = json.decodeFromString(AppConfig.serializer(), blob)

            then("what was stored survives") {
                cfg.baseUrl shouldBe "http://example.test:1234/v1"
                cfg.model shouldBe "m1"
                cfg.maxOutputTokens shouldBe 256
            }
            then("fields added since then fall back to defaults instead of failing the load") {
                cfg.temperature shouldBe 0.7
                cfg.useResponsesApi shouldBe true
                cfg.storeResponses shouldBe true
            }
        }
    }

    given("the resolved key of a default config") {
        `when`("round-tripping through the stored form") {
            val cfg = AppConfig(baseUrl = "http://h:1/v1", apiKey = "k", topP = null)
            val back = json.decodeFromString(AppConfig.serializer(), json.encodeToString(AppConfig.serializer(), cfg))

            then("the base url and key survive verbatim") {
                back.baseUrl shouldBe "http://h:1/v1"
                back.apiKey shouldBe "k"
            }
            then("an unset top_p stays unset rather than becoming zero") {
                back.topP shouldBe null
            }
        }
    }

    given("a config storing no system prompt") {
        `when`("resolving the prompt") {
            then("the built-in English default is used") {
                AppConfig(systemPrompt = "").resolvedSystemPrompt() shouldBe
                    AppConfig.DEFAULT_SYSTEM_PROMPT
            }
            then("text typed by the user wins over the default") {
                AppConfig(systemPrompt = "Answer in haiku.").resolvedSystemPrompt() shouldBe
                    "Answer in haiku."
            }
        }
    }

    given("a settings blob written before language and loopback existed") {
        `when`("reading it back") {
            val cfg = json.decodeFromString(AppConfig.serializer(), """{"model":"m1"}""")

            then("the language falls back to English") {
                cfg.language shouldBe "en"
            }
            then("the server is the local default, not someone else's LAN address") {
                cfg.baseUrl shouldBe "http://127.0.0.1:8000/v1"
            }
            then("the tool loop defaults to asking, never writing silently") {
                cfg.toolMode shouldBe ToolMode.Ask
                cfg.maxToolIterations shouldBe 8
            }
        }
    }
})
