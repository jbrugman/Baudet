package dev.mule.app

/**
 * Why the current conversation can or cannot enter the tool loop. One value, the
 * first condition that holds, checked in the same precedence the gate itself uses.
 * The UI shows a message for the reasons that mean the user likely expected file work
 * but the folder does not allow it, and stays quiet for the reasons that are already
 * an explicit choice (tools switched off, or a chat with no project at all).
 */
enum class ToolGate {
    /** Tools are switched off in Settings, so no turn ever reaches the loop. */
    Disabled,

    /** The conversation belongs to no project, so there is no folder in play. */
    NoProject,

    /** A project is chosen but points at no folder, so there is nothing to read or change. */
    NoFolder,

    /** The folder is linked but Write is not granted, so edits cannot land. */
    NoWrite,

    /** Everything is in place: tools may run over a writable folder. */
    Active
}
