package org.turboio.customai.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.turboio.customai.diag.DiagnosticLog

/**
 * Android's built-in recognizer on the PHONE microphone (no API key). Partial results are forwarded so
 * they can be mirrored on the lens. All calls are marshalled to the main thread as the API requires.
 */
class PhoneRecognizer(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(language: String, onPartial: (String) -> Unit, onResult: (String?, String?) -> Unit) { main.post { startNow(language, onPartial, onResult) } }

    private fun startNow(language: String, onPartial: (String) -> Unit, onResult: (String?, String?) -> Unit) {
        cancelNow()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { onResult(null, "Reconhecimento de voz do Android indisponível"); return }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        var delivered = false
        fun deliver(text: String?, error: String?) { if (!delivered) { delivered = true; onResult(text, error) } }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { DiagnosticLog.i("STT", "microfone do telefone pronto ($language)") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                DiagnosticLog.w("STT", "SpeechRecognizer erro $error")
                deliver(null, describe(error))
            }
            override fun onResults(results: Bundle?) {
                deliver(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty(), null)
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) onPartial(it) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try { r.startListening(intent) } catch (e: Exception) { deliver(null, "Falha ao iniciar reconhecimento: ${e.javaClass.simpleName}") }
    }

    fun cancel() { main.post { cancelNow() } }

    private fun cancelNow() {
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.cancel() }; runCatching { r.destroy() }
    }

    private fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não entendi. Tente falar novamente."
        SpeechRecognizer.ERROR_AUDIO -> "Erro de áudio no microfone"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permissão de microfone negada"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sem rede para o reconhecimento de voz"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconhecedor ocupado; tente de novo"
        else -> "Erro no reconhecimento de voz ($code)"
    }
}
