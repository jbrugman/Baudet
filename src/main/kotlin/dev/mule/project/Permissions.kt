package dev.mule.project

/** Bitmask helpers for the permission set: pure, so they test without a database. */
object Permissions {
    fun encode(permissions: Set<FolderPermission>): Int =
        permissions.fold(0) { acc, p -> acc or p.bit }

    fun decode(bits: Int): Set<FolderPermission> =
        FolderPermission.entries.filter { bits and it.bit != 0 }.toSet()

    fun has(bits: Int, p: FolderPermission): Boolean = bits and p.bit != 0

    fun with(bits: Int, p: FolderPermission, granted: Boolean): Int =
        if (granted) bits or p.bit else bits and p.bit.inv()
}
