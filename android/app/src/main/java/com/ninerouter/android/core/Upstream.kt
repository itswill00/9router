package com.ninerouter.android.core

import com.ninerouter.android.data.Connection
import com.ninerouter.android.data.ProviderDef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** OkHttp replacement for the per-provider executors in open-sse/executors. */
class Upstream {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaJson = "application/json; charset=utf-8".toMediaType()

    fun execute(def: ProviderDef, conn: Connection, body: JsonObject, wantsStream: Boolean): Response {
        val payload = JsonObject(body.toMap().filterKeys { it != "_stream" })
        val url = resolveUrl(def, payload, wantsStream)
        var request = requestFor(url, def, conn, payload.toString(), wantsStream)
        var response = client.newCall(request).execute()

        if ((response.code == 401 || response.code == 403) && refreshable(def, conn)) {
            val fresh = refresh(def, conn)
            if (fresh != null) {
                conn.accessToken = fresh.first
                if (fresh.second.isNotBlank()) conn.refreshToken = fresh.second
                conn.expiresAt = System.currentTimeMillis() + 3_600_000L
                response.close()
                request = requestFor(url, def, conn, payload.toString(), wantsStream)
                response = client.newCall(request).execute()
            }
        }
        return response
    }

    /** Explicit URL for non-chat endpoints (embeddings, images, audio, ...). */
    fun executeUrl(url: String, def: ProviderDef, conn: Connection, body: JsonObject): Response {
        val payload = JsonObject(body.toMap().filterKeys { it != "_stream" })
        return client.newCall(requestFor(url, def, conn, payload.toString(), false)).execute()
    }

    private fun refreshable(def: ProviderDef, conn: Connection): Boolean =
        conn.refreshToken.isNotBlank() && def.tokenUrl.isNotBlank()

    private fun refresh(def: ProviderDef, conn: Connection): Pair<String, String>? {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", conn.refreshToken)
        if (def.clientId.isNotBlank()) form.add("client_id", def.clientId)
        val req = Request.Builder().url(def.tokenUrl).post(form.build()).build()
        return try {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val obj = json.parseToJsonElement(res.body!!.string()) as? JsonObject ?: return null
                val access = (obj["access_token"] as? JsonPrimitive)?.content ?: return null
                val refresh = (obj["refresh_token"] as? JsonPrimitive)?.content ?: ""
                access to refresh
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun requestFor(
        url: String,
        def: ProviderDef,
        conn: Connection,
        payload: String,
        wantsStream: Boolean,
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", if (wantsStream) "text/event-stream" else "application/json")
        val token = conn.accessToken.ifBlank { conn.apiKey }
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        if (def.format == Formats.CLAUDE) builder.header("anthropic-version", "2023-06-01")
        if (def.format == Formats.GEMINI && conn.apiKey.isNotBlank()) builder.header("x-goog-api-key", conn.apiKey)
        return builder.post(payload.toRequestBody(mediaJson)).build()
    }

    private fun resolveUrl(def: ProviderDef, body: JsonObject, wantsStream: Boolean): String {
        val model = (body["model"] as? JsonPrimitive)?.content ?: "gemini"
        var base = def.baseUrl.ifBlank {
            when (def.format) {
                Formats.CLAUDE -> "https://api.anthropic.com/v1/messages"
                Formats.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                else -> "https://api.openai.com/v1/chat/completions"
            }
        }
        if (def.format == Formats.GEMINI && wantsStream) {
            base = base.replace(":generateContent", ":streamGenerateContent")
            if (!base.contains("alt=sse")) base += (if (base.contains("?")) "&" else "?") + "alt=sse"
        }
        return base
    }
}
