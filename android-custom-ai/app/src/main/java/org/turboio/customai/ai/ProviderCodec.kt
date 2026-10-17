package org.turboio.customai.ai

import org.json.JSONArray
import org.json.JSONObject
import org.turboio.customai.diag.Redactor
import java.net.URI

/** Pure request builders / response parsers for each provider (unit-tested on the JVM). */
object ProviderCodec {
    private val MODEL = Regex("^[A-Za-z0-9._:/@-]{1,160}$")

    fun validate(config: AiConfig, apiKey: String): String? {
        if (apiKey.isBlank()) return "Informe a chave de API do provedor"
        if (apiKey.length > 512 || apiKey.any { it.isWhitespace() || it.code < 0x20 }) return "Chave de API com formato inválido"
        if (!MODEL.matches(config.model.trim())) return "Modelo inválido"
        if (config.maxTokens !in 16..8192) return "Limite de tokens deve ficar entre 16 e 8192"
        if (config.timeoutSeconds !in 5..180) return "Tempo máximo deve ficar entre 5 e 180 segundos"
        if (config.systemPrompt.length > 8000) return "Instruções de sistema muito longas (máx. 8000 caracteres)"
        return endpointError(config)
    }

    private fun endpointError(config: AiConfig): String? {
        val endpoint = resolvedEndpoint(config)
        val uri = try { URI(endpoint) } catch (_: Exception) { return "Endpoint inválido" }
        if (!"https".equals(uri.scheme, true) || uri.host.isNullOrBlank()) return "O endpoint deve usar HTTPS"
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return "Endpoint não pode conter usuário, parâmetros ou fragmento"
        if (config.provider == ProviderKind.OPENAI_COMPATIBLE && !uri.path.endsWith("/chat/completions")) {
            return "Endpoint compatível deve terminar em /chat/completions"
        }
        return null
    }

    /** Fixed providers always use their official endpoint; compatible accepts a base URL or a full path. */
    fun resolvedEndpoint(config: AiConfig): String = when (config.provider) {
        ProviderKind.OPENAI_COMPATIBLE -> config.endpoint.trim().trimEnd('/').let {
            if (it.endsWith("/chat/completions")) it else "$it/chat/completions"
        }
        else -> config.provider.defaultEndpoint
    }

    private fun messagesFor(config: AiConfig, history: List<ChatMessage>, question: String): List<ChatMessage> =
        history.takeLast(config.historyTurns.coerceIn(0, 10) * 2) + ChatMessage("user", question)

