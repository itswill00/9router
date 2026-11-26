package com.ninerouter.android.server

import android.content.Context
import com.ninerouter.android.core.ChatEngine
import com.ninerouter.android.core.Formats
import com.ninerouter.android.core.ModelResolver
import com.ninerouter.android.core.Translator
import com.ninerouter.android.core.Upstream
import com.ninerouter.android.data.ApiKey
import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.Combo
import com.ninerouter.android.data.Connection
import com.ninerouter.android.data.ProviderNode
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
    private var scope = newScope()
    private var server: ServerSocket? = null
    private val extraServers = mutableListOf<ServerSocket>()

    @Volatile
    private var started = false

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)

    @Synchronized
    fun start() {
        if (started) return
        // A previous stop() cancelled the old scope. Never reuse it, otherwise
        // the socket binds successfully but no accept loop ever runs and every
        // browser request hangs until it times out.
        if (!scope.isActive) scope = newScope()
        val bound = mutableListOf<ServerSocket>()
        try {
            // Loopback only: remote access requires an explicit reverse tunnel,
            // never a 0.0.0.0 bind from the app itself. IPv4 is primary; IPv6
            // loopback is added when available so `localhost` works either way.
            bound += bind(InetAddress.getByName("127.0.0.1"))
            runCatching { bound += bind(InetAddress.getByName("::1")) }
            server = bound.first()
            extraServers.clear()
            extraServers += bound.drop(1)
            started = true
            val activeScope = scope
            bound.forEach { socket -> activeScope.launch { acceptLoop(socket) } }
            GatewayState.running.value = true
            GatewayState.error.value = ""
            android.util.Log.i("9R-Gateway", "listening on ${GatewayState.BASE_URL}")
        } catch (e: Exception) {
            bound.forEach { runCatching { it.close() } }
            GatewayState.running.value = false
            GatewayState.error.value = when (e) {
                is java.net.BindException ->
                    "Port ${GatewayState.PORT} is already in use. Stop the other gateway instance and try again."
                else -> e.message ?: e.javaClass.simpleName
            }
            android.util.Log.e("9R-Gateway", "start failed", e)
        }
    }

    private fun bind(address: InetAddress): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(address, GatewayState.PORT), 64)
        }

    @Synchronized
    fun stop() {
        started = false
        server?.close()
        server = null
        extraServers.forEach { runCatching { it.close() } }
        extraServers.clear()
        scope.cancel()
        GatewayState.running.value = false
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        val activeScope = scope
        while (started && activeScope.isActive) {
            val client = try { socket.accept() } catch (_: Exception) { break }
            client.soTimeout = 30_000
            activeScope.launch { handle(client) }
        }
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        try {
            val request = try {
                HttpRequestParser.read(input)
            } catch (e: HttpRequestParseException) {
                return writeJson(output, e.status, errorJson(e.status, e.message ?: "Invalid HTTP request"))
            }
            val method = request.method
            val rawTarget = request.target
            val path = rawTarget.substringBefore("?")
            val headers = request.headers
            val body = request.body.toString(Charsets.UTF_8)
            val authorized = authorized(headers)

            when {
                method == "OPTIONS" && isApiCorsRoute(path) -> writePreflight(output, ApiHttp.preflight(headers))
                path == "/" || path == "/index.html" -> writeHtml(output, statusPage())

                path == "/api/health" || path == "/health" -> writeJson(output, 200, buildJsonObject {
                    put("ok", true)
                    put("android", true)
                    put("version", com.ninerouter.android.AppInfo.VERSION_NAME)
                    put("baseUrl", GatewayState.BASE_URL)
                    put("connections", Store.state.connections.size)
                    put("models", Catalog.modelIds().size + Store.state.nodes.sumOf { it.models.size })
                }.let { json.encodeToString(it) })

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
                    when (val result = engine.handle(body, source, responseFormat, path)) {
                        is ChatEngine.Outcome.Json -> writeJson(output, 200, result.body)
                        is ChatEngine.Outcome.Error -> writeJson(output, result.status, errorJson(result.status, result.message))
                        is ChatEngine.Outcome.Stream -> writeSse(output, result)
                    }
                }

                method == "POST" && path in setOf("/v1/embeddings", "/api/v1/embeddings") -> {
                    if (!authorized) return writeJson(output, 401, errorJson(401, "Invalid API key"))
                    handleEmbeddings(body, output)
                }

                path == "/api/version" && method == "GET" -> writeJson(output, 200, versionJson())

                path == "/api/tunnel/status" && method == "GET" ->
                    writeJson(output, 200, "{\"enabled\":false,\"status\":\"disabled\",\"android\":true}")

                path == "/api/models/availability" && method == "GET" ->
                    writeJson(output, 200, "{\"models\":{}}")

                path in setOf("/api/version/shutdown", "/api/shutdown") && method == "POST" -> {
                    if (!authorized) return writeJson(output, 401, errorJson(401, "Invalid API key"))
                    writeJson(output, 200, "{\"ok\":true}")
                    context.stopService(android.content.Intent(context, com.ninerouter.android.GatewayService::class.java))
                }

                path == "/api/auth/status" && method == "GET" -> writeJson(output, 200, buildJsonObject {
                    put("requireApiKey", Store.state.settings.requireApiKey)
                    put("authenticated", authorized(headers))
                    put("android", true)
                }.let { json.encodeToString(it) })

                path == "/api/usage/stats" && method == "GET" -> writeJson(output, 200, usageStatsJson())
                path == "/api/usage/request-logs" && method == "GET" -> writeJson(output, 200, requestLogsJson())

                path == "/api/keys" && method == "GET" -> writeJson(output, 200, keysJson())
                path == "/api/keys" && method == "POST" -> createKey(body, output)
                path.startsWith("/api/keys/") && method == "DELETE" -> deleteKey(path, output)
                path.startsWith("/api/keys/") && method == "PATCH" -> patchKey(path, body, output)

                path == "/api/providers" && method == "GET" -> writeJson(output, 200, connectionsJson())
                path == "/api/providers" && method == "POST" -> createConnection(body, output)
                path.startsWith("/api/providers/") && method == "PUT" -> patchConnection(path, body, output)
                path.startsWith("/api/providers/") && method == "DELETE" -> deleteConnection(path, output)

                path == "/api/combos" && method == "GET" -> writeJson(output, 200, combosJson())
                path == "/api/combos" && method == "POST" -> createCombo(body, output)
                path.startsWith("/api/combos/") && method == "DELETE" -> deleteCombo(path, output)

                path == "/api/provider-nodes" && method == "GET" -> writeJson(output, 200, nodesJson())
                path == "/api/provider-nodes" && method == "POST" -> createNode(body, output)
                path.startsWith("/api/provider-nodes/") && method == "PUT" -> updateNode(path, body, output)
                path.startsWith("/api/provider-nodes/") && method == "DELETE" -> deleteNode(path, output)

                path == "/api/models/alias" && method == "GET" -> writeJson(output, 200, aliasesJson())
                path == "/api/models/alias" && method == "POST" -> setAlias(body, output)

                path == "/api/settings" && method == "GET" -> {
                    if (!authorized) return writeJson(output, 401, errorJson(401, "Invalid API key"))
                    writeJson(output, 200, json.encodeToString(settingsJson()))
                }

                path == "/api/settings" && (method == "POST" || method == "PATCH") -> {
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
            ?: Store.state.nodes.firstOrNull { it.id == target.provider }?.let {
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

    // ---------- management API parity (subset) ----------

    private fun bodyObject(raw: String): JsonObject? =
        try { json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }

    private fun textOf(o: JsonObject?, key: String): String =
        (o?.get(key) as? JsonPrimitive)?.content.orEmpty()

    private fun boolOf(o: JsonObject?, key: String, fallback: Boolean): Boolean =
        (o?.get(key) as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: fallback

    private fun intOf(o: JsonObject?, key: String, fallback: Int): Int =
        (o?.get(key) as? JsonPrimitive)?.content?.toIntOrNull() ?: fallback

    private fun stringsOf(o: JsonObject?, key: String): List<String> =
        ((o?.get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty())

    private fun idFrom(path: String): String = path.substringAfterLast('/')

    private fun versionJson(): String = buildJsonObject {
        put("version", "0.5.123")
        put("android", true)
        put("edition", "native-preview")
    }.let { json.encodeToString(it) }

    private fun usageStatsJson(): String {
        val usage = Store.state.usage
        val timed = usage.filter { it.latencyMs > 0 }
        return buildJsonObject {
            put("requests", usage.size)
            put("prompt_tokens", usage.sumOf { it.prompt })
            put("completion_tokens", usage.sumOf { it.completion })
            put("cached_tokens", usage.sumOf { it.cached })
            put("total_cost", usage.sumOf { it.cost })
            put("failed", usage.count { !it.ok })
            put("streams", usage.count { it.stream })
            put("avg_latency_ms", if (timed.isEmpty()) 0L else timed.sumOf { it.latencyMs } / timed.size)
            put("providers", buildJsonArray {
                usage.groupBy { it.provider }.forEach { (provider, entries) ->
                    add(buildJsonObject {
                        put("provider", provider)
                        put("requests", entries.size)
                        put("tokens", entries.sumOf { it.prompt + it.completion })
                    })
                }
            })
        }.let { json.encodeToString(it) }
    }

    private fun requestLogsJson(): String = json.encodeToString(buildJsonArray {
        Store.state.usage.takeLast(200).asReversed().forEach { u ->
            add(buildJsonObject {
                put("ts", u.ts)
                put("provider", u.provider)
                put("model", u.model)
                put("status", if (u.ok) "ok" else "failed")
                put("code", u.status)
                put("endpoint", u.endpoint)
                put("stream", u.stream)
                put("latency_ms", u.latencyMs)
                put("prompt_tokens", u.prompt)
                put("completion_tokens", u.completion)
                put("cached_tokens", u.cached)
                put("cost", u.cost)
                put("error", u.error)
            })
        }
    })

    private fun keysJson(): String = json.encodeToString(buildJsonArray {
        Store.state.apiKeys.forEach { k ->
            add(buildJsonObject {
                put("id", k.id)
                put("name", k.name)
                put("key", k.key)
                put("isActive", k.active)
                put("allowedModels", JsonArray(k.allowedModels.map { JsonPrimitive(it) }))
            })
        }
    })

    private fun createKey(raw: String, output: OutputStream) {
        val incoming = bodyObject(raw)
        val key = com.ninerouter.android.data.ApiKey(
            id = java.util.UUID.randomUUID().toString(),
            name = textOf(incoming, "name"),
            key = textOf(incoming, "key").ifBlank { Store.newKey() },
            active = boolOf(incoming, "isActive", true),
            allowedModels = stringsOf(incoming, "allowedModels"),
        )
        Store.mutate { apiKeys.add(key) }
        writeJson(output, 201, json.encodeToString(buildJsonObject {
            put("id", key.id); put("name", key.name); put("key", key.key); put("isActive", key.active)
        }))
    }

    private fun deleteKey(path: String, output: OutputStream) {
        val id = idFrom(path)
        Store.mutate { apiKeys.removeAll { it.id == id } }
        writeJson(output, 200, "{\"ok\":true}")
    }

    private fun patchKey(path: String, raw: String, output: OutputStream) {
        val incoming = bodyObject(raw) ?: return writeJson(output, 400, errorJson(400, "Invalid JSON"))
        val id = idFrom(path)
        Store.mutate {
            val index = apiKeys.indexOfFirst { it.id == id }
            if (index >= 0) {
                val old = apiKeys[index]
                apiKeys[index] = old.copy(
                    name = if (incoming["name"] != null) textOf(incoming, "name") else old.name,
                    active = if (incoming["isActive"] != null) boolOf(incoming, "isActive", old.active) else old.active,
                    allowedModels = if (incoming["allowedModels"] != null) stringsOf(incoming, "allowedModels") else old.allowedModels,
                )
            }
        }
        writeJson(output, 200, keysJson())
    }

    private fun connectionsJson(): String = json.encodeToString(buildJsonArray {
        Store.state.connections.forEach { c ->
            add(buildJsonObject {
                put("id", c.id)
                put("provider", c.provider)
                put("name", c.name)
                put("authType", c.authType)
                put("isActive", c.enabled)
                put("priority", c.priority)
                put("testStatus", if (c.unavailableUntil > System.currentTimeMillis()) "error" else "active")
                put("lastError", c.lastError)
            })
        }
    })

    private fun createConnection(raw: String, output: OutputStream) {
        val incoming = bodyObject(raw)
        val provider = textOf(incoming, "provider")
        if (provider.isBlank()) return writeJson(output, 400, errorJson(400, "provider is required"))
        val c = Connection(
            id = java.util.UUID.randomUUID().toString(),
            provider = provider,
            name = textOf(incoming, "name"),
            authType = textOf(incoming, "authType").ifBlank { "apikey" },
            apiKey = textOf(incoming, "apiKey"),
            accessToken = textOf(incoming, "accessToken"),
            refreshToken = textOf(incoming, "refreshToken"),
            enabled = boolOf(incoming, "isActive", true),
            priority = intOf(incoming, "priority", 0),
        )
        Store.mutate { connections.add(c) }
        writeJson(output, 201, json.encodeToString(buildJsonObject { put("id", c.id); put("provider", c.provider) }))
    }

    private fun patchConnection(path: String, raw: String, output: OutputStream) {
        val incoming = bodyObject(raw) ?: return writeJson(output, 400, errorJson(400, "Invalid JSON"))
        val id = idFrom(path)
        Store.mutate {
            val index = connections.indexOfFirst { it.id == id }
            if (index >= 0) {
                val old = connections[index]
                connections[index] = old.copy(
                    name = textOf(incoming, "name").ifBlank { old.name },
                    enabled = if (incoming["isActive"] != null) boolOf(incoming, "isActive", old.enabled) else old.enabled,
                    priority = if (incoming["priority"] != null) intOf(incoming, "priority", old.priority) else old.priority,
                )
            }
        }
        writeJson(output, 200, connectionsJson())
    }

    private fun deleteConnection(path: String, output: OutputStream) {
        val id = idFrom(path)
        Store.mutate { connections.removeAll { it.id == id } }
        writeJson(output, 200, "{\"ok\":true}")
    }

    private fun combosJson(): String = json.encodeToString(buildJsonArray {
        Store.state.combos.forEach { c ->
            add(buildJsonObject {
                put("id", c.name)
                put("name", c.name)
                put("models", JsonArray(c.models.map { JsonPrimitive(it) }))
                put("strategy", c.strategy)
                put("routerModel", c.routerModel)
                put("judgeModel", c.judgeModel)
            })
        }
    })

    private fun createCombo(raw: String, output: OutputStream) {
        val incoming = bodyObject(raw)
        val name = textOf(incoming, "name")
        val models = stringsOf(incoming, "models")
        if (name.isBlank() || models.isEmpty()) return writeJson(output, 400, errorJson(400, "name and models are required"))
        Store.mutate {
            combos.add(
                Combo(
                    name = name,
                    models = models,
                    strategy = textOf(incoming, "strategy").ifBlank { "fallback" },
                    routerModel = textOf(incoming, "routerModel"),
                    judgeModel = textOf(incoming, "judgeModel"),
                )
            )
        }
        writeJson(output, 201, json.encodeToString(buildJsonObject { put("id", name); put("name", name) }))
    }

    private fun deleteCombo(path: String, output: OutputStream) {
        val name = idFrom(path)
        Store.mutate { combos.removeAll { it.name == name } }
        writeJson(output, 200, "{\"ok\":true}")
    }

    private fun aliasesJson(): String = json.encodeToString(buildJsonObject {
        Store.state.aliases.forEach { (alias, target) -> put(alias, target) }
    })

    private fun setAlias(raw: String, output: OutputStream) {
        val incoming = bodyObject(raw)
        val alias = textOf(incoming, "alias")
        val target = textOf(incoming, "targetModel").ifBlank { textOf(incoming, "model") }
        if (alias.isBlank() || target.isBlank()) {
            return writeJson(output, 400, errorJson(400, "alias and targetModel are required"))
        }
        Store.mutate { aliases[alias] = target }
        writeJson(output, 200, aliasesJson())
    }

    private fun nodeJson(n: ProviderNode): JsonObject = buildJsonObject {
        put("id", n.id)
        put("name", n.name)
        put("prefix", n.prefix)
        put("type", n.type)
        put("apiType", n.apiType)
        put("baseUrl", n.baseUrl)
        put("models", buildJsonArray { n.models.forEach { add(JsonPrimitive(it)) } })
        put("hasApiKey", n.apiKey.isNotBlank())
    }

    private fun nodesJson(): String = json.encodeToString(buildJsonObject {
        put("nodes", buildJsonArray { Store.state.nodes.forEach { add(nodeJson(it)) } })
    })

    private fun sanitizedNodeBase(type: String, baseUrl: String): String {
        var base = baseUrl.trim().trimEnd('/')
        if (type == "anthropic-compatible" && base.endsWith("/messages")) {
            base = base.dropLast("/messages".length)
        }
        if (type == "custom-embedding" && base.endsWith("/embeddings")) {
            base = base.dropLast("/embeddings".length)
        }
        return base
    }

    private fun createNode(raw: String, output: OutputStream) {
        val incoming = bodyObject(raw)
        val name = textOf(incoming, "name").trim()
        val prefix = textOf(incoming, "prefix").trim()
        val type = textOf(incoming, "type").ifBlank { "openai-compatible" }
        val apiType = textOf(incoming, "apiType").ifBlank { "chat" }
        if (name.isBlank()) return writeJson(output, 400, errorJson(400, "Name is required"))
        if (prefix.isBlank()) return writeJson(output, 400, errorJson(400, "Prefix is required"))
        if (type == "openai-compatible" && apiType !in setOf("chat", "responses")) {
            return writeJson(output, 400, errorJson(400, "Invalid OpenAI compatible API type"))
        }
        if (type !in setOf("openai-compatible", "anthropic-compatible", "custom-embedding")) {
            return writeJson(output, 400, errorJson(400, "Invalid provider node type"))
        }
        if (Store.state.nodes.any { it.prefix.equals(prefix, ignoreCase = true) }) {
            return writeJson(output, 409, errorJson(409, "Prefix already exists"))
        }
        val defaultBase = when (type) {
            "anthropic-compatible" -> "https://api.anthropic.com/v1"
            else -> "https://api.openai.com/v1"
        }
        val node = ProviderNode(
            id = "$type-" + java.util.UUID.randomUUID().toString().take(8),
            prefix = prefix,
            name = name,
            type = type,
            apiType = if (type == "openai-compatible") apiType else "chat",
            baseUrl = sanitizedNodeBase(type, textOf(incoming, "baseUrl").ifBlank { defaultBase }),
            apiKey = textOf(incoming, "apiKey"),
            models = stringsOf(incoming, "models").map { it.trim() }.filter { it.isNotBlank() }.distinct(),
        )
        Store.mutate { nodes.add(node) }
        writeJson(output, 201, json.encodeToString(buildJsonObject { put("node", nodeJson(node)) }))
    }

    private fun updateNode(path: String, raw: String, output: OutputStream) {
        val id = idFrom(path)
        val node = Store.state.nodes.firstOrNull { it.id == id }
            ?: return writeJson(output, 404, errorJson(404, "Provider node not found"))
        val incoming = bodyObject(raw)
        val name = textOf(incoming, "name").trim()
        val prefix = textOf(incoming, "prefix").trim()
        val baseUrl = textOf(incoming, "baseUrl").trim()
        val apiType = textOf(incoming, "apiType").ifBlank { node.apiType }
        if (name.isBlank()) return writeJson(output, 400, errorJson(400, "Name is required"))
        if (prefix.isBlank()) return writeJson(output, 400, errorJson(400, "Prefix is required"))
        if (baseUrl.isBlank()) return writeJson(output, 400, errorJson(400, "Base URL is required"))
        if (node.type == "openai-compatible" && apiType !in setOf("chat", "responses")) {
            return writeJson(output, 400, errorJson(400, "Invalid OpenAI compatible API type"))
        }
        if (Store.state.nodes.any { it.id != id && it.prefix.equals(prefix, ignoreCase = true) }) {
            return writeJson(output, 409, errorJson(409, "Prefix already exists"))
        }
        node.name = name
        node.prefix = prefix
        node.baseUrl = sanitizedNodeBase(node.type, baseUrl)
        if (node.type == "openai-compatible") node.apiType = apiType
        if (incoming?.containsKey("apiKey") == true) node.apiKey = textOf(incoming, "apiKey")
        if (incoming?.containsKey("models") == true) {
            node.models = stringsOf(incoming, "models").map { it.trim() }.filter { it.isNotBlank() }.distinct()
        }
        Store.save()
        writeJson(output, 200, json.encodeToString(buildJsonObject { put("node", nodeJson(node)) }))
    }

    private fun deleteNode(path: String, output: OutputStream) {
        val id = idFrom(path)
        if (Store.state.nodes.none { it.id == id }) {
            return writeJson(output, 404, errorJson(404, "Provider node not found"))
        }
        Store.mutate {
            nodes.removeAll { it.id == id }
            connections.removeAll { it.provider == id }
        }
        writeJson(output, 200, "{\"success\":true}")
    }

    private fun embeddingsUrl(def: com.ninerouter.android.data.ProviderDef): String {
        var base = def.baseUrl.ifBlank { "https://api.openai.com/v1" }.trim().trimEnd('/')
        for (suffix in listOf("/chat/completions", "/responses", "/messages", "/embeddings")) {
            if (base.endsWith(suffix, ignoreCase = true)) {
                base = base.dropLast(suffix.length).trimEnd('/')
                break
            }
        }
        return "$base/embeddings"
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
            Store.state.nodes.forEach { node ->
                node.models.forEach { model ->
                    add(buildJsonObject {
                        put("id", "${node.prefix}/$model")
                        put("object", "model")
                        put("created", 0L)
                        put("owned_by", node.id)
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
        put("cavemanEnabled", s.cavemanEnabled)
        put("cavemanLevel", s.cavemanLevel)
        put("ponytailEnabled", s.ponytailEnabled)
        put("ponytailLevel", s.ponytailLevel)
        put("customPromptEnabled", s.customPromptEnabled)
        put("customPrompt", s.customPrompt)
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
        put("aliases", buildJsonObject {
            Store.state.aliases.forEach { (alias, target) -> put(alias, target) }
        })
    }

    private fun applySettings(raw: String) {
        val incoming = try { json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null } ?: return
        Store.mutate {
            (incoming["requireApiKey"] as? JsonPrimitive)?.content?.toBoolean()?.let { settings = settings.copy(requireApiKey = it) }
            (incoming["rtkEnabled"] as? JsonPrimitive)?.content?.toBoolean()?.let { settings = settings.copy(rtkEnabled = it) }
            (incoming["fallbackStrategy"] as? JsonPrimitive)?.content?.let { settings = settings.copy(fallbackStrategy = it) }
            (incoming["stickyLimit"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { settings = settings.copy(stickyLimit = it) }
            (incoming["cavemanEnabled"] as? JsonPrimitive)?.content?.toBoolean()?.let { settings = settings.copy(cavemanEnabled = it) }
            (incoming["ponytailEnabled"] as? JsonPrimitive)?.content?.toBoolean()?.let { settings = settings.copy(ponytailEnabled = it) }
            (incoming["customPromptEnabled"] as? JsonPrimitive)?.content?.toBoolean()?.let { settings = settings.copy(customPromptEnabled = it) }
            (incoming["customPrompt"] as? JsonPrimitive)?.content?.let { settings = settings.copy(customPrompt = it) }
            (incoming["cavemanLevel"] as? JsonPrimitive)?.content?.let { settings = settings.copy(cavemanLevel = it) }
            (incoming["ponytailLevel"] as? JsonPrimitive)?.content?.let { settings = settings.copy(ponytailLevel = it) }
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
        SseWriter.writeBody(output, result)
    }

    private fun writeChunk(output: OutputStream, data: String) {
        val bytes = data.toByteArray(Charsets.UTF_8)
        output.write(bytes.size.toString(16).toByteArray(Charsets.US_ASCII))
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
    }

    private fun statusPage(): String = """
        <!doctype html>
        <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>9Router Android</title>
        <style>
          body{font-family:system-ui,sans-serif;background:#0b0b0d;color:#e8e8ea;margin:0;padding:32px}
          .card{max-width:560px;margin:0 auto;background:#151518;border:1px solid #26262b;border-radius:16px;padding:24px}
          h1{margin:0 0 4px;font-size:22px} .muted{color:#8b8b93;font-size:13px}
          .ok{color:#4ade80;font-weight:600} code{background:#0b0b0d;padding:2px 6px;border-radius:6px}
          li{margin:8px 0} a{color:#f97316}
        </style></head>
        <body><div class="card">
          <h1>9Router Android</h1>
          <p class="muted">Native gateway · <span class="ok">running</span> on ${GatewayState.BASE_URL}</p>
          <ul>
            <li>Version: <code>${com.ninerouter.android.AppInfo.VERSION_NAME}</code></li>
            <li>OpenAI base URL: <code>${GatewayState.BASE_URL}/v1</code></li>
            <li>Health: <a href="/api/health">/api/health</a></li>
            <li>Models: <a href="/v1/models">/v1/models</a></li>
            <li>API key: <code>${if (Store.state.settings.requireApiKey) "required" else "not required"}</code></li>
            <li>Connections: ${Store.state.connections.size} · Models: ${Catalog.modelIds().size + Store.state.nodes.sumOf { it.models.size }}</li>
          </ul>
          <p class="muted">This server only listens on loopback inside the phone. From a computer use
          <code>adb reverse tcp:${GatewayState.PORT} tcp:${GatewayState.PORT}</code>.</p>
        </div></body></html>
    """.trimIndent()

    private fun writeHtml(output: OutputStream, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        output.write((
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun writeJson(output: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"
            400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
            413 -> "Payload Too Large"; 500 -> "Internal Server Error"; 501 -> "Not Implemented"
            502 -> "Bad Gateway"; 503 -> "Service Unavailable"
            else -> "Error"
        }
        val headers = ApiHttp.jsonHeaders(bytes)
        output.write((buildString {
            append("HTTP/1.1 $status $reason\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }).toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun writePreflight(output: OutputStream, response: ApiHttpResponse) {
        output.write((buildString {
            append("HTTP/1.1 ${response.status} No Content\r\n")
            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }).toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun isApiCorsRoute(path: String): Boolean =
        path == "/v1" || path.startsWith("/v1/") || path.startsWith("/api/") || path == "/health"

    private fun errorJson(status: Int, message: String): String = buildJsonObject {
        put("error", buildJsonObject {
            put("message", message)
            put("type", "android_gateway_error")
            put("code", status)
        })
    }.let { json.encodeToString(it) }
}
