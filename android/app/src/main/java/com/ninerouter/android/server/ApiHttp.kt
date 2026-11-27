package com.ninerouter.android.server

internal data class ApiHttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray = ByteArray(0),
)

/** Shared HTTP headers for public API responses and browser preflight. */
internal object ApiHttp {
    private const val METHODS = "GET, POST, PATCH, PUT, DELETE, OPTIONS"
    private const val DEFAULT_HEADERS = "Authorization, Content-Type, X-API-Key"
    private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

    fun preflight(requestHeaders: Map<String, String>, allowAnyOrigin: Boolean = false): ApiHttpResponse {
        val requested = requestHeaders.entries
            .firstOrNull { it.key.equals("access-control-request-headers", ignoreCase = true) }
            ?.value
        val allowedHeaders = requested
            ?.takeIf { validHeaderList(it) }
            ?.split(',')
            ?.map { it.trim() }
            ?.distinctBy { it.lowercase() }
            ?.joinToString(", ")
            ?: DEFAULT_HEADERS
        val headers = linkedMapOf(
            "Access-Control-Allow-Methods" to METHODS,
            "Access-Control-Allow-Headers" to allowedHeaders,
            "Content-Length" to "0",
            "Connection" to "close",
        )
        if (allowAnyOrigin) headers["Access-Control-Allow-Origin"] = "*"
        if (requested != null && validHeaderList(requested)) {
            headers["Vary"] = "Access-Control-Request-Headers"
        }
        return ApiHttpResponse(204, headers)
    }

    fun jsonHeaders(body: ByteArray, allowAnyOrigin: Boolean = false): Map<String, String> = linkedMapOf<String, String>().apply {
        put("Content-Type", "application/json; charset=utf-8")
        put("Content-Length", body.size.toString())
        if (allowAnyOrigin) put("Access-Control-Allow-Origin", "*")
        put("Access-Control-Allow-Methods", METHODS)
        put("Access-Control-Allow-Headers", DEFAULT_HEADERS)
        put("Connection", "close")
    }

    fun mediaHeaders(body: ByteArray, contentType: String, allowAnyOrigin: Boolean = false): Map<String, String> =
        linkedMapOf<String, String>().apply {
            put("Content-Type", contentType)
            put("Content-Length", body.size.toString())
            if (allowAnyOrigin) put("Access-Control-Allow-Origin", "*")
            put("Access-Control-Allow-Methods", METHODS)
            put("Access-Control-Allow-Headers", DEFAULT_HEADERS)
            put("Cache-Control", "no-store")
            put("Connection", "close")
        }

    private fun validHeaderList(value: String): Boolean {
        if (value.any { it == '\r' || it == '\n' }) return false
        val names = value.split(',').map { it.trim() }
        return names.isNotEmpty() && names.all { it.matches(HEADER_NAME) }
    }
}