    fun build(config: AiConfig, apiKey: String, history: List<ChatMessage>, question: String): HttpRequestSpec {
        val model = config.model.trim()
        val turns = messagesFor(config, history, question)
        return when (config.provider) {
            ProviderKind.OPENAI, ProviderKind.OPENAI_COMPATIBLE -> {
                val messages = JSONArray()
                if (config.systemPrompt.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", config.systemPrompt))
                turns.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }
                val body = JSONObject().put("model", model).put("messages", messages).put("stream", false)
                // Official OpenAI reasoning models require max_completion_tokens; compatible servers expect max_tokens.
                if (config.provider == ProviderKind.OPENAI) body.put("max_completion_tokens", config.maxTokens)
                else body.put("max_tokens", config.maxTokens)
                HttpRequestSpec(resolvedEndpoint(config), mapOf(
                    "Authorization" to "Bearer $apiKey", "Content-Type" to "application/json", "Accept" to "application/json",
                ), body.toString())
            }
            ProviderKind.GEMINI -> {
                val id = model.removePrefix("models/")
                val contents = JSONArray()
                turns.forEach {
                    contents.put(JSONObject().put("role", if (it.role == "assistant") "model" else "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", it.content))))
                }
                val body = JSONObject().put("contents", contents)
                    .put("generationConfig", JSONObject().put("maxOutputTokens", config.maxTokens))
                if (config.systemPrompt.isNotBlank()) {
                    body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", config.systemPrompt))))
                }
                HttpRequestSpec("${ProviderKind.GEMINI.defaultEndpoint}/models/$id:generateContent", mapOf(
                    "x-goog-api-key" to apiKey, "Content-Type" to "application/json", "Accept" to "application/json",
                ), body.toString())
            }
            ProviderKind.ANTHROPIC -> {
                val messages = JSONArray()
                turns.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }
                val body = JSONObject().put("model", model).put("max_tokens", config.maxTokens).put("messages", messages)
                if (config.systemPrompt.isNotBlank()) body.put("system", config.systemPrompt)
                HttpRequestSpec(ProviderKind.ANTHROPIC.defaultEndpoint, mapOf(
                    "x-api-key" to apiKey, "anthropic-version" to "2023-06-01",
                    "Content-Type" to "application/json", "Accept" to "application/json",
                ), body.toString())
            }
        }
    }

    class Parsed(val text: String, val truncated: Boolean)

    fun parse(kind: ProviderKind, result: HttpResult): Parsed {
        if (result.status !in 200..299) throw httpError(kind, result)
        val json = try { JSONObject(result.body) } catch (_: Exception) { throw AiException("Resposta do provedor não é JSON válido", result.status) }
        val (text, truncated) = when (kind) {
            ProviderKind.OPENAI, ProviderKind.OPENAI_COMPATIBLE -> {
                val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: throw AiException("Resposta sem 'choices'")
                val content = choice.optJSONObject("message")?.opt("content")
                val text = when (content) {
                    is String -> content
                    is JSONArray -> (0 until content.length()).mapNotNull { content.optJSONObject(it)?.optString("text") }.joinToString("")
                    else -> ""
                }
                text to (choice.optString("finish_reason") == "length")
            }
            ProviderKind.GEMINI -> {
                val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
                if (candidate == null) {
                    val blocked = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                    throw AiException(if (blocked.isNotEmpty()) "Pergunta bloqueada pelo filtro do Gemini ($blocked)" else "Resposta sem candidatos")
                }
                val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                val text = (0 until parts.length()).mapNotNull { i ->
                    parts.optJSONObject(i)?.takeIf { !it.optBoolean("thought", false) }?.optString("text")
                }.joinToString("")
                text to (candidate.optString("finishReason") == "MAX_TOKENS")
            }
            ProviderKind.ANTHROPIC -> {
                val content = json.optJSONArray("content") ?: JSONArray()
                val text = (0 until content.length()).mapNotNull { i ->
                    content.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.optString("text")
                }.joinToString("")
                text to (json.optString("stop_reason") == "max_tokens")
            }
        }
        if (text.isBlank()) {
            throw AiException(if (truncated) "Limite de tokens atingido antes da resposta; aumente o limite de tokens" else "O provedor retornou uma resposta vazia")
        }
        return Parsed(text.trim(), truncated)
    }

    private fun httpError(kind: ProviderKind, result: HttpResult): AiException {
        val detail = try {
            val json = JSONObject(result.body)
            val error = json.optJSONObject("error")
            (error?.optString("message") ?: json.optString("message")).orEmpty()
        } catch (_: Exception) { "" }
        val keyProblem = Regex("(?i)api[ _-]?key|x-api-key|invalid authentication|unauthenticated").containsMatchIn(detail)
        val summary = when (result.status) {
            400 -> if (keyProblem) "Chave de API inválida" else "Requisição recusada (verifique modelo e parâmetros)"
            401 -> "Chave de API inválida"
            403 -> "Chave sem permissão para este modelo/serviço"
            404 -> "Modelo ou endpoint não encontrado"
            408, 504 -> "Tempo esgotado no provedor"
            413 -> "Requisição grande demais"
            429 -> "Limite de uso ou cota excedido"
            in 500..599 -> "Erro temporário no servidor do provedor"
            else -> "Falha HTTP"
        }
        val safe = Redactor.redact(detail).replace(Regex("\\s+"), " ").take(160)
        return AiException("${kind.label}: $summary (HTTP ${result.status})" + if (safe.isNotEmpty()) " · $safe" else "", result.status)
    }
}
