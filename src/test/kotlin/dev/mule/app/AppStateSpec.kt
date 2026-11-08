package dev.mule.app

import dev.mule.config.ToolMode
import dev.mule.i18n.Language
import dev.mule.i18n.Texts
import dev.mule.project.FolderPermission
import dev.mule.project.Permissions
import dev.mule.session.Msg
import dev.mule.store.H2Store
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
        AppState(H2Store(Files.createTempDirectory("mule-app").resolve("db")))

    given("a store holding the most recent conversation") {
        val store = H2Store(Files.createTempDirectory("mule-app").resolve("db"))
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

    given("the tool-loop gate") {
        fun writableState(mode: ToolMode, read: Boolean, write: Boolean, link: Boolean): AppState {
            val state = freshState()
            val p = state.addProject("P")
            if (link) {
                val rights = buildSet {
                    if (read) add(FolderPermission.Read)
                    if (write) add(FolderPermission.Write)
                }
                state.linkFolder(p.id, "/tmp/mule-gate", Permissions.encode(rights))
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
