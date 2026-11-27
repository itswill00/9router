package com.ninerouter.android.core

import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.Connection
import com.ninerouter.android.data.MediaConfig
import com.ninerouter.android.data.ProviderDef
import com.ninerouter.android.data.Store
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Native media gateway: image generation, speech synthesis, transcription and
 * web search/fetch. It follows the same provider registry the dashboard uses,
 * but only declares support for the transports this build can actually run.
 */
object MediaEngine {
    private val json = Json { ignoreUnknownKeys = true }
    private val upstream = Upstream()

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Target(val def: ProviderDef, val model: String)

    data class Plan(
        val provider: String,
        val model: String,
        val kind: String,
        val url: String,
        val method: String = "POST",
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray? = null,
        val contentType: String? = "application/json; charset=utf-8",
        val timeoutMs: Long = 150_000L,
    )

    data class Reply(val status: Int, val contentType: String, val bytes: ByteArray)

    data class Attempt(val plan: Plan, val reply: Reply)

    class MediaException(val status: Int, override val message: String) : Exception(message)

    // ---------------------------------------------------------------------
    // Targets and credentials
    // ---------------------------------------------------------------------

    fun target(modelField: String, providerField: String?, kind: String): Target {
        val model = modelField.trim()
        val provider = providerField?.trim().orEmpty()
        if (model.isBlank() && provider.isBlank()) {
            throw MediaException(400, "Missing required field: model")
        }

        if (provider.isNotBlank()) {
            val def = Catalog.find(provider) ?: throw MediaException(400, "Unknown provider: $provider")
            ensureKind(def, kind)
            return Target(def, if (model.contains('/')) model.substringAfter('/') else model)
        }

        if (model.contains('/')) {
            val resolved = ModelResolver.resolve(model)
                ?: throw MediaException(400, "Unknown model: $model")
            val def = defFor(resolved.provider)
                ?: throw MediaException(400, "Unknown provider: ${resolved.provider}")
            ensureKind(def, kind)
            return Target(def, resolved.model)
        }

        Catalog.find(model)?.let { def ->
            ensureKind(def, kind)
            return Target(def, "")
        }

        // Bare model id: use a connected provider that advertises this model
        // and supports the requested capability.
        val active = Store.state.connections.filter { it.enabled }.map { it.provider }.toSet()
        Catalog.providers().firstOrNull { def ->
            def.id in active && kind in def.serviceKinds &&
                def.models.any { it.equals(model, ignoreCase = true) }
        }?.let { return Target(it, model) }

        val resolved = ModelResolver.resolve(model)
            ?: throw MediaException(400, "Unknown model: $model")
        val def = defFor(resolved.provider)
            ?: throw MediaException(400, "Unknown provider: ${resolved.provider}")
        ensureKind(def, kind)
        return Target(def, resolved.model)
    }

    fun targetWeb(modelField: String, providerField: String?, kind: String): Target {
        var raw = providerField?.trim().takeUnless { it.isNullOrBlank() } ?: modelField.trim()
        if (raw.isBlank()) throw MediaException(400, "Missing required field: model")
        raw = raw.substringBefore('?').substringBefore('#')
        val name = when {
            raw.endsWith("/$kind", true) -> raw.dropLast(kind.length + 1)
            raw.endsWith("/search", true) || raw.endsWith("/fetch", true) ->
                raw.substringBeforeLast('/')
            else -> raw
        }
        val def = Catalog.find(name.trim()) ?: Catalog.find(raw.trim())
            ?: throw MediaException(400, "Unknown provider: $raw")
        return Target(def, kind)
    }

    fun supports(def: ProviderDef, kind: String): Boolean = when (kind) {
        "image" -> def.id in IMAGE_ADAPTERS && !def.imageConfig?.baseUrl.isNullOrBlank()
        "tts" -> def.id in TTS_ADAPTERS && !def.ttsConfig?.baseUrl.isNullOrBlank() &&
            def.ttsConfig?.format?.lowercase(Locale.US) in setOf("openai", "elevenlabs")
        "stt" -> def.id in STT_ADAPTERS && !def.sttConfig?.baseUrl.isNullOrBlank() &&
            (def.sttConfig?.format.isNullOrBlank() == true || def.sttConfig?.format.equals("openai", true))
        "search" -> !def.searchConfig?.baseUrl.isNullOrBlank() &&
            def.id in setOf("tavily", "exa", "brave-search", "serper")
        "fetch" -> !def.fetchConfig?.baseUrl.isNullOrBlank() &&
            def.id in setOf("jina-reader", "firecrawl", "tavily", "exa")
        else -> kind in def.serviceKinds
    }

    private val IMAGE_ADAPTERS = setOf(
        "openai", "minimax", "openrouter", "recraft", "vercel-ai-gateway",
        "xai", "sensenova", "sensenova-intl", "gemini",
    )
    private val TTS_ADAPTERS = setOf("openai", "elevenlabs")
    private val STT_ADAPTERS = setOf("openai", "groq")

    private fun defFor(provider: String): ProviderDef? =
        Catalog.find(provider) ?: Store.state.nodes.firstOrNull { it.id == provider }?.let { node ->
            ProviderDef(
                id = node.id,
                alias = node.prefix,
                name = node.name,
                category = "compatible",
                format = if (node.type.startsWith("anthropic", true)) "claude" else "openai",
                baseUrl = node.baseUrl,
                serviceKinds = listOf("llm"),
            )
        }

