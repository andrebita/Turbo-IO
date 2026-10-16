package org.turboio.customai

import android.app.Application
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.turboio.customai.ai.AiAnswer
import org.turboio.customai.ai.AiClient
import org.turboio.customai.ai.AiException
import org.turboio.customai.ai.ChatMessage
import org.turboio.customai.ble.AndroidBondManager
import org.turboio.customai.ble.DeviceInfo
import org.turboio.customai.ble.GattLink
import org.turboio.customai.ble.GlassesSession
import org.turboio.customai.ble.HandlerScheduler
import org.turboio.customai.ble.Phase
import org.turboio.customai.ble.PhoneIdentity
import org.turboio.customai.ble.PrefsPeerStore
import org.turboio.customai.ble.SecureRandomness
import org.turboio.customai.ble.SessionStatus
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.display.DisplayText
import org.turboio.customai.display.LensDisplay
import org.turboio.customai.service.ConnectionService
import org.turboio.customai.stt.GoogleSpeechTranscriber
import org.turboio.customai.stt.OpenAiTranscriber
import org.turboio.customai.stt.PhoneRecognizer
import org.turboio.customai.stt.SttEngine
import org.turboio.customai.stt.SttException
import org.turboio.customai.voice.SpeechInput
import org.turboio.customai.voice.VoiceAssistant
import kotlin.coroutines.resume

/** Application-wide object graph. Everything that touches the session runs on one HandlerThread. */
class TurboApp : Application() {
    lateinit var settings: SettingsRepository; private set
    lateinit var session: GlassesSession; private set
    lateinit var display: LensDisplay; private set
    lateinit var voice: VoiceAssistant; private set
    lateinit var scope: CoroutineScope; private set
    val ai = AiClient()
    private val history = ArrayList<ChatMessage>()
    private val _status = MutableStateFlow(SessionStatus())
    val status: StateFlow<SessionStatus> = _status

    override fun onCreate() {
        super.onCreate()
        instance = this
        DiagnosticLog.sink = { level, tag, message ->
            when (level) {
                DiagnosticLog.Level.ERROR -> Log.e("TurboIO/$tag", message)
                DiagnosticLog.Level.WARN -> Log.w("TurboIO/$tag", message)
                else -> Log.i("TurboIO/$tag", message)
            }
        }
        settings = SettingsRepository(this)
        val scheduler = HandlerScheduler("turboio-glasses")
        scope = CoroutineScope(SupervisorJob() + scheduler.handler.asCoroutineDispatcher())
        val random = SecureRandomness()
        val identity = PhoneIdentity(this)
        session = GlassesSession(
            scheduler = scheduler,
            linkFactory = { GattLink(this, scheduler) },
            bond = AndroidBondManager(this),
            random = random,
            identity = { identity.get() },
            device = DeviceInfo("Turbo IO", Build.MANUFACTURER ?: "Android", Build.MODEL ?: "Android"),
            store = PrefsPeerStore(this),
        )
        display = LensDisplay(session, scope, random)
        voice = VoiceAssistant(session, scope, random, ::speechInput, { q -> askAi(q).text }, settings::voiceSettings, { !display.active })
        var wasReady = false
        session.onStatus = { s ->
            _status.value = s
            val ready = s.phase == Phase.READY
            if (ready != wasReady) { wasReady = ready; voice.connectionChanged(ready) }
        }
        DiagnosticLog.i("APP", "Turbo IO Custom AI ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    fun connectSaved(): Boolean {
        val ok = session.reconnectSaved()
        if (ok && settings.keepConnected) ConnectionService.start(this)
        return ok
    }

    fun connect(address: String, name: String) {
        session.connect(address, name)
        if (settings.keepConnected) ConnectionService.start(this)
    }

    fun disconnect() { session.disconnect(); ConnectionService.stop(this) }

    /** Shared by the test screen and voice turns. Keeps a short history for follow-up questions. */
    suspend fun askAi(question: String): AiAnswer {
        val config = settings.aiConfig()
        val key = settings.apiKey(config.provider)
        val snapshot = synchronized(history) { history.toList() }
        val effective = if (DisplayText.wantsDetail(question)) config.copy(maxTokens = maxOf(config.maxTokens, 1500)) else config
        val result = ai.ask(effective, key, snapshot, question)
        synchronized(history) {
            history.add(ChatMessage("user", question.take(4000)))
            history.add(ChatMessage("assistant", result.text.take(8000)))
            while (history.size > 20) history.removeAt(0)
        }
        return result
    }

    fun clearHistory() = synchronized(history) { history.clear() }

    private fun speechInput(): SpeechInput {
        val config = settings.sttConfig()
        return when (config.engine) {
            SttEngine.PHONE_ANDROID -> object : SpeechInput {
                private val recognizer = PhoneRecognizer(this@TurboApp)
                override val usesGlassesMic = false
                override suspend fun listenOnPhone(onPartial: (String) -> Unit): String = suspendCancellableCoroutine { cont ->
                    recognizer.start(config.language, onPartial) { text, error ->
                        scope.launch { if (cont.isActive) { if (error != null) cont.resumeWith(Result.failure(SttException(error))) else cont.resume(text ?: "") } }
                    }
                    cont.invokeOnCancellation { recognizer.cancel() }
                }
                override fun cancel() = recognizer.cancel()
            }
            SttEngine.GLASSES_OPENAI, SttEngine.GLASSES_GOOGLE -> object : SpeechInput {
                override val usesGlassesMic = true
                override suspend fun transcribe(pcm: ByteArray): String {
                    val key = settings.sttKey(config.engine)
                    val transcriber = if (config.engine == SttEngine.GLASSES_OPENAI) OpenAiTranscriber(config, key) else GoogleSpeechTranscriber(config, key)
                    return transcriber.transcribe(pcm)
                }
            }
        }
    }

    companion object {
        lateinit var instance: TurboApp; private set
        fun friendly(e: Throwable): String = when (e) {
            is AiException, is SttException -> e.message ?: "Erro"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
