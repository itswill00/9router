package com.ninerouter.android.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builds the upstream embedding payload from an OpenAI-compatible request. */
internal object EmbeddingsApi {
    private val passthrough = listOf("dimensions", "encoding_format", "user")

    fun payload(body: JsonObject, upstreamModel: String): JsonObject = buildJsonObject {
        put("model", upstreamModel)
        put("input", body["input"] ?: JsonPrimitive(""))
        for (field in passthrough) {
            body[field]?.let { put(field, it) }
        }
    }
}
