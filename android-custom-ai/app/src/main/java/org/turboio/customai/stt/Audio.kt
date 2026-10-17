package org.turboio.customai.stt

import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusPacketInfo
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Decodes the glasses' live microphone packets: raw Opus, 16 kHz mono, one packet per AI_ASSISTANT(13)
 * type-3 envelope (same profile as harmony-sdk AudioNapi.cpp, which used libopus). Pure Java (Concentus).
 */
class GlassesOpusDecoder {
    private var decoder = OpusDecoder(SAMPLE_RATE, 1)
    private val pcm = ShortArray(1920)

    fun reset() { decoder = OpusDecoder(SAMPLE_RATE, 1) }

    /** Returns little-endian PCM16 mono samples; throws on malformed packets (same bounds as the reference). */
    fun decode(packet: ByteArray): ShortArray {
        if (packet.isEmpty() || packet.size > 4096) throw IllegalArgumentException("opus-input")
        val expected = OpusPacketInfo.getNumSamples(packet, 0, packet.size, SAMPLE_RATE)
        if (expected <= 0 || expected > 1920 || expected % 160 != 0) throw IllegalArgumentException("opus-frame-size")
        val samples = decoder.decode(packet, 0, packet.size, pcm, 0, 1920, false)
        if (samples != expected) throw IllegalArgumentException("opus-decode-failed")
        return pcm.copyOf(samples)
    }

    companion object { const val SAMPLE_RATE = 16000 }
}

/** Bounded 16 kHz mono PCM accumulator. */
class PcmBuffer(private val maxSeconds: Int = 30) {
    private val out = ByteArrayOutputStream()
    val durationMs: Long get() = out.size() / 32L
    fun append(samples: ShortArray) {
        if (out.size() + samples.size * 2 > maxSeconds * 32000) return
        for (s in samples) { out.write(s.toInt() and 0xff); out.write((s.toInt() shr 8) and 0xff) }
    }
    fun bytes(): ByteArray = out.toByteArray()
    fun clear() { out.reset() }
}

/**
 * Simple energy end-pointer for push-free voice turns. Speech starts when the 20 ms frame RMS exceeds
 * an adaptive threshold; the turn ends after [silenceMs] of quiet following speech, or at [maxMs].
 */
class EndPointer(
    private val silenceMs: Int = 1200,
    private val maxMs: Int = 15000,
    private val noSpeechMs: Int = 7000,
    private val minThreshold: Double = 450.0,
) {
    enum class State { WAITING, SPEAKING, DONE_SPEECH, DONE_NO_SPEECH, DONE_MAX }
    var state = State.WAITING; private set
    private var elapsed = 0
    private var quiet = 0
    private var noise = minThreshold / 3
    private var spoken = 0
    /** Highest 20 ms frame RMS seen; lets the caller still try STT on very quiet microphones. */
    var peakRms = 0.0; private set

    fun reset() { state = State.WAITING; elapsed = 0; quiet = 0; noise = minThreshold / 3; spoken = 0; peakRms = 0.0 }
    val finished: Boolean get() = state == State.DONE_SPEECH || state == State.DONE_NO_SPEECH || state == State.DONE_MAX

    fun feed(samples: ShortArray): State {
        var i = 0
        while (i < samples.size && !finished) {
            val n = minOf(320, samples.size - i)
            var sum = 0.0
            for (k in i until i + n) sum += samples[k].toDouble() * samples[k]
            val rms = sqrt(sum / n)
            val ms = n / 16
            elapsed += ms
            if (rms > peakRms) peakRms = rms
            // Noise floor adapts only on quiet frames, so speech at the very start is not learnt as noise.
            val threshold = maxOf(minThreshold, noise * 3.0)
            if (rms > threshold) {
                spoken += ms; quiet = 0
                if (spoken >= 60) state = State.SPEAKING
            } else {
                if (state == State.WAITING) noise = noise * 0.95 + rms * 0.05
                quiet += ms
            }
            if (state == State.SPEAKING && quiet >= silenceMs) state = State.DONE_SPEECH
            else if (state == State.WAITING && elapsed >= noSpeechMs) state = State.DONE_NO_SPEECH
            else if (elapsed >= maxMs) state = if (state == State.SPEAKING) State.DONE_MAX else State.DONE_NO_SPEECH
            i += n
        }
        return state
    }
}

object Wav {
    fun pcm16Mono(pcm: ByteArray, sampleRate: Int = 16000): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun int(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff); out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff) }
        fun short(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        out.write("RIFF".toByteArray()); int(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(sampleRate); int(sampleRate * 2); short(2); short(16)
        out.write("data".toByteArray()); int(pcm.size); out.write(pcm)
        return out.toByteArray()
    }
}
