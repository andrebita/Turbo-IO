package org.turboio.customai.stt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.turboio.customai.ai.readNBytesCompat
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.diag.Redactor
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64

enum class SttEngine(val id: String, val label: String) {
    PHONE_ANDROID("android", "Android nativo · microfone do telefone (sem chave)"),
    GLASSES_OPENAI("openai", "Microfone dos óculos → OpenAI (Whisper / gpt-4o-transcribe)"),
    GLASSES_GOOGLE("google", "Microfone dos óculos → Google Cloud Speech-to-Text"),
    GLASSES_DEEPGRAM("deepgram", "Microfone dos óculos → Deepgram (usa a chave do ambiente)"),
    GLASSES_GEMINI("gemini", "Microfone dos óculos → Gemini (usa a chave do Gemini da IA)");

    val usesGlassesMic: Boolean get() = this != PHONE_ANDROID
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: PHONE_ANDROID }
}

data class SttConfig(
    val engine: SttEngine = SttEngine.PHONE_ANDROID,
    val language: String = "pt-BR",
    val openAiModel: String = "whisper-1",
    val openAiEndpoint: String = "https://api.openai.com/v1/audio/transcriptions",
    val silenceMs: Int = 1200,
    val maxSeconds: Int = 15,
)

class SttException(message: String) : Exception(message)

interface BatchTranscriber {
    /** [pcm] is 16 kHz mono little-endian PCM16. Returns the transcript (may be empty). */
    suspend fun transcribe(pcm: ByteArray): String
}

private fun checkHttps(url: String) {
    val uri = try { URI(url) } catch (_: Exception) { throw SttException("Endpoint de transcrição inválido") }
    if (!"https".equals(uri.scheme, true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null) {
        throw SttException("Endpoint de transcrição deve ser HTTPS, sem parâmetros")
    }
}

private suspend fun <T> timed(seconds: Int, block: () -> T): T = try {
    withTimeout(seconds * 1000L) { runInterruptible(Dispatchers.IO) { block() } }
} catch (_: TimeoutCancellationException) {
    throw SttException("Tempo esgotado na transcrição")
} catch (e: IOException) {
    throw SttException("Falha de rede na transcrição (${e.javaClass.simpleName})")
}

private fun error(service: String, status: Int, body: String): SttException {
    val detail = try { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() } catch (_: Exception) { "" }
    if (detail.contains("API keys are not supported", ignoreCase = true))
        return SttException("$service: esta chave não serve para esta API (chave do Gemini/AI Studio?). Use uma chave de API do Google Cloud " +
            "com o Speech-to-Text ativado, ou escolha “Microfone dos óculos → Deepgram” (HTTP $status)")
    val summary = when (status) { 401 -> "chave inválida"; 403 -> "sem permissão (ative a API)"; 429 -> "cota excedida"; else -> "erro" }
    return SttException("$service: $summary (HTTP $status)" + Redactor.redact(detail).take(120).let { if (it.isNotEmpty()) " · $it" else "" })
}

/** OpenAI /v1/audio/transcriptions (multipart WAV). Also works with compatible servers (e.g. Groq). */
class OpenAiTranscriber(private val config: SttConfig, private val apiKey: String, private val timeoutSeconds: Int = 30) : BatchTranscriber {
    override suspend fun transcribe(pcm: ByteArray): String {
        if (apiKey.isBlank()) throw SttException("Informe a chave da API de transcrição")
        checkHttps(config.openAiEndpoint)
        val boundary = "----turboio" + System.nanoTime()
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("model", config.openAiModel)
        field("language", config.language.substringBefore('-'))
        field("response_format", "json")
        body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fala.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        body.write(Wav.pcm16Mono(pcm)); body.write("\r\n--$boundary--\r\n".toByteArray())
        val started = System.currentTimeMillis()
        val (status, text) = timed(timeoutSeconds) {
            val c = URL(config.openAiEndpoint).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = timeoutSeconds * 1000
                c.instanceFollowRedirects = false
                c.setRequestProperty("Authorization", "Bearer $apiKey")
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                code to String((if (code >= 400) c.errorStream else c.inputStream)?.use { it.readNBytesCompat(1 shl 20) } ?: ByteArray(0))
            } finally { c.disconnect() }
        }
        DiagnosticLog.i("STT", "OpenAI HTTP $status em ${System.currentTimeMillis() - started} ms (${pcm.size / 32} ms de áudio)")
        if (status !in 200..299) throw error("Transcrição OpenAI", status, text)
        return JSONObject(text).optString("text").trim()
    }
}

