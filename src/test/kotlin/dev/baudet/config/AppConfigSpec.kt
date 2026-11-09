package dev.baudet.config

import dev.baudet.api.json
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
                AppConfig(apiKey = "", apiKeyEnvVar = "BAUDET_DEFINITELY_NOT_SET_XY")
                    .resolvedApiKey() shouldBe ""
            }
            then("having no key at all is reported as such") {
                AppConfig(apiKey = "", apiKeyEnvVar = "BAUDET_DEFINITELY_NOT_SET_XY").hasKey() shouldBe false
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

    /*
     * TruncateAtMiddle was retired as a context strategy. A build that stored it keeps that string
     * in its saved blob, and [dev.baudet.store.H2Store.load] resets EVERY setting to default on any
     * decode failure -- so without coercion the whole config, base url, key and model included,
     * would be wiped on the first launch after the upgrade. The Json layer sets coerceInputValues,
     * so a retired enum value reverts to the new default and the rest of the blob rides along.
     */
    given("a settings blob that stored a now-retired context strategy") {
        val blob = """{"baseUrl":"http://h:1/v1","model":"m","apiKey":"k",""" +
            """"contextStrategy":"TruncateAtMiddle","contextBudgetTokens":42}"""

        `when`("reading it back") {
            val back = json.decodeFromString(AppConfig.serializer(), blob)

            then("the retired value coerces to the new default rather than failing the load") {
                back.contextStrategy shouldBe ContextStrategy.RecentMessages
            }
            then("the rest of the stored configuration survives, not reset to defaults") {
                back.baseUrl shouldBe "http://h:1/v1"
                back.apiKey shouldBe "k"
                back.model shouldBe "m"
                back.contextBudgetTokens shouldBe 42
            }
            then("a field the old blob never had falls back to its default count") {
                back.contextKeepRecentMessages shouldBe 15
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
            then("the context strategy survives the round-trip") {
                back.contextStrategy shouldBe ContextStrategy.RecentMessages
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
            then("context defaults to keep-last-turns, with output room left to the server") {
                cfg.contextStrategy shouldBe ContextStrategy.RecentMessages
                cfg.contextKeepRecentMessages shouldBe 15
                cfg.maxOutputTokens shouldBe 0
            }
        }
    }

    given("window geometry") {
        `when`("a fresh config is made") {
            then("nothing is stored yet, so the first launch can fall back to a default") {
                val cfg = AppConfig()
                cfg.windowX shouldBe 0
                cfg.windowY shouldBe 0
                cfg.windowWidth shouldBe 0
                cfg.windowHeight shouldBe 0
                cfg.leftDivider shouldBe 0
                cfg.rightDivider shouldBe 0
                cfg.diffPopupX shouldBe 0
                cfg.diffPopupY shouldBe 0
                cfg.diffPopupWidth shouldBe 0
                cfg.diffPopupHeight shouldBe 0
            }
        }
        `when`("geometry round-trips through the stored form") {
            val cfg = AppConfig(
                windowX = 80, windowY = 60, windowWidth = 1040,
                windowHeight = 720, leftDivider = 280, rightDivider = 700,
                diffPopupX = 40, diffPopupY = 120,
                diffPopupWidth = 900, diffPopupHeight = 640
            )
            val back = json.decodeFromString(
                AppConfig.serializer(), json.encodeToString(AppConfig.serializer(), cfg)
            )
            then("every remembered value survives") {
                back.windowX shouldBe 80
                back.windowY shouldBe 60
                back.windowWidth shouldBe 1040
                back.windowHeight shouldBe 720
                back.leftDivider shouldBe 280
                back.rightDivider shouldBe 700
                back.diffPopupX shouldBe 40
                back.diffPopupY shouldBe 120
                back.diffPopupWidth shouldBe 900
                back.diffPopupHeight shouldBe 640
            }
        }
    }
})
