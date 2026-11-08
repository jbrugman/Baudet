package dev.mule.api

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
