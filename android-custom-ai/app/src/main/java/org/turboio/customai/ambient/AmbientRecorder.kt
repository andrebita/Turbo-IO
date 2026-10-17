package org.turboio.customai.ambient

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.turboio.customai.TurboApp
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.service.ConnectionService
import java.io.File
import java.util.concurrent.TimeUnit

/** Phone microphone as an ambient source: 16 kHz mono PCM → Opus 20 ms (Concentus) → 1 s batches. */
@SuppressLint("MissingPermission")
class PhoneMicSource : AudioSource {
    override val id = "phone_mic"
    override val providesWearer = false
    override val headMounted = false
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start(sink: FrameSink) {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16000))
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); throw IllegalStateException("Microfone indisponível") }
        running = true
        thread = Thread({
            val encoder = OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply { setBitrate(24000) }
            val frame = ShortArray(320); val out = ByteArray(1275)
            val batch = ArrayList<ByteArray>(50)
            try {
                record.startRecording()
                while (running) {
                    var read = 0
                    while (read < 320 && running) { val n = record.read(frame, read, 320 - read); if (n < 0) throw IllegalStateException("read $n"); read += n }
                    if (!running) break
                    batch.add(out.copyOf(encoder.encode(frame, 0, 320, out, 0, out.size)))
                    if (batch.size == 50) { sink.frames(OpusBatch(ArrayList(batch), 1, 20, System.currentTimeMillis())); batch.clear() }
                }
                if (batch.isNotEmpty()) sink.frames(OpusBatch(ArrayList(batch), 1, 20, System.currentTimeMillis()))
            } catch (e: Exception) {
                DiagnosticLog.w("AMBIENTE", "microfone parou: ${e.javaClass.simpleName}")
                sink.gap("mic-error")
            } finally { runCatching { record.stop() }; record.release() }
        }, "turboio-phone-mic").apply { start() }
    }

    override fun stop() { running = false; thread?.join(2000); thread = null }
}

data class AmbientStatus(
    val recording: Boolean = false,
    val source: String = "",
    val startedAt: Long = 0,
    val capturedMs: Long = 0,
    val segments: Int = 0,
    val counts: AmbientStore.Counts = AmbientStore.Counts(0, 0, 0, 0, 0),
    val note: String = "Gravação ambiente desligada",
    val error: String = "",
)

/**
 * Owns the ambient pipeline: one ingest thread, the current source, the durable queue and the worker.
 * Raw audio lives in app-private storage and is deleted after transcription (default).
 */
class AmbientRecorder(private val app: TurboApp) {
    val root = File(app.filesDir, "ambient").apply { mkdirs() }
    private val thread = HandlerThread("turboio-ambient").apply { start() }
    private val handler = Handler(thread.looper)
    val store: AmbientStore by lazy { AmbientStore(app.sqlDb) }
    val processor: AmbientProcessor by lazy {
        AmbientProcessor(store, app.journal, { app.settings.ambientSettings().provider }, ::transcriber, { app.settings.ambientSettings().deleteRaw })
    }
    private val _status = MutableStateFlow(AmbientStatus())
    val status: StateFlow<AmbientStatus> = _status
    private var source: AudioSource? = null
    private var ingest: AmbientIngest? = null
    private var segments = 0

    private fun transcriber(providerId: String): AmbientTranscriber {
        val s = app.settings.ambientSettings()
        return when (AmbientProvider.of(providerId)) {
            AmbientProvider.DEEPGRAM -> DeepgramTranscriber(app.settings.deepgramKey())
            AmbientProvider.OPENAI -> OpenAiAmbientTranscriber(app.settings.ambientOpenAiKey(), s.openAiModel)
        }
    }

    fun startPhoneMic(): String? {
        if (_status.value.recording) return "Já está gravando"
        if (!app.settings.ambientSettings().consent) return "Aceite o aviso de consentimento primeiro"
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "Permita o uso do microfone"
        if (storageBytes() > 1_500L * 1024 * 1024) return "Mais de 1,5 GB de áudio aguardando transcrição; transcreva antes de gravar mais"
        ConnectionService.start(app)   // foreground service (microphone type) keeps capture alive with the screen off
        val src = PhoneMicSource()
        val sink = threadedSink()
        ingest = AmbientIngest(root, src, { dir, m -> segments++; processor.onSealed(dir, m); enqueue(app); refresh() })
        return try {
            src.start(sink); source = src
            _status.value = _status.value.copy(recording = true, source = src.id, startedAt = System.currentTimeMillis(),
                note = "Gravando pelo microfone do telefone", error = "")
            DiagnosticLog.i("AMBIENTE", "gravação ambiente iniciada (microfone do telefone)")
            null
        } catch (e: Exception) { ingest = null; e.message ?: "Falha ao iniciar o microfone" }
    }

    fun stop() {
        val src = source ?: return
        source = null
        src.stop()
        handler.post { ingest?.seal("stop"); ingest = null; refresh() }
        _status.value = _status.value.copy(recording = false, note = "Gravação parada; transcrição em fila")
        DiagnosticLog.i("AMBIENTE", "gravação ambiente parada")
        if (!app.settings.keepConnected || !app.session.isReady) ConnectionService.stop(app)
    }

    /** All sink calls are serialized on the ingest thread. */
    private fun threadedSink() = object : FrameSink {
        override fun frames(batch: OpusBatch) { handler.post { ingest?.frames(batch); _status.value = _status.value.copy(capturedMs = (ingest?.framesTotal ?: 0) * batch.frameMs) } }
        override fun gap(reason: String) { handler.post { ingest?.gap(reason) } }
    }

    fun refresh() {
        handler.post {
            val counts = runCatching { store.counts() }.getOrNull() ?: return@post
            val error = runCatching { store.lastError() }.getOrDefault("")
            _status.value = _status.value.copy(segments = segments, counts = counts, error = if (counts.failed > 0 || counts.pending > 0) error else "")
        }
    }

    fun retryFailed() { handler.post { store.requeueFailed(); enqueue(app); refresh() } }

    fun storageBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun wipe() { handler.post { runCatching { store.wipe() }; root.listFiles()?.forEach { it.deleteRecursively() }; refresh() } }

    companion object {
        private const val WORK = "ambient-transcription"
        fun enqueue(app: TurboApp) {
            val wifi = app.settings.ambientSettings().wifiOnly
            val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TurboApp
        return when (val outcome = app.ambient.processor.processPending()) {
            is AmbientProcessor.Outcome.Done -> { app.ambient.refresh(); Result.success() }
            is AmbientProcessor.Outcome.Retry -> { app.ambient.refresh(); if (runAttemptCount < 10) Result.retry() else Result.failure() }
            is AmbientProcessor.Outcome.Stopped -> { app.ambient.refresh(); DiagnosticLog.w("AMBIENTE", "fila pausada: ${outcome.message}"); Result.failure() }
        }
    }
}
