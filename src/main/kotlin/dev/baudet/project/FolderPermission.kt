package dev.baudet.project

/**
 * A right Baudet can grant itself on a project folder, stored as a bitmask so the set
 * grows by adding an entry rather than by altering the table. Entry names stay
 * PascalCase, matching the house style for enums.
 */
enum class FolderPermission(val bit: Int) {
    Read(1),
    Write(2),
    Execute(4)
}