    private fun ensureKind(def: ProviderDef, kind: String) {
        val supported = kind in def.serviceKinds || when (kind) {
            "image" -> !def.imageConfig?.baseUrl.isNullOrBlank()
            "tts" -> !def.ttsConfig?.baseUrl.isNullOrBlank()
            "stt" -> !def.sttConfig?.baseUrl.isNullOrBlank()
            "search" -> !def.searchConfig?.baseUrl.isNullOrBlank()
            "fetch" -> !def.fetchConfig?.baseUrl.isNullOrBlank()
            else -> false
        }
        if (!supported) {
            throw MediaException(400, "${def.name} does not support $kind in this build")
        }
    }

    private fun connections(provider: String, def: ProviderDef): List<Connection> {
        val now = System.currentTimeMillis()
        val configured = Store.state.connections
            .filter { it.enabled && it.provider == provider && it.unavailableUntil <= now }
            .sortedBy { it.priority }
        if (configured.isNotEmpty()) return configured
        val node = Store.state.nodes.firstOrNull { it.id == provider }
        if (node != null) {
            val keys = node.effectiveKeys()
            if (keys.isEmpty()) {
                return listOf(Connection(id = "node:${node.id}", provider = provider, name = node.name))
            }
            return keys.mapIndexed { index, key ->
                Connection(
                    id = "node:${node.id}#$index",
                    provider = provider,
                    name = if (keys.size == 1) node.name else "${node.name} · ${index + 1}",
                    apiKey = key,
                    priority = index,
                )
            }
        }
        if (def.noAuth || (def.authType == "none" && def.category != "oauth")) {
            return listOf(Connection(id = "noauth:${def.id}", provider = def.id, name = def.name))
        }
        throw MediaException(404, "No credentials for ${def.name}")
    }

    private fun isAuthFailure(status: Int): Boolean =
        status == 401 || status == 403 || status == 429 || status == 402

    /**
     * Run a plan against every configured account until one succeeds on auth,
     * quota or rate-limit errors. Provider errors such as a bad voice id are
     * returned immediately so callers see the real reason.
     */
    fun attempt(provider: String, build: (ProviderDef, Connection) -> Plan): Attempt {
        val def = defFor(provider) ?: throw MediaException(400, "Unknown provider: $provider")
        val candidates = connections(provider, def).take(4)
        var last: Attempt? = null
        for (conn in candidates) {
            upstream.refreshIfNeeded(def, conn)
            val plan = build(def, conn)
            val reply = execute(plan)
            last = Attempt(plan, reply)
            if (isAuthFailure(reply.status)) {
                ConsoleLog.warn("[MEDIA] ${def.id} auth/quota HTTP ${reply.status}, trying next account")
            } else {
                if (reply.status in 200..299) {
                    ConsoleLog.debug("[MEDIA] ${def.id} ${plan.kind} HTTP ${reply.status} ${reply.bytes.size}B")
                } else {
                    ConsoleLog.warn("[MEDIA] ${def.id} ${plan.kind} HTTP ${reply.status}")
                }
            }
            if (!isAuthFailure(reply.status)) {
                if (reply.status in 200..299) {
                    conn.unavailableUntil = 0L
                    conn.lastError = ""
                    Store.save()
                }
                return last
            }
            conn.lastError = "Media request failed (HTTP ${reply.status})"
            if (reply.status == 429) conn.unavailableUntil = System.currentTimeMillis() + 60_000L
            Store.save()
        }
        return last ?: throw MediaException(404, "No credentials for $provider")
    }

    // ---------------------------------------------------------------------
    // Image generation
    // ---------------------------------------------------------------------

    fun image(body: JsonObject, binary: Boolean): Reply {
        val target = target(body.str("model"), body.str("provider"), "image")
        ConsoleLog.info("[MEDIA] image ${target.def.id}/${target.model}")
        val provider = target.def.id
        val attempt = attempt(provider) { def, conn -> imagePlan(def, conn, target.model, body) }
        if (attempt.reply.status !in 200..299) return attempt.reply

        val normalized = if (target.def.id == "gemini") normalizeGeminiImage(attempt.reply.bytes) else attempt.reply.bytes
        if (!binary) return attempt.reply.copy(bytes = normalized)

        return decodeImageBytes(normalized, body)
    }

