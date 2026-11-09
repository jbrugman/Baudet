package dev.baudet.app

import dev.baudet.config.ContextStrategy
import dev.baudet.config.ToolMode
import dev.baudet.i18n.Language
import dev.baudet.i18n.Texts
import dev.baudet.project.FolderPermission
import dev.baudet.project.Permissions
import dev.baudet.session.Msg
import dev.baudet.store.H2Store
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * AppState is the seam between config, session and persistence, and until now it
 * carried no tests of its own. These cover the offline surface -- opening, creating
 * and deleting projects and conversations, and persisting a config -- not the
 * network-bound send()/compact(), which need a live backend.
 */
class AppStateSpec : BehaviorSpec({

    afterEach { Texts.language = Language.EN }

    fun freshState(): AppState =
        AppState(H2Store(Files.createTempDirectory("baudet-app").resolve("db")))

    given("a store holding the most recent conversation") {
        val store = H2Store(Files.createTempDirectory("baudet-app").resolve("db"))
        store.upsertSession("s-last", model = "m", prevResponseId = "resp", lastSeq = 1)
        store.rewriteMessages("s-last", listOf(Msg("user", "hi")))
        `when`("AppState opens on that store") {
            val state = AppState(store)
            then("the conversation is restored into the live session") {
                state.session.sessionId shouldBe "s-last"
                state.session.messages.map { it.content } shouldBe listOf("hi")
            }
        }
    }

    given("a fresh AppState with a project selected") {
        val state = freshState()
        val p = state.addProject("Notes")
        state.currentProjectId = p.id
        `when`("a new session is started") {
            val s = state.newSession()
            then("it belongs to the selected project") {
                s.projectId shouldBe p.id
                state.sessionsIn(p.id).map { it.id } shouldBe listOf(s.sessionId)
            }
        }
    }

    given("a project holding one conversation, with the view elsewhere") {
        val state = freshState()
        val p = state.addProject("P")
        val s = state.newSession(p.id)
        state.newSession() // move the live session to the Unassigned bucket
        `when`("that project conversation is loaded") {
            state.loadSession(s.sessionId)
            then("the current project follows the loaded conversation") {
                state.currentProjectId shouldBe p.id
                state.session.sessionId shouldBe s.sessionId
            }
        }
    }

    given("a fresh AppState") {
        val state = freshState()
        var savedModel: String? = null
        state.onConfigSaved = { savedModel = it.model }
        `when`("a config is saved") {
            state.saveConfig(state.config.copy(model = "qwen", language = "nl"))
            then("the live session adopts the model") {
                state.session.model shouldBe "qwen"
            }
            then("the change is persisted to the store") {
                state.store.load()?.model shouldBe "qwen"
            }
            then("the saved-config callback fires with the new model") {
                savedModel shouldBe "qwen"
            }
            then("the chosen language is persisted in the config") {
                state.store.load()?.language shouldBe "nl"
            }
        }
    }

    given("an AppState whose config switches to Dutch") {
        val state = freshState()
        `when`("that config is saved") {
            state.saveConfig(state.config.copy(language = "nl"))
            then("the live language adopts it immediately") {
                Texts.language shouldBe Language.NL
            }
        }
    }

    given("an AppState whose project holds the open conversation") {
        val state = freshState()
        val p = state.addProject("P")
        state.newSession(p.id)
        state.currentProjectId = p.id
        `when`("the project is deleted") {
            state.deleteProject(p.id)
            then("the project is gone") {
                state.projects() shouldBe emptyList()
            }
            then("its conversation is gone, not moved to Unassigned") {
                state.sessionsIn(p.id) shouldBe emptyList()
                state.sessionsIn(null) shouldBe emptyList()
            }
            then("the current project clears") {
                state.currentProjectId shouldBe null
            }
            then("a fresh empty conversation replaces the deleted one") {
                state.session.messages shouldBe emptyList()
            }
        }
    }

    given("an AppState with one open conversation") {
        val state = freshState()
        val openId = state.newSession().sessionId
        `when`("that open conversation is deleted") {
            state.deleteSession(openId)
            then("a different, empty conversation takes its place") {
                (state.session.sessionId == openId) shouldBe false
                state.session.messages shouldBe emptyList()
            }
            then("nothing is left behind in the store") {
                state.sessionsIn(null) shouldBe emptyList()
            }
        }
    }

    given("a project whose chat has not been started") {
        val state = freshState()
        val p = state.addProject("P")
        `when`("the project is opened through startSessionIn") {
            val s = state.startSessionIn(p.id)
            then("it is bound to the project but stays unsaved") {
                s.projectId shouldBe p.id
                state.sessionsIn(p.id) shouldBe emptyList()
            }
            then("a later new session persists while the unsaved one never appears") {
                val saved = state.newSession(p.id)
                state.sessionsIn(p.id).map { it.id } shouldBe listOf(saved.sessionId)
            }
        }
    }

    given("two conversations with the newest one open") {
        val state = freshState()
        val oldId = state.newSession().sessionId
        val cur = state.newSession()
        `when`("the older, not-open conversation is deleted") {
            state.deleteSession(oldId)
            then("the open conversation stays put") {
                state.session.sessionId shouldBe cur.sessionId
            }
            then("only the deleted conversation is gone from the list") {
                state.sessionsIn(null).map { it.id } shouldBe listOf(cur.sessionId)
            }
        }
    }

    /**
     * The guard that keeps an oversized conversation from being sent at all. It has to agree with
     * the number the status line shows, or the user is refused for a reason the app never
     * displayed, and it has to stay silent when the budget is switched off.
     */
    given("a conversation over the configured context budget") {
        val state = freshState()
        state.saveConfig(state.config.copy(
            contextBudgetTokens = 100, contextStrategy = ContextStrategy.StopAtLimit
        ))
        state.session.messages.add(Msg("user", "a".repeat(4_000)))
        `when`("asking whether this may be sent") {
            val reason = state.contextGuard()
            then("it is refused, and the refusal stays calm about raw token counts") {
                (reason != null) shouldBe true
                // The numbers belong in the status line, not in an error the user reads.
                (reason!!.contains("100")) shouldBe false
            }
        }
    }

    given("a conversation inside the budget") {
        val state = freshState()
        state.saveConfig(state.config.copy(contextBudgetTokens = 100_000))
        state.session.messages.add(Msg("user", "short question"))
        `when`("asking whether this may be sent") {
            then("nothing holds it back") { state.contextGuard() shouldBe null }
        }
    }

    /*
     * The automatic strategies are the whole point of the new default: a conversation that
     * outgrows the model is never refused here, because send bounds the window it sends instead.
     * Only StopAtLimit refuses, so a refusal must not appear under the other two.
     */
    given("a large conversation under an automatic strategy") {
        `when`("the strategy keeps the last turns") {
            val state = freshState()
            state.saveConfig(state.config.copy(
                contextBudgetTokens = 100, contextStrategy = ContextStrategy.RecentMessages
            ))
            state.session.messages.add(Msg("user", "a".repeat(400_000)))
            then("the guard never bothers") { state.contextGuard() shouldBe null }
        }
        `when`("the strategy keeps a rolling window") {
            val state = freshState()
            state.saveConfig(state.config.copy(
                contextBudgetTokens = 100, contextStrategy = ContextStrategy.RollingWindow
            ))
            state.session.messages.add(Msg("user", "a".repeat(400_000)))
            then("the guard is silent here too") { state.contextGuard() shouldBe null }
        }
    }

    given("a budget of zero against a model whose window is unknown") {
        val state = freshState()
        state.saveConfig(state.config.copy(contextBudgetTokens = 0))
        state.session.messages.add(Msg("user", "a".repeat(400_000)))
        `when`("asking whether this may be sent") {
            then("the estimate is not consulted, so the user decides") {
                state.contextGuard() shouldBe null
            }
        }
    }

    /*
     * The budget is a number typed into Settings, so it can sit above the window the model really
     * has -- which is exactly how a 128k backend ends up handed a 129k prompt. Where the window is
     * known the cap is not a preference: it applies whatever the field says, and it stands in for the
     * field when the field is 0. Where the window is unknown nothing is invented, and the field rules.
     */
    given("a budget typed above the window the model really has") {
        `when`("asking whether an oversized conversation may be sent") {
            val state = freshState()
            state.saveConfig(state.config.copy(
                model = "gpt-4o", contextBudgetTokens = 400_000,
                contextStrategy = ContextStrategy.StopAtLimit
            ))
            state.session.messages.add(Msg("user", "a".repeat(400_000)))
            val reason = state.contextGuard()
            then("the real window refuses it, so the typed number is not the last word") {
                (reason != null) shouldBe true
                (reason!!.contains("400000")) shouldBe false
            }
            then("the refusal stays calm: it names no raw window token count either") {
                (reason!!.contains("123904")) shouldBe false
            }
        }
        `when`("the status line is read on the same conversation") {
            val state = freshState()
            state.saveConfig(state.config.copy(model = "gpt-4o", contextBudgetTokens = 400_000))
            state.session.messages.add(Msg("user", "a".repeat(400_000)))
            then("the bar shows where folding starts, not the number in Settings") {
                // The bar deliberately marks the early ceiling -- three quarters of the window,
                // where the user can still act -- rather than the point of refusal, which is what
                // the guard names. Either way the typed 400000 must not appear: it is not a window.
                (state.contextWarning().contains("96000")) shouldBe true
                (state.contextWarning().contains("400000")) shouldBe false
            }
        }
        `when`("the conversation still fits inside the window") {
            val state = freshState()
            state.saveConfig(state.config.copy(model = "gpt-4o", contextBudgetTokens = 400_000))
            state.session.messages.add(Msg("user", "short question"))
            then("a cap that low would be a bug, so nothing is held back") {
                state.contextGuard() shouldBe null
            }
        }
    }

    given("a budget of zero against a model whose window is known") {
        `when`("asking whether an oversized conversation may be sent") {
            val state = freshState()
            state.saveConfig(state.config.copy(
                model = "gpt-4o", contextBudgetTokens = 0,
                contextStrategy = ContextStrategy.StopAtLimit
            ))
            state.session.messages.add(Msg("user", "a".repeat(400_000)))
            then("the window still refuses under the stop strategy, even with the budget off") {
                (state.contextGuard() != null) shouldBe true
            }
        }
    }

    given("a budget below the window") {
        `when`("asking whether a conversation over that budget may be sent") {
            val state = freshState()
            state.saveConfig(state.config.copy(
                model = "gpt-4o", contextBudgetTokens = 100,
                contextStrategy = ContextStrategy.StopAtLimit
            ))
            state.session.messages.add(Msg("user", "a".repeat(4_000)))
            then("the user's own smaller budget still wins, so keeping a chat cheap holds") {
                (state.contextGuard() != null) shouldBe true
            }
        }
    }

    /*
     * The window learned from a refusal is the only trustworthy number for a model this table has
     * never heard of. It is stored under the server and model it was learned on, because a number
     * carried over to a different one is worse than knowing nothing: it would cap a 128k model to the
     * window of a 32k one, or the other way round.
     */
    given("a refusal that named the window") {
        `when`("it is recorded") {
            val state = freshState()
            state.learnContextWindow(65_536)
            then("it is persisted, so the next start guards against it too") {
                (state.store.load()?.learnedContextWindow) shouldBe 65_536
            }
            then("it caps a budget the model never had") {
                state.saveConfig(state.config.copy(
                    contextBudgetTokens = 400_000, contextStrategy = ContextStrategy.StopAtLimit
                ))
                state.session.messages.add(Msg("user", "a".repeat(200_000)))
                (state.contextGuard() != null) shouldBe true
            }
            then("it beats the table for a model the table does know") {
                state.saveConfig(state.config.copy(model = "gpt-4o"))
                // The key no longer matches, so the learned number is gone and the table answers.
                state.contextWindowTokens() shouldBe 128_000
            }
        }
        `when`("the model it was learned on is switched away from") {
            val state = freshState()
            state.learnContextWindow(65_536)
            state.saveConfig(state.config.copy(model = "something-else"))
            then("the stale number is dropped instead of inherited") {
                state.contextWindowTokens() shouldBe null
            }
        }
        `when`("the refusal named no number") {
            val state = freshState()
            state.learnContextWindow(null)
            then("nothing is stored, so unknown stays unknown") {
                state.config.learnedContextWindow shouldBe 0
            }
        }
    }

    given("pinning a command to always allow") {
        // mvn is a payload tool, not a provably read-only command, so it pins to its whole trimmed text
        // rather than a reusable signature: only the exact command approved is trusted after a restart.
        val state = freshState()
        `when`("a command is allowed always") {
            state.allowCommandAlways("mvn -B test")
            then("the exact command is persisted, so a restart keeps the decision") {
                (state.store.load()?.autoAllowCommands?.contains("mvn -B test")) shouldBe true
            }
            then("repeating it does not add a duplicate") {
                state.allowCommandAlways("mvn -B test")
                state.store.load()?.autoAllowCommands?.count { it == "mvn -B test" } shouldBe 1
            }
        }
    }


    given("remembering the reasoning dock height") {
        val state = freshState()
        `when`("the user drags the dock divider") {
            state.rememberReasoningDivider(123)
            then("the live config keeps the dragged height") {
                state.config.reasoningDivider shouldBe 123
            }
            then("the height is persisted, so a restart keeps it") {
                state.store.load()?.reasoningDivider shouldBe 123
            }
        }
        `when`("a non-positive height arrives") {
            state.rememberReasoningDivider(123)
            state.rememberReasoningDivider(0)
            then("a reset never overwrites the stored height") {
                state.config.reasoningDivider shouldBe 123
            }
        }
    }

    given("the tool-loop gate") {
        fun writableState(mode: ToolMode, read: Boolean, write: Boolean, link: Boolean): AppState {
            val state = freshState()
            val p = state.addProject("P")
            if (link) {
                val rights = buildSet {
                    if (read) add(FolderPermission.Read)
                    if (write) add(FolderPermission.Write)
                }
                state.linkFolder(p.id, "/tmp/baudet-gate", Permissions.encode(rights))
            }
            state.startSessionIn(p.id)
            state.saveConfig(state.config.copy(toolMode = mode))
            return state
        }
        `when`("tools are off, even on a writable project") {
            val state = writableState(ToolMode.Off, read = true, write = true, link = true)
            then("the loop never activates") { state.toolsActive() shouldBe false }
            then("the gate reports tools-off, not a missing folder or right") {
                state.toolGate() shouldBe ToolGate.Disabled
            }
        }
        `when`("ask is on over a project with the write right") {
            val state = writableState(ToolMode.Ask, read = true, write = true, link = true)
            then("the loop activates") { state.toolsActive() shouldBe true }
            then("the gate reports it active") { state.toolGate() shouldBe ToolGate.Active }
        }
        `when`("the folder is linked read-only") {
            val state = writableState(ToolMode.Ask, read = true, write = false, link = true)
            then("the loop stays off") { state.toolsActive() shouldBe false }
            then("the gate names the missing write right") { state.toolGate() shouldBe ToolGate.NoWrite }
        }
        `when`("the project has no linked folder") {
            val state = writableState(ToolMode.Auto, read = true, write = true, link = false)
            then("there is nothing to write into, so the loop stays off") {
                state.toolsActive() shouldBe false
            }
            then("the gate names the missing folder") { state.toolGate() shouldBe ToolGate.NoFolder }
        }
        `when`("auto is on over a writable project") {
            val state = writableState(ToolMode.Auto, read = true, write = true, link = true)
            then("the loop activates") { state.toolsActive() shouldBe true }
            then("the gate reports it active") { state.toolGate() shouldBe ToolGate.Active }
        }
        `when`("the conversation belongs to no project") {
            val state = freshState()
            state.saveConfig(state.config.copy(toolMode = ToolMode.Ask))
            then("the gate names the missing project") { state.toolGate() shouldBe ToolGate.NoProject }
        }
    }
})
