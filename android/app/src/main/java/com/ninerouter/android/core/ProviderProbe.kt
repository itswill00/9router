package com.ninerouter.android.core

import com.ninerouter.android.data.Connection
import com.ninerouter.android.data.ProviderDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Minimal inference probe for a built-in provider, API key or OAuth alike.
 *
 * Reuses the same request translation and the same [Upstream] transport that
 * real chat requests use, so OAuth headers, CLI envelopes, Claude identity and
 * 401/403 token refresh behave identically. Only the payload (short prompt,
 * tiny token budget) and the client timeout differ.
 */
object ProviderProbe {
    data class Result(val ok: Boolean, val latencyMs: Long, val message: String)

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun test(
        def: ProviderDef,
        conn: Connection,
        model: String,
    ): Result = withContext(Dispatchers.IO) {
        if (model.isBlank()) return@withContext Result(false, 0L, "Model id is empty")
        if (!conn.enabled) return@withContext Result(false, 0L, "Connection is disabled")

        val started = System.currentTimeMillis()
        try {
            val upstream = Upstream(probeClient)
            upstream.refreshIfNeeded(def, conn)
            val body = buildJsonObject {
                put("model", model)
                put("stream", false)
                put("max_tokens", 16)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", "hi")
                    })
                })
            }
            val payload = Translator.requestBody(
                source = Formats.OPENAI,
                target = Formats.normalize(def.format),
                body = body,
                upstreamModel = model,
                wantsStream = false,
            )
            upstream.execute(def, conn, payload, wantsStream = false).use { response ->
                val elapsed = System.currentTimeMillis() - started
                if (response.isSuccessful) {
                    Result(true, elapsed, "OK")
                } else {
                    val detail = response.body?.string().orEmpty().take(220)
                    Result(false, elapsed, "HTTP ${response.code}${if (detail.isBlank()) "" else ": $detail"}")
                }
            }
        } catch (e: Exception) {
            Result(false, System.currentTimeMillis() - started, e.message?.take(220) ?: "Request failed")
        }
    }
}
