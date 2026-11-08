package dev.mule.store

import dev.mule.project.FolderPermission
import dev.mule.project.Permissions
import dev.mule.session.Msg
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * Projects own their folder grants and the parent-child link to sessions, and
 * removal promises never to swallow a conversation. Each scenario opens a throwaway
 * database in its own directory, so nothing here can leak into another.
 */
class ProjectStoreSpec : BehaviorSpec({

    fun freshStore(): H2Store =
        H2Store(Files.createTempDirectory("mule-projects").resolve("db"))

    given("a fresh store") {
        val store = freshStore()
        `when`("a project is created") {
            val p = store.createProject("  Notes  ")
            then("the name is trimmed and the row is active by default") {
                p.name shouldBe "Notes"
                p.archived shouldBe false
                p.folderPath shouldBe null
            }
            then("it is the only project listed") {
                store.listProjects().map { it.id } shouldBe listOf(p.id)
            }
        }
        `when`("no session has been written yet") {
            then("the Unassigned bucket is empty rather than a fabricated row") {
                store.sessionsIn(null) shouldBe emptyList()
            }
        }
    }

    given("a project linked to a folder with granted rights") {
        val store = freshStore()
        val p = store.createProject("Repo")
        val bits = Permissions.encode(setOf(FolderPermission.Read, FolderPermission.Execute))
        store.setFolder(p.id, "/tmp/repo", bits)
        `when`("the project is read back") {
            val got = store.listProjects().first { it.id == p.id }
            then("the folder and the granted bitmask round-trip together") {
                got.folderPath shouldBe "/tmp/repo"
                got.permissions shouldBe bits
                got.granted() shouldBe setOf(FolderPermission.Read, FolderPermission.Execute)
                got.isGranted(FolderPermission.Write) shouldBe false
            }
        }
        `when`("the folder is unlinked") {
            store.setFolder(p.id, null, 0)
            then("the path and the grants are cleared at once") {
                val gone = store.listProjects().first { it.id == p.id }
                gone.folderPath shouldBe null
                gone.permissions shouldBe 0
            }
        }
    }

    given("an active project and an archived one") {
        val store = freshStore()
        val a = store.createProject("A")
        val b = store.createProject("B")
        store.setArchived(a.id, true)
        `when`("projects are listed") {
            then("the active one precedes the archived one, regardless of creation order") {
                store.listProjects().map { it.name } shouldBe listOf("B", "A")
            }
        }
    }

    given("a project holding one conversation with a first user turn") {
        val store = freshStore()
        val p = store.createProject("Notes")
        store.upsertSession("s-keep", model = "m", prevResponseId = null, lastSeq = 2, projectId = p.id)
        store.rewriteMessages(
            "s-keep",
            listOf(Msg("user", "first question\nmore detail"), Msg("assistant", "an answer"))
        )
        `when`("the project's sessions are listed") {
            val rows = store.sessionsIn(p.id)
            then("the conversation appears under it, titled by its first line") {
                rows.map { it.id } shouldBe listOf("s-keep")
                rows.first().title shouldBe "first question"
                rows.first().messageCount shouldBe 2
            }
            then("the session points back at its project") {
                store.projectOfSession("s-keep") shouldBe p.id
            }
            then("it is not also visible under Unassigned") {
                store.sessionsIn(null) shouldBe emptyList()
            }
        }
    }

    given("a conversation with a first user turn") {
        val store = freshStore()
        store.upsertSession("s-t", model = "m", prevResponseId = null, lastSeq = 1, projectId = null)
        store.rewriteMessages("s-t", listOf(Msg("user", "auto title"), Msg("assistant", "a")))
        `when`("it is renamed") {
            store.renameSession("s-t", "  My Session  ")
            then("the chosen name wins over the derived one and is trimmed") {
                store.sessionsIn(null).first { it.id == "s-t" }.title shouldBe "My Session"
            }
            then("a later turn persists without clobbering the custom name") {
                store.upsertSession("s-t", model = "m", prevResponseId = "r", lastSeq = 3, projectId = null)
                store.sessionsIn(null).first { it.id == "s-t" }.title shouldBe "My Session"
            }
        }
        `when`("the name is cleared again") {
            store.renameSession("s-t", "   ")
            then("the title falls back to the first user line") {
                store.sessionsIn(null).first { it.id == "s-t" }.title shouldBe "auto title"
            }
        }
    }

    given("an empty session with no project") {
        val store = freshStore()
        store.upsertSession("s-free", model = "m", prevResponseId = null, lastSeq = 0)
        `when`("Unassigned is listed") {
            then("the session is there, titled by its id because no user turn exists yet") {
                val rows = store.sessionsIn(null)
                rows.map { it.id } shouldBe listOf("s-free")
                rows.first().title shouldBe "s-free"
            }
        }
    }

    given("a session linked to a project") {
        val store = freshStore()
        val p = store.createProject("P")
        store.upsertSession("s", model = "m", prevResponseId = null, lastSeq = 0, projectId = p.id)
        `when`("it is re-saved with no project, the authoritative value persist sends") {
            store.upsertSession("s", model = "m", prevResponseId = null, lastSeq = 0, projectId = null)
            then("the old link is cleared rather than left stale") {
                store.projectOfSession("s") shouldBe null
            }
        }
    }

    given("a project holding conversations and linked to a folder on disk") {
        val store = freshStore()
        val p = store.createProject("Temp")
        // One conversation sits in Unassigned; the requirement is that only the
        // project's own conversations are removed, never anybody else's.
        store.upsertSession("s-keep", model = "m", prevResponseId = null, lastSeq = 1, projectId = null)
        store.rewriteMessages("s-keep", listOf(Msg("user", "unassigned kept")))
        store.upsertSession("s-x", model = "m", prevResponseId = null, lastSeq = 1, projectId = p.id)
        store.rewriteMessages("s-x", listOf(Msg("user", "gone")))
        // Linking a folder must never make delete touch that folder.
        val folder = Files.createTempDirectory("mule-folder")
        val onDisk = folder.resolve("note.txt")
        Files.writeString(onDisk, "untouched")
        store.setFolder(p.id, folder.toString(), 0)
        store.deleteProject(p.id)
        `when`("the project is deleted") {
            then("it is no longer listed") {
                store.listProjects().any { it.id == p.id } shouldBe false
            }
            then("its conversation and messages are gone, not moved to Unassigned") {
                store.sessionsIn(p.id) shouldBe emptyList()
                store.messages("s-x") shouldBe emptyList()
            }
            then("an Unassigned conversation is left untouched") {
                store.sessionsIn(null).map { it.id } shouldBe listOf("s-keep")
                store.messages("s-keep") shouldBe listOf(Msg("user", "unassigned kept"))
            }
            then("the folder and its files on disk are never removed") {
                Files.exists(onDisk) shouldBe true
                Files.readString(onDisk) shouldBe "untouched"
            }
        }
        `when`("an unknown project is deleted") {
            then("it is a safe no-op that touches nothing") {
                store.deleteProject("no-such-project")
                store.messages("s-keep") shouldBe listOf(Msg("user", "unassigned kept"))
            }
        }
    }
})
