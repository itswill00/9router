package com.ninerouter.android.server

import com.ninerouter.android.core.TokenEstimator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure request/response shaping for the utility `/v1` routes so the gateway
 * dispatch stays thin and the contracts are unit-testable without a socket.
 */
internal object CompatibilityRoutes {
    private val json = Json { ignoreUnknownKeys = true }

    data class Response(val status: Int, val body: String)

    data class CountTokensResult(val status: Int, val body: String, val model: String?)

    fun countTokens(rawBody: String): CountTokensResult {
        val obj = try { json.parseToJsonElement(rawBody) as? JsonObject } catch (_: Exception) { null }
            ?: return CountTokensResult(400, errorBody(400, "Invalid JSON body"), null)
        val model = (obj["model"] as? JsonPrimitive)?.content
        val body = json.encodeToString(buildJsonObject {
            put("input_tokens", TokenEstimator.estimateAnthropicInputTokens(obj))
        })
        return CountTokensResult(200, body, model)
    }

    /**
     * `responses/compact` maps to Codex server-side compaction, which the
     * native gateway cannot reproduce. Fail with a structured 501 rather than
     * silently running an ordinary completion.
     */
    fun compact(): Response = Response(
        501,
        json.encodeToString(buildJsonObject {
            put("error", buildJsonObject {
                put(
                    "message",
                    "responses/compact requires server-side compaction that the Android gateway does not implement",
                )
                put("type", "unsupported_operation")
                put("code", "unsupported_operation")
            })
        }),
    )

    private fun errorBody(status: Int, message: String): String = json.encodeToString(buildJsonObject {
        put("error", buildJsonObject {
            put("message", message)
            put("type", "android_gateway_error")
            put("code", status)
        })
    })
}
