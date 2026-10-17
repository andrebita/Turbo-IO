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
    GLASSES_GOOGLE("google", "Microfone dos óculos → Google Cloud Speech-to-Text");

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
