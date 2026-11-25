package com.ninerouter.android.core

import com.ninerouter.android.data.Connection
import com.ninerouter.android.data.ProviderDef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        var payload = JsonObject(body.toMap().filterKeys { it != "_stream" })
        // Claude OAuth tokens must carry the Claude Code identity prompt.
        if (def.id == "claude" && conn.accessToken.isNotBlank()) payload = withClaudeCodeIdentity(payload)
        val wire = if (isCliEnvelope(def)) wrapCliEnvelope(def, conn, payload) else payload
        val url = resolveUrl(def, payload, wantsStream)
        var request = requestFor(url, def, conn, wire.toString(), wantsStream)
        var response = client.newCall(request).execute()

        if ((response.code == 401 || response.code == 403) && refreshable(def, conn)) {
            val fresh = refresh(def, conn)
            if (fresh != null) {
                conn.accessToken = fresh.first
                if (fresh.second.isNotBlank()) conn.refreshToken = fresh.second
                conn.expiresAt = System.currentTimeMillis() + 3_600_000L
                response.close()
                request = requestFor(url, def, conn, wire.toString(), wantsStream)
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

    private fun isCliEnvelope(def: ProviderDef): Boolean =
        def.format == "gemini-cli" || def.format == "antigravity"

    /** Anthropic accepts Claude OAuth tokens only with the Claude Code identity prompt. */
    private fun withClaudeCodeIdentity(payload: JsonObject): JsonObject {
        val identity = buildJsonObject {
            put("type", "text")
            put("text", "You are Claude Code, Anthropic's official CLI for Claude.")
        }
        val systems = buildJsonArray {
            add(identity)
            when (val existing = payload["system"]) {
                is JsonArray -> existing.forEach { add(it) }
                is JsonPrimitive -> if (existing.content.isNotBlank()) {
                    add(buildJsonObject { put("type", "text"); put("text", existing.content) })
                }
                else -> {}
            }
        }
        return JsonObject(payload.toMap() + ("system" to systems))
    }

    /** Cloud Code Assist (Gemini CLI / Antigravity) wraps the Gemini body. */
    private fun wrapCliEnvelope(def: ProviderDef, conn: Connection, payload: JsonObject): JsonObject {
        val model = (payload["model"] as? JsonPrimitive)?.content ?: ""
        val project = conn.projectId.ifBlank {
            if (def.format == "antigravity") randomProjectId() else ""
        }
        return buildJsonObject {
            put("project", project)
            put("model", model)
            if (def.format == "antigravity") {
                put("userAgent", "antigravity")
                put("requestId", "agent-" + java.util.UUID.randomUUID().toString())
            }
            put("request", payload)
        }
    }

    private fun randomProjectId(): String {
        val adj = listOf("useful", "bright", "swift", "calm", "bold", "tidy", "lively", "sunny")
        val noun = listOf("falcon", "river", "cedar", "orbit", "tiger", "willow", "comet", "maple")
        return adj.random() + "-" + noun.random() + "-" + (100000..999999).random()
    }

    private fun refreshable(def: ProviderDef, conn: Connection): Boolean =
        def.id != "muse" && def.id != "nous" && conn.refreshToken.isNotBlank() &&
            (def.tokenUrl.isNotBlank() || def.copilotTokenUrl.isNotBlank())

    private fun refresh(def: ProviderDef, conn: Connection): Pair<String, String>? {
        if (def.copilotTokenUrl.isNotBlank()) {
            copilotToken(def, conn.refreshToken)?.let { return it to conn.refreshToken }
        }
        val tokenUrl = def.refreshUrl.ifBlank { def.tokenUrl }
        if (tokenUrl.isBlank()) return null
        if (def.id == "iflow") {
            val basic = java.util.Base64.getEncoder()
                .encodeToString("${def.clientId}:${def.clientSecret}".toByteArray())
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", conn.refreshToken)
                .add("client_id", def.clientId)
                .add("client_secret", def.clientSecret)
                .build()
            val req = Request.Builder()
                .url(tokenUrl)
                .header("Authorization", "Basic $basic")
                .post(form)
                .build()
            return try {
                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return null
                    val obj = json.parseToJsonElement(res.body!!.string()) as? JsonObject ?: return null
                    val access = (obj["access_token"] as? JsonPrimitive)?.content ?: return null
                    val refresh = (obj["refresh_token"] as? JsonPrimitive)?.content ?: conn.refreshToken
                    access to refresh
                }
            } catch (_: Exception) {
                null
            }
        }
        val encoding = def.refreshEncoding.ifBlank { def.tokenEncoding }
        var req = if (encoding.equals("json", true)) {
            val payload = buildJsonObject {
                put("grant_type", "refresh_token")
                put("refresh_token", conn.refreshToken)
                if (def.clientId.isNotBlank()) put("client_id", def.clientId)
                if (def.clientSecret.isNotBlank()) put("client_secret", def.clientSecret)
            }
            Request.Builder()
                .url(tokenUrl)
                .post(payload.toString().toRequestBody(mediaJson))
                .build()
        } else {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", conn.refreshToken)
                .apply {
                    if (def.clientId.isNotBlank()) add("client_id", def.clientId)
                    if (def.clientSecret.isNotBlank()) add("client_secret", def.clientSecret)
                    if (def.refreshScope.isNotBlank()) add("scope", def.refreshScope)
                }
                .build()
            Request.Builder().url(tokenUrl).post(form).build()
        }
        if (def.id == "kimi") {
            req = req.newBuilder()
                .header("X-Msh-Platform", "9router")
                .header("X-Msh-Version", com.ninerouter.android.AppInfo.VERSION_NAME)
                .header("X-Msh-Device-Name", android.os.Build.MODEL ?: "android")
                .header("X-Msh-Device-Model", "Android ${android.os.Build.VERSION.RELEASE}")
                .apply { if (conn.deviceId.isNotBlank()) header("X-Msh-Device-Id", conn.deviceId) }
                .build()
        }
        if (def.id == "grok-cli") {
            req = req.newBuilder()
                .header("User-Agent", "grok-pager/1.0.44 grok-shell/1.0.44 (linux; x86_64)")
                .build()
        }
        return try {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val obj = json.parseToJsonElement(res.body!!.string()) as? JsonObject ?: return null
                val access = (obj["access_token"] as? JsonPrimitive)?.content ?: return null
                val refresh = (obj["refresh_token"] as? JsonPrimitive)?.content ?: conn.refreshToken
                access to refresh
            }
        } catch (_: Exception) {
            null
        }
    }

    /** GitHub Copilot OAuth tokens are exchanged for a short-lived Copilot token. */
    private fun copilotToken(def: ProviderDef, githubToken: String): String? {
        if (githubToken.isBlank()) return null
        val req = Request.Builder()
            .url(def.copilotTokenUrl)
            .header("Authorization", "Bearer $githubToken")
            .header("Accept", "application/json")
            .header("User-Agent", "GitHubCopilotChat/0.26.7")
            .header("Editor-Version", "vscode/1.85.0")
            .header("Editor-Plugin-Version", "copilot-chat/0.26.7")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        return try {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val obj = json.parseToJsonElement(res.body!!.string()) as? JsonObject ?: return null
                (obj["token"] as? JsonPrimitive)?.content
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
        // Provider-declared static headers first (User-Agent, client identity...).
        def.transportHeaders.forEach { (k, v) ->
            if (v.isNotBlank() && !k.equals("authorization", true)) builder.header(k, v)
        }
        if (def.id == "grok-cli") {
            builder.header("x-xai-token-auth", "xai-grok-cli")
            builder.header("x-grok-client-identifier", "grok-shell")
            builder.header("x-grok-client-version", "1.0.44")
            builder.header("User-Agent", "grok-shell/1.0.44 (linux; x86_64)")
        }
        if (def.id == "codex") {
            builder.header("session_id", java.util.UUID.randomUUID().toString())
            if (conn.accountId.isNotBlank()) builder.header("ChatGPT-Account-ID", conn.accountId)
        }
        if (def.id == "kimi") {
            builder.header("X-Msh-Platform", "9router")
            builder.header("X-Msh-Version", com.ninerouter.android.AppInfo.VERSION_NAME)
            builder.header("X-Msh-Device-Name", android.os.Build.MODEL ?: "android")
            builder.header("X-Msh-Device-Model", "Android ${android.os.Build.VERSION.RELEASE}")
            if (conn.deviceId.isNotBlank()) builder.header("X-Msh-Device-Id", conn.deviceId)
        }
        if (def.id == "minimax-code" || def.id == "minimax-code-global") {
            builder.header("x-api-key", "sk-xxx")
            builder.header("X-Mavis-Timezone-Offset", (java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000).toString())
            builder.header("X-Mavis-Session-Id", java.util.UUID.randomUUID().toString())
        }
        if (def.id == "muse" && conn.accessToken.isNotBlank() && conn.apiKey.isBlank()) {
            builder.header("x-api-version", "1.0.0")
        }
        if (def.format == "antigravity") {
            builder.header("User-Agent", "antigravity/hub/2.33.0 darwin/arm64")
            builder.header("x-request-source", "local")
        }
        val oauth = conn.accessToken.isNotBlank()
        val token = conn.accessToken.ifBlank { conn.apiKey }
        when {
            // Web-session providers authenticate with the browser cookie header.
            conn.authType.equals("cookie", true) && conn.apiKey.isNotBlank() ->
                builder.header("Cookie", conn.apiKey)
            // Claude Code is special: OAuth tokens need the CLI identity and beta
            // headers. Other format=claude providers (Kimi, MiniMax, GLM) use
            // their registry-declared header instead.
            def.format == Formats.CLAUDE -> {
                if (oauth && def.id == "claude") {
                    builder.header("Authorization", "Bearer ${conn.accessToken}")
                    builder.header(
                        "anthropic-beta",
                        "claude-code-20250219,oauth-2025-04-20,interleaved-thinking-2025-05-14,prompt-caching-scope-2026-01-05",
                    )
                    builder.header("anthropic-dangerous-direct-browser-access", "true")
                    builder.header("user-agent", "claude-cli/2.0.0 (external, cli)")
                    builder.header("x-app", "cli")
                } else if (token.isNotBlank()) {
                    if (def.authHeader.equals("Authorization", true) ||
                        def.authScheme.equals("bearer", true)
                    ) {
                        builder.header("Authorization", "Bearer $token")
                    } else {
                        builder.header("x-api-key", token)
                    }
                }
            }
            def.authHeader.equals("x-api-key", true) && token.isNotBlank() ->
                builder.header("x-api-key", token)
            def.authHeader.equals("x-goog-api-key", true) ->
                if (token.isNotBlank()) builder.header("x-goog-api-key", token)
            token.isNotBlank() -> builder.header("Authorization", "Bearer $token")
        }
        if (def.format == Formats.CLAUDE) {
            builder.header("anthropic-version", "2023-06-01")
        }
        return builder.post(payload.toRequestBody(mediaJson)).build()
    }

    private fun resolveUrl(def: ProviderDef, body: JsonObject, wantsStream: Boolean): String {
        val model = (body["model"] as? JsonPrimitive)?.content ?: "gemini"
        if (isCliEnvelope(def)) {
            val base = def.baseUrl.ifBlank { "https://cloudcode-pa.googleapis.com/v1internal" }.trimEnd('/')
            val action = if (wantsStream) "streamGenerateContent?alt=sse" else "generateContent"
            val root = if (base.endsWith("v1internal")) base else "$base/v1internal"
            return "$root:$action"
        }
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
        if (def.urlSuffix.isNotBlank() && !base.contains("?")) base += def.urlSuffix
        return base
    }
}
