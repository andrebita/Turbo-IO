package org.turboio.customai.ambient

import kotlin.math.sqrt

/**
 * A batch of Opus frames from any audio source. Glasses sources deliver 240-byte stereo 20 ms frames
 * (format to be confirmed on hardware); the phone microphone delivers mono Opus encoded by the app.
 */
class OpusBatch(
    val frames: List<ByteArray>,
    val channels: Int,
    val frameMs: Int = 20,
    val receivedAtWall: Long,
    val deviceVad: BooleanArray? = null,     // glasses vadMask (semantics to validate)
    val wearerVoice: BooleanArray? = null,   // glasses vpuMask (hypothesis: wearer's own voice)
    val cached: Boolean = false,             // delivered from an offline cache (capture time ≠ receive time)
) {
    init { require(channels == 1 || channels == 2) { "channels" }; require(frameMs in setOf(10, 20, 40, 60)) { "frameMs" } }
}

interface FrameSink {
    fun frames(batch: OpusBatch)
    fun gap(reason: String)
}

interface AudioSource {
    val id: String
    /** True when the source can tell the wearer's own voice apart (e.g. glasses VPU). */
    val providesWearer: Boolean
    /** True when the microphones sit at the wearer's head (energy can hint who is speaking). */
    val headMounted: Boolean
    fun start(sink: FrameSink)
    fun stop()
}

/** Per-frame flags stored next to the audio. */
object FrameFlags {
    const val SPEECH = 1        // phone-side VAD (with hangover)
    const val DEVICE_VAD = 2    // glasses vadMask
    const val WEARER = 4        // glasses vpuMask
    const val CORRUPT = 8       // undecodable frame (kept for timing)
}

fun rms(samples: ShortArray, from: Int = 0, to: Int = samples.size): Double {
    if (to <= from) return 0.0
    var sum = 0.0
    for (i in from until to) sum += samples[i].toDouble() * samples[i]
    return sqrt(sum / (to - from))
}

/** Equal-weight downmix (one glasses channel can be almost silent — docs/ARCHITECTURE.md §4). */
fun downmix(interleaved: ShortArray, channels: Int): ShortArray {
    if (channels == 1) return interleaved
    return ShortArray(interleaved.size / 2) { ((interleaved[2 * it].toInt() + interleaved[2 * it + 1].toInt()) / 2).toShort() }
}

/**
 * Continuous energy VAD for always-on capture: adaptive noise floor learnt only on quiet frames,
 * onset after [onsetMs] above threshold, hangover of [hangoverMs] so short pauses stay "speech".
 */
class OnlineVad(
    private val minThreshold: Double = 450.0,
    private val ratio: Double = 3.0,
    private val onsetMs: Int = 60,
    private val hangoverMs: Int = 600,
) {
    private var noise = minThreshold / ratio
    private var above = 0
    private var hang = 0
    var speaking = false; private set

    fun feed(frameRms: Double, frameMs: Int): Boolean {
        val threshold = maxOf(minThreshold, noise * ratio)
        if (frameRms > threshold) {
            above += frameMs
            if (above >= onsetMs) { speaking = true; hang = hangoverMs }
        } else {
            above = 0
            noise = noise * 0.97 + frameRms * 0.03
            if (speaking) { hang -= frameMs; if (hang <= 0) speaking = false }
        }
        return speaking
    }

    fun reset() { noise = minThreshold / ratio; above = 0; hang = 0; speaking = false }
}

/** A piece of the original timeline included in a chunk: frames [frameStart, frameEnd) placed at [chunkStartMs]. */
data class Piece(val chunkStartMs: Long, val frameStart: Int, val frameEnd: Int)

/** One transcription request: pieces of one segment, concatenated with short separators. */
data class ChunkPlan(val pieces: List<Piece>, val frameMs: Int, val forcedCut: Boolean) {
    /** Billable audio length of the assembled chunk. */
    val audioMs: Long get() = pieces.lastOrNull()?.let { it.chunkStartMs + (it.frameEnd - it.frameStart).toLong() * frameMs } ?: 0
}

/**
 * Chunking rules. Deepgram: up to 5 min of speech per request (better diarization, fewer boundaries).
 * Whisper-style APIs: 30–60 s chunks around their 30 s window.
 */
data class ChunkPolicy(
    val maxSpeechMs: Long,            // flush when the next span would exceed this
    val splitSearchFromMs: Long,      // when one span is too long, cut at the longest pause after this point
    val overlapMs: Long = 1000,       // overlap on a forced cut inside continuous speech
    val mergeGapMs: Long = 1500,      // speech runs closer than this are one span
    val padMs: Long = 300,            // pre/post roll around speech
    val minSpeechMs: Long = 800,      // spans with less real speech are dropped (coughs, clicks)
    val separatorMs: Long = 400,      // silence inserted between spans inside one chunk
) {
    companion object {
        val DEEPGRAM = ChunkPolicy(maxSpeechMs = 300_000, splitSearchFromMs = 180_000)
        val WHISPER = ChunkPolicy(maxSpeechMs = 60_000, splitSearchFromMs = 30_000)
    }
}

data class Word(val startMs: Long, val endMs: Long, val text: String, val speaker: Int?)

/** Transcriber output for one chunk, times relative to the chunk audio. */
data class ChunkTranscript(val words: List<Word>, val plainText: String, val provider: String, val model: String)
