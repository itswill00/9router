package com.ninerouter.android.server

import com.ninerouter.android.data.AppState
import com.ninerouter.android.data.Combo
import com.ninerouter.android.data.Connection
import com.ninerouter.android.data.ProviderDef
import com.ninerouter.android.data.ProviderNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsApiTest {
    private val openAi = ProviderDef(
        id = "openai",
        alias = "oa",
        name = "OpenAI",
        serviceKinds = listOf("llm", "embedding", "tts", "stt", "image", "imageToText"),
        models = (1..20).map { "gpt-$it" } + listOf(
            "text-embedding-3-large",
            "tts-1",
            "whisper-1",
            "dall-e-3",
        ),
    )

    private fun state() = AppState().apply {
        connections += Connection(id = "c1", provider = "openai", apiKey = "sk")
    }

    @Test
    fun listsAllConfiguredModelsWithoutArbitraryCap() {
        val listed = ModelsApi.list(state(), listOf(openAi), "llm")

        assertEquals(20, listed.size)
        assertTrue(listed.all { (it["owned_by"]?.toString()?.trim('"') == "oa") })
    }

    @Test
    fun includesNodeAndComboModels() {
        val state = state().apply {
            nodes += ProviderNode(
                id = "n1",
                prefix = "my-node",
                name = "Node",
                baseUrl = "https://example.test/v1",
                models = listOf("custom-model"),
            )
            combos += Combo("fast", listOf("oa/gpt-1"))
        }

        val ids = ModelsApi.list(state, emptyList(), "llm").map { it["id"].toString().trim('"') }

        assertTrue("my-node/custom-model" in ids)
        assertTrue("fast" in ids)
    }

    @Test
    fun filtersEmbeddingKindByModelName() {
        val ids = ModelsApi.list(state(), listOf(openAi), "embedding").map { it["id"].toString().trim('"') }

        assertEquals(listOf("oa/text-embedding-3-large"), ids)
    }

    @Test
    fun filtersTtsSttAndImageKinds() {
        val state = state()

        assertEquals(listOf("oa/tts-1"), ModelsApi.list(state, listOf(openAi), "tts").map { it["id"].toString().trim('"') })
        assertEquals(listOf("oa/whisper-1"), ModelsApi.list(state, listOf(openAi), "stt").map { it["id"].toString().trim('"') })
        assertEquals(listOf("oa/dall-e-3"), ModelsApi.list(state, listOf(openAi), "image").map { it["id"].toString().trim('"') })
    }

    @Test
    fun findsProviderPrefixedModel() {
        val model = ModelsApi.find(state(), listOf(openAi), "oa/gpt-1")

        assertNotNull(model)
        assertEquals("oa/gpt-1", model!!["id"]?.toString()?.trim('"'))
    }

    @Test
    fun returnsNullForUnknownOrWrongKindModel() {
        assertNull(ModelsApi.find(state(), listOf(openAi), "oa/does-not-exist"))
        assertNull(ModelsApi.find(state(), listOf(openAi), "oa/tts-1", "embedding"))
    }

    @Test
    fun listsWebVirtualModelsForSearchProviders() {
        val search = ProviderDef(
            id = "tavily",
            alias = "tvly",
            name = "Tavily",
            serviceKinds = listOf("webSearch", "webFetch"),
        )

        val ids = ModelsApi.list(state(), listOf(search), "web").map { it["id"].toString().trim('"') }

        assertTrue("tvly/search" in ids)
        assertTrue("tvly/fetch" in ids)
    }

    @Test
    fun infoReturnsCapabilitiesWithoutInventingLimits() {
        val info = ModelsApi.info(state(), listOf(openAi), "oa/gpt-1")

        assertNotNull(info)
        assertEquals("oa/gpt-1", info!!["id"]?.toString()?.trim('"'))
        assertTrue(info.containsKey("capabilities"))
        assertTrue(!info.containsKey("context_length"))
    }
}
