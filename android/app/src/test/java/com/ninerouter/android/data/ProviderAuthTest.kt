package com.ninerouter.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAuthTest {
    private fun def(id: String, category: String = "oauth", modes: List<String> = emptyList()) =
        ProviderDef(
            id = id,
            category = category,
            authModes = modes,
            hasOAuth = true,
            authorizeUrl = "https://example.com/authorize",
            tokenUrl = "https://example.com/token",
            clientId = "client",
        )

    @Test
    fun `browser oauth requires an authorize endpoint`() {
        assertTrue(ProviderAuth.browserOAuth(def("xai", modes = listOf("oauth", "apikey"))))
        assertFalse(ProviderAuth.browserOAuth(def("github")))
    }

    @Test
    fun `device oauth is limited to implemented providers`() {
        assertTrue(
            ProviderAuth.deviceOAuth(
                def("nous", modes = listOf("oauth", "apikey")).copy(
                    authorizeUrl = "",
                    deviceCodeUrl = "https://example.com/device",
                )
            )
        )
        assertTrue(
            ProviderAuth.deviceOAuth(
                def("minimax-code", modes = listOf("oauth")).copy(
                    authorizeUrl = "",
                    deviceCodeUrl = "https://example.com/device",
                )
            )
        )
        assertFalse(
            ProviderAuth.deviceOAuth(
                def("gitlab").copy(authorizeUrl = "", deviceCodeUrl = "https://example.com/device")
            )
        )
    }

    @Test
    fun `api key provider with unused oauth flag is not a custom oauth provider`() {
        val gemini = ProviderDef(
            id = "gemini",
            category = "freeTier",
            authModes = listOf("apikey"),
            hasOAuth = true,
        )
        assertFalse(ProviderAuth.customOAuth(gemini))
        assertTrue(ProviderAuth.apiKey(gemini))
    }

    @Test
    fun `explicit none or cookie modes do not expose an api key flow`() {
        val keyless = ProviderDef(id = "opencode", category = "free", authModes = listOf("none"))
        val cookieOnly = ProviderDef(id = "chatgpt-web", category = "webCookie", authModes = listOf("cookie"))

        assertFalse(ProviderAuth.apiKey(keyless))
        assertFalse(ProviderAuth.apiKey(cookieOnly))
    }

    @Test
    fun `oauth only provider rejects api keys`() {
        assertFalse(ProviderAuth.apiKey(def("claude")))
        assertTrue(ProviderAuth.customOAuth(def("claude").copy(authorizeUrl = "", tokenUrl = "")))
    }
}
