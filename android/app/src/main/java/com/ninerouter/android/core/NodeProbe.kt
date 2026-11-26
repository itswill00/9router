package com.ninerouter.android.core

import com.ninerouter.android.data.ProviderNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Custom (manually added) OpenAI/Anthropic-compatible endpoints.
 *
 * The desktop dashboard stores a base URL (usually ending in `/v1`) and appends
 * `/chat/completions`, `/messages`, or `/responses` at request time. This mirrors
 * that and adds the two actions the dashboard exposes: fetch the model list and
 * run an inference test.
 */
object NodeProbe {
    data class ModelsResult(val models: List<String>, val error: String? = null)
    data class TestResult(val ok: Boolean, val message: String, val latencyMs: Long = 0L)

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaJson = "application/json; charset=utf-8".toMediaType()

    fun isAnthropic(node: ProviderNode): Boolean =
        node.type.startsWith("anthropic", ignoreCase = true)

    fun isResponses(node: ProviderNode): Boolean =
        !isAnthropic(node) && node.apiType.equals("responses", ignoreCase = true)

    fun typeLabel(node: ProviderNode): String = when {
        isAnthropic(node) -> "Anthropic compatible"
        isResponses(node) -> "OpenAI · Responses API"
        else -> "OpenAI · Chat Completions"
    }

    /** Base root without a trailing endpoint suffix (for /models). */
    fun rootUrl(node: ProviderNode): String {
        var base = node.baseUrl.trim().trimEnd('/')
        for (suffix in listOf("/chat/completions", "/responses", "/messages", "/models")) {
            if (base.endsWith(suffix, ignoreCase = true)) {
                base = base.dropLast(suffix.length).trimEnd('/')
                break
            }
        }
        return base
    }

    fun chatUrl(node: ProviderNode): String {
        val raw = node.baseUrl.trim().trimEnd('/')
        if (raw.endsWith("/chat/completions", true) ||
            raw.endsWith("/responses", true) ||
            raw.endsWith("/messages", true)
        ) {
            return raw
        }
        return when {
            isAnthropic(node) -> "$raw/messages"
            isResponses(node) -> "$raw/responses"
            else -> "$raw/chat/completions"
        }
    }

    fun modelsUrl(node: ProviderNode): String = "${rootUrl(node)}/models"

    suspend fun fetchModels(node: ProviderNode): ModelsResult = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(modelsUrl(node))
                .header("Accept", "application/json")
                .apply { authHeaders(this, node) }
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    ModelsResult(
                        emptyList(),
                        "HTTP ${response.code}: ${com.ninerouter.android.data.Redact.text(body, 180)}",
                    )
                } else {
                    ModelsResult(parseModelIds(body))
                }
            }
        }.getOrElse {
            ModelsResult(emptyList(), com.ninerouter.android.data.Redact.text(it.message.orEmpty(), 220))
        }
    }

    suspend fun testModel(node: ProviderNode, model: String): TestResult = withContext(Dispatchers.IO) {
        if (model.isBlank()) return@withContext TestResult(false, "Model id is empty")
        val started = System.currentTimeMillis()
        runCatching {
            val payload = when {
                isAnthropic(node) -> buildJsonObject {
                    put("model", model)
                    put("max_tokens", 8)
                    put("messages", buildJsonArray {
                        add(buildJsonObject { put("role", "user"); put("content", "Reply with OK.") })
                    })
                }
                isResponses(node) -> buildJsonObject {
                    put("model", model)
                    put("input", "Reply with OK.")
                    put("max_output_tokens", 16)
                    put("stream", false)
                }
                else -> buildJsonObject {
                    put("model", model)
                    put("messages", buildJsonArray {
                        add(buildJsonObject { put("role", "user"); put("content", "Reply with OK.") })
                    })
                    put("max_tokens", 8)
                    put("stream", false)
                }
            }
            val request = Request.Builder()
                .url(chatUrl(node))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .apply { authHeaders(this, node) }
                .post(payload.toString().toRequestBody(mediaJson))
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val elapsed = System.currentTimeMillis() - started
                if (response.isSuccessful) {
                    val summary = replySummary(body)
                    TestResult(true, if (summary.isBlank()) "Model responded" else summary, elapsed)
                } else {
                    TestResult(
                        false,
                        "HTTP ${response.code}: ${com.ninerouter.android.data.Redact.text(body, 220)}",
                        elapsed,
                    )
                }
            }
        }.getOrElse {
            TestResult(
                false,
                com.ninerouter.android.data.Redact.text(it.message.orEmpty(), 220).ifBlank { "Request failed" },
                System.currentTimeMillis() - started,
            )
        }
    }

    private fun authHeaders(builder: Request.Builder, node: ProviderNode) {
        if (node.apiKey.isBlank()) return
        if (isAnthropic(node)) {
            builder.header("x-api-key", node.apiKey)
            builder.header("anthropic-version", "2023-06-01")
        } else {
            builder.header("Authorization", "Bearer ${node.apiKey}")
        }
    }

    private fun parseModelIds(body: String): List<String> {
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return emptyList()
        val ids = mutableListOf<String>()
        (obj["data"] as? JsonArray)?.forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val id = (item["id"] as? JsonPrimitive)?.content
                ?: (item["name"] as? JsonPrimitive)?.content
            if (!id.isNullOrBlank()) ids += id
        }
        (obj["models"] as? JsonArray)?.forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val id = (item["id"] as? JsonPrimitive)?.content
                ?: (item["name"] as? JsonPrimitive)?.content
            if (!id.isNullOrBlank()) ids += id
        }
        return ids.distinct().sorted()
    }

    private fun replySummary(body: String): String {
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return ""
        val chat = ((obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.get("message") as? JsonObject
        (chat?.get("content") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { return it.take(120) }
        val output = (obj["output"] as? JsonArray)?.firstOrNull() as? JsonObject
        val text = ((output?.get("content") as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.get("text") as? JsonPrimitive
        text?.content?.takeIf { it.isNotBlank() }?.let { return it.take(120) }
        val anthropic = (obj["content"] as? JsonArray)?.firstOrNull() as? JsonObject
        (anthropic?.get("text") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { return it.take(120) }
        return ""
    }
}
