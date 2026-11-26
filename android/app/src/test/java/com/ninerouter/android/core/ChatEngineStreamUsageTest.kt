package com.ninerouter.android.core

import com.ninerouter.android.data.Connection
import com.ninerouter.android.data.ProviderDef
import com.ninerouter.android.data.Store
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatEngineStreamUsageTest {
    @Before
    fun setUp() {
        Store.mutate {
            usage.clear()
            combos.clear()
            nodes.clear()
            connections.clear()
            connections.add(
                Connection(
                    id = "test-openai",
                    provider = "openai",
                    name = "test",
                    apiKey = "sk-test",
                )
            )
        }
    }

    @After
    fun tearDown() {
        Store.mutate {
            usage.clear()
            combos.clear()
            nodes.clear()
            connections.clear()
        }
    }

    private class FakeUpstream(private val sse: String) : Upstream() {
        override fun execute(
            def: ProviderDef,
            conn: Connection,
            body: JsonObject,
            wantsStream: Boolean,
        ): Response = Response.Builder()
            .request(Request.Builder().url("https://example.test/v1/chat/completions").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(sse.toResponseBody("text/event-stream".toMediaType()))
            .build()
    }

    private fun requestBody(stream: Boolean = true): String = buildJsonObject {
        put("model", "openai/gpt-4o-mini")
        put("stream", stream)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", "Say hello")
            })
        })
    }.toString()

    @Test
    fun `streaming request records usage when the stream is consumed`() {
        val sse = """
            data: {"id":"c1","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}

            data: {"id":"c1","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent()
        val engine = ChatEngine(FakeUpstream(sse))

        val outcome = engine.handle(requestBody(), Formats.OPENAI, Formats.OPENAI, "/v1/chat/completions")
        assertTrue("expected a streaming outcome, got $outcome", outcome is ChatEngine.Outcome.Stream)
        (outcome as ChatEngine.Outcome.Stream).response.body!!.source().use { it.readUtf8() }

        assertEquals(1, Store.state.usage.size)
        val entry = Store.state.usage.single()
        assertEquals("openai", entry.provider)
        assertEquals("openai/gpt-4o-mini", entry.model)
        assertTrue(entry.ok)
        assertTrue("stream flag should be set", entry.stream)
        assertEquals("/v1/chat/completions", entry.endpoint)
        assertTrue("output tokens should be estimated from the stream", entry.completion > 0)
        assertTrue("prompt tokens should be estimated from the request", entry.prompt > 0)
    }

    @Test
    fun `stream usage prefers upstream token counts when provided`() {
        val sse = """
            data: {"id":"c1","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}

            data: {"id":"c1","choices":[],"usage":{"prompt_tokens":1234,"completion_tokens":56,"prompt_tokens_details":{"cached_tokens":100}}}

            data: [DONE]

        """.trimIndent()
        val engine = ChatEngine(FakeUpstream(sse))

        val outcome = engine.handle(requestBody(), Formats.OPENAI, Formats.OPENAI, "/v1/chat/completions")
        (outcome as ChatEngine.Outcome.Stream).response.body!!.source().use { it.readUtf8() }

        val entry = Store.state.usage.single()
        assertEquals(1234, entry.prompt)
        assertEquals(56, entry.completion)
        assertEquals(100, entry.cached)
    }

    @Test
    fun `failed request is recorded in the log`() {
        class FailingUpstream : Upstream() {
            override fun execute(
                def: ProviderDef,
                conn: Connection,
                body: JsonObject,
                wantsStream: Boolean,
            ): Response = Response.Builder()
                .request(Request.Builder().url("https://example.test/v1/chat/completions").build())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("{\"error\":\"bad key\"}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        val engine = ChatEngine(FailingUpstream())

        val outcome = engine.handle(requestBody(stream = false), Formats.OPENAI, Formats.OPENAI, "/v1/chat/completions")

        assertTrue("expected an error outcome, got $outcome", outcome is ChatEngine.Outcome.Error)
        assertEquals(1, Store.state.usage.size)
        val entry = Store.state.usage.single()
        assertTrue(!entry.ok)
        assertEquals(401, entry.status)
        assertTrue(entry.error.isNotBlank())
    }
}
