package dev.baudet.store

import dev.baudet.api.json
import dev.baudet.thread.AgentMessageItem
import dev.baudet.thread.ApprovalDecision
import dev.baudet.thread.CommandExecutionItem
import dev.baudet.thread.ErrorItem
import dev.baudet.thread.FileChangeItem
import dev.baudet.thread.FileChangeKind
import dev.baudet.thread.ImageItem
import dev.baudet.thread.Item
import dev.baudet.thread.ItemKind
import dev.baudet.thread.ItemStatus
import dev.baudet.thread.PlanEntry
import dev.baudet.thread.PlanEntryStatus
import dev.baudet.thread.PlanItem
import dev.baudet.thread.ReasoningItem
import dev.baudet.thread.ToolCallItem
import dev.baudet.thread.UserMessageItem
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.sql.DriverManager

/**
 * The transcript rows the interface shows are stored verbatim so a reload rebuilds the cards
 * rather than flattening them to prose. These tests fix the three things that make that safe:
 * a row survives the JSON round-trip exactly (including a command whose exit code is genuinely
 * null, which must not round to zero); the stored spelling is the protocol spelling, so this
 * seam is ready to speak the wire out loud; and a row that no longer decodes is dropped rather
 * than erasing the conversation around it.
 */
class ItemStoreSpec : BehaviorSpec({

    fun freshStore(): H2Store = H2Store(Files.createTempDirectory("baudet-items").resolve("db"))

    given("one row of every kind") {
        val store = freshStore()
        val id = "s-items"
        val rows = listOf<Item>(
            UserMessageItem("i1", "list the files"),
            AgentMessageItem("i2", "here they are", ItemStatus.Completed),
            ReasoningItem("i3", "thinking", ItemStatus.Completed),
            CommandExecutionItem(
                itemId = "i4",
                command = "git status",
                cwd = "/tmp/repo",
                timeoutSeconds = 30,
                status = ItemStatus.Completed,
                exitCode = null,
                timedOut = true,
                durationMs = 1234L,
                output = "on branch main\n",
                approval = ApprovalDecision.AcceptForSession
            ),
            FileChangeItem("i5", "a.txt", FileChangeKind.Add, ItemStatus.Completed, "--- a\n+++ b\n@@\n+x\n", 1, 0),
            ToolCallItem("i6", "read_file", "a.txt", ItemStatus.Completed, ok = false, output = "denied"),
            PlanItem("i7", listOf(PlanEntry("step", PlanEntryStatus.Completed)), ItemStatus.Completed),
            ErrorItem("i8", "the model stopped early"),
            ImageItem("i9", "mcp__qwen__generate", "image/png", "aGVsbG8=")
        )
        store.rewriteItems(id, rows)
        `when`("reading the rows back") {
            then("every kind survives the round-trip, in order, field for field") {
                store.items(id) shouldBe rows
            }
            then("an exit code that was never produced stays absent, not zero") {
                val cmd = store.items(id).filterIsInstance<CommandExecutionItem>().single()
                cmd.exitCode shouldBe null
                cmd.timedOut shouldBe true
                cmd.approval shouldBe ApprovalDecision.AcceptForSession
            }
        }
        `when`("rewriting with fewer rows, as a shorter transcript does") {
            store.rewriteItems(id, listOf(UserMessageItem("i1", "only this")))
            then("the earlier rows are replaced, not appended") {
                store.items(id) shouldBe listOf(UserMessageItem("i1", "only this"))
            }
        }
    }

    given("the stored spelling") {
        `when`("an enum is encoded") {
            then("it uses the app-server wireName, never the Kotlin constant name") {
                // Read the spelling back through the parser, so this checks the tag value itself
                // rather than a byte-level escaping of it that the format could legitimately change.
                ItemStatus.entries.forEach {
                    spelled(ItemStatus.serializer(), it) shouldBe it.wireName
                }
                ItemKind.entries.forEach {
                    spelled(ItemKind.serializer(), it) shouldBe it.wireName
                }
                ApprovalDecision.entries.forEach {
                    spelled(ApprovalDecision.serializer(), it) shouldBe it.wireName
                }
                FileChangeKind.entries.forEach {
                    spelled(FileChangeKind.serializer(), it) shouldBe it.wireName
                }
                PlanEntryStatus.entries.forEach {
                    spelled(PlanEntryStatus.serializer(), it) shouldBe it.wireName
                }
            }
        }
        `when`("a row is encoded as its base type") {
            then("the polymorphic tag is the kind's wireName") {
                val rows = listOf<Item>(
                    UserMessageItem("i", "x"),
                    AgentMessageItem("i", "x", ItemStatus.Completed),
                    ReasoningItem("i", "x", ItemStatus.Completed),
                    CommandExecutionItem("i", "ls", status = ItemStatus.Completed),
                    FileChangeItem("i", "a"),
                    ToolCallItem("i", "read_file"),
                    PlanItem("i"),
                    ErrorItem("i", "boom"),
                    ImageItem("i", "generate", "image/png", "data")
                )
                rows.forEach { item ->
                    val tag = json.parseToJsonElement(json.encodeToString(Item.serializer(), item))
                        .jsonObject["type"]!!.jsonPrimitive.content
                    tag shouldBe item.kind.wireName
                }
            }
        }
    }

    given("a conversation whose store later carries a row it cannot decode") {
        val dir = Files.createTempDirectory("baudet-corrupt").resolve("db")
        val store = H2Store(dir)
        val id = "s-corrupt"
        store.rewriteItems(id, listOf(UserMessageItem("i1", "kept")))
        // Drop a garbage row straight into the table the store reads, the way a future rename
        // of a row shape would leave a stale row behind: the read must survive it.
        val url = "jdbc:h2:" + dir.resolve("baudet").toAbsolutePath() + ";DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(url, "sa", "").use { c ->
            c.prepareStatement("INSERT INTO items(session_id, seq, payload) VALUES(?, ?, ?)").use { ps ->
                ps.setString(1, id)
                ps.setInt(2, 1)
                ps.setString(3, "{\"type\":\"no_such_kind\"}")
                ps.executeUpdate()
            }
        }
        `when`("reading the rows back") {
            then("the readable row returns and the unreadable one is dropped, not fatal") {
                store.items(id) shouldBe listOf(UserMessageItem("i1", "kept"))
            }
        }
    }

    given("deleting a conversation or its whole project") {
        `when`("a session is deleted") {
            val store = freshStore()
            store.upsertSession("s-del", model = "m", prevResponseId = null, lastSeq = 1)
            store.rewriteItems("s-del", listOf(AgentMessageItem("i", "x", ItemStatus.Completed)))
            store.deleteSession("s-del")
            then("its rows are gone with it") {
                store.items("s-del") shouldBe emptyList()
            }
        }
        `when`("a project is deleted") {
            val store = freshStore()
            val project = store.createProject("P")
            store.upsertSession("s-proj", model = "m", prevResponseId = null, lastSeq = 1, projectId = project.id)
            store.rewriteItems("s-proj", listOf(AgentMessageItem("i", "x", ItemStatus.Completed)))
            store.deleteProject(project.id)
            then("the rows of the project's sessions are cascade-deleted") {
                store.items("s-proj") shouldBe emptyList()
            }
        }
    }
    given("a long transcript paged for a windowed read") {
        val store = freshStore()
        val id = "s-page"
        store.rewriteItems(id, (0 until 250).map { UserMessageItem("i$it", "row $it") })
        `when`("the newest window is read") {
            val w = store.itemsWindow(id, 100)
            then("it holds the newest rows, oldest first") {
                w.items.size shouldBe 100
                w.items.first() shouldBe UserMessageItem("i150", "row 150")
                w.items.last() shouldBe UserMessageItem("i249", "row 249")
            }
            then("it reports the older boundary and that more precede it") {
                w.olderSeq shouldBe 150
                w.hasMoreOlder shouldBe true
            }
        }
        `when`("the next older window is read before that boundary") {
            val w = store.itemsWindow(id, 100, beforeSeq = 150)
            then("it yields the next hundred rows, oldest first, still with older behind") {
                w.items.size shouldBe 100
                w.items.first() shouldBe UserMessageItem("i50", "row 50")
                w.items.last() shouldBe UserMessageItem("i149", "row 149")
                w.olderSeq shouldBe 50
                w.hasMoreOlder shouldBe true
            }
        }
        `when`("a window reaches the start of the conversation") {
            val w = store.itemsWindow(id, 100, beforeSeq = 50)
            then("the remaining rows come back and nothing is reported as older") {
                w.items.size shouldBe 50
                w.items.first() shouldBe UserMessageItem("i0", "row 0")
                w.olderSeq shouldBe 0
                w.hasMoreOlder shouldBe false
            }
        }
        `when`("the whole conversation fits one page") {
            val w = store.itemsWindow(id, 300)
            then("every row returns and nothing older is reported") {
                w.items.size shouldBe 250
                w.hasMoreOlder shouldBe false
            }
        }
    }
})

/** Encode an enum with its generated serializer and read the primitive string back, decoder-side. */
private fun <T> spelled(serializer: kotlinx.serialization.KSerializer<T>, value: T): String =
    json.parseToJsonElement(json.encodeToString(serializer, value)).jsonPrimitive.content
