package com.ninerouter.android.core

import com.ninerouter.android.data.Connection
import com.ninerouter.android.data.MediaConfig
import com.ninerouter.android.data.ProviderDef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaEngineTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun def(
        id: String,
        alias: String = id,
        kinds: List<String> = listOf("image", "tts", "stt", "webSearch", "webFetch"),
        image: MediaConfig? = null,
        tts: MediaConfig? = null,
        stt: MediaConfig? = null,
        search: MediaConfig? = null,
        fetch: MediaConfig? = null,
    ) = ProviderDef(
        id = id,
        alias = alias,
        name = id,
        serviceKinds = kinds,
        imageConfig = image,
        ttsConfig = tts,
        sttConfig = stt,
        searchConfig = search,
        fetchConfig = fetch,
    )

    private val conn = Connection(id = "c1", provider = "openai", apiKey = "sk-test")

    private fun body(jsonText: String): JsonObject = json.parseToJsonElement(jsonText).jsonObject

    @Test
    fun `openai image plan sends model prompt and bearer auth`() {
        val openai = def(
            "openai",
            image = MediaConfig(baseUrl = "https://api.openai.com/v1/images/generations"),
        )

        val plan = MediaEngine.imagePlan(openai, conn, "gpt-image-1", body("""{"prompt":"a fox","n":2,"size":"1024x1024"}"""))

        assertEquals("https://api.openai.com/v1/images/generations", plan.url)
        assertEquals("Bearer sk-test", plan.headers["Authorization"])
        val payload = json.parseToJsonElement(plan.body!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals("gpt-image-1", payload["model"]!!.jsonPrimitive.content)
        assertEquals("a fox", payload["prompt"]!!.jsonPrimitive.content)
        assertEquals(2, payload["n"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `xai image plan drops unsupported fields`() {
        val xai = def(
            "xai",
            image = MediaConfig(
                baseUrl = "https://api.x.ai/v1/images/generations",
                bodyFields = listOf("model", "prompt", "n", "response_format"),
            ),
        )

        val plan = MediaEngine.imagePlan(xai, conn, "grok-2-image", body("""{"prompt":"a fox","quality":"hd","size":"1024x1024"}"""))
        val payload = json.parseToJsonElement(plan.body!!.toString(Charsets.UTF_8)).jsonObject

        assertTrue(payload.containsKey("prompt"))
        assertTrue(!payload.containsKey("quality"))
        assertTrue(!payload.containsKey("size"))
    }

    @Test
    fun `gemini image plan uses generateContent and google key header`() {
        val gemini = def(
            "gemini",
            image = MediaConfig(baseUrl = "https://generativelanguage.googleapis.com/v1beta/models"),
        )
        val google = Connection(id = "g1", provider = "gemini", apiKey = "gm-key")

        val plan = MediaEngine.imagePlan(gemini, google, "gemini-3-pro-image-preview", body("""{"prompt":"a fox"}"""))

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-pro-image-preview:generateContent",
            plan.url,
        )
        assertEquals("gm-key", plan.headers["x-goog-api-key"])
        assertTrue(!plan.headers.containsKey("Authorization"))
        val payload = json.parseToJsonElement(plan.body!!.toString(Charsets.UTF_8)).jsonObject
        val parts = payload["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals("a fox", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai speech plan parses model slash voice`() {
        val openai = def(
            "openai",
            tts = MediaConfig(
                baseUrl = "https://api.openai.com/v1/audio/speech",
                format = "openai",
                defaultModel = "gpt-4o-mini-tts",
            ),
        )

        val plan = MediaEngine.speechPlan(openai, conn, "tts-1/nova", body("""{"input":"hello"}"""), "mp3")
        val payload = json.parseToJsonElement(plan.body!!.toString(Charsets.UTF_8)).jsonObject

        assertEquals("tts-1", payload["model"]!!.jsonPrimitive.content)
        assertEquals("nova", payload["voice"]!!.jsonPrimitive.content)
        assertEquals("hello", payload["input"]!!.jsonPrimitive.content)
    }

    @Test
    fun `elevenlabs speech plan puts voice id in the path`() {
        val eleven = def(
            "elevenlabs",
            tts = MediaConfig(
                baseUrl = "https://api.elevenlabs.io/v1/text-to-speech",
                format = "elevenlabs",
                authHeader = "xi-api-key",
            ),
        )
        val elConn = Connection(id = "e1", provider = "elevenlabs", apiKey = "el-key")

        val plan = MediaEngine.speechPlan(eleven, elConn, "eleven_v4/voice-123", body("""{"input":"hello"}"""), "mp3")

        assertEquals(
            "https://api.elevenlabs.io/v1/text-to-speech/voice-123?output_format=mp3_44100_128",
            plan.url,
        )
        assertEquals("el-key", plan.headers["xi-api-key"])
        assertEquals("voice-123", plan.url.substringAfterLast('/').substringBefore('?'))
    }

    @Test
    fun `openai compatible stt plan is supported and other formats are rejected`() {
        val groq = def(
            "groq",
            stt = MediaConfig(
                baseUrl = "https://api.groq.com/openai/v1/audio/transcriptions",
                format = "openai",
                authHeader = "bearer",
            ),
        )
        val plan = MediaEngine.transcribePlan(groq, conn, "audio".toByteArray(), "multipart/form-data; boundary=B")
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", plan.url)

        val deepgram = def(
            "deepgram",
            stt = MediaConfig(baseUrl = "https://api.deepgram.com/v1/listen", format = "deepgram"),
        )
        val error = runCatching {
            MediaEngine.transcribePlan(deepgram, conn, "audio".toByteArray(), "multipart/form-data; boundary=B")
        }.exceptionOrNull()
        assertTrue(error is MediaEngine.MediaException)
        assertEquals(501, (error as MediaEngine.MediaException).status)
    }

    @Test
    fun `multipart model is read and rewritten without touching the file`() {
        val boundary = "BOUND"
        val raw = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
            append("openai/whisper-1\r\n")
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\n")
            append("Content-Type: audio/wav\r\n\r\n")
            append("RIFFDATA\r\n")
            append("--$boundary--\r\n")
        }.toByteArray()

        assertEquals("openai/whisper-1", MediaEngine.multipartModel(raw, "multipart/form-data; boundary=$boundary"))

        val rewritten = MediaEngine.rewriteMultipartField(raw, "multipart/form-data; boundary=$boundary", "model", "whisper-1")
            .toString(Charsets.UTF_8)

        assertTrue(rewritten.contains("name=\"model\"\r\n\r\nwhisper-1\r\n"))
        assertTrue(rewritten.contains("RIFFDATA"))
        assertTrue(!rewritten.contains("openai/whisper-1"))
    }

    @Test
    fun `gemini image response normalizes inline images to b64_json`() {
        val raw = """
            {"candidates":[{"content":{"parts":[
              {"text":"here"},
              {"inlineData":{"mimeType":"image/png","data":"QUJD"}}
            ]}}]}
        """.trimIndent().toByteArray()

        val normalized = MediaEngine.normalizeGeminiImage(raw)
        val payload = json.parseToJsonElement(normalized.toString(Charsets.UTF_8)).jsonObject
        val first = payload["data"]!!.jsonArray[0].jsonObject

        assertEquals("QUJD", first["b64_json"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tavily search plan uses bearer and max results`() {
        val tavily = def(
            "tavily",
            search = MediaConfig(
                baseUrl = "https://api.tavily.com/search",
                authHeader = "bearer",
                defaultMaxResults = 5,
                maxMaxResults = 20,
            ),
        )
        val tvConn = Connection(id = "t1", provider = "tavily", apiKey = "tv-key")

        val plan = MediaEngine.searchPlan(tavily, tvConn, body("""{"query":"9router","max_results":99}"""))
        val payload = json.parseToJsonElement(plan.body!!.toString(Charsets.UTF_8)).jsonObject

        assertEquals("https://api.tavily.com/search", plan.url)
        assertEquals("Bearer tv-key", plan.headers["Authorization"])
        assertEquals(20, payload["max_results"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `brave search plan uses GET query parameters`() {
        val brave = def(
            "brave-search",
            search = MediaConfig(
                baseUrl = "https://api.search.brave.com/res/v1",
                method = "GET",
                authHeader = "x-subscription-token",
                maxMaxResults = 20,
            ),
        )
        val braveConn = Connection(id = "b1", provider = "brave-search", apiKey = "br-key")

        val plan = MediaEngine.searchPlan(brave, braveConn, body("""{"query":"a b","max_results":3,"language":"id"}"""))

        assertEquals("GET", plan.method)
        assertTrue(plan.url.startsWith("https://api.search.brave.com/res/v1/web/search?"))
        assertTrue(plan.url.contains("q=a+b") || plan.url.contains("q=a%20b"))
        assertTrue(plan.url.contains("count=3"))
        assertEquals("br-key", plan.headers["X-Subscription-Token"])
    }

    @Test
    fun `serper search normalizer maps organic results`() {
        val raw = """
            {"searchParameters":{"totalResults":1},"organic":[
              {"title":"9Router","link":"https://example.com/x","snippet":"router","date":"2026-01-01"}
            ]}
        """.trimIndent().toByteArray()

        val out = MediaEngine.normalizeSearch("serper", raw, "router")
        val payload = json.parseToJsonElement(out.toString(Charsets.UTF_8)).jsonObject
        val first = payload["results"]!!.jsonArray[0].jsonObject

        assertEquals("9Router", first["title"]!!.jsonPrimitive.content)
        assertEquals("https://example.com/x", first["url"]!!.jsonPrimitive.content)
        assertEquals("router", first["snippet"]!!.jsonPrimitive.content)
    }

    @Test
    fun `jina fetch plan posts to reader with format header`() {
        val jina = def(
            "jina-reader",
            fetch = MediaConfig(
                baseUrl = "https://r.jina.ai",
                authHeader = "bearer",
                formats = listOf("markdown", "text", "html"),
            ),
        )
        val jConn = Connection(id = "j1", provider = "jina-reader", apiKey = "j-key")

        val plan = MediaEngine.fetchPlan(jina, jConn, body("""{"url":"https://example.com","format":"text"}"""))

        assertEquals("https://r.jina.ai/", plan.url)
        assertEquals("text", plan.headers["x-respond-with"])
        assertEquals("Bearer j-key", plan.headers["Authorization"])
    }

    @Test
    fun `fetch rejects unsupported provider formats`() {
        val firecrawl = def(
            "firecrawl",
            fetch = MediaConfig(
                baseUrl = "https://api.firecrawl.dev/v1/scrape",
                formats = listOf("markdown"),
            ),
        )
        val error = runCatching {
            MediaEngine.fetchPlan(firecrawl, conn, body("""{"url":"https://example.com","format":"text"}"""))
        }.exceptionOrNull()

        assertTrue(error is MediaEngine.MediaException)
        assertEquals(400, (error as MediaEngine.MediaException).status)
    }

    @Test
    fun `media execute posts body and headers to a local server`() {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        var seenAuth = ""
        var seenBody = ""
        val worker = Thread {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine() // request line
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        when {
                            line.startsWith("Authorization:", true) -> seenAuth = line.substringAfter(':').trim()
                            line.startsWith("Content-Length:", true) -> contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                    }
                    if (contentLength > 0) {
                        val chars = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val count = reader.read(chars, read, contentLength - read)
                            if (count < 0) break
                            read += count
                        }
                        seenBody = String(chars, 0, read)
                    }
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n\r\n{\"ok\":true}"
                    socket.getOutputStream().write(response.toByteArray())
                    socket.getOutputStream().flush()
                }
            }
        }
        worker.start()
        try {
            val port = server.localPort
            val reply = MediaEngine.execute(
                MediaEngine.Plan(
                    provider = "test",
                    model = "m",
                    kind = "test",
                    url = "http://127.0.0.1:$port/echo",
                    headers = mapOf("Authorization" to "Bearer abc"),
                    body = "{\"hello\":1}".toByteArray(),
                ),
            )
            worker.join(5_000)

            assertEquals(200, reply.status)
            assertEquals("Bearer abc", seenAuth)
            assertEquals("{\"hello\":1}", seenBody)
            assertEquals("{\"ok\":true}", reply.bytes.toString(Charsets.UTF_8))
        } finally {
            runCatching { server.close() }
        }
    }

    @Test
    fun `media support matrix only advertises implemented transports`() {
        val openai = def(
            "openai",
            image = MediaConfig(baseUrl = "https://api.openai.com/v1/images/generations"),
            tts = MediaConfig(baseUrl = "https://api.openai.com/v1/audio/speech", format = "openai"),
            stt = MediaConfig(baseUrl = "https://api.openai.com/v1/audio/transcriptions", format = "openai"),
            fetch = MediaConfig(baseUrl = "https://api.openai.com/v1/x"),
        )
        val deepgram = def(
            "deepgram",
            kinds = listOf("stt"),
            stt = MediaConfig(baseUrl = "https://api.deepgram.com/v1/listen", format = "deepgram"),
        )

        assertTrue(MediaEngine.supports(openai, "image"))
        assertTrue(MediaEngine.supports(openai, "tts"))
        assertTrue(MediaEngine.supports(openai, "stt"))
        assertTrue(!MediaEngine.supports(openai, "fetch"))
        assertTrue(!MediaEngine.supports(deepgram, "stt"))
    }
}
