package com.ninerouter.android.data

import kotlinx.serialization.Serializable

/** Subset of open-sse/providers/registry exported by scripts/export-android-config.mjs. */
@Serializable
data class ProviderDef(
    val id: String,
    val alias: String = id,
    val name: String = id,
    val category: String = "",
    val authType: String = "apikey",
    val authModes: List<String> = emptyList(),
    val hasOAuth: Boolean = false,
    val priority: Int = 999,
    val website: String = "",
    val deprecated: Boolean = false,
    val hidden: Boolean = false,
    val serviceKinds: List<String> = emptyList(),
    val format: String = "openai",
    val baseUrl: String = "",
    val transportHeaders: Map<String, String> = emptyMap(),
    val authHeader: String = "",
    val authScheme: String = "bearer",
    val urlSuffix: String = "",
    val tokenUrl: String = "",
    val clientId: String = "",
    val authorizeUrl: String = "",
    val deviceCodeUrl: String = "",
    val copilotTokenUrl: String = "",
    val redirectUri: String = "",
    val scope: String = "",
    val clientSecret: String = "",
    val tokenEncoding: String = "form",
    val codeChallengeMethod: String = "",
    val refreshUrl: String = "",
    val refreshEncoding: String = "",
    val refreshScope: String = "",
    val userInfoUrl: String = "",
    val agentKeyUrl: String = "",
    val deviceAuthUrl: String = "",
    val stateUrl: String = "",
    val initiateUrl: String = "",
    val pollUrlBase: String = "",
    val platform: String = "",
    val userAgent: String = "",
    val pollInterval: Int = 5,
    val callbackPath: String = "/callback",
    val fixedPort: Int = 0,
    val extraParams: Map<String, String> = emptyMap(),
    val models: List<String> = emptyList(),
    val modelNames: Map<String, String> = emptyMap(),
    val modelCapabilities: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class Connection(
    val id: String,
    val provider: String,
    val name: String = "",
    val authType: String = "apikey",
    val apiKey: String = "",
    var accessToken: String = "",
    var refreshToken: String = "",
    var expiresAt: Long = 0L,
    var projectId: String = "",
    var email: String = "",
    var accountId: String = "",
    var deviceId: String = "",
    var enabled: Boolean = true,
    var priority: Int = 0,
    var unavailableUntil: Long = 0L,
    var consecutiveUseCount: Int = 0,
    var lastUsedAt: Long = 0L,
    var lastError: String = "",
)

@Serializable
data class ApiKey(
    val id: String,
    val name: String = "",
    val key: String,
    var active: Boolean = true,
    val allowedModels: List<String> = emptyList(),
)

@Serializable
data class ProviderNode(
    val id: String,
    var prefix: String,
    var name: String = prefix,
    var type: String = "openai-compatible",
    var apiType: String = "chat",
    var baseUrl: String,
    var apiKey: String = "",
    var models: List<String> = emptyList(),
)

@Serializable
data class Combo(
    val name: String,
    val models: List<String>,
    val strategy: String = "fallback",
    val routerModel: String = "",
    val judgeModel: String = "",
)

@Serializable
data class Settings(
    val requireApiKey: Boolean = true,
    val apiKey: String = "",
    val autoStartGateway: Boolean = true,
    val rtkEnabled: Boolean = true,
    val fallbackStrategy: String = "fill-first",
    val stickyLimit: Int = 3,
    val cavemanEnabled: Boolean = false,
    val ponytailEnabled: Boolean = false,
    val customPromptEnabled: Boolean = false,
    val customPrompt: String = "",
    val cavemanLevel: String = "full",
    val ponytailLevel: String = "full",
)

@Serializable
data class UsageEntry(
    val ts: Long,
    val provider: String,
    val model: String,
    val prompt: Int = 0,
    val completion: Int = 0,
    val cached: Int = 0,
    val cost: Double = 0.0,
    val ok: Boolean = true,
    val latencyMs: Long = 0L,
    val status: Int = 200,
    val stream: Boolean = false,
    val endpoint: String = "",
    val error: String = "",
)

@Serializable
data class AppState(
    var settings: Settings = Settings(),
    val connections: MutableList<Connection> = mutableListOf(),
    val combos: MutableList<Combo> = mutableListOf(),
    val nodes: MutableList<ProviderNode> = mutableListOf(),
    val apiKeys: MutableList<ApiKey> = mutableListOf(),
    val aliases: MutableMap<String, String> = mutableMapOf(),
    val usage: MutableList<UsageEntry> = mutableListOf(),
)
