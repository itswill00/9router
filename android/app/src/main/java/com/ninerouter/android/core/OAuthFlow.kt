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
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Provider-agnostic OAuth for Android.
 *
 * The desktop dashboard completes PKCE flows with a loopback callback server
 * (`http://localhost:<port>/callback`). Android does the same: a short-lived
 * ServerSocket is opened on loopback, the browser is sent there, and the
 * redirect is captured in-process. This is the only redirect shape most
 * provider client IDs actually accept — a custom scheme is not registered.
 */
object OAuthFlow {
    const val REDIRECT_URI = "ninerouter://oauth/callback"

    data class Tokens(val access: String, val refresh: String, val expiresIn: Long)
    data class DeviceChallenge(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val interval: Long,
        val expiresAt: Long,
    )

    private val client = OkHttpClient.Builder().build()
    private val json = Json { ignoreUnknownKeys = true }
    private val pending = HashMap<String, PendingPkce>()

    private data class PendingPkce(
        val verifier: String,
        val state: String,
        val redirectUri: String,
        val provider: String,
    )

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
        return exchange(def, code, state, p.verifier, p.redirectUri)
    }

    /**
     * Full browser PKCE flow. Suspends on an IO thread while the loopback
     * callback server waits, then exchanges the code with the provider's
     * declared encoding (form or JSON).
     */
    suspend fun loginWithBrowser(
        def: ProviderDef,
        openBrowser: (String) -> Unit,
        onStatus: (String) -> Unit = {},
    ): Tokens? = withContext(Dispatchers.IO) {
        if (def.authorizeUrl.isBlank() || def.clientId.isBlank()) return@withContext null
        val path = def.callbackPath.ifBlank { "/callback" }
        var server: ServerSocket? = null
        try {
            server = ServerSocket(def.fixedPort.takeIf { it > 0 } ?: 0, 1, InetAddress.getByName("127.0.0.1"))
            server.soTimeout = 300_000
            val port = server.localPort
            val redirectUri = "http://localhost:$port$path"
            val verifier = randomToken(64)
            val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val state = UUID.randomUUID().toString()
            val url = buildAuthorizeUrl(def, redirectUri, challenge, state)
            onStatus("Waiting for browser authorization...")
            openBrowser(url)

            val callback = withContext(Dispatchers.IO) { awaitCallback(server, path) }
                ?: return@withContext null
            if (callback.error != null || callback.code.isNullOrBlank()) return@withContext null
            exchange(def, callback.code, callback.state ?: state, verifier, redirectUri)
        } catch (_: Exception) {
            null
        } finally {
            try { server?.close() } catch (_: Exception) {}
        }
    }

    fun startDevice(def: ProviderDef): DeviceChallenge? {
        if (def.deviceCodeUrl.isBlank()) return null
        val form = FormBody.Builder()
            .add("client_id", def.clientId)
            .add("scope", def.scope.ifBlank { "openid profile offline_access" })
            .build()
        val req = Request.Builder()
            .url(def.deviceCodeUrl)
            .header("Accept", "application/json")
            .post(form)
            .build()
        val obj = try {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                json.parseToJsonElement(res.body!!.string()) as? JsonObject ?: return null
            }
        } catch (_: Exception) { return null }
        fun s(key: String) = (obj[key] as? JsonPrimitive)?.content ?: ""
        return DeviceChallenge(
            deviceCode = s("device_code"),
            userCode = s("user_code"),
            verificationUri = s("verification_uri").ifBlank { s("verification_url") },
            interval = (obj["interval"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 5L,
            expiresAt = System.currentTimeMillis() +
                ((obj["expires_in"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 600L) * 1000L,
        ).takeIf { it.deviceCode.isNotBlank() }
    }

    fun pollDevice(def: ProviderDef, challenge: DeviceChallenge): Tokens? {
        if (System.currentTimeMillis() >= challenge.expiresAt) return null
        val form = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("device_code", challenge.deviceCode)
            .add("client_id", def.clientId)
            .build()
        return postTokens(def, form, null)
    }

    private fun buildAuthorizeUrl(def: ProviderDef, redirectUri: String, challenge: String, state: String): String {
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to def.clientId,
            "redirect_uri" to redirectUri,
            "scope" to def.scope.ifBlank { "openid profile offline_access" },
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

    private fun exchange(def: ProviderDef, code: String, state: String, verifier: String, redirectUri: String): Tokens? {
        // Claude returns `code#state` from its consent screen.
        val authCode = if (code.contains("#")) code.substringBefore("#") else code
        val authState = if (code.contains("#")) code.substringAfter("#") else state
        return if (def.tokenEncoding.equals("json", true)) {
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
            postTokens(def, form, null)
        }
    }

    private fun postTokens(def: ProviderDef, form: FormBody, jsonPayload: JsonObject?): Tokens? {
        val body = jsonPayload?.toString()?.toRequestBody("application/json".toMediaType())
            ?: form
        val req = Request.Builder()
            .url(def.tokenUrl)
            .header("Accept", "application/json")
            .post(body)
            .build()
        return executeToken(req)
    }

    private fun executeToken(req: Request): Tokens? {
        if (req.url.toString().isBlank()) return null
        return try {
            client.newCall(req).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                val obj = try { json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
                    ?: return null
                val error = (obj["error"] as? JsonPrimitive)?.content
                if (!res.isSuccessful || error != null) return null
                val access = (obj["access_token"] as? JsonPrimitive)?.content ?: return null
                Tokens(
                    access = access,
                    refresh = (obj["refresh_token"] as? JsonPrimitive)?.content ?: "",
                    expiresIn = (obj["expires_in"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 3600L,
                )
            }
        } catch (_: Exception) { null }
    }

    private data class Callback(val code: String?, val state: String?, val error: String?)

    private fun awaitCallback(server: ServerSocket, expectedPath: String): Callback? {
        return try {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val requestLine = readLine(input) ?: return null
                val target = requestLine.split(" ").getOrNull(1) ?: return null
                val uri = android.net.Uri.parse("http://localhost$target")
                val html = "<html><body style='font-family:sans-serif;padding:32px'>" +
                    "<h2>9Router</h2><p>Authorization complete. You can close this tab.</p>" +
                    "</body></html>"
                val bytes = html.toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(head.toByteArray(Charsets.UTF_8))
                    write(bytes)
                    flush()
                }
                Callback(
                    code = uri.getQueryParameter("code"),
                    state = uri.getQueryParameter("state"),
                    error = uri.getQueryParameter("error"),
                )
            }
        } catch (_: Exception) {
            null
        }
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
