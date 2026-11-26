package com.ninerouter.android.server

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

internal data class HttpRequest(
    val method: String,
    val target: String,
    val version: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

internal class HttpRequestParseException(
    val status: Int,
    message: String,
) : Exception(message)

/** Minimal, bounded HTTP/1.1 request parser for the loopback gateway. */
internal object HttpRequestParser {
    private const val MAX_REQUEST_LINE_BYTES = 8 * 1024
    private const val MAX_HEADER_LINE_BYTES = 8 * 1024
    private const val MAX_HEADER_COUNT = 100
    private const val MAX_TRAILER_COUNT = 100
    private const val DEFAULT_MAX_BODY_BYTES = 8 * 1024 * 1024

    fun read(input: InputStream, maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES): HttpRequest {
        if (maxBodyBytes < 0) throw IllegalArgumentException("maxBodyBytes must be non-negative")
        val requestLine = readLine(input, MAX_REQUEST_LINE_BYTES)
            ?: throw HttpRequestParseException(400, "Missing request line")
        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts.any { it.isBlank() }) {
            throw HttpRequestParseException(400, "Malformed request line")
        }
        val (method, target, version) = parts
        if (!method.matches(TOKEN) || !target.startsWith('/') || version != "HTTP/1.1") {
            throw HttpRequestParseException(400, "Unsupported or malformed request line")
        }

        val headers = LinkedHashMap<String, String>()
        var count = 0
        while (true) {
            val line = readLine(input, MAX_HEADER_LINE_BYTES)
                ?: throw HttpRequestParseException(400, "Truncated headers")
            if (line.isEmpty()) break
            if (++count > MAX_HEADER_COUNT) throw HttpRequestParseException(400, "Too many headers")
            if (line.first().isWhitespace()) throw HttpRequestParseException(400, "Folded headers are not supported")
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpRequestParseException(400, "Malformed header")
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (!name.matches(TOKEN)) throw HttpRequestParseException(400, "Malformed header name")
            if (headers.putIfAbsent(name, value) != null) {
                throw HttpRequestParseException(400, "Duplicate header: $name")
            }
        }

        val transferEncoding = headers["transfer-encoding"]
            ?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val contentLengthHeader = headers["content-length"]
        if (transferEncoding.isNotEmpty() && contentLengthHeader != null) {
            throw HttpRequestParseException(400, "Conflicting body framing headers")
        }

        val body = when {
            transferEncoding.isNotEmpty() -> {
                if (transferEncoding != listOf("chunked")) {
                    throw HttpRequestParseException(400, "Unsupported transfer encoding")
                }
                readChunked(input, maxBodyBytes)
            }
            contentLengthHeader != null -> {
                val length = contentLengthHeader.toLongOrNull()
                    ?: throw HttpRequestParseException(400, "Invalid Content-Length")
                if (length < 0) throw HttpRequestParseException(400, "Invalid Content-Length")
                if (length > maxBodyBytes) throw HttpRequestParseException(413, "Request body too large")
                readExact(input, length.toInt())
            }
            else -> ByteArray(0)
        }

        return HttpRequest(method, target, version, headers, body)
    }

    private fun readChunked(input: InputStream, maxBodyBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input, MAX_HEADER_LINE_BYTES)
                ?: throw HttpRequestParseException(400, "Truncated chunk header")
            val sizeToken = sizeLine.substringBefore(';').trim()
            if (sizeToken.isEmpty() || sizeToken.any { it.digitToIntOrNull(16) == null }) {
                throw HttpRequestParseException(400, "Malformed chunk size")
            }
            val size = sizeToken.toLongOrNull(16)
                ?: throw HttpRequestParseException(400, "Malformed chunk size")
            if (size > maxBodyBytes - out.size().toLong()) {
                throw HttpRequestParseException(413, "Request body too large")
            }
            if (size == 0L) {
                var trailers = 0
                while (true) {
                    val trailer = readLine(input, MAX_HEADER_LINE_BYTES)
                        ?: throw HttpRequestParseException(400, "Truncated chunk trailers")
                    if (trailer.isEmpty()) return out.toByteArray()
                    if (++trailers > MAX_TRAILER_COUNT) throw HttpRequestParseException(400, "Too many chunk trailers")
                    if (trailer.indexOf(':') <= 0 || trailer.first().isWhitespace()) {
                        throw HttpRequestParseException(400, "Malformed chunk trailer")
                    }
                }
            }
            val chunk = readExact(input, size.toInt())
            out.write(chunk)
            if (readLine(input, 2) != "") throw HttpRequestParseException(400, "Malformed chunk terminator")
        }
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) throw HttpRequestParseException(400, "Truncated request body")
            if (count == 0) continue
            offset += count
        }
        return bytes
    }

    private fun readLine(input: InputStream, maxBytes: Int): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (bytes.size() == 0) return null
                throw HttpRequestParseException(400, "Truncated HTTP line")
            }
            if (value == '\n'.code) {
                val raw = bytes.toByteArray()
                val length = if (raw.isNotEmpty() && raw.last() == '\r'.code.toByte()) raw.size - 1 else raw.size
                return String(raw, 0, length, Charsets.US_ASCII)
            }
            if (bytes.size() >= maxBytes) throw HttpRequestParseException(400, "HTTP line too long")
            bytes.write(value)
        }
    }

    private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
}
