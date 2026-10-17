package org.turboio.customai.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.turboio.customai.diag.DiagnosticLog

class FakeTransport(var status: Int = 200, var body: String = "", var delayMs: Long = 0) : HttpTransport {
    var last: HttpRequestSpec? = null
    override fun post(request: HttpRequestSpec, timeoutMs: Int): HttpResult {
        last = request
        if (delayMs > 0) Thread.sleep(delayMs)
        return HttpResult(status, body)
    }
}

class ProviderTest {
    private val key = "sk-test-SECRET-1234567890"
    private val history = listOf(ChatMessage("user", "Oi"), ChatMessage("assistant", "Olá!"))

    @Test fun openAiRequestUsesBearerAndMaxCompletionTokens() {
        val req = ProviderCodec.build(AiConfig(ProviderKind.OPENAI, maxTokens = 300), key, history, "Qual a capital do Brasil?")
        assertEquals("https://api.openai.com/v1/chat/completions", req.url)
        assertEquals("Bearer $key", req.headers["Authorization"])
        val body = JSONObject(req.body)
        assertEquals("gpt-5-mini", body.getString("model"))
        assertEquals(300, body.getInt("max_completion_tokens"))
        assertFalse(body.has("max_tokens"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals(4, messages.length())
        assertEquals("Qual a capital do Brasil?", messages.getJSONObject(3).getString("content"))
        assertFalse(req.describe().contains(key))
    }

    @Test fun compatibleRequestAcceptsBaseUrlAndUsesMaxTokens() {
        val config = AiConfig(ProviderKind.OPENAI_COMPATIBLE, model = "deepseek-chat", endpoint = "https://api.deepseek.com/v1/")
        assertNull(ProviderCodec.validate(config, key))
        val req = ProviderCodec.build(config, key, emptyList(), "Oi")
        assertEquals("https://api.deepseek.com/v1/chat/completions", req.url)
        assertEquals(800, JSONObject(req.body).getInt("max_tokens"))
    }

    @Test fun geminiRequestShape() {
        val req = ProviderCodec.build(AiConfig(ProviderKind.GEMINI, model = "models/gemini-2.5-flash"), key, history, "Oi")
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent", req.url)
        assertEquals(key, req.headers["x-goog-api-key"])
        assertFalse(req.url.contains(key))
        val body = JSONObject(req.body)
        assertEquals("model", body.getJSONArray("contents").getJSONObject(1).getString("role"))
        assertTrue(body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text").contains("português do Brasil"))
        assertEquals(800, body.getJSONObject("generationConfig").getInt("maxOutputTokens"))
    }

    @Test fun anthropicRequestShape() {
        val req = ProviderCodec.build(AiConfig(ProviderKind.ANTHROPIC), key, history, "Oi")
        assertEquals("https://api.anthropic.com/v1/messages", req.url)
        assertEquals(key, req.headers["x-api-key"])
        assertEquals("2023-06-01", req.headers["anthropic-version"])
        val body = JSONObject(req.body)
        assertEquals("claude-opus-5-5", body.getString("model"))
        assertTrue(body.getString("system").contains("português do Brasil"))
        assertEquals(3, body.getJSONArray("messages").length())
        assertEquals(800, body.getInt("max_tokens"))
    }

    @Test fun parsesDocumentedResponseFormats() {
        assertEquals("Brasília.", ProviderCodec.parse(ProviderKind.OPENAI, HttpResult(200,
            """{"choices":[{"message":{"role":"assistant","content":"Brasília."},"finish_reason":"stop"}]}""")).text)
        assertEquals("Brasília.", ProviderCodec.parse(ProviderKind.GEMINI, HttpResult(200,
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"pensando","thought":true},{"text":"Brasília."}]},"finishReason":"STOP"}]}""")).text)
        val claude = ProviderCodec.parse(ProviderKind.ANTHROPIC, HttpResult(200,
            """{"type":"message","content":[{"type":"text","text":"Brasília."}],"stop_reason":"max_tokens"}"""))
        assertEquals("Brasília.", claude.text); assertTrue(claude.truncated)
    }

    @Test fun emptyOrTruncatedResponsesHaveClearErrors() {
        val e = assertThrows(AiException::class.java) {
            ProviderCodec.parse(ProviderKind.OPENAI, HttpResult(200, """{"choices":[{"message":{"content":""},"finish_reason":"length"}]}"""))
        }
        assertTrue(e.message!!.contains("Limite de tokens"))
        assertThrows(AiException::class.java) { ProviderCodec.parse(ProviderKind.GEMINI, HttpResult(200, """{"promptFeedback":{"blockReason":"SAFETY"}}""")) }
        assertThrows(AiException::class.java) { ProviderCodec.parse(ProviderKind.ANTHROPIC, HttpResult(200, "not json")) }
    }

    @Test fun httpErrorsAreMappedAndRedacted() {
        val e = assertThrows(AiException::class.java) {
            ProviderCodec.parse(ProviderKind.OPENAI, HttpResult(401, """{"error":{"message":"Incorrect API key provided: sk-test-SECRET-1234567890."}}"""))
        }
        assertTrue(e.message!!.contains("Chave de API inválida")); assertTrue(e.message!!.contains("HTTP 401"))
        assertFalse(e.message!!.contains("SECRET"))
        assertTrue(assertThrows(AiException::class.java) { ProviderCodec.parse(ProviderKind.ANTHROPIC, HttpResult(429, """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""")) }
            .message!!.contains("cota"))
        assertTrue(assertThrows(AiException::class.java) { ProviderCodec.parse(ProviderKind.GEMINI, HttpResult(503, "<html>")) }.message!!.contains("HTTP 503"))
    }

    @Test fun validationRejectsInsecureOrMalformedConfig() {
        assertNotNull(ProviderCodec.validate(AiConfig(ProviderKind.OPENAI), ""))
        assertNotNull(ProviderCodec.validate(AiConfig(ProviderKind.OPENAI_COMPATIBLE, model = "x", endpoint = "http://example.com/v1/chat/completions"), key))
        assertNotNull(ProviderCodec.validate(AiConfig(ProviderKind.OPENAI_COMPATIBLE, model = "x", endpoint = "https://u:p@example.com/v1/chat/completions"), key))
        assertNotNull(ProviderCodec.validate(AiConfig(ProviderKind.OPENAI_COMPATIBLE, model = "x", endpoint = "https://example.com/v1/chat/completions?key=1"), key))
        assertNotNull(ProviderCodec.validate(AiConfig(ProviderKind.OPENAI, model = "bad model"), key))
        assertNotNull(ProviderCodec.validate(AiConfig(ProviderKind.OPENAI, maxTokens = 0), key))
        assertNotNull(ProviderCodec.validate(AiConfig(ProviderKind.OPENAI, timeoutSeconds = 500), key))
        assertNotNull(ProviderCodec.validate(AiConfig(ProviderKind.OPENAI), "has space"))
        // Fixed providers ignore a tampered endpoint and always use the official one.
        assertEquals("https://api.anthropic.com/v1/messages", ProviderCodec.resolvedEndpoint(AiConfig(ProviderKind.ANTHROPIC, endpoint = "https://evil.example/v1/messages")))
    }

    @Test fun historyIsBounded() {
        val long = (1..30).flatMap { listOf(ChatMessage("user", "q$it"), ChatMessage("assistant", "a$it")) }
        val body = JSONObject(ProviderCodec.build(AiConfig(ProviderKind.ANTHROPIC, historyTurns = 2), key, long, "agora").body)
        assertEquals(5, body.getJSONArray("messages").length())
    }

    @Test fun clientReturnsAnswerAndNeverLogsKey() = runBlocking {
        DiagnosticLog.clear()
        val transport = FakeTransport(body = """{"choices":[{"message":{"content":"**Brasília** é a capital."}}]}""")
        val answer = AiClient(transport).ask(AiConfig(ProviderKind.OPENAI), key, emptyList(), "Capital do Brasil?")
        assertEquals("**Brasília** é a capital.", answer.text)
        assertFalse(DiagnosticLog.export().contains("SECRET"))
        assertTrue(DiagnosticLog.export().contains("HTTP 200"))
    }

    @Test fun clientEnforcesTimeout() = runBlocking {
        val transport = FakeTransport(body = """{"choices":[{"message":{"content":"tarde"}}]}""", delayMs = 7000)
        val started = System.currentTimeMillis()
        val e = runCatching { AiClient(transport).ask(AiConfig(ProviderKind.OPENAI, timeoutSeconds = 5), key, emptyList(), "Oi") }.exceptionOrNull()
        assertTrue(e is AiException && e.message!!.contains("Tempo máximo"))
        assertTrue(System.currentTimeMillis() - started < 6900)
    }

    @Test fun clientRejectsEmptyQuestionAndMissingKey() = runBlocking {
        val client = AiClient(FakeTransport())
        assertTrue(runCatching { client.ask(AiConfig(), key, emptyList(), "  ") }.exceptionOrNull() is AiException)
        assertTrue(runCatching { client.ask(AiConfig(), "", emptyList(), "Oi") }.exceptionOrNull()!!.message!!.contains("chave"))
    }
}
