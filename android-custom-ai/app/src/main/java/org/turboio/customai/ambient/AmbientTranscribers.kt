package org.turboio.customai.ambient

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
import java.net.URL

enum class AmbientProvider(val id: String, val label: String, val policy: ChunkPolicy) {
    DEEPGRAM("deepgram", "Deepgram Nova-3 (separa falantes)", ChunkPolicy.DEEPGRAM),
    OPENAI("openai", "OpenAI (sem separação de falantes)", ChunkPolicy.WHISPER);
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: DEEPGRAM }
}

/** [permanent] = configuration/credential problem: stop the queue instead of retrying forever. */
class TranscriptionException(message: String, val permanent: Boolean) : Exception(message)

interface AmbientTranscriber {
    suspend fun transcribe(oggOpus: ByteArray, audioMs: Long): ChunkTranscript
}

/** Thin HTTP seam so request/response handling is unit-testable. */
fun interface RawHttp { fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int): Pair<Int, String> }

val urlConnectionHttp = RawHttp { url, headers, body, timeoutMs ->
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = timeoutMs
        c.instanceFollowRedirects = false
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body) }
        val code = c.responseCode
        code to String((if (code >= 400) c.errorStream else c.inputStream)?.use { it.readNBytesCompat(4 shl 20) } ?: ByteArray(0))
    } finally { c.disconnect() }
}

private suspend fun call(http: RawHttp, url: String, headers: Map<String, String>, body: ByteArray, audioMs: Long): Pair<Int, String> {
    // Generous deadline: providers process roughly real time / 10 or faster; uploads may be on mobile data.
    val timeout = (60_000 + audioMs / 2).coerceAtMost(600_000)
    return try {
        withTimeout(timeout) { runInterruptible(Dispatchers.IO) { http.post(url, headers, body, timeout.toInt()) } }
    } catch (_: TimeoutCancellationException) { throw TranscriptionException("Tempo esgotado na transcrição", permanent = false) }
    catch (e: IOException) { throw TranscriptionException("Falha de rede na transcrição (${e.javaClass.simpleName})", permanent = false) }
}

private fun failure(service: String, status: Int, body: String): TranscriptionException {
    val detail = try { JSONObject(body).let { o -> o.optString("err_msg").ifEmpty { o.optJSONObject("error")?.optString("message").orEmpty() } } } catch (_: Exception) { "" }
    val permanent = status in setOf(400, 401, 402, 403, 404, 413, 415)
    val summary = when (status) { 401 -> "chave inválida"; 402 -> "sem créditos"; 403 -> "sem permissão"; 429 -> "limite de uso"; else -> "erro" }
    return TranscriptionException("$service: $summary (HTTP $status)" + Redactor.redact(detail).take(140).let { if (it.isNotEmpty()) " · $it" else "" }, permanent)
}

/** Deepgram pre-recorded API with diarization (speaker per word) and smart formatting, pt-BR. */
class DeepgramTranscriber(private val apiKey: String, private val model: String = "nova-3", private val language: String = "pt-BR",
                          private val http: RawHttp = urlConnectionHttp) : AmbientTranscriber {
    fun url(): String = "https://api.deepgram.com/v1/listen?model=$model&language=$language" +
        "&diarize=true&utterances=true&smart_format=true&punctuate=true"

    override suspend fun transcribe(oggOpus: ByteArray, audioMs: Long): ChunkTranscript {
        if (apiKey.isBlank()) throw TranscriptionException("Informe a chave da Deepgram", permanent = true)
        val started = System.currentTimeMillis()
        val (status, body) = call(http, url(), mapOf("Authorization" to "Token $apiKey", "Content-Type" to "audio/ogg"), oggOpus, audioMs)
        DiagnosticLog.i("AMBIENTE", "Deepgram HTTP $status em ${System.currentTimeMillis() - started} ms (${audioMs / 1000} s de áudio)")
        if (status !in 200..299) throw failure("Deepgram", status, body)
        return parse(body, model)
    }

    companion object {
        fun parse(body: String, model: String = "nova-3"): ChunkTranscript {
            val alt = JSONObject(body).optJSONObject("results")?.optJSONArray("channels")?.optJSONObject(0)
                ?.optJSONArray("alternatives")?.optJSONObject(0) ?: return ChunkTranscript(emptyList(), "", "deepgram", model)
            val words = alt.optJSONArray("words")
            val list = (0 until (words?.length() ?: 0)).mapNotNull { i ->
                val w = words!!.getJSONObject(i)
                val text = w.optString("punctuated_word").ifEmpty { w.optString("word") }
                if (text.isBlank()) null else Word((w.optDouble("start") * 1000).toLong(), (w.optDouble("end") * 1000).toLong(), text,
                    if (w.has("speaker")) w.optInt("speaker") else null)
            }
            return ChunkTranscript(list, alt.optString("transcript"), "deepgram", model)
        }
    }
}

/**
 * OpenAI /v1/audio/transcriptions with Ogg Opus. whisper-1 returns segment times (verbose_json);
 * gpt-4o-*-transcribe models return text only (one block spanning the chunk). No diarization.
 */
class OpenAiAmbientTranscriber(private val apiKey: String, private val model: String = "gpt-4o-mini-transcribe",
                               private val http: RawHttp = urlConnectionHttp) : AmbientTranscriber {
    override suspend fun transcribe(oggOpus: ByteArray, audioMs: Long): ChunkTranscript {
        if (apiKey.isBlank()) throw TranscriptionException("Informe a chave da OpenAI", permanent = true)
        val verbose = model.startsWith("whisper")
        val boundary = "----turboio" + System.nanoTime()
        val body = ByteArrayOutputStream()
        fun field(k: String, v: String) = body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n".toByteArray())
        field("model", model); field("language", "pt"); field("response_format", if (verbose) "verbose_json" else "json")
        body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"trecho.ogg\"\r\nContent-Type: audio/ogg\r\n\r\n".toByteArray())
        body.write(oggOpus); body.write("\r\n--$boundary--\r\n".toByteArray())
        val (status, text) = call(http, "https://api.openai.com/v1/audio/transcriptions",
            mapOf("Authorization" to "Bearer $apiKey", "Content-Type" to "multipart/form-data; boundary=$boundary"), body.toByteArray(), audioMs)
        DiagnosticLog.i("AMBIENTE", "OpenAI transcrição HTTP $status (${audioMs / 1000} s de áudio)")
        if (status !in 200..299) throw failure("OpenAI", status, text)
        return parse(text, model, audioMs)
    }

    companion object {
        fun parse(body: String, model: String, audioMs: Long): ChunkTranscript {
            val o = JSONObject(body)
            val segments = o.optJSONArray("segments")
            val words = if (segments != null && segments.length() > 0) (0 until segments.length()).map { i ->
                val s = segments.getJSONObject(i)
                Word((s.optDouble("start") * 1000).toLong(), (s.optDouble("end") * 1000).toLong(), s.optString("text").trim(), null)
            }.filter { it.text.isNotEmpty() }
            else o.optString("text").trim().takeIf { it.isNotEmpty() }?.let { listOf(Word(0, audioMs, it, null)) } ?: emptyList()
            return ChunkTranscript(words, o.optString("text").trim(), "openai", model)
        }
    }
}
