package dev.baudet.project

/**
 * A project groups conversations and may point at a folder on disk. The folder is
 * never touched on its own: Baudet records which rights it grants itself there (read,
 * write, execute). Only Read is enforced at runtime today; Write and Execute are
 * stored now so the file tools that arrive later can honor them without a schema
 * migration. The grants are this app's own model, not an OS/TCC permission.
 */
data class Project(
    val id: String,
    var name: String,
    var folderPath: String? = null,
    var permissions: Int = 0,
    var archived: Boolean = false,
    val createdAt: Long
) {
    /** The granted rights decoded from the stored bitmask. */
    fun granted(): Set<FolderPermission> = Permissions.decode(permissions)

    fun isGranted(p: FolderPermission): Boolean = Permissions.has(permissions, p)
}
