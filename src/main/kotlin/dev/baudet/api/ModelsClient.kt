package dev.baudet.api

import dev.baudet.config.AppConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

object ModelsClient {

    /** GET {base}/models -> sorted ids. */
    fun fetch(cfg: AppConfig): List<ModelInfo> {
        val (status, body) = Http(cfg).get("/models")
        if (status >= 400) throw BackendException(status, body)
        return parseModels(body)
    }

    /**
     * Tolerant of data[] or models[], and of entries missing owned_by or created:
     * a local server that omits metadata should still yield a usable picker.
     * Pure over the body text, so the tolerance is testable.
     */
    fun parseModels(body: String): List<ModelInfo> {
        val root = json.parseToJsonElement(body).jsonObject
        val arr: JsonArray = root.arr("data") ?: root.arr("models") ?: JsonArray(emptyList())
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            ModelInfo(id, o.str("owned_by"), o.long("created"))
        }.sortedBy { it.id.lowercase() }
    }
}