    internal fun imagePlan(def: ProviderDef, conn: Connection, model: String, body: JsonObject): Plan {
        val cfg = def.imageConfig?.takeIf { it.baseUrl.isNotBlank() }
            ?: throw MediaException(501, "${def.name} image generation is not supported in this build")
        if (def.id !in IMAGE_ADAPTERS) {
            throw MediaException(501, "${def.name} image generation needs a provider adapter that this build does not include")
        }
        if (model.isBlank()) throw MediaException(400, "Missing required field: model")
        val prompt = body.str("prompt")
        if (prompt.isBlank()) throw MediaException(400, "Missing required field: prompt")

        val gemini = def.id == "gemini" || cfg.baseUrl.contains("generativelanguage.googleapis.com")
        val headers = baseHeaders(def, cfg, conn, accept = "application/json")
        if (gemini) {
            val url = "${cfg.baseUrl.trimEnd('/')}/${model.removePrefix("models/")}:generateContent"
            val parts = buildJsonArray {
                val image = body.str("image").ifBlank { (body.array("images")?.firstOrNull() as? JsonPrimitive)?.content.orEmpty() }
                inlineImage(image)?.let { add(it) }
                add(buildJsonObject { put("text", prompt) })
            }
            val payload = buildJsonObject {
                put("contents", buildJsonArray {
                    add(buildJsonObject { put("parts", parts) })
                })
                put("generationConfig", buildJsonObject {
                    put("responseModalities", buildJsonArray { add(JsonPrimitive("TEXT")); add(JsonPrimitive("IMAGE")) })
                })
            }
            return Plan(
                provider = def.id,
                model = model,
                kind = "image",
                url = url,
                headers = headers,
                body = payload.toString().toByteArray(),
            )
        }

        val payload = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            put("n", body.int("n") ?: 1)
            put("size", body.str("size").ifBlank { "1024x1024" })
            body.str("quality").takeIf { it.isNotBlank() }?.let { put("quality", it) }
            body.str("style").takeIf { it.isNotBlank() }?.let { put("style", it) }
            body.str("response_format").takeIf { it.isNotBlank() }?.let { put("response_format", it) }
            body.str("output_format").takeIf { it.isNotBlank() }?.let { put("output_format", it) }
            body.str("watermark").takeIf { it.isNotBlank() }?.let { put("watermark", it) }
            body.str("prompt_extend").takeIf { it.isNotBlank() }?.let { put("prompt_extend", it) }
        }
        val filtered = if (cfg.bodyFields.isEmpty()) payload else JsonObject(
            payload.filterKeys { it in cfg.bodyFields },
        )
        return Plan(
            provider = def.id,
            model = model,
            kind = "image",
            url = cfg.baseUrl,
            headers = headers,
            body = filtered.toString().toByteArray(),
        )
    }

    private fun inlineImage(value: String): JsonObject? {
        if (value.isBlank()) return null
        val data = Regex("^data:([^;]+);base64,(.+)$").find(value)
        if (data != null) {
            return buildJsonObject {
                put("inlineData", buildJsonObject {
                    put("mimeType", data.groupValues[1])
                    put("data", data.groupValues[2])
                })
            }
        }
        if (!value.startsWith("http") && value.length > 100) {
            return buildJsonObject {
                put("inlineData", buildJsonObject {
                    put("mimeType", "image/png")
                    put("data", value)
                })
            }
        }
        return null
    }

    internal fun normalizeGeminiImage(raw: ByteArray): ByteArray {
        val obj = runCatching { json.parseToJsonElement(raw.toString(Charsets.UTF_8)) as JsonObject }.getOrNull()
            ?: throw MediaException(502, "Gemini returned invalid JSON")
        val parts = obj.array("candidates")?.firstOrNull() as? JsonObject
        val contentParts = (parts?.obj("content")?.array("parts")).orEmpty()
        val images = contentParts.mapNotNull { part ->
            (part as? JsonObject)?.obj("inlineData")?.str("data")?.takeIf { it.isNotBlank() }
        }
        if (images.isEmpty()) throw MediaException(502, "Gemini returned no image data")
        val payload = buildJsonObject {
            put("created", System.currentTimeMillis() / 1000)
            put("data", buildJsonArray {
                images.forEach { data -> add(buildJsonObject { put("b64_json", data) }) }
            })
        }
        return payload.toString().toByteArray()
    }

    private fun decodeImageBytes(raw: ByteArray, body: JsonObject): Reply {
        val obj = runCatching { json.parseToJsonElement(raw.toString(Charsets.UTF_8)) as JsonObject }.getOrNull()
            ?: return Reply(502, "application/json", raw)
        val first = obj.array("data")?.firstOrNull() as? JsonObject ?: return Reply(502, "application/json", raw)
        val b64 = first.str("b64_json")
        if (b64.isNotBlank()) {
            val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
                ?: return Reply(502, "application/json", raw)
            return Reply(200, detectImageMime(bytes) ?: imageMime(body.str("output_format")), bytes)
        }
        val url = first.str("url")
        if (url.startsWith("https://") || url.startsWith("http://")) {
            if (!isPublicHttpUrl(url)) return Reply(502, "application/json", raw)
            val fetched = execute(
                Plan(
                    provider = "media",
                    model = "",
                    kind = "image",
                    url = url,
                    method = "GET",
                    headers = emptyMap(),
                    contentType = null,
                    timeoutMs = 60_000L,
                ),
            )
            if (fetched.status in 200..299 && fetched.bytes.size <= 25 * 1024 * 1024) {
                return Reply(200, fetched.contentType.substringBefore(';'), fetched.bytes)
            }
        }
        return Reply(502, "application/json", raw)
    }

    private fun imageMime(format: String): String = when (format.lowercase(Locale.US)) {
        "jpeg", "jpg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    private fun detectImageMime(bytes: ByteArray): String? {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image/jpeg"
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) return "image/png"
        if (bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) return "image/webp"
        if (bytes.size >= 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte()
        ) return "image/gif"
        return null
    }

    /** Never follow a provider-supplied image URL into the phone's own network. */
    private fun isPublicHttpUrl(value: String): Boolean {
        val parsed = runCatching { value.toHttpUrl() }.getOrNull() ?: return false
        if (parsed.scheme != "http" && parsed.scheme != "https") return false
        val addresses = runCatching { java.net.InetAddress.getAllByName(parsed.host) }.getOrNull()
            ?: return false
        return addresses.all { address ->
            if (address.isAnyLocalAddress || address.isLoopbackAddress ||
                address.isLinkLocalAddress || address.isSiteLocalAddress ||
                address.isMulticastAddress
            ) {
                false
            } else {
                val bytes = address.address
                val v4Private = bytes.size == 4 && (
                    (bytes[0].toInt() and 0xFF) == 10 ||
                        (bytes[0].toInt() and 0xFF) == 127 ||
                        ((bytes[0].toInt() and 0xFF) == 169 && (bytes[1].toInt() and 0xFF) == 254) ||
                        ((bytes[0].toInt() and 0xFF) == 172 && (bytes[1].toInt() and 0xFF) in 16..31) ||
                        ((bytes[0].toInt() and 0xFF) == 192 && (bytes[1].toInt() and 0xFF) == 168) ||
                        ((bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xFF) in 64..127)
                    )
                val v6Ula = bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
                !v4Private && !v6Ula
            }
        }
    }

    // ---------------------------------------------------------------------
    // Text to speech
    // ---------------------------------------------------------------------

    fun speech(body: JsonObject, responseFormat: String): Reply {
        val target = target(body.str("model"), body.str("provider"), "tts")
        ConsoleLog.info("[MEDIA] tts ${target.def.id}/${target.model}")
        val format = responseFormat.ifBlank { body.str("response_format") }.ifBlank { "mp3" }
        val attempt = attempt(target.def.id) { def, conn ->
            speechPlan(def, conn, target.model, body, format)
        }
        if (attempt.reply.status !in 200..299) return attempt.reply
        if (format.equals("json", true)) {
            val base64 = Base64.getEncoder().encodeToString(attempt.reply.bytes)
            val payload = buildJsonObject {
                put("audio", base64)
                put("format", audioFormat(format))
            }
            return Reply(200, "application/json", payload.toString().toByteArray())
        }
        return attempt.reply
    }

    internal fun speechPlan(
        def: ProviderDef,
        conn: Connection,
        model: String,
        body: JsonObject,
        responseFormat: String,
    ): Plan {
        val cfg = def.ttsConfig?.takeIf { it.baseUrl.isNotBlank() }
            ?: throw MediaException(501, "${def.name} speech is not supported in this build")
        if (def.id !in TTS_ADAPTERS) {
            throw MediaException(501, "${def.name} speech needs a provider adapter that this build does not include")
        }
        val input = body.str("input")
        if (input.isBlank()) throw MediaException(400, "Missing required field: input")

        return when (cfg.format.lowercase(Locale.US)) {
            "elevenlabs" -> elevenLabsSpeechPlan(def, cfg, conn, model, body, input, responseFormat)
            "openai", "openai-speech" -> openAiSpeechPlan(def, cfg, conn, model, body, input, responseFormat)
            else -> throw MediaException(501, "${def.name} speech format is not supported in this build")
        }
    }

    private fun openAiSpeechPlan(
        def: ProviderDef,
        cfg: MediaConfig,
        conn: Connection,
        model: String,
        body: JsonObject,
        input: String,
        responseFormat: String,
    ): Plan {
        var modelId = model.ifBlank { cfg.defaultModel.ifBlank { "gpt-4o-mini-tts" } }
        var voice = body.str("voice")
        if (model.contains('/')) {
            modelId = model.substringBefore('/')
            if (voice.isBlank()) voice = model.substringAfter('/')
        } else if (voice.isBlank()) {
            if (model.startsWith("tts-") || model.startsWith("gpt-4o")) voice = "alloy"
            else if (model.isNotBlank()) {
                voice = model
                modelId = cfg.defaultModel.ifBlank { "gpt-4o-mini-tts" }
            } else {
                voice = "alloy"
            }
        }
        if (voice.isBlank()) voice = "alloy"
        val payload = buildJsonObject {
            put("model", modelId)
            put("input", input)
            put("voice", voice)
            put("response_format", audioFormat(responseFormat))
            body.double("speed")?.let { put("speed", it) }
        }
        return Plan(
            provider = def.id,
            model = modelId,
            kind = "tts",
            url = cfg.baseUrl,
            headers = baseHeaders(def, cfg, conn, accept = "audio/*"),
            body = payload.toString().toByteArray(),
        )
    }

    private fun elevenLabsSpeechPlan(
        def: ProviderDef,
        cfg: MediaConfig,
        conn: Connection,
        model: String,
        body: JsonObject,
        input: String,
        responseFormat: String,
    ): Plan {
        var modelId = "eleven_flash_v2_5"
        var voiceId = model.ifBlank { body.str("voice") }
        if (model.contains('/')) {
            modelId = model.substringBefore('/')
            voiceId = model.substringAfter('/')
        } else if (model.startsWith("eleven_") && body.str("voice").isNotBlank()) {
            modelId = model
            voiceId = body.str("voice")
        }
        if (voiceId.isBlank()) voiceId = "21m00Tcm4TlvDq8ikWAM"
        val out = when (responseFormat.lowercase(Locale.US)) {
            "opus" -> "opus_48000_128"
            "wav" -> "wav_24000"
            "pcm" -> "pcm_24000"
            else -> "mp3_44100_128"
        }
        val payload = buildJsonObject {
            put("text", input)
            put("model_id", modelId)
            body.str("language").takeIf { it.isNotBlank() }?.let { put("language_code", it) }
            body["voice_settings"]?.let { put("voice_settings", it) }
            body["seed"]?.let { put("seed", it) }
        }
        val url = "${cfg.baseUrl.trimEnd('/')}/${urlEncode(voiceId)}?output_format=$out"
        return Plan(
            provider = def.id,
            model = modelId,
            kind = "tts",
            url = url,
            headers = baseHeaders(def, cfg, conn, accept = "audio/*"),
            body = payload.toString().toByteArray(),
        )
    }

    private fun audioFormat(format: String): String = when (format.lowercase(Locale.US)) {
        "opus" -> "opus"
        "wav" -> "wav"
        "pcm" -> "pcm"
        "flac" -> "flac"
        "aac" -> "aac"
        "json" -> "mp3"
        else -> "mp3"
    }

    // ---------------------------------------------------------------------
    // Speech to text
    // ---------------------------------------------------------------------

    fun transcribe(rawBody: ByteArray, contentType: String): Reply {
        if (!contentType.lowercase(Locale.US).startsWith("multipart/form-data")) {
            throw MediaException(400, "Expected multipart/form-data")
        }
        val field = multipartModel(rawBody, contentType)
            ?: throw MediaException(400, "Missing required field: model")
        val target = target(field, null, "stt")
        ConsoleLog.info("[MEDIA] stt ${target.def.id}/${target.model}")
        val model = if (field.contains('/')) field.substringAfter('/') else field
        val rewritten = rewriteMultipartField(rawBody, contentType, "model", model)
        val attempt = attempt(target.def.id) { def, conn ->
            transcribePlan(def, conn, rewritten, contentType)
        }
        return attempt.reply
    }

    fun multipartModel(rawBody: ByteArray, contentType: String): String? =
        multipartField(rawBody, contentType, "model")

    internal fun transcribePlan(
        def: ProviderDef,
        conn: Connection,
        body: ByteArray,
        contentType: String,
    ): Plan {
        val cfg = def.sttConfig?.takeIf { it.baseUrl.isNotBlank() }
            ?: throw MediaException(501, "${def.name} transcription is not supported in this build")
        if (def.id !in STT_ADAPTERS) {
            throw MediaException(501, "${def.name} transcription needs a provider adapter that this build does not include")
        }
        if (cfg.format.isNotBlank() && cfg.format.lowercase(Locale.US) != "openai") {
            throw MediaException(501, "${def.name} transcription format is not supported in this build")
        }
        return Plan(
            provider = def.id,
            model = "",
            kind = "stt",
            url = cfg.baseUrl,
            headers = baseHeaders(def, cfg, conn, accept = "application/json"),
            body = body,
            contentType = contentType,
            timeoutMs = 300_000L,
        )
    }

    private fun multipartField(raw: ByteArray, contentType: String, name: String): String? {
        val boundary = boundaryOf(contentType) ?: return null
        val parts = splitMultipart(raw, boundary)
        for (part in parts) {
            val separator = indexOf(part, CRLF_CRLF) ?: continue
            val headers = String(part, 0, separator, Charsets.ISO_8859_1)
            if (!headers.contains("name=\"$name\"", ignoreCase = true)) continue
            return String(part, separator + 4, part.size - separator - 4, Charsets.UTF_8)
                .trimEnd('\r', '\n')
        }
        return null
    }

    internal fun rewriteMultipartField(raw: ByteArray, contentType: String, name: String, value: String): ByteArray {
        val boundary = boundaryOf(contentType) ?: return raw
        val parts = splitMultipart(raw, boundary)
        var replaced = false
        val out = java.io.ByteArrayOutputStream()
        for (part in parts) {
            out.write("--$boundary\r\n".toByteArray(Charsets.ISO_8859_1))
            if (!replaced) {
                val separator = indexOf(part, CRLF_CRLF)
                if (separator != null) {
                    val headers = String(part, 0, separator, Charsets.ISO_8859_1)
                    if (headers.contains("name=\"$name\"", ignoreCase = true)) {
                        out.write(part, 0, separator + 4)
                        out.write(value.toByteArray(Charsets.UTF_8))
                        out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
                        replaced = true
                        continue
                    }
                }
            }
            out.write(part)
            out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        }
        out.write("--$boundary--\r\n".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private fun boundaryOf(contentType: String): String? =
        Regex("boundary=\"?([^\";]+)\"?").find(contentType)?.groupValues?.get(1)?.trim()

    /** Returns each part body without the CRLF that precedes the next boundary. */
    private fun splitMultipart(raw: ByteArray, boundary: String): List<ByteArray> {
        val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val parts = mutableListOf<ByteArray>()
        var index = indexOf(raw, delimiter)
        if (index < 0) return emptyList()
        index += delimiter.size
        while (index < raw.size) {
            if (index + 2 <= raw.size && raw[index] == '\r'.code.toByte() && raw[index + 1] == '\n'.code.toByte()) {
                index += 2
            }
            if (index + 2 <= raw.size && raw[index] == '-'.code.toByte() && raw[index + 1] == '-'.code.toByte()) break
            val start = index
            val next = indexOf(raw, delimiter, index)
            if (next < 0) break
            var end = next
            if (end >= 2 && raw[end - 1] == '\n'.code.toByte() && raw[end - 2] == '\r'.code.toByte()) end -= 2
            parts += raw.copyOfRange(start, end)
            index = next + delimiter.size
        }
        return parts
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, start: Int = 0): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in start..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private val CRLF_CRLF = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

    // ---------------------------------------------------------------------
    // Web search
    // ---------------------------------------------------------------------

    fun search(body: JsonObject): Reply {
        val query = body.str("query")
        if (query.isBlank()) throw MediaException(400, "Missing required field: query")
        val target = targetWeb(body.str("model"), body.str("provider"), "search")
        ConsoleLog.info("[MEDIA] search ${target.def.id} \"${query.take(80)}\"")
        val attempt = attempt(target.def.id) { def, conn -> searchPlan(def, conn, body) }
        if (attempt.reply.status !in 200..299) return attempt.reply
        val started = System.currentTimeMillis()
        val normalized = normalizeSearch(target.def.id, attempt.reply.bytes, query)
        val elapsed = System.currentTimeMillis() - started
        val enriched = runCatching {
            val obj = json.parseToJsonElement(normalized.toString(Charsets.UTF_8)) as JsonObject
            JsonObject(obj + ("metrics" to buildJsonObject {
                put("response_time_ms", elapsed)
                put("upstream_latency_ms", elapsed)
                put("total_results_available", (obj["results"] as? JsonArray)?.size ?: 0)
            })).toString().toByteArray()
        }.getOrDefault(normalized)
        return Reply(200, "application/json", enriched)
    }

    internal fun searchPlan(def: ProviderDef, conn: Connection, body: JsonObject): Plan {
        val cfg = def.searchConfig?.takeIf { it.baseUrl.isNotBlank() }
            ?: throw MediaException(501, "${def.name} web search is not supported in this build")
        val query = body.str("query")
        val max = minOf(body.int("max_results") ?: cfg.defaultMaxResults, cfg.maxMaxResults)
        val type = body.str("search_type").ifBlank { "web" }
        val headers = baseHeaders(def, cfg, conn, accept = "application/json")
        val timeout = cfg.timeoutMs.toLong().coerceAtLeast(5_000L)

        return when (def.id) {
            "tavily" -> {
                val payload = buildJsonObject {
                    put("query", query)
                    put("max_results", max)
                    put("topic", if (type == "news") "news" else "general")
                    body.str("country").takeIf { it.isNotBlank() }?.let { put("country", it) }
                    body.array("domain_filter")?.let { domains ->
                        val include = domains.mapNotNull { (it as? JsonPrimitive)?.content }.filter { !it.startsWith("-") }
                        val exclude = domains.mapNotNull { (it as? JsonPrimitive)?.content }.filter { it.startsWith("-") }.map { it.drop(1) }
                        if (include.isNotEmpty()) put("include_domains", buildJsonArray { include.forEach { add(JsonPrimitive(it)) } })
                        if (exclude.isNotEmpty()) put("exclude_domains", buildJsonArray { exclude.forEach { add(JsonPrimitive(it)) } })
                    }
                }
                Plan(def.id, query, "search", cfg.baseUrl, headers = headers, body = payload.toString().toByteArray(), timeoutMs = timeout)
            }
            "exa" -> {
                val payload = buildJsonObject {
                    put("query", query)
                    put("numResults", max)
                    put("type", "auto")
                    put("text", true)
                    put("highlights", true)
                    if (type == "news") put("category", "news")
                    body.array("domain_filter")?.let { domains ->
                        val include = domains.mapNotNull { (it as? JsonPrimitive)?.content }.filter { !it.startsWith("-") }
                        val exclude = domains.mapNotNull { (it as? JsonPrimitive)?.content }.filter { it.startsWith("-") }.map { it.drop(1) }
                        if (include.isNotEmpty()) put("includeDomains", buildJsonArray { include.forEach { add(JsonPrimitive(it)) } })
                        if (exclude.isNotEmpty()) put("excludeDomains", buildJsonArray { exclude.forEach { add(JsonPrimitive(it)) } })
                    }
                }
                Plan(def.id, query, "search", cfg.baseUrl, headers = headers, body = payload.toString().toByteArray(), timeoutMs = timeout)
            }
            "brave-search" -> {
                val root = cfg.baseUrl.trimEnd('/') + if (type == "news") "/news/search" else "/web/search"
                val url = root.toHttpUrl().newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("count", max.toString())
                    .apply {
                        body.str("country").takeIf { it.isNotBlank() }?.let { addQueryParameter("country", it) }
                        body.str("language").takeIf { it.isNotBlank() }?.let { addQueryParameter("search_lang", it) }
                    }
                    .build()
                    .toString()
                Plan(def.id, query, "search", url, "GET", headers, timeoutMs = timeout)
            }
            "serper" -> {
                val root = cfg.baseUrl.trimEnd('/') + if (type == "news") "/news" else "/search"
                val payload = buildJsonObject {
                    put("q", query)
                    put("num", max)
                    body.str("country").takeIf { it.isNotBlank() }?.let { put("gl", it.lowercase(Locale.US)) }
                    body.str("language").takeIf { it.isNotBlank() }?.let { put("hl", it) }
                }
                Plan(def.id, query, "search", root, headers = headers, body = payload.toString().toByteArray(), timeoutMs = timeout)
            }
            else -> throw MediaException(501, "${def.name} web search is not supported in this build")
        }
    }

    internal fun normalizeSearch(provider: String, raw: ByteArray, query: String): ByteArray {
        val data = runCatching { json.parseToJsonElement(raw.toString(Charsets.UTF_8)) as JsonObject }.getOrNull()
            ?: throw MediaException(502, "$provider returned invalid JSON")
        val items = when (provider) {
            "tavily" -> data.array("results").orEmpty()
            "exa" -> data.array("results").orEmpty()
            "brave-search" -> {
                val container = data.obj("web") ?: data.obj("news") ?: data
                container.array("results").orEmpty()
            }
            "serper" -> data.array("organic") ?: data.array("news").orEmpty()
            else -> emptyList()
        }
        val now = java.time.Instant.now().toString()
        val results = buildJsonArray {
            items.forEachIndexed { index, element ->
                val item = element as? JsonObject ?: return@forEachIndexed
                val url = when (provider) {
                    "serper" -> item.str("link")
                    "exa", "tavily" -> item.str("url")
                    else -> item.str("url")
                }
                val title = item.str("title")
                val snippet = when (provider) {
                    "serper" -> item.str("snippet").ifBlank { item.str("description") }
                    "brave-search" -> item.str("description")
                    "exa" -> item.array("highlights")?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }
                        .orEmpty().ifBlank { item.str("text").take(300) }
                    "tavily" -> item.str("content")
                    else -> ""
                }
                val fullText = when (provider) {
                    "tavily" -> item.str("raw_content")
                    "exa" -> item.str("text")
                    else -> ""
                }
                val score = item.double("score")
                val published = item.str("published_date").ifBlank { item.str("publishedDate") }
                    .ifBlank { item.str("date") }.ifBlank { item.str("page_age") }
                val favicon = item.obj("meta_url")?.str("favicon") ?: item.str("favicon")
                val author = item.str("author")
                val image = item.str("image")
                add(buildJsonObject {
                    put("title", title)
                    put("url", url)
                    if (url.isNotBlank()) {
                        put("display_url", url.removePrefix("https://").removePrefix("http://")
                            .removePrefix("www.").substringBefore('?'))
                    }
                    put("snippet", snippet)
                    put("position", index + 1)
                    if (score != null) put("score", score) else put("score", JsonNull)
                    if (published.isNotBlank()) put("published_at", published) else put("published_at", JsonNull)
                    if (favicon.isNotBlank()) put("favicon_url", favicon) else put("favicon_url", JsonNull)
                    if (fullText.isNotBlank()) {
                        put("content", buildJsonObject {
                            put("format", "text")
                            put("text", fullText)
                            put("length", fullText.length)
                        })
                    } else {
                        put("content", JsonNull)
                    }
                    put("metadata", buildJsonObject {
                        if (author.isNotBlank()) put("author", author) else put("author", JsonNull)
                        put("language", JsonNull)
                        put("source_type", JsonNull)
                        if (image.isNotBlank()) put("image_url", image) else put("image_url", JsonNull)
                    })
                    put("citation", buildJsonObject {
                        put("provider", provider)
                        put("retrieved_at", now)
                        put("rank", index + 1)
                    })
                    put("provider_raw", JsonNull)
                })
            }
        }
        val payload = buildJsonObject {
            put("provider", provider)
            put("query", query)
            put("results", results)
            put("answer", JsonNull)
            put("usage", buildJsonObject {
                put("queries_used", 1)
                put("search_cost_usd", JsonNull)
            })
            put("errors", buildJsonArray { })
        }
        return payload.toString().toByteArray()
    }

    // ---------------------------------------------------------------------
    // Web fetch
    // ---------------------------------------------------------------------

    fun fetch(body: JsonObject): Reply {
        val url = body.str("url")
        if (url.isBlank()) throw MediaException(400, "Missing required field: url")
        val target = targetWeb(body.str("model"), body.str("provider"), "fetch")
        ConsoleLog.info("[MEDIA] fetch ${target.def.id} ${url.take(120)}")
        val attempt = attempt(target.def.id) { def, conn -> fetchPlan(def, conn, body) }
        if (attempt.reply.status !in 200..299) return attempt.reply
        val started = System.currentTimeMillis()
        val normalized = normalizeFetch(target.def.id, attempt.reply, body, started)
        return Reply(200, "application/json", normalized)
    }

    internal fun fetchPlan(def: ProviderDef, conn: Connection, body: JsonObject): Plan {
        val cfg = def.fetchConfig?.takeIf { it.baseUrl.isNotBlank() }
            ?: throw MediaException(501, "${def.name} web fetch is not supported in this build")
        val url = body.str("url")
        val format = body.str("format").ifBlank { "markdown" }.lowercase(Locale.US)
        if (cfg.formats.isNotEmpty() && format !in cfg.formats) {
            throw MediaException(400, "Unsupported format for ${def.name}. Supported: ${cfg.formats.joinToString(", ")}")
        }
        val headers = baseHeaders(def, cfg, conn, accept = "application/json")
        val timeout = cfg.timeoutMs.toLong().coerceAtLeast(5_000L)
        return when (def.id) {
            "jina-reader" -> Plan(
                def.id, url, "fetch", cfg.baseUrl.trimEnd('/') + "/",
                headers = headers + ("x-respond-with" to format),
                body = buildJsonObject { put("url", url) }.toString().toByteArray(),
                timeoutMs = timeout,
            )
            "firecrawl" -> Plan(
                def.id, url, "fetch", cfg.baseUrl,
                headers = headers,
                body = buildJsonObject {
                    put("url", url)
                    put("formats", buildJsonArray { add(JsonPrimitive(format)) })
                }.toString().toByteArray(),
                timeoutMs = timeout,
            )
            "tavily" -> Plan(
                def.id, url, "fetch", cfg.baseUrl,
                headers = headers,
                body = buildJsonObject {
                    put("urls", buildJsonArray { add(JsonPrimitive(url)) })
                    put("extract_depth", "basic")
                    put("format", format)
                }.toString().toByteArray(),
                timeoutMs = timeout,
            )
            "exa" -> Plan(
                def.id, url, "fetch", cfg.baseUrl,
                headers = headers,
                body = buildJsonObject {
                    put("ids", buildJsonArray { add(JsonPrimitive(url)) })
                    put("text", true)
                }.toString().toByteArray(),
                timeoutMs = timeout,
            )
            else -> throw MediaException(501, "${def.name} web fetch is not supported in this build")
        }
    }

    private fun normalizeFetch(provider: String, reply: Reply, body: JsonObject, started: Long): ByteArray {
        val format = body.str("format").ifBlank { "markdown" }
        val max = body.int("max_characters") ?: 0
        var title: String? = null
        var text = ""
        when (provider) {
            "jina-reader" -> {
                text = reply.bytes.toString(Charsets.UTF_8)
                title = Regex("(?im)^\\s*Title:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()
                    ?: Regex("(?m)^\\s*#\\s+(.+)$").find(text)?.groupValues?.get(1)?.trim()
            }
            "firecrawl" -> {
                val data = runCatching { json.parseToJsonElement(reply.bytes.toString(Charsets.UTF_8)) as JsonObject }.getOrNull()
                    ?.obj("data")
                text = data?.str("markdown").orEmpty().ifBlank { data?.str("html").orEmpty() }
                    .ifBlank { data?.str("text").orEmpty() }
                title = data?.obj("metadata")?.str("title")
            }
            "tavily" -> {
                val first = runCatching { json.parseToJsonElement(reply.bytes.toString(Charsets.UTF_8)) as JsonObject }.getOrNull()
                    ?.array("results")?.firstOrNull() as? JsonObject
                text = first?.str("raw_content").orEmpty()
            }
            "exa" -> {
                val first = runCatching { json.parseToJsonElement(reply.bytes.toString(Charsets.UTF_8)) as JsonObject }.getOrNull()
                    ?.array("results")?.firstOrNull() as? JsonObject
                text = first?.str("text").orEmpty()
                title = first?.str("title")
            }
        }
        if (max > 0 && text.length > max) text = text.take(max)
        val payload = buildJsonObject {
            put("provider", provider)
            put("url", body.str("url"))
            if (title.isNullOrBlank()) put("title", JsonNull) else put("title", title!!)
            put("content", buildJsonObject {
                put("format", format)
                put("text", text)
                put("length", text.length)
            })
            put("metadata", buildJsonObject {
                put("author", JsonNull)
                put("published_at", JsonNull)
                put("language", JsonNull)
            })
            put("usage", buildJsonObject { put("fetch_cost_usd", JsonNull) })
            put("metrics", buildJsonObject {
                put("response_time_ms", System.currentTimeMillis() - started)
                put("upstream_latency_ms", System.currentTimeMillis() - started)
            })
        }
        return payload.toString().toByteArray()
    }

    // ---------------------------------------------------------------------
    // Shared HTTP
    // ---------------------------------------------------------------------

    internal fun baseHeaders(
        def: ProviderDef,
        cfg: MediaConfig?,
        conn: Connection,
        accept: String,
    ): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        def.transportHeaders.forEach { (key, value) ->
            if (value.isNotBlank() && !key.equals("authorization", true)) headers[key] = value
        }
        cfg?.headers?.forEach { (key, value) -> headers[key] = value }
        headers["Accept"] = accept
        val token = conn.accessToken.ifBlank { conn.apiKey }
        if (token.isNotBlank()) {
            // Gemini API keys use their own header; OAuth access tokens fall
            // back to Bearer, which the generative endpoints also accept.
            if (def.id == "gemini" && conn.apiKey.isNotBlank()) {
                headers["x-goog-api-key"] = conn.apiKey
                return headers
            }
            val declared = cfg?.authHeader?.lowercase(Locale.US).orEmpty()
                .ifBlank { def.authHeader.lowercase(Locale.US) }
            when (declared) {
                "xi-api-key" -> headers["xi-api-key"] = token
                "x-api-key" -> headers["x-api-key"] = token
                "x-subscription-token" -> headers["X-Subscription-Token"] = token
                "key", "x-goog-api-key" -> headers["x-goog-api-key"] = token
                "token" -> headers["Authorization"] = "Token $token"
                "none" -> Unit
                else -> headers["Authorization"] = "Bearer $token"
            }
        }
        return headers
    }

    fun execute(plan: Plan): Reply {
        val client = baseClient.newBuilder()
            .callTimeout(plan.timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val builder = Request.Builder().url(plan.url)
        plan.headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank() && !key.equals("content-type", true)) {
                builder.header(key, value)
            }
        }
        val body = plan.body?.toRequestBody(plan.contentType?.toMediaTypeOrNull())
        if (plan.method.equals("GET", true)) {
            builder.get()
        } else {
            builder.method(plan.method, body ?: ByteArray(0).toRequestBody(plan.contentType?.toMediaTypeOrNull()))
        }
        return try {
            client.newCall(builder.build()).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                Reply(
                    status = response.code,
                    contentType = response.header("Content-Type") ?: "application/json",
                    bytes = bytes,
                )
            }
        } catch (e: Exception) {
            throw MediaException(502, e.message ?: "Upstream request failed")
        }
    }

    private fun String.toMediaTypeOrNull() = runCatching { toMediaType() }.getOrNull()

    // ---------------------------------------------------------------------
    // JSON helpers
    // ---------------------------------------------------------------------

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content.orEmpty()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
