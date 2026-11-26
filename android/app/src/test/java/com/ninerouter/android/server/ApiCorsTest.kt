package com.ninerouter.android.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiCorsTest {
    @Test
    fun preflightReturns204AndCoreCorsHeaders() {
        val response = ApiHttp.preflight(emptyMap())

        assertEquals(204, response.status)
        assertFalse(response.headers.containsKey("Access-Control-Allow-Origin"))
        assertTrue(response.headers["Access-Control-Allow-Methods"].orEmpty().contains("POST"))
        assertTrue(response.headers["Access-Control-Allow-Methods"].orEmpty().contains("OPTIONS"))
        assertTrue(response.headers["Access-Control-Allow-Headers"].orEmpty().contains("Authorization"))
        assertEquals(0, response.body.size)
    }

    @Test
    fun preflightReflectsRequestedHeadersWithoutCredentials() {
        val response = ApiHttp.preflight(
            mapOf("access-control-request-headers" to "Authorization, Content-Type, X-API-Key")
        )

        assertEquals("Authorization, Content-Type, X-API-Key", response.headers["Access-Control-Allow-Headers"])
        assertEquals("Access-Control-Request-Headers", response.headers["Vary"])
        assertFalse(response.headers.containsKey("Access-Control-Allow-Credentials"))
    }

    @Test
    fun requestedHeadersAreValidatedBeforeReflection() {
        val response = ApiHttp.preflight(
            mapOf("access-control-request-headers" to "Authorization\r\nX-Evil: yes")
        )

        assertEquals("Authorization, Content-Type, X-API-Key", response.headers["Access-Control-Allow-Headers"])
    }

    @Test
    fun jsonResponsesHaveContentLengthAndCorsMethods() {
        val headers = ApiHttp.jsonHeaders("{\"ok\":true}".toByteArray())

        assertFalse(headers.containsKey("Access-Control-Allow-Origin"))
        assertTrue(headers["Access-Control-Allow-Methods"].orEmpty().contains("OPTIONS"))
        assertEquals("11", headers["Content-Length"])
    }

    @Test
    fun networkModeCanDropTheWildcardOrigin() {
        val headers = ApiHttp.jsonHeaders("{}".toByteArray(), allowAnyOrigin = false)
        val preflight = ApiHttp.preflight(emptyMap(), allowAnyOrigin = false)

        assertFalse(headers.containsKey("Access-Control-Allow-Origin"))
        assertFalse(preflight.headers.containsKey("Access-Control-Allow-Origin"))
        assertEquals(204, preflight.status)
    }
}
