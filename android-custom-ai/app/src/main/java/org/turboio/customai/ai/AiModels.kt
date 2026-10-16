package org.turboio.customai.ai

enum class ProviderKind(
    val id: String,
    val label: String,
    val defaultEndpoint: String,
    val defaultModel: String,
    val suggestedModels: List<String>,
    val endpointEditable: Boolean,
) {
    OPENAI("openai", "OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-5-mini",
        listOf("gpt-5-mini", "gpt-5", "gpt-4.1-mini", "gpt-4o-mini"), false),
    GEMINI("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash",
        listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro"), false),
    ANTHROPIC("anthropic", "Anthropic Claude", "https://api.anthropic.com/v1/messages", "claude-opus-5-5",
        listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"), false),
    OPENAI_COMPATIBLE("compatible", "Compatível com OpenAI (DeepSeek, Groq, OpenRouter, Mistral, servidor próprio…)", "", "",
        listOf("deepseek-chat", "llama-3.3-70b-versatile", "mistral-small-latest"), true);

    companion object {
        fun fromId(id: String?): ProviderKind = entries.firstOrNull { it.id == id } ?: OPENAI
    }
}

const val DEFAULT_SYSTEM_PROMPT = """Você é um assistente de voz exibido nas lentes dos óculos RayNeo iO.
Responda sempre em português do Brasil.
O display é pequeno: responda de forma curta e objetiva, em no máximo 2 ou 3 frases (cerca de 250 caracteres).
Use apenas texto simples: sem Markdown, sem tabelas, sem listas longas, sem emojis e sem links.
Comece pela informação essencial.
Se o usuário pedir explicitamente mais detalhes ("detalhe", "explique melhor", "resposta completa"), responda de forma mais completa, ainda em texto simples e em parágrafos curtos.
Se não souber algo ou precisar de dados em tempo real que você não tem, diga isso em uma frase."""

data class AiConfig(
    val provider: ProviderKind = ProviderKind.OPENAI,
    val model: String = provider.defaultModel,
    val endpoint: String = provider.defaultEndpoint,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val maxTokens: Int = 800,
    val timeoutSeconds: Int = 30,
    val historyTurns: Int = 3,
)

data class ChatMessage(val role: String, val content: String) {
    init { require(role == "user" || role == "assistant") }
}

data class AiAnswer(val text: String, val latencyMs: Long, val provider: ProviderKind, val model: String, val truncated: Boolean)

/** User-facing failure. [message] is pt-BR and already safe to display/log (no secrets). */
class AiException(message: String, val httpStatus: Int = 0) : Exception(message)

class HttpRequestSpec(val url: String, val headers: Map<String, String>, val body: String) {
    /** For diagnostics only: URL without query and header names without values. */
    fun describe(): String = url.substringBefore('?') + " headers=" + headers.keys.sorted().joinToString(",")
}

class HttpResult(val status: Int, val body: String)

interface HttpTransport {
    /** Blocking POST. Throws [java.io.IOException] on network errors / timeouts. */
    fun post(request: HttpRequestSpec, timeoutMs: Int): HttpResult
}
