package dev.baudet

import java.util.Properties

/**
 * The version and build time this binary was made from, read from a resource Maven filters at
 * build time. Because the resource comes from the pom, the About box can never drift onto a number
 * that does not match what shipped. A build that for some reason lacks the resource -- or that ran
 * without filtering, which would leave the raw `${...}` token in place -- reports "dev" rather
 * than a stale version.
 */
object BuildInfo {

    private val props: Properties? = try {
        BuildInfo::class.java.getResourceAsStream("/baudet-build.properties")
            ?.use { stream -> Properties().apply { load(stream) } }
    } catch (_: Exception) {
        null
    }

    /** A filtered value, or null when absent or left as an unsubstituted `${...}` placeholder. */
    private fun filtered(key: String): String? =
        props?.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("\u0024{") }

    val version: String = filtered("version") ?: "dev"
    val built: String = filtered("built") ?: ""
}
