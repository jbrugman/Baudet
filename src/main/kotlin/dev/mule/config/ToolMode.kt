package dev.mule.config

/**
 * When the assistant may use tools during a turn. [Off] keeps the tool loop out
 * entirely, [Ask] previews each file change for approval, [Auto] writes directly.
 * The declaration order also drives the Settings combo index, so keep Off|Ask|Auto.
 */
enum class ToolMode { Off, Ask, Auto }
