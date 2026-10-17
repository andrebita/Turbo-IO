package org.turboio.customai.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Optional network test against the REAL provider endpoints using a deliberately invalid key.
 * Verifies TLS reachability, request acceptance by each API and our HTTP error handling, without any
 * credential. Enable with RUN_LIVE_PROVIDER_TESTS=1. Optionally pass real keys through environment
 * variables (OPENAI_API_KEY / GEMINI_API_KEY / ANTHROPIC_API_KEY) to run a real pt-BR question.
 */
class LiveProviderTest {
    private fun live() = assumeTrue(System.getenv("RUN_LIVE_PROVIDER_TESTS") == "1")

    private fun check(kind: ProviderKind, envKey: String) = runBlocking {
        live()
        val realKey = System.getenv(envKey).orEmpty()
        val key = realKey.ifEmpty { "invalid-key-for-turboio-test" }
        val result = runCatching { AiClient().ask(AiConfig(kind, timeoutSeconds = 30), key, emptyList(), "Qual é a capital do Brasil? Responda em uma frase.") }
        if (realKey.isEmpty()) {
            val e = result.exceptionOrNull()
            println("$kind (chave inválida) → ${e?.message}")
            assertTrue("esperado erro HTTP 400/401/403 do provedor", e is AiException && e.httpStatus in setOf(400, 401, 403))
        } else {
            val answer = result.getOrThrow()
            println("$kind → ${answer.text} (${answer.latencyMs} ms)")
            assertTrue(answer.text.contains("Brasília", ignoreCase = true))
        }
    }

    @Test fun openAi() = check(ProviderKind.OPENAI, "OPENAI_API_KEY")
    @Test fun gemini() = check(ProviderKind.GEMINI, "GEMINI_API_KEY")
    @Test fun anthropic() = check(ProviderKind.ANTHROPIC, "ANTHROPIC_API_KEY")
}
