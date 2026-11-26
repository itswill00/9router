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

    data class Tokens(
        val access: String,
        val refresh: String,
        val expiresIn: Long,
        val projectId: String = "",
        val email: String = "",
        val accountId: String = "",
        val idToken: String = "",
        val deviceId: String = "",
    )

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
            val redirectUri = def.redirectUri.ifBlank { "http://localhost:$port$path" }
            val verifier = randomToken(64)
            val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val state = UUID.randomUUID().toString()
            val url = buildAuthorizeUrl(def, redirectUri, challenge, state)
            onStatus("Waiting for browser authorization...")
            openBrowser(url)

            val callback = awaitCallback(server, path, state, manualCode)
                ?: return@withContext LoginResult(null, "Authorization timed out. Try again.")
            if (callback.error != null) {
                return@withContext LoginResult(null, "Provider returned: ${callback.error}")
            }
            if (callback.code.isNullOrBlank()) {
                return@withContext LoginResult(null, "No authorization code was returned.")
            }
            // Never accept a callback without the state we generated: a local
            // app could otherwise inject its own authorization code.
            if (callback.state.isNullOrBlank() || callback.state != state) {
                return@withContext LoginResult(null, "OAuth state mismatch. Please retry.")
            }
            val result = exchange(def, callback.code, callback.state, verifier, redirectUri)
            result
        } catch (e: Exception) {
            LoginResult(null, e.message ?: e.javaClass.simpleName)
        } finally {
            try { server?.close() } catch (_: Exception) {}
        }
    }

    private fun usesPkce(def: ProviderDef): Boolean =
        def.codeChallengeMethod.isNotBlank() || def.id.startsWith("minimax")

    /** Extra request headers some device flows require (mirrors the CLI clients). */
    private fun deviceHeaders(def: ProviderDef, deviceId: String): Map<String, String> = when (def.id) {
        "kimi" -> mapOf(
            "X-Msh-Platform" to "9router",
            "X-Msh-Version" to com.ninerouter.android.AppInfo.VERSION_NAME,
            "X-Msh-Device-Name" to (android.os.Build.MODEL ?: "android"),
            "X-Msh-Device-Model" to "Android ${android.os.Build.VERSION.RELEASE}",
            "X-Msh-Device-Id" to deviceId,
        )
        "grok-cli" -> mapOf(
            "User-Agent" to "grok-pager/1.0.44 grok-shell/1.0.44 (linux; x86_64)",
        )
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
        if (def.id == "grok-cli") builder.add("referrer", def.extraParams["referrer"] ?: "grok-build")
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
        val rawExpires = s("expires_in").toLongOrNull() ?: s("expired_in").toLongOrNull() ?: 600L
        val expires = if (rawExpires > 1_000_000_000_000L) {
            ((rawExpires - System.currentTimeMillis()) / 1000).coerceAtLeast(60)
        } else {
            rawExpires
        }
        var interval = s("interval").toLongOrNull() ?: 5L
        if (pollByUser && interval >= 60) interval = (interval / 1000).coerceAtLeast(2)
        val verification = s("verification_uri_complete")
            .ifBlank { s("verification_uri") }
            .ifBlank { s("verification_url") }
            .ifBlank { def.deviceAuthUrl }
        val userCode = s("user_code")
        val verificationWithCode = if (
            (def.id == "kimi" || def.id.startsWith("minimax")) &&
            userCode.isNotBlank() &&
            verification.isNotBlank() &&
            !verification.contains("user_code=") &&
            !verification.contains("code=")
        ) {
            verification + (if (verification.contains("?")) "&" else "?") + "user_code=" +
                android.net.Uri.encode(userCode)
        } else {
            verification
        }
        return DeviceChallenge(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = verificationWithCode,
            interval = interval,
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
        )
        if (usesPkce(def)) {
            params["code_challenge"] = challenge
            params["code_challenge_method"] = "S256"
        }
        params["state"] = state
        if (def.id == "claude") params["code"] = "true"
        if (def.id == "xai") params["nonce"] = randomToken(16)
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
                if (usesPkce(def)) put("code_verifier", verifier)
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
                .apply { if (usesPkce(def)) add("code_verifier", verifier) }
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
                val obj = parseTokenBody(raw)
                    ?: return LoginResult(null, "Invalid token response (${res.code}).")
                val error = (obj["error"] as? JsonPrimitive)?.content
                    ?: (obj["status"] as? JsonPrimitive)?.content?.takeIf {
                        it == "pending" || it == "slow_down" || it == "denied" || it == "expired"
                    }
                val success = (obj["success"] as? JsonPrimitive)?.content
                if (!res.isSuccessful || error != null || success == "false") {
                    val desc = (obj["error_description"] as? JsonPrimitive)?.content
                        ?: (obj["message"] as? JsonPrimitive)?.content
                        ?: (obj["msg"] as? JsonPrimitive)?.content
                        ?: raw.take(180)
                    return LoginResult(null, desc.ifBlank { "Token exchange failed (${res.code})." })
                }
                val access = (obj["access_token"] as? JsonPrimitive)?.content
                    ?: return LoginResult(null, "Token response had no access_token.")
                val refresh = (obj["refresh_token"] as? JsonPrimitive)?.content ?: ""
                val expires = (obj["expires_in"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 3600L
                val idToken = (obj["id_token"] as? JsonPrimitive)?.content ?: ""
                LoginResult(Tokens(access, refresh, expires, idToken = idToken))
            }
        } catch (e: Exception) {
            LoginResult(null, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Some providers return application/x-www-form-urlencoded even with Accept: json. */
    private fun parseTokenBody(raw: String): JsonObject? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            return runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
        }
        if (!trimmed.contains("=")) return null
        val obj = buildJsonObject {
            android.net.Uri.parse("http://localhost/?$trimmed").let { uri ->
                uri.queryParameterNames.forEach { key ->
                    uri.getQueryParameter(key)?.let { put(key, it) }
                }
            }
        }
        return obj
    }

    /** GitHub Copilot needs a short-lived Copilot token derived from the GitHub OAuth token. */
    fun postProcess(def: ProviderDef, tokens: Tokens): LoginResult {
        if (def.id == "muse") return mintMuseKey(tokens)
        if (def.id == "nous" && def.agentKeyUrl.isNotBlank()) return mintNousKey(def, tokens)
        if (def.format == "gemini-cli" || def.format == "antigravity") {
            val (project, email) = googleProject(def, tokens.access)
            return LoginResult(tokens.copy(projectId = project, email = email))
        }
        if (def.id == "codex") {
            val claims = decodeJwt(tokens.idToken.ifBlank { tokens.access }).orEmpty()
            val auth = claims["https://api.openai.com/auth"] as? JsonObject
            val accountId = (auth?.get("chatgpt_account_id") as? JsonPrimitive)?.content
                ?: (claims["chatgpt_account_id"] as? JsonPrimitive)?.content.orEmpty()
            val email = (claims["email"] as? JsonPrimitive)?.content.orEmpty()
            return LoginResult(tokens.copy(accountId = accountId, email = email))
        }
        if (def.id == "xai" || def.id == "grok-cli") {
            val email = (decodeJwt(tokens.idToken)?.get("email") as? JsonPrimitive)?.content.orEmpty()
            return LoginResult(tokens.copy(email = email))
        }
        if (def.id != "github" || def.copilotTokenUrl.isBlank()) return LoginResult(tokens)
        val req = Request.Builder()
            .url(def.copilotTokenUrl)
            .header("Authorization", "Bearer ${tokens.access}")
            .header("Accept", "application/json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "GitHubCopilotChat/0.26.7")
            .header("Editor-Version", "vscode/1.85.0")
            .header("Editor-Plugin-Version", "copilot-chat/0.26.7")
            .build()
        return try {
            client.newCall(req).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                val obj = parseTokenBody(raw)
                val token = (obj?.get("token") as? JsonPrimitive)?.content
                if (!res.isSuccessful || token.isNullOrBlank()) {
                    return LoginResult(null, "GitHub Copilot token exchange failed (${res.code}).")
                }
                val expiresAt = (obj["expires_at"] as? JsonPrimitive)?.content?.toLongOrNull()
                val expiresIn = expiresAt?.let { (it - System.currentTimeMillis() / 1000).coerceAtLeast(60) } ?: 1800L
                val email = githubEmail(def, tokens.access)
                LoginResult(Tokens(token, tokens.access, expiresIn, email = email))
            }
        } catch (e: Exception) {
            LoginResult(null, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun githubEmail(def: ProviderDef, githubToken: String): String {
        val url = def.userInfoUrl.ifBlank { "https://api.github.com/user" }
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $githubToken")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "GitHubCopilotChat/0.26.7")
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use ""
                val obj = json.parseToJsonElement(res.body?.string().orEmpty()) as? JsonObject ?: return@use ""
                (obj["email"] as? JsonPrimitive)?.content.orEmpty()
            }
        }.getOrDefault("")
    }

    /** Decode a JWT payload without verifying the signature (identity claims only). */
    private fun decodeJwt(token: String): JsonObject? {
        val parts = token.split(".")
        if (parts.size < 2) return null
        return runCatching {
            val payload = Base64.getUrlDecoder().decode(parts[1].padEnd((parts[1].length + 3) / 4 * 4, '='))
            json.parseToJsonElement(String(payload, Charsets.UTF_8)) as? JsonObject
        }.getOrNull()
    }

    /** Cloud Code Assist project id (loadCodeAssist), plus the account email. */
    private fun googleProject(def: ProviderDef, accessToken: String): Pair<String, String> {
        var project = ""
        var email = ""
        val antigravity = def.format == "antigravity"
        val userAgent = if (antigravity) "antigravity/hub/2.33.0 darwin/arm64" else ""
        val metadata = buildJsonObject {
            put("ideType", 9)
            // 3 = Linux x86_64 in the Antigravity ClientMetadata enum.
            put("platform", 3)
            put("pluginType", 2)
        }
        fun postJson(url: String, payload: JsonObject): JsonObject? = runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .apply {
                    if (userAgent.isNotBlank()) header("User-Agent", userAgent)
                    if (antigravity) header("x-request-source", "local")
                }
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use null
                json.parseToJsonElement(res.body?.string().orEmpty()) as? JsonObject
            }
        }.getOrNull()

        val load = buildJsonObject {
            put("metadata", metadata)
            if (!antigravity) put("mode", 1)
        }
        var tierId = "legacy-tier"
        postJson("https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist", load)?.let { obj ->
            val proj = obj["cloudaicompanionProject"]
            project = when (proj) {
                is JsonPrimitive -> proj.content
                is JsonObject -> (proj["id"] as? JsonPrimitive)?.content.orEmpty()
                else -> ""
            }
            val tiers = obj["allowedTiers"] as? kotlinx.serialization.json.JsonArray
            tiers?.forEach { element ->
                val tier = element as? JsonObject ?: return@forEach
                val isDefault = (tier["isDefault"] as? JsonPrimitive)?.content == "true"
                val id = (tier["id"] as? JsonPrimitive)?.content ?: ""
                if (isDefault && id.isNotBlank()) tierId = id
            }
        }

        // Antigravity needs the onboarding call before the project is usable.
        if (antigravity && project.isBlank()) {
            for (attempt in 0 until 3) {
                val onboard = buildJsonObject {
                    put("tierId", tierId)
                    put("metadata", metadata)
                }
                val obj = postJson("https://cloudcode-pa.googleapis.com/v1internal:onboardUser", onboard)
                val id = when (val proj = obj?.get("cloudaicompanionProject")) {
                    is JsonPrimitive -> proj.content
                    is JsonObject -> (proj["id"] as? JsonPrimitive)?.content.orEmpty()
                    else -> ""
                }
                if (id.isNotBlank()) {
                    project = id
                    break
                }
                if (obj?.get("done")?.toString() == "true") break
                Thread.sleep(1000L * (attempt + 1))
            }
        }

        runCatching {
            val url = def.userInfoUrl.ifBlank { "https://www.googleapis.com/oauth2/v1/userinfo" }
            val req = Request.Builder()
                .url("$url?alt=json")
                .header("Authorization", "Bearer $accessToken")
                .apply { if (antigravity) header("x-request-source", "local") }
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use
                val obj = json.parseToJsonElement(res.body?.string().orEmpty()) as? JsonObject ?: return@use
                email = (obj["email"] as? JsonPrimitive)?.content.orEmpty()
            }
        }
        return project to email
    }

    /** Nous returns an OAuth token, but inference wants a short-lived agent key. */
    private fun mintNousKey(def: ProviderDef, tokens: Tokens): LoginResult {
        val payload = buildJsonObject { put("min_ttl_seconds", 7200) }
        return try {
            val req = Request.Builder()
                .url(def.agentKeyUrl)
                .header("Authorization", "Bearer ${tokens.access}")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                val apiKey = (obj?.get("api_key") as? JsonPrimitive)?.content
                if (!res.isSuccessful || apiKey.isNullOrBlank()) {
                    val detail = (obj?.get("detail") as? JsonPrimitive)?.content
                        ?: (obj?.get("title") as? JsonPrimitive)?.content
                    return LoginResult(null, "Nous agent key mint failed: ${detail ?: "HTTP ${res.code}"}")
                }
                val expires = (obj["expires_in"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 7200L
                LoginResult(Tokens(apiKey, tokens.refresh, expires, email = tokens.email))
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
        return try {
            var response: okhttp3.Response? = null
            for (attempt in 0 until 3) {
                val req = Request.Builder()
                    .url("https://api.meta.ai/muse-code/key")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer ${tokens.access}")
                    .header("Content-Type", "application/json")
                    .header("x-api-version", "1.0.0")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val res = client.newCall(req).execute()
                response = res
                if (res.code != 429 && res.code < 500) break
                if (attempt == 2) break
                res.close()
                Thread.sleep(2000L * (attempt + 1))
            }
            response!!.use { res ->
                val raw = res.body?.string().orEmpty()
                val obj = try { json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
                val apiKey = (obj?.get("api_key") as? JsonPrimitive)?.content
                if (!res.isSuccessful || apiKey.isNullOrBlank()) {
                    val detail = (obj?.get("detail") as? JsonPrimitive)?.content
                        ?: (obj?.get("title") as? JsonPrimitive)?.content
                        ?: raw.take(180)
                    return LoginResult(null, "Muse Code key mint failed: ${detail.ifBlank { "HTTP ${res.code}" }}")
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
        expectedState: String,
        manualCode: () -> String?,
    ): Callback? {
        val deadline = System.currentTimeMillis() + 300_000
        while (System.currentTimeMillis() < deadline) {
            manualCode()?.trim()?.takeIf { it.isNotEmpty() }?.let { return parseManual(it, expectedState) }
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
                val code = uri.getQueryParameter("code")
                val state = uri.getQueryParameter("state")
                val error = uri.getQueryParameter("error")
                val detail = uri.getQueryParameter("error_description").orEmpty()
                val html = callbackPage(ok = error == null && !code.isNullOrBlank(), message = detail)
                val bytes = html.toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                client.getOutputStream().apply {
                    write(head.toByteArray(Charsets.UTF_8))
                    write(bytes)
                    flush()
                }
                return Callback(
                    code = code,
                    state = state,
                    error = error,
                )
            }
        }
        return null
    }

    private fun parseManual(raw: String, expectedState: String): Callback {
        val value = raw.trim()
        if (value.contains("code=") || value.startsWith("http")) {
            val uri = android.net.Uri.parse(value)
            return Callback(
                code = uri.getQueryParameter("code") ?: value,
                state = uri.getQueryParameter("state"),
                error = uri.getQueryParameter("error"),
            )
        }
        // A bare code is typed by the user, not injected by a local socket, so
        // it inherits the state this flow generated.
        return Callback(value, expectedState, null)
    }

    /**
     * Loopback landing page. Browsers cannot be closed by an app, so the page
     * offers an explicit deep link back into 9Router (and tries to follow it
     * automatically). The manifest accepts ninerouter://oauth/callback.
     */
    private fun callbackPage(ok: Boolean, message: String): String {
        val title = if (ok) "Authorization complete" else "Authorization failed"
        val subtitle = if (ok) {
            "9Router received the login. Tap below to go back to the app."
        } else {
            message.ifBlank { "The provider rejected the login. Return to 9Router and try again." }
        }
        val accent = if (ok) "#22c55e" else "#ef4444"
        val icon = if (ok) "\u2713" else "!"
        val safeSubtitle = subtitle
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>9Router OAuth</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center;
         background:#0b0b0b; color:#f5f5f5;
         font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif; padding:24px; }
  .card { width:100%; max-width:420px; background:#161616; border:1px solid #2a2a2a;
          border-radius:20px; padding:28px; text-align:center; }
  .mark { width:56px; height:56px; border-radius:16px; margin:0 auto 16px; display:flex;
          align-items:center; justify-content:center; background:rgba(255,122,26,.14);
          color:#ff7a1a; font-weight:800; font-size:20px; border:1px solid rgba(255,122,26,.28); }
  .badge { width:44px; height:44px; border-radius:50%; margin:0 auto 14px; display:flex;
           align-items:center; justify-content:center; background:${accent}22; color:$accent;
           font-size:22px; font-weight:700; border:1px solid ${accent}55; }
  h1 { font-size:20px; margin:0 0 8px; }
  p { font-size:14px; line-height:1.5; color:#a3a3a3; margin:0 0 20px; }
  a.button { display:block; text-decoration:none; background:#ff7a1a; color:#0b0b0b;
             font-weight:700; padding:14px 18px; border-radius:12px; font-size:15px; }
  a.button:active { opacity:.85; }
  .hint { font-size:12px; color:#6b6b6b; margin:14px 0 0; }
</style>
</head>
<body>
<div class="card">
  <div class="mark">9R</div>
  <div class="badge">$icon</div>
  <h1>$title</h1>
  <p>$safeSubtitle</p>
  <a class="button" href="ninerouter://oauth/callback?status=done">Return to 9Router</a>
  <p class="hint">If the button does nothing, switch back to the 9Router app manually.</p>
</div>
<script>
  setTimeout(function () { window.location.href = "ninerouter://oauth/callback?status=done"; }, 650);
</script>
</body>
</html>
        """.trimIndent()
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
