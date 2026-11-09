package dev.baudet.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tolerant JSON layer. Local backends deviate from the documented shape, so
 * anything that can be missing here is allowed to be missing; otherwise the
 * app would fall over on every variation.
 */
val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
    // A saved value that no longer fits its type falls back to the property default instead of
    // throwing. Removing a Settings choice, such as a retired context strategy, must not make the
    // whole stored configuration undecodable: [dev.baudet.store.H2Store.load] resets every setting
    // to its default on any decode failure, so one stale value would wipe the base url, key and
    // model too. With coercion a retired value simply reverts to the new default and the rest rides
    // along untouched.
    coerceInputValues = true
}

/** JsonNull is a JsonPrimitive singleton, so it has to be filtered explicitly. */
private fun JsonElement?.prim(): JsonPrimitive? =
    (this?.takeIf { it !is JsonNull }) as? JsonPrimitive

fun JsonElement?.text(): String? = prim()?.content

fun JsonElement?.longVal(): Long? = prim()?.content?.trim()?.toLongOrNull()

fun JsonObject.str(key: String): String? = this[key].text()

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.long(key: String): Long? = this[key].longVal()
