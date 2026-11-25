package com.ninerouter.android.core

import com.ninerouter.android.data.ProviderDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Provider-agnostic OAuth for Android.
 *
 * Desktop 9router completes PKCE flows with a temporary loopback callback
 * server (`http://localhost:<port>/callback`). Android mirrors that exactly:
 * a short-lived ServerSocket is opened on loopback, the browser is sent there,
 * and the redirect is captured in-process. A custom scheme is not registered
 * with the providers' client ids.
 */
object OAuthFlow {
    const val REDIRECT_URI = "ninerouter://oauth/callback"

    data class Tokens(val access: String, val refresh: String, val expiresIn: Long)

    data class LoginResult(val tokens: Tokens?, val error: String? = null)

    data class DeviceChallenge(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val interval: Long,
        val expiresAt: Long,
        val verifier: String = "",
        val pollByUser: Boolean = false,
        val deviceId: String = "",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val pending = HashMap<String, PendingPkce>()

    private data class PendingPkce(
        val verifier: String,
        val state: String,
        val redirectUri: String,
        val provider: String,
    )

    private data class Callback(val code: String?, val state: String?, val error: String?)

    /** Legacy custom-scheme entry point, kept for the manifest callback filter. */
    fun authorizeUrl(def: ProviderDef): String {
        val verifier = randomToken(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = UUID.randomUUID().toString()
        pending[state] = PendingPkce(verifier, state, REDIRECT_URI, def.id)
        return buildAuthorizeUrl(def, REDIRECT_URI, challenge, state)
    }

    fun providerFor(state: String): String? = pending[state]?.provider

    fun exchangeCode(def: ProviderDef, code: String, state: String): Tokens? {
        val p = pending[state] ?: return null
        return exchange(def, code, state, p.verifier, p.redirectUri).tokens
    }

    /**
     * Full browser PKCE flow. Suspends on an IO thread while the loopback
     * callback server waits. [manualCode] lets the user paste a callback URL or
     * authorization code when a browser cannot reach the loopback server.
     */
    suspend fun loginWithBrowser(
        def: ProviderDef,
        openBrowser: (String) -> Unit,
        onStatus: (String) -> Unit = {},
        manualCode: () -> String? = { null },
    ): LoginResult = withContext(Dispatchers.IO) {
        if (def.authorizeUrl.isBlank() || def.clientId.isBlank()) {
            return@withContext LoginResult(null, "This provider has no browser login endpoint.")
        }
        val path = def.callbackPath.ifBlank { "/callback" }
        var server: ServerSocket? = null
        try {
            server = ServerSocket(def.fixedPort.takeIf { it > 0 } ?: 0, 4, InetAddress.getByName("127.0.0.1"))
            server.soTimeout = 500
            val port = server.localPort
            val redirectUri = "http://localhost:$port$path"
            val verifier = randomToken(64)
            val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val state = UUID.randomUUID().toString()
            val url = buildAuthorizeUrl(def, redirectUri, challenge, state)
            onStatus("Waiting for browser authorization...")
            openBrowser(url)

            val callback = awaitCallback(server, path, manualCode)
                ?: return@withContext LoginResult(null, "Authorization timed out. Try again.")
            if (callback.error != null) {
                return@withContext LoginResult(null, "Provider returned: ${callback.error}")
            }
            if (callback.code.isNullOrBlank()) {
                return@withContext LoginResult(null, "No authorization code was returned.")
            }
            if (!callback.state.isNullOrBlank() && callback.state != state) {
                return@withContext LoginResult(null, "OAuth state mismatch. Please retry.")
            }
            val result = exchange(def, callback.code, callback.state ?: state, verifier, redirectUri)
            result
        } catch (e: Exception) {
            LoginResult(null, e.message ?: e.javaClass.simpleName)
        } finally {
            try { server?.close() } catch (_: Exception) {}
        }
    }

    private fun usesPkce(def: ProviderDef): Boolean = def.id.startsWith("minimax")

    /** Extra request headers some device flows require (mirrors the CLI clients). */
    private fun deviceHeaders(def: ProviderDef, deviceId: String): Map<String, String> = when (def.id) {
        "kimi" -> mapOf(
            "X-Msh-Platform" to "9router",
            "X-Msh-Version" to "9router-android",
            "X-Msh-Device-Name" to (android.os.Build.MODEL ?: "android"),
            "X-Msh-Device-Model" to "Android ${android.os.Build.VERSION.RELEASE}",
            "X-Msh-Device-Id" to deviceId,
        )
        "grok-cli" -> mapOf("User-Agent" to "grok-cli/pager")
        "muse" -> mapOf("x-api-version" to "1.0.0")
        else -> emptyMap()
    }

    fun startDevice(def: ProviderDef): DeviceChallenge? {
        if (def.deviceCodeUrl.isBlank()) return null
        val verifier = randomToken(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val deviceId = UUID.randomUUID().toString()
        val builder = FormBody.Builder().add("client_id", def.clientId)
        if (def.scope.isNotBlank()) builder.add("scope", def.scope)
        if (def.id == "grok-cli") builder.add("referrer", "grok-build")
        if (def.id.startsWith("minimax")) {
            val audience = def.extraParams["audience"] ?: "agent-backend"
            builder.add("audience", audience)
        }
        if (usesPkce(def)) {
            builder.add("code_challenge", challenge)
            builder.add("code_challenge_method", "S256")
        }
        val reqBuilder = Request.Builder()
            .url(def.deviceCodeUrl)
            .header("Accept", "application/json")
            .post(builder.build())
        deviceHeaders(def, deviceId).forEach { (k, v) -> reqBuilder.header(k, v) }
        val obj = try {
            client.newCall(reqBuilder.build()).execute().use { res ->
                if (!res.isSuccessful) return null
                json.parseToJsonElement(res.body!!.string()) as? JsonObject ?: return null
            }
        } catch (_: Exception) { return null }
        fun s(key: String) = (obj[key] as? JsonPrimitive)?.content ?: ""
        val deviceCode = s("device_code").ifBlank { s("user_code") }
        val pollByUser = s("device_code").isBlank()
        val expires = s("expires_in").toLongOrNull() ?: 600L
        return DeviceChallenge(
            deviceCode = deviceCode,
            userCode = s("user_code"),
            verificationUri = s("verification_uri").ifBlank { s("verification_url") }
                .ifBlank { s("verification_uri_complete") },
            interval = s("interval").toLongOrNull() ?: 5L,
            expiresAt = System.currentTimeMillis() + expires * 1000L,
            verifier = if (usesPkce(def)) verifier else "",
            pollByUser = pollByUser,
            deviceId = deviceId,
        ).takeIf { it.deviceCode.isNotBlank() }
    }

    fun pollDevice(def: ProviderDef, challenge: DeviceChallenge): LoginResult {
        if (System.currentTimeMillis() >= challenge.expiresAt) {
            return LoginResult(null, "Device code expired.")
        }
        val builder = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add(if (challenge.pollByUser) "user_code" else "device_code", challenge.deviceCode)
            .add("client_id", def.clientId)
            .apply {
                if (challenge.verifier.isNotBlank()) add("code_verifier", challenge.verifier)
                if (def.clientSecret.isNotBlank()) add("client_secret", def.clientSecret)
            }
        val reqBuilder = Request.Builder()
            .url(def.tokenUrl)
            .header("Accept", "application/json")
            .post(builder.build())
        deviceHeaders(def, challenge.deviceId).forEach { (k, v) -> reqBuilder.header(k, v) }
        return executeToken(reqBuilder.build())
    }

    private fun buildAuthorizeUrl(def: ProviderDef, redirectUri: String, challenge: String, state: String): String {
        // iFlow uses its own query shape (`redirect`, no PKCE) and Basic Auth.
        if (def.id == "iflow") {
            val params = linkedMapOf(
                "loginMethod" to (def.extraParams["loginMethod"] ?: "phone"),
                "type" to (def.extraParams["type"] ?: "phone"),
                "redirect" to redirectUri,
                "state" to state,
                "client_id" to def.clientId,
            )
            val query = params.entries.joinToString("&") { (k, v) ->
                "${android.net.Uri.encode(k)}=${android.net.Uri.encode(v)}"
            }
            return "${def.authorizeUrl.trimEnd('?', '&')}?$query"
        }
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to def.clientId,
            "redirect_uri" to redirectUri,
            "scope" to def.scope.ifBlank { "openid profile email offline_access" },
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
        )
        if (def.id == "claude") params["code"] = "true"
        if (def.id == "gemini" || def.id == "gemini-cli" || def.id == "antigravity") {
            params["access_type"] = "offline"
            params["prompt"] = "consent"
        }
        def.extraParams.forEach { (k, v) -> params[k] = v }
        val query = params.entries.joinToString("&") { (k, v) ->
            "${android.net.Uri.encode(k)}=${android.net.Uri.encode(v)}"
        }
        return "${def.authorizeUrl.trimEnd('?', '&')}?$query"
    }

    private fun exchange(
        def: ProviderDef,
        code: String,
        state: String,
        verifier: String,
        redirectUri: String,
    ): LoginResult {
        // Claude returns `code#state` from its consent screen.
        val authCode = if (code.contains("#")) code.substringBefore("#") else code
        val authState = if (code.contains("#")) code.substringAfter("#") else state
        if (def.id == "iflow") {
            val basic = Base64.getEncoder()
                .encodeToString("${def.clientId}:${def.clientSecret}".toByteArray())
            val form = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", authCode)
                .add("redirect_uri", redirectUri)
                .add("client_id", def.clientId)
                .add("client_secret", def.clientSecret)
                .build()
            val req = Request.Builder()
                .url(def.tokenUrl)
                .header("Accept", "application/json")
                .header("Authorization", "Basic $basic")
                .post(form)
                .build()
            return executeToken(req)
        }
        val result = if (def.tokenEncoding.equals("json", true)) {
            val payload = buildJsonObject {
                put("code", authCode)
                put("state", authState)
                put("grant_type", "authorization_code")
                put("client_id", def.clientId)
                put("redirect_uri", redirectUri)
                put("code_verifier", verifier)
                if (def.clientSecret.isNotBlank()) put("client_secret", def.clientSecret)
            }
            val req = Request.Builder()
                .url(def.tokenUrl)
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            executeToken(req)
        } else {
            val form = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", authCode)
                .add("redirect_uri", redirectUri)
                .add("client_id", def.clientId)
                .add("code_verifier", verifier)
                .apply { if (def.clientSecret.isNotBlank()) add("client_secret", def.clientSecret) }
                .build()
            postTokens(def, form)
        }
        return result
    }

    private fun postTokens(def: ProviderDef, form: FormBody): LoginResult {
        val req = Request.Builder()
            .url(def.tokenUrl)
            .header("Accept", "application/json")
            .post(form)
            .build()
        return executeToken(req)
    }

    private fun executeToken(req: Request): LoginResult {
        if (req.url.toString().isBlank()) return LoginResult(null, "Missing token endpoint.")
        return try {
            client.newCall(req).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                val obj = try { json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
                    ?: return LoginResult(null, "Invalid token response (${res.code}).")
                val error = (obj["error"] as? JsonPrimitive)?.content
                    ?: (obj["status"] as? JsonPrimitive)?.content?.takeIf {
                        it == "pending" || it == "slow_down" || it == "denied" || it == "expired"
                    }
                if (!res.isSuccessful || error != null) {
                    val desc = (obj["error_description"] as? JsonPrimitive)?.content
                        ?: (obj["message"] as? JsonPrimitive)?.content
                        ?: raw.take(180)
                    return LoginResult(null, desc.ifBlank { "Token exchange failed (${res.code})." })
                }
                val access = (obj["access_token"] as? JsonPrimitive)?.content
                    ?: return LoginResult(null, "Token response had no access_token.")
                val refresh = (obj["refresh_token"] as? JsonPrimitive)?.content ?: ""
                val expires = (obj["expires_in"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 3600L
                LoginResult(Tokens(access, refresh, expires))
            }
        } catch (e: Exception) {
            LoginResult(null, e.message ?: e.javaClass.simpleName)
        }
    }

    /** GitHub Copilot needs a short-lived Copilot token derived from the GitHub OAuth token. */
    fun postProcess(def: ProviderDef, tokens: Tokens): LoginResult {
        if (def.id == "muse") return mintMuseKey(tokens)
        if (def.id != "github" || def.copilotTokenUrl.isBlank()) return LoginResult(tokens)
        val req = Request.Builder()
            .url(def.copilotTokenUrl)
            .header("Authorization", "token ${tokens.access}")
            .header("Accept", "application/json")
            .header("User-Agent", "GitHubCopilotChat/0.26.7")
            .header("Editor-Version", "vscode/1.85.0")
            .header("Editor-Plugin-Version", "copilot-chat/0.26.7")
            .build()
        return try {
            client.newCall(req).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                val obj = json.parseToJsonElement(raw) as? JsonObject
                val token = (obj?.get("token") as? JsonPrimitive)?.content
                if (!res.isSuccessful || token.isNullOrBlank()) {
                    return LoginResult(null, "GitHub Copilot token exchange failed (${res.code}).")
                }
                val expiresAt = (obj["expires_at"] as? JsonPrimitive)?.content?.toLongOrNull()
                val expiresIn = expiresAt?.let { (it - System.currentTimeMillis() / 1000).coerceAtLeast(60) } ?: 1800L
                LoginResult(Tokens(token, tokens.access, expiresIn))
            }
        } catch (e: Exception) {
            LoginResult(null, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Meta Muse Code hands out a Meta account token, but inference needs the
     * Model API key minted from it. The minted key never expires, so keep the
     * account token as the "refresh" value for a future re-mint.
     */
    private fun mintMuseKey(tokens: Tokens): LoginResult {
        val payload = buildJsonObject { put("onboard", true) }
        val req = Request.Builder()
            .url("https://api.meta.ai/muse-code/key")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${tokens.access}")
            .header("Content-Type", "application/json")
            .header("x-api-version", "1.0.0")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                val obj = try { json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
                val apiKey = (obj?.get("api_key") as? JsonPrimitive)?.content
                if (!res.isSuccessful || apiKey.isNullOrBlank()) {
                    val detail = (obj?.get("detail") as? JsonPrimitive)?.content
                        ?: (obj?.get("title") as? JsonPrimitive)?.content
                    return LoginResult(null, "Muse Code key mint failed: ${detail ?: "HTTP ${res.code}"}")
                }
                if ((obj["is_subs_active"] as? JsonPrimitive)?.content == "false") {
                    return LoginResult(null, "Muse Code subscription is inactive on this account.")
                }
                LoginResult(Tokens(apiKey, tokens.access, 365L * 24 * 3600))
            }
        } catch (e: Exception) {
            LoginResult(null, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun awaitCallback(
        server: ServerSocket,
        expectedPath: String,
        manualCode: () -> String?,
    ): Callback? {
        val deadline = System.currentTimeMillis() + 300_000
        while (System.currentTimeMillis() < deadline) {
            manualCode()?.trim()?.takeIf { it.isNotEmpty() }?.let { return parseManual(it) }
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                return null
            }
            socket.use { client ->
                val input = client.getInputStream()
                val requestLine = readLine(input) ?: return@use
                val target = requestLine.split(" ").getOrNull(1) ?: return@use
                val uri = android.net.Uri.parse("http://localhost$target")
                val isCallback = uri.path == expectedPath || uri.path == "/callback" || uri.path == "/auth/callback"
                if (!isCallback) {
                    val body = "Not found".toByteArray(Charsets.UTF_8)
                    client.getOutputStream().apply {
                        write(("HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.UTF_8))
                        write(body)
                        flush()
                    }
                    return@use
                }
                val html = "<html><body style='font-family:sans-serif;padding:32px;background:#111;color:#eee'>" +
                    "<h2>9Router</h2><p>Authorization complete. You can close this tab.</p>" +
                    "<script>setTimeout(()=>window.close(),1200)</script></body></html>"
                val bytes = html.toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                client.getOutputStream().apply {
                    write(head.toByteArray(Charsets.UTF_8))
                    write(bytes)
                    flush()
                }
                return Callback(
                    code = uri.getQueryParameter("code"),
                    state = uri.getQueryParameter("state"),
                    error = uri.getQueryParameter("error"),
                )
            }
        }
        return null
    }

    private fun parseManual(raw: String): Callback {
        val value = raw.trim()
        if (value.contains("code=") || value.startsWith("http")) {
            val uri = android.net.Uri.parse(value)
            return Callback(
                code = uri.getQueryParameter("code") ?: value,
                state = uri.getQueryParameter("state"),
                error = uri.getQueryParameter("error"),
            )
        }
        return Callback(value, null, null)
    }

    private fun readLine(input: InputStream): String? {
        val buf = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8").trimEnd('\r')
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
        }
    }

    private fun randomToken(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return base64Url(buf)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
