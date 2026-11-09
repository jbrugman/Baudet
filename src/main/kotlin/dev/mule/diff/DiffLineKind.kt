package dev.mule.diff

/** Category of one unified-diff line, used to pick a viewer color. */
enum class DiffLineKind { Context, Added, Removed, FileHeader, HunkHeader, Meta }
