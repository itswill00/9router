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

    fun preflight(requestHeaders: Map<String, String>): ApiHttpResponse {
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
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to METHODS,
            "Access-Control-Allow-Headers" to allowedHeaders,
            "Content-Length" to "0",
            "Connection" to "close",
        )
        if (requested != null && validHeaderList(requested)) {
            headers["Vary"] = "Access-Control-Request-Headers"
        }
        return ApiHttpResponse(204, headers)
    }

    fun jsonHeaders(body: ByteArray): Map<String, String> = linkedMapOf(
        "Content-Type" to "application/json; charset=utf-8",
        "Content-Length" to body.size.toString(),
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to METHODS,
        "Access-Control-Allow-Headers" to DEFAULT_HEADERS,
        "Connection" to "close",
    )

    private fun validHeaderList(value: String): Boolean {
        if (value.any { it == '\r' || it == '\n' }) return false
        val names = value.split(',').map { it.trim() }
        return names.isNotEmpty() && names.all { it.matches(HEADER_NAME) }
    }
}
