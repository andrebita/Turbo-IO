package org.turboio.customai.ambient

import io.github.jaredmdobson.concentus.OpusDecoder
import org.turboio.customai.diag.DiagnosticLog
import java.io.File
import java.util.UUID

/**
 * Receives Opus batches from a source, decodes them only to measure speech (online VAD), and writes the
 * ORIGINAL frames to ≈5 min segments that are always cut at a pause (≥ [cutPauseMs] after [targetMs];
 * forced at [hardMaxMs]). Sealed segments are handed to [onSealed] for planning/transcription.
 * Not thread-safe: call from one thread.
 */
class AmbientIngest(
    private val root: File,
    private val source: AudioSource,
    private val onSealed: (File, SegmentManifest) -> Unit,
    private val targetMs: Long = 5 * 60_000,
    private val hardMaxMs: Long = 7 * 60_000,
    private val cutPauseMs: Long = 400,
    private val trustDeviceVad: Boolean = false,
    private val now: () -> Long = System::currentTimeMillis,
) : FrameSink {
    private var writer: SegmentWriter? = null
    private var decoder: OpusDecoder? = null
    private var decoderChannels = 0
    private val vad = OnlineVad()
    private var quietMs = 0L
    private val pcm = ShortArray(5760 * 2)
    var batches = 0L; private set
    var framesTotal = 0L; private set

    override fun frames(batch: OpusBatch) {
        batches++
        // Capture time of the first frame in this batch (live: received now minus batch duration).
        val firstWall = batch.receivedAtWall - batch.frames.size.toLong() * batch.frameMs
        writer?.let { if (it.manifest.channels != batch.channels || it.manifest.frameMs != batch.frameMs) seal("format-change") }
        var w: SegmentWriter = writer ?: open(firstWall, batch)
        w.anchor(firstWall)
        for ((i, frame) in batch.frames.withIndex()) {
            var flag = 0; var level = 0
            try {
                val samples = decode(frame, batch.channels)
                val mono = downmix(samples, batch.channels)
                val r = rms(mono)
                level = r.toInt()
                if (vad.feed(r, batch.frameMs)) flag = flag or FrameFlags.SPEECH
            } catch (_: Exception) { flag = flag or FrameFlags.CORRUPT }
            if (batch.deviceVad?.getOrNull(i) == true) { flag = flag or FrameFlags.DEVICE_VAD; if (trustDeviceVad) flag = flag or FrameFlags.SPEECH }
            if (batch.wearerVoice?.getOrNull(i) == true) flag = flag or FrameFlags.WEARER
            w.append(frame, flag, level)
            framesTotal++
            quietMs = if (flag and FrameFlags.SPEECH != 0) 0 else quietMs + batch.frameMs
            val length = w.manifest.frames.toLong() * batch.frameMs
            if ((length >= targetMs && quietMs >= cutPauseMs) || length >= hardMaxMs) {
                seal(if (length >= hardMaxMs) "hard-max" else "pause")
                w = open(firstWall + (i + 1).toLong() * batch.frameMs, batch)
            }
        }
        w.persist()
    }

    override fun gap(reason: String) {
        DiagnosticLog.i("AMBIENTE", "descontinuidade: $reason")
        seal("gap:$reason")
    }

    fun seal(reason: String) {
        val w = writer ?: return
        writer = null
        if (w.manifest.frames == 0) { w.seal(reason, now()); w.dir.deleteRecursively(); return }
        w.seal(reason, w.manifest.wallAt(w.manifest.frames))
        DiagnosticLog.i("AMBIENTE", "segmento selado (${w.manifest.frames * w.manifest.frameMs / 1000} s, fala ${w.manifest.speechFrames * w.manifest.frameMs / 1000} s, $reason)")
        onSealed(w.dir, w.manifest)
    }

    private fun open(wall: Long, batch: OpusBatch): SegmentWriter {
        val id = "seg-" + wall + "-" + UUID.randomUUID().toString().take(8)
        val dir = File(root, id).apply { mkdirs() }
        val m = SegmentManifest(id, source.id, batch.channels, batch.frameMs, source.providesWearer, source.headMounted, wall)
        return SegmentWriter(dir, m).also { writer = it; quietMs = 0 }
    }

    private fun decode(frame: ByteArray, channels: Int): ShortArray {
        if (decoder == null || decoderChannels != channels) { decoder = OpusDecoder(16000, channels); decoderChannels = channels }
        val n = decoder!!.decode(frame, 0, frame.size, pcm, 0, pcm.size / channels, false)
        return pcm.copyOf(n * channels)
    }
}
