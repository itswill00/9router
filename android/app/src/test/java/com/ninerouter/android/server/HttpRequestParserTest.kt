package com.ninerouter.android.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class HttpRequestParserTest {
    @Test
    fun readsContentLengthBody() {
        val request = parse("POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\nContent-Length: 7\r\n\r\n{\"a\":1}")

        assertEquals("POST", request.method)
        assertEquals("/v1/chat/completions", request.target)
        assertEquals("HTTP/1.1", request.version)
        assertEquals("localhost", request.headers["host"])
        assertArrayEquals("{\"a\":1}".toByteArray(), request.body)
    }

    @Test
    fun decodesChunkedBody() {
        val request = parse("POST /v1 HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n5\r\npedia\r\n0\r\nTrailer: ignored\r\n\r\n")

        assertArrayEquals("Wikipedia".toByteArray(), request.body)
    }

    @Test
    fun rejectsConflictingFramingHeaders() {
        assertThrows(HttpRequestParseException::class.java) {
            parse("POST / HTTP/1.1\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n")
        }
    }

    @Test
    fun rejectsMalformedChunk() {
        assertThrows(HttpRequestParseException::class.java) {
            parse("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\nxyz\r\nabc\r\n0\r\n\r\n")
        }
    }

    @Test
    fun rejectsTruncatedContentLengthBody() {
        assertThrows(HttpRequestParseException::class.java) {
            parse("POST / HTTP/1.1\r\nContent-Length: 5\r\n\r\nabc")
        }
    }

    @Test
    fun rejectsBodyOverLimitWith413() {
        val error = assertThrows(HttpRequestParseException::class.java) {
            parse("POST / HTTP/1.1\r\nContent-Length: 4\r\n\r\n1234", maxBodyBytes = 3)
        }
        assertEquals(413, error.status)
    }

    @Test
    fun normalizesHeaderNamesAndRejectsDuplicateLength() {
        assertThrows(HttpRequestParseException::class.java) {
            parse("POST / HTTP/1.1\r\nContent-Length: 1\r\ncontent-length: 1\r\n\r\nx")
        }
    }

    private fun parse(raw: String, maxBodyBytes: Int = 1024) =
        HttpRequestParser.read(ByteArrayInputStream(raw.toByteArray()), maxBodyBytes)
}
