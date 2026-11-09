package dev.baudet.project

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The permission bitmask is the contract behind every folder grant, and it is pure
 * arithmetic, so it is pinned here without a database. If the bits ever collide or a
 * toggle stops sticking, these scenarios fail before the store can hide it.
 */
class FolderPermissionsSpec : BehaviorSpec({

    given("an empty permission set") {
        `when`("encoded") {
            then("the bitmask is zero") { Permissions.encode(emptySet()) shouldBe 0 }
        }
        `when`("decoded from zero") {
            then("the set comes back empty") { Permissions.decode(0) shouldBe emptySet() }
        }
    }

    given("each single right") {
        then("read, write and execute hold distinct bits") {
            FolderPermission.Read.bit shouldBe 1
            FolderPermission.Write.bit shouldBe 2
            FolderPermission.Execute.bit shouldBe 4
        }
        then("each one round-trips through encode and decode") {
            FolderPermission.entries.forEach { p ->
                Permissions.decode(Permissions.encode(setOf(p))) shouldBe setOf(p)
            }
        }
    }

    given("several rights together") {
        val bits = Permissions.encode(setOf(FolderPermission.Read, FolderPermission.Write))
        `when`("decoded") {
            then("both come back") {
                Permissions.decode(bits) shouldBe setOf(FolderPermission.Read, FolderPermission.Write)
            }
        }
        `when`("probed with has") {
            then("a granted right reads true and a missing one false") {
                Permissions.has(bits, FolderPermission.Read) shouldBe true
                Permissions.has(bits, FolderPermission.Execute) shouldBe false
            }
        }
    }

    given("a bitmask toggled on and off") {
        val withWrite = Permissions.with(Permissions.encode(setOf(FolderPermission.Read)), FolderPermission.Write, true)
        val withoutRead = Permissions.with(withWrite, FolderPermission.Read, false)
        then("the toggled-on right is present") {
            Permissions.has(withWrite, FolderPermission.Write) shouldBe true
        }
        then("the toggled-off right is gone while the survivor stays") {
            Permissions.has(withoutRead, FolderPermission.Read) shouldBe false
            Permissions.has(withoutRead, FolderPermission.Write) shouldBe true
        }
    }
})
