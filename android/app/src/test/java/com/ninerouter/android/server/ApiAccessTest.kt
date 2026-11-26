package com.ninerouter.android.server

import com.ninerouter.android.data.ApiKey
import com.ninerouter.android.data.AppState
import com.ninerouter.android.data.Combo
import com.ninerouter.android.data.Exposure
import com.ninerouter.android.data.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiAccessTest {
    @Test
    fun rejectsMissingGatewayKeyWhenRequired() {
        val state = state()

        assertFalse(ApiAccess.isAuthorized(emptyMap(), state))
    }

    @Test
    fun allowsPrimaryAndActiveKeysButRejectsInactiveKey() {
        val state = state().apply {
            apiKeys += ApiKey("active", name = "active", key = "sk-active")
            apiKeys += ApiKey("inactive", name = "inactive", key = "sk-inactive", active = false)
        }

        assertTrue(ApiAccess.isAuthorized(mapOf("authorization" to "Bearer sk-primary"), state))
        assertTrue(ApiAccess.isAuthorized(mapOf("x-api-key" to "sk-active"), state))
        assertFalse(ApiAccess.isAuthorized(mapOf("x-api-key" to "sk-inactive"), state))
        assertFalse(ApiAccess.isAuthorized(mapOf("authorization" to "Bearer invalid"), state))
    }

    @Test
    fun allowsExactRestrictedModelAndDeniesOtherModel() {
        val state = state().apply { apiKeys += ApiKey("restricted", key = "sk-limited", allowedModels = listOf("openai/gpt-4o-mini")) }
        val headers = mapOf("x-api-key" to "sk-limited")

        assertNull(ApiAccess.deniedModel(headers, body("openai/gpt-4o-mini"), state))
        assertEquals("Model not allowed", ApiAccess.deniedModel(headers, body("openai/gpt-4.1"), state))
    }

    @Test
    fun comboGrantAllowsComboRequestButNotDirectMembers() {
        val state = state().apply {
            combos += Combo("fast", listOf("openai/gpt-4o-mini", "anthropic/claude-sonnet"))
            apiKeys += ApiKey("restricted", key = "sk-limited", allowedModels = listOf("fast"))
        }
        val headers = mapOf("Authorization" to "Bearer sk-limited")

        assertNull(ApiAccess.deniedModel(headers, body("fast"), state))
        assertEquals("Model not allowed", ApiAccess.deniedModel(headers, body("openai/gpt-4o-mini"), state))
        assertEquals("Model not allowed", ApiAccess.deniedModel(headers, body("anthropic/claude-sonnet"), state))
        assertEquals("Model not allowed", ApiAccess.deniedModel(headers, body("openai/gpt-4.1"), state))
    }

    @Test
    fun allowsCallingComboItselfWhenComboIsGranted() {
        val state = state().apply {
            combos += Combo("fast", listOf("openai/gpt-4o-mini"))
            apiKeys += ApiKey("restricted", key = "sk-limited", allowedModels = listOf("fast"))
        }

        assertNull(ApiAccess.deniedModel(mapOf("x-api-key" to "sk-limited"), body("fast"), state))
    }

    @Test
    fun aliasesResolveForRestrictedKey() {
        val state = state().apply {
            aliases["cheap"] = "openai/gpt-4o-mini"
            apiKeys += ApiKey("restricted", key = "sk-limited", allowedModels = listOf("cheap"))
        }

        assertNull(ApiAccess.deniedModel(mapOf("api-key" to "sk-limited"), body("openai/gpt-4o-mini"), state))
        assertNull(ApiAccess.deniedModel(mapOf("api-key" to "sk-limited"), body("cheap"), state))
    }

    @Test
    fun restrictedModelListingDoesNotExposeComboMembersGrantedByCombo() {
        val state = state().apply {
            combos += Combo("fast", listOf("openai/gpt-4o-mini"))
            apiKeys += ApiKey("restricted", key = "sk-limited", allowedModels = listOf("fast"))
        }
        val models = listOf(model("fast"), model("openai/gpt-4o-mini"), model("openai/gpt-4.1"))

        assertEquals(
            listOf("fast"),
            ApiAccess.filterModels(mapOf("x-api-key" to "sk-limited"), models, state)
                .map { it["id"].toString().trim('"') },
        )
    }

    @Test
    fun directModelGrantWinsEvenWhenItIsAlsoAComboMember() {
        val state = state().apply {
            combos += Combo("fast", listOf("openai/gpt-4o-mini", "anthropic/claude-sonnet"))
            apiKeys += ApiKey("restricted", key = "sk-limited", allowedModels = listOf("openai/gpt-4o-mini"))
        }
        val headers = mapOf("x-api-key" to "sk-limited")

        assertNull(ApiAccess.deniedModel(headers, body("openai/gpt-4o-mini"), state))
        assertEquals("Model not allowed", ApiAccess.deniedModel(headers, body("anthropic/claude-sonnet"), state))
    }

    @Test
    fun modelListedAsComboMemberIsNotGrantedAsDirectModel() {
        val state = state().apply {
            combos += Combo("fast", listOf("openai/gpt-4o-mini"))
            apiKeys += ApiKey("restricted", key = "sk-limited", allowedModels = listOf("fast"))
        }

        assertEquals(
            "Model not allowed",
            ApiAccess.deniedModel(mapOf("x-api-key" to "sk-limited"), body("openai/gpt-4o-mini"), state),
        )
    }

    @Test
    fun restrictedModelListingShowsOnlyExactAllowedModel() {
        val state = state().apply {
            apiKeys += ApiKey("restricted", key = "sk-limited", allowedModels = listOf("openai/gpt-4o-mini"))
        }
        val models = listOf(model("openai/gpt-4o-mini"), model("openai/gpt-4.1"))

        assertEquals(
            listOf("openai/gpt-4o-mini"),
            ApiAccess.filterModels(mapOf("x-api-key" to "sk-limited"), models, state)
                .map { it["id"].toString().trim('"') },
        )
    }

    @Test
    fun emptyAllowListAndPrimaryKeyAreUnrestricted() {
        val state = state().apply { apiKeys += ApiKey("unrestricted", key = "sk-any") }

        assertNull(ApiAccess.deniedModel(mapOf("x-api-key" to "sk-any"), body("anything"), state))
        assertNull(ApiAccess.deniedModel(mapOf("authorization" to "Bearer sk-primary"), body("anything"), state))
    }

    @Test
    fun networkModeRequiresKeyEvenWhenToggleIsOff() {
        val state = AppState(
            settings = Settings(requireApiKey = false, apiKey = "sk-primary", exposure = Exposure.NETWORK),
        )

        assertFalse(ApiAccess.isAuthorized(emptyMap(), state))
        assertTrue(ApiAccess.isAuthorized(mapOf("authorization" to "Bearer sk-primary"), state))
    }

    @Test
    fun networkModeWithBlankKeyDeniesEveryRequest() {
        val state = AppState(
            settings = Settings(requireApiKey = false, apiKey = "", exposure = Exposure.NETWORK),
        )

        assertFalse(ApiAccess.isAuthorized(emptyMap(), state))
        assertFalse(ApiAccess.isAuthorized(mapOf("x-api-key" to ""), state))
    }

    @Test
    fun blankPrimaryKeyFailsClosedWhenAuthIsRequired() {
        val state = AppState(settings = Settings(requireApiKey = true, apiKey = ""))

        assertFalse(ApiAccess.isAuthorized(emptyMap(), state))
        assertFalse(ApiAccess.isPrimaryKey(emptyMap(), state))
    }

    @Test
    fun onlyThePrimaryKeyCanAdminister() {
        val state = state().apply {
            apiKeys += ApiKey("scoped", key = "sk-scoped", allowedModels = listOf("openai/gpt-4o-mini"))
        }

        assertTrue(ApiAccess.isPrimaryKey(mapOf("authorization" to "Bearer sk-primary"), state))
        assertFalse(ApiAccess.isPrimaryKey(mapOf("x-api-key" to "sk-scoped"), state))
        assertTrue(ApiAccess.isAuthorized(mapOf("x-api-key" to "sk-scoped"), state))
    }

    private fun state() = AppState(settings = Settings(requireApiKey = true, apiKey = "sk-primary"))
    private fun body(model: String) = buildJsonObject { put("model", model) }
    private fun model(id: String): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "model")
    }
}