/** Deepgram pre-recorded /v1/listen (Nova-3, pt-BR) with the utterance as WAV; same key as the ambient transcription. */
class DeepgramSpeechTranscriber(private val config: SttConfig, private val apiKey: String, private val timeoutSeconds: Int = 30) : BatchTranscriber {
    override suspend fun transcribe(pcm: ByteArray): String {
        if (apiKey.isBlank()) throw SttException("Informe a chave da Deepgram (aba IA → Transcrição do ambiente)")
        val started = System.currentTimeMillis()
        val (status, text) = timed(timeoutSeconds) {
            val c = URL("https://api.deepgram.com/v1/listen?model=nova-3&language=${config.language}&smart_format=true&punctuate=true").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = timeoutSeconds * 1000
                c.instanceFollowRedirects = false
                c.setRequestProperty("Authorization", "Token $apiKey")
                c.setRequestProperty("Content-Type", "audio/wav")
                c.outputStream.use { it.write(wav(pcm)) }
                val code = c.responseCode
                code to String((if (code >= 400) c.errorStream else c.inputStream)?.use { it.readNBytesCompat(1 shl 20) } ?: ByteArray(0))
            } finally { c.disconnect() }
        }
        DiagnosticLog.i("STT", "Deepgram HTTP $status em ${System.currentTimeMillis() - started} ms")
        if (status !in 200..299) {
            val detail = runCatching { JSONObject(text).optString("err_msg").ifEmpty { JSONObject(text).optString("message") } }.getOrDefault("")
            throw error("Deepgram", status, JSONObject().put("error", JSONObject().put("message", detail)).toString())
        }
        return parseDeepgram(text)
    }

    companion object {
        fun parseDeepgram(body: String): String = JSONObject(body).optJSONObject("results")?.optJSONArray("channels")?.optJSONObject(0)
            ?.optJSONArray("alternatives")?.optJSONObject(0)?.optString("transcript")?.trim().orEmpty()

        /** 16 kHz mono PCM16 with a RIFF/WAVE header. */
        fun wav(pcm: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(pcm.size + 44)
            fun i32(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff); out.write(v shr 16 and 0xff); out.write(v shr 24 and 0xff) }
            fun i16(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff) }
            out.write("RIFF".toByteArray()); i32(36 + pcm.size); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(16000); i32(32000); i16(2); i16(16)
            out.write("data".toByteArray()); i32(pcm.size); out.write(pcm)
            return out.toByteArray()
        }
    }
}

/** Gemini generateContent with the utterance as inline WAV audio; same key and model as the AI provider. */
class GeminiSpeechTranscriber(private val config: SttConfig, private val apiKey: String, private val model: String, private val timeoutSeconds: Int = 30) : BatchTranscriber {
    override suspend fun transcribe(pcm: ByteArray): String {
        if (apiKey.isBlank()) throw SttException("Informe a chave do Gemini na aba IA (provedor Google Gemini)")
        if (!Regex("^[A-Za-z0-9._-]{1,80}$").matches(model)) throw SttException("Modelo do Gemini inválido")
        val body = JSONObject().put("contents", org.json.JSONArray().put(JSONObject().put("role", "user").put("parts", org.json.JSONArray()
            .put(JSONObject().put("text", PROMPT.format(config.language)))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav")
                .put("data", Base64.getEncoder().encodeToString(DeepgramSpeechTranscriber.wav(pcm))))))))
            .put("generationConfig", JSONObject().put("temperature", 0).put("maxOutputTokens", 400)).toString()
        val started = System.currentTimeMillis()
        val (status, text) = timed(timeoutSeconds) {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = timeoutSeconds * 1000
                c.instanceFollowRedirects = false
                c.setRequestProperty("x-goog-api-key", apiKey)
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                code to String((if (code >= 400) c.errorStream else c.inputStream)?.use { it.readNBytesCompat(1 shl 20) } ?: ByteArray(0))
            } finally { c.disconnect() }
        }
        DiagnosticLog.i("STT", "Gemini HTTP $status em ${System.currentTimeMillis() - started} ms")
        if (status !in 200..299) throw error("Gemini", status, text)
        return parseGemini(text)
    }

    companion object {
        private const val PROMPT = "Transcreva exatamente o que é dito neste áudio (idioma: %s). Responda somente com a transcrição, sem comentários. " +
            "Se não houver fala, responda com uma linha vazia."

        fun parseGemini(body: String): String {
            val parts = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
            return (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.takeIf { p -> !p.optBoolean("thought") }?.optString("text") }
                .joinToString(" ").trim()
        }
    }
}

/** Google Cloud Speech-to-Text v1 speech:recognize (synchronous, ≤ 60 s), API key in header. */
class GoogleSpeechTranscriber(private val config: SttConfig, private val apiKey: String, private val timeoutSeconds: Int = 30) : BatchTranscriber {
    override suspend fun transcribe(pcm: ByteArray): String {
        if (apiKey.isBlank()) throw SttException("Informe a chave do Google Cloud Speech-to-Text")
        val payload = JSONObject()
            .put("config", JSONObject().put("encoding", "LINEAR16").put("sampleRateHertz", 16000)
                .put("languageCode", config.language).put("enableAutomaticPunctuation", true))
            .put("audio", JSONObject().put("content", Base64.getEncoder().encodeToString(pcm)))
            .toString()
        val started = System.currentTimeMillis()
        val (status, text) = timed(timeoutSeconds) {
            val c = URL("https://speech.googleapis.com/v1/speech:recognize").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = timeoutSeconds * 1000
                c.instanceFollowRedirects = false
                c.setRequestProperty("x-goog-api-key", apiKey)
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(payload.toByteArray()) }
                val code = c.responseCode
                code to String((if (code >= 400) c.errorStream else c.inputStream)?.use { it.readNBytesCompat(1 shl 20) } ?: ByteArray(0))
            } finally { c.disconnect() }
        }
        DiagnosticLog.i("STT", "Google HTTP $status em ${System.currentTimeMillis() - started} ms")
        if (status !in 200..299) throw error("Google Speech", status, text)
        return parseGoogle(text)
    }

    companion object {
        fun parseGoogle(body: String): String {
            val results = JSONObject(body).optJSONArray("results") ?: return ""
            return (0 until results.length()).mapNotNull {
                results.optJSONObject(it)?.optJSONArray("alternatives")?.optJSONObject(0)?.optString("transcript")
            }.joinToString(" ").trim()
        }
    }
}
