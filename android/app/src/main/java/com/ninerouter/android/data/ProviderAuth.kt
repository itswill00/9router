package com.ninerouter.android.data

/**
 * Capability rules mirrored from the desktop dashboard:
 * - OAuth providers are OAuth-only unless the registry lists an `apikey` mode.
 * - Free / free-tier providers accept API keys unless the registry says
 *   otherwise (upstream `dualAuthTypes` does the same fallback).
 * - A provider can be logged in through the generic loopback flow only when the
 *   registry exposes an authorize URL, token URL and client id.
 */
object ProviderAuth {
    fun browserOAuth(def: ProviderDef): Boolean =
        def.id != "github" &&
            def.authorizeUrl.isNotBlank() &&
            def.tokenUrl.isNotBlank() &&
            def.clientId.isNotBlank()

    /**
     * Only flows we actually implement are advertised. GitHub/Grok/Kimi/MiniMax
     * are RFC 8628 device flows; Muse mints its Model API key after the device
     * grant. Everything else stays on the manual-token path.
     */
    private val DEVICE_PROVIDERS = setOf(
        "github", "grok-cli", "kimi", "muse", "minimax-code", "minimax-code-global",
    )

    fun deviceOAuth(def: ProviderDef): Boolean =
        def.id in DEVICE_PROVIDERS &&
            def.deviceCodeUrl.isNotBlank() &&
            def.tokenUrl.isNotBlank() &&
            def.clientId.isNotBlank()

    fun apiKey(def: ProviderDef): Boolean {
        val modes = def.authModes.map { it.lowercase() }
        if (modes.any { it == "apikey" || it == "api_key" }) return true
        if (modes.isNotEmpty() && !modes.any { it == "apikey" || it == "api_key" }) {
            // Explicit modes win: OAuth-only entries stay OAuth-only.
            if (modes.all { it == "oauth" }) return false
        }
        return when (def.category.lowercase()) {
            "oauth" -> false
            "free", "freetier", "apikey" -> true
            else -> def.authType.lowercase() != "oauth"
        }
    }

    fun cookie(def: ProviderDef): Boolean =
        def.category.equals("webCookie", true) ||
            def.authModes.any { it.equals("cookie", true) } ||
            def.authType.equals("cookie", true)

    /** OAuth exists upstream but needs a provider-specific flow we cannot run. */
    fun customOAuth(def: ProviderDef): Boolean =
        def.hasOAuth && !browserOAuth(def) && !deviceOAuth(def) &&
            !def.category.equals("webCookie", true)
}
