package dev.baudet.i18n

/**
 * The live language. AppState keeps this in step with the persisted AppConfig,
 * so any component can read [get] without threading the config through.
 */
object Texts {

    var language: Language = Language.EN

    operator fun get(k: Key): String = when (language) {
        Language.EN -> k.en
        Language.NL -> k.nl
        Language.FR -> k.fr
        Language.DE -> k.de
    }

    /** Substitutes {0}, {1}, ... in the active language. */
    fun fmt(k: Key, vararg args: Any): String {
        var s = get(k)
        args.forEachIndexed { i, v -> s = s.replace("{$i}", v.toString()) }
        return s
    }

    /** Anything unrecognized falls back to English rather than failing. */
    fun parse(stored: String): Language =
        when (stored.trim().lowercase()) {
            "nl" -> Language.NL
            "fr" -> Language.FR
            "de" -> Language.DE
            else -> Language.EN
        }
}
