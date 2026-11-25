package com.ninerouter.android.core

import com.ninerouter.android.data.ProviderDef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Generic PKCE + device-code flows. No browser app or shell is required to host the callback. */
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
    private val pending = HashMap<String, String>()

    fun authorizeUrl(def: ProviderDef): String {
        val verifier = randomToken(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = UUID.randomUUID().toString()
        pending[state] = verifier
        pending["provider"] = def.id
        val query = listOf(
            "response_type" to "code",
            "client_id" to def.clientId,
            "redirect_uri" to REDIRECT_URI,
            "scope" to def.scope.ifBlank { "openid profile offline_access" },
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
        ).joinToString("&") { (k, v) -> "$k=${android.net.Uri.encode(v)}" }
        return "${def.authorizeUrl.trimEnd('?', '&')}?$query"
    }

    fun providerFor(state: String): String? =
        if (pending.containsKey(state)) pending["provider"] else null

    fun exchangeCode(def: ProviderDef, code: String, state: String): Tokens? {
        val verifier = pending[state] ?: return null
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", def.clientId)
            .add("code_verifier", verifier)
            .build()
        return postTokens(def.tokenUrl, form)
    }

    fun startDevice(def: ProviderDef): DeviceChallenge? {
        if (def.deviceCodeUrl.isBlank()) return null
        val form = FormBody.Builder()
            .add("client_id", def.clientId)
            .add("scope", def.scope.ifBlank { "openid profile offline_access" })
            .build()
        val req = Request.Builder().url(def.deviceCodeUrl).post(form).build()
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
        return postTokens(def.tokenUrl, form)
    }

    private fun postTokens(url: String, form: FormBody): Tokens? {
        if (url.isBlank()) return null
        val req = Request.Builder().url(url).post(form).build()
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

    private fun randomToken(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return base64Url(buf)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
