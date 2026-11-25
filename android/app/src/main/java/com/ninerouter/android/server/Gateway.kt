package com.ninerouter.android.server

import android.content.Context
import com.ninerouter.android.core.ChatEngine
import com.ninerouter.android.core.Formats
import com.ninerouter.android.core.ModelResolver
import com.ninerouter.android.core.Translator
import com.ninerouter.android.core.Upstream
import com.ninerouter.android.data.ApiKey
import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.Connection
import com.ninerouter.android.data.Store
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/**
 * Tiny HTTP/1.1 server bound to loopback only. No Node, no shell, no root.
 * Supports the /v1 surface needed by coding clients plus the dashboard's
 * health/settings endpoints.
 */
class Gateway(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val engine = ChatEngine()
    private val handler = CoroutineExceptionHandler { _, t ->
        android.util.Log.e("9R-Gateway", "connection failed", t)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)
    private var server: ServerSocket? = null

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        try {
            // Loopback only: remote access requires an explicit reverse tunnel,
            // never a 0.0.0.0 bind from the app itself.
            val socket = ServerSocket(GatewayState.PORT, 32, InetAddress.getByName("127.0.0.1"))
            server = socket
            started = true
            scope.launch { acceptLoop(socket) }
            GatewayState.running.value = true
            android.util.Log.i("9R-Gateway", "listening on ${GatewayState.BASE_URL}")
        } catch (e: Exception) {
            android.util.Log.e("9R-Gateway", "start failed", e)
        }
    }

    fun stop() {
        started = false
        server?.close()
        server = null
        scope.cancel()
        GatewayState.running.value = false
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (started && scope.isActive) {
            val client = try { socket.accept() } catch (_: Exception) { break }
            scope.launch { handle(client) }
        }
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        try {
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore("?")
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (len > 0) String(readExactly(input, len), Charsets.UTF_8) else ""
            val authorized = authorized(headers)

            when {
                path == "/api/health" || path == "/health" ->
                    writeJson(output, 200, "{\"ok\":true,\"android\":true}")

                path == "/v1/models" || path == "/api/v1/models" -> writeJson(output, 200, modelsJson())

                method == "POST" && path in setOf(
                    "/v1/chat/completions", "/api/v1/chat/completions",
                    "/v1/messages", "/api/v1/messages",
                    "/v1/responses", "/api/v1/responses",
                ) -> {
                    if (!authorized) return writeJson(output, 401, errorJson(401, "Invalid API key"))
                    val deny = keyAccessDenied(headers, body)
                    if (deny != null) return writeJson(output, 403, errorJson(403, deny))
                    val source = when {
                        path.contains("messages") -> Formats.CLAUDE
                        path.contains("responses") -> Formats.OPENAI_RESPONSES
                        else -> Formats.OPENAI
                    }
                    val responseFormat = source
                    when (val result = engine.handle(body, source, responseFormat)) {
                        is ChatEngine.Outcome.Json -> writeJson(output, 200, result.body)
                        is ChatEngine.Outcome.Error -> writeJson(output, result.status, errorJson(result.status, result.message))
                        is ChatEngine.Outcome.Stream -> writeSse(output, result)
                    }
                }

                method == "POST" && path in setOf("/v1/embeddings", "/api/v1/embeddings") -> {
                    if (!authorized) return writeJson(output, 401, errorJson(401, "Invalid API key"))
                    handleEmbeddings(body, output)
                }

                path == "/api/settings" && method == "GET" -> {
                    if (!authorized) return writeJson(output, 401, errorJson(401, "Invalid API key"))
                    writeJson(output, 200, json.encodeToString(settingsJson()))
                }

                path == "/api/settings" && method == "POST" -> {
                    if (!authorized) return writeJson(output, 401, errorJson(401, "Invalid API key"))
                    applySettings(body)
                    writeJson(output, 200, json.encodeToString(settingsJson()))
                }

                else -> writeJson(output, 404, errorJson(404, "Not found"))
            }
        } catch (e: Exception) {
            android.util.Log.w("9R-Gateway", "request failed: ${e.message}")
            try { writeJson(output, 500, errorJson(500, e.message ?: "Internal error")) } catch (_: Exception) {}
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun presentedKey(headers: Map<String, String>): String =
        headers["authorization"]?.removePrefix("Bearer ")?.trim()
            ?: headers["x-api-key"]?.trim().orEmpty()

    private fun same(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun authorized(headers: Map<String, String>): Boolean {
        val settings = Store.state.settings
        if (!settings.requireApiKey || settings.apiKey.isBlank()) return true
        val presented = presentedKey(headers)
        if (same(presented, settings.apiKey)) return true
        return Store.state.apiKeys.any { it.active && same(presented, it.key) }
    }

    private fun keyAccessDenied(headers: Map<String, String>, rawBody: String): String? {
        val presented = presentedKey(headers)
        val key = Store.state.apiKeys.firstOrNull { it.active && same(presented, it.key) } ?: return null
        if (key.allowedModels.isEmpty()) return null
        val body = try { json.parseToJsonElement(rawBody) as? JsonObject } catch (_: Exception) { null } ?: return null
        val model = (body["model"] as? JsonPrimitive)?.content ?: return null
        val allowed = key.allowedModels.any { entry ->
            entry == model || Store.state.combos.firstOrNull { it.name == entry }?.models?.contains(model) == true
        }
        return if (allowed) null else "Key '${key.name.ifBlank { key.id }}' cannot use model $model"
    }

    private fun handleEmbeddings(raw: String, output: OutputStream) {
        val body = try { json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
        if (body == null) return writeJson(output, 400, errorJson(400, "Invalid JSON body"))
        val requested = (body["model"] as? JsonPrimitive)?.content.orEmpty()
        val target = ModelResolver.resolve(requested)
        if (target == null) return writeJson(output, 400, errorJson(400, "Unknown model"))
        val def = Catalog.find(target.provider) ?: Store.state.nodes.firstOrNull { it.id == target.provider }?.let {
            com.ninerouter.android.data.ProviderDef(
                id = it.id, alias = it.id, name = it.name, baseUrl = it.baseUrl,
            )
        } ?: com.ninerouter.android.data.ProviderDef(id = target.provider, alias = target.provider)

        val conn = Store.state.connections.firstOrNull { it.enabled && it.provider == target.provider }
            ?: Store.state.nodes.firstOrNull { it.id == target.provider && it.apiKey.isNotBlank() }?.let {
                Connection(id = "node:${it.id}", provider = it.id, apiKey = it.apiKey)
            }
        if (conn == null) return writeJson(output, 404, errorJson(404, "No credentials for ${target.provider}"))

        val url = embeddingsUrl(def)
        val payload = buildJsonObject {
            put("model", target.model)
            put("input", body["input"] ?: JsonPrimitive(""))
        }
        try {
            Upstream().executeUrl(url, def, conn, payload).use { res ->
                writeJson(output, res.code, res.body?.string().orEmpty().ifBlank { "{}" })
            }
        } catch (e: Exception) {
            writeJson(output, 502, errorJson(502, e.message ?: "Embedding failed"))
        }
    }

    private fun embeddingsUrl(def: com.ninerouter.android.data.ProviderDef): String {
        val base = def.baseUrl.ifBlank { "https://api.openai.com/v1/chat/completions" }
        if (base.contains("/embeddings")) return base
        return base.substringBeforeLast('/', base) + "/embeddings"
    }

    private fun modelsJson(): String = json.encodeToString(buildJsonObject {
        put("object", "list")
        put("data", buildJsonArray {
            Catalog.providers().forEach { p ->
                p.models.take(12).forEach { m ->
                    add(buildJsonObject {
                        put("id", "${p.id}/$m")
                        put("object", "model")
                        put("created", 0L)
                        put("owned_by", p.id)
                    })
                }
            }
            Store.state.combos.forEach { c ->
                add(buildJsonObject {
                    put("id", c.name)
                    put("object", "model")
                    put("created", 0L)
                    put("owned_by", "combo")
                })
            }
        })
    })

    private fun settingsJson(): JsonObject = buildJsonObject {
        val s = Store.state.settings
        put("requireApiKey", s.requireApiKey)
        put("apiKey", s.apiKey)
        put("rtkEnabled", s.rtkEnabled)
        put("fallbackStrategy", s.fallbackStrategy)
        put("stickyLimit", s.stickyLimit)
        put("connections", buildJsonArray {
            Store.state.connections.forEach { c ->
                add(buildJsonObject {
                    put("id", c.id)
                    put("provider", c.provider)
                    put("name", c.name)
                    put("enabled", c.enabled)
                    put("priority", c.priority)
                })
            }
        })
        put("combos", buildJsonArray {
            Store.state.combos.forEach { c ->
                add(buildJsonObject {
                    put("name", c.name)
                    put("models", JsonArray(c.models.map { JsonPrimitive(it) }))
                    put("strategy", c.strategy)
                })
            }
        })
        put("apiKeys", buildJsonArray {
            Store.state.apiKeys.forEach { k ->
                add(buildJsonObject {
                    put("id", k.id)
                    put("name", k.name)
                    put("active", k.active)
                    put("allowedModels", JsonArray(k.allowedModels.map { JsonPrimitive(it) }))
                })
            }
        })
        put("nodes", buildJsonArray {
            Store.state.nodes.forEach { n ->
                add(buildJsonObject {
                    put("id", n.id)
                    put("prefix", n.prefix)
                    put("baseUrl", n.baseUrl)
                })
            }
        })
    }

    private fun applySettings(raw: String) {
        val incoming = try { json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null } ?: return
        Store.mutate {
            (incoming["requireApiKey"] as? JsonPrimitive)?.content?.toBoolean()?.let { settings = settings.copy(requireApiKey = it) }
            (incoming["rtkEnabled"] as? JsonPrimitive)?.content?.toBoolean()?.let { settings = settings.copy(rtkEnabled = it) }
            (incoming["fallbackStrategy"] as? JsonPrimitive)?.content?.let { settings = settings.copy(fallbackStrategy = it) }
            (incoming["stickyLimit"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { settings = settings.copy(stickyLimit = it) }
        }
    }

    private fun writeSse(output: OutputStream, result: ChatEngine.Outcome.Stream) {
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.UTF_8))
        val source = result.response.body?.source()
        var sawDone = false
        try {
            while (true) {
                val line = source?.readUtf8Line() ?: break
                var converted = Translator.sseDelta(result.targetFormat, line, result.model)
                if (converted == null && line.contains("[DONE]")) converted = "data: [DONE]"
                if (converted == null) continue
                if (converted.contains("[DONE]")) sawDone = true
                writeChunk(output, converted + "\n\n")
            }
            if (!sawDone) writeChunk(output, "data: [DONE]\n\n")
            output.write("0\r\n\r\n".toByteArray(Charsets.UTF_8))
            output.flush()
        } finally {
            result.response.close()
        }
    }

    private fun writeChunk(output: OutputStream, data: String) {
        val bytes = data.toByteArray(Charsets.UTF_8)
        output.write(bytes.size.toString(16).toByteArray(Charsets.US_ASCII))
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
    }

    private fun writeJson(output: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
            500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"
            else -> "Error"
        }
        output.write((
            "HTTP/1.1 $status $reason\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun errorJson(status: Int, message: String): String = buildJsonObject {
        put("error", buildJsonObject {
            put("message", message)
            put("type", "android_gateway_error")
            put("code", status)
        })
    }.let { json.encodeToString(it) }

    private fun readLine(input: InputStream): String? {
        val buf = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8").trimEnd('\r')
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(out, read, length - read)
            if (n <= 0) break
            read += n
        }
        return if (read == length) out else out.copyOf(read)
    }
}
