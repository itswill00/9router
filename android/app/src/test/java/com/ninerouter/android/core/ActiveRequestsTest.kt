package com.ninerouter.android.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveRequestsTest {
    @After
    fun tearDown() {
        ActiveRequests.clear()
    }

    @Test
    fun `counts concurrent requests per provider model and account`() {
        val now = 1_000L
        val key = ActiveRequests.begin("OpenAI", "gpt-5", "Work", now)
        ActiveRequests.begin("openai", "gpt-5", "Work", now + 10)
        ActiveRequests.begin("claude", "sonnet", "Personal", now + 20)

        val active = ActiveRequests.snapshot(now + 100)
        assertEquals(2, active.size)
        assertEquals(2, active.first { it.provider == "openai" }.count)

        ActiveRequests.end(key, now = now + 150)
        val after = ActiveRequests.snapshot(now + 200)
        assertEquals(1, after.first { it.provider == "openai" }.count)
    }

    @Test
    fun `stuck requests expire after the upstream timeout`() {
        val now = 5_000L
        ActiveRequests.begin("gemini", "flash", "Main", now)

        assertEquals(1, ActiveRequests.snapshot(now + 10_000).size)
        assertTrue(ActiveRequests.snapshot(now + ActiveRequests.TIMEOUT_MS).isEmpty())
    }

    @Test
    fun `failed requests mark the provider for the error window`() {
        val now = System.currentTimeMillis()
        val key = ActiveRequests.begin("codex", "gpt-5", "Main", now)
        ActiveRequests.end(key, error = true, now = now)
        assertEquals("codex", ActiveRequests.errorProvider(now))
        assertEquals("", ActiveRequests.errorProvider(now + ActiveRequests.ERROR_TTL_MS + 1))
    }
}
