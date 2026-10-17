package org.turboio.customai.probe

import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusPacketInfo
import org.json.JSONArray
import org.json.JSONObject
import org.turboio.customai.ambient.OnlineVad
import org.turboio.customai.ambient.rms
import java.io.ByteArrayOutputStream

/** One received envelope, as captured (audio kept only in [binary]). */
class ProbeRecord(val atMs: Long, val business: Int, val type: Int, val json: String, val binary: ByteArray)

/**
 * Turns raw probe captures into answers for the validation gates:
 *  G2 audio format (Opus TOC, channels, frame size, decodability at 16 kHz),
 *  G3 mask semantics (vadMask vs phone VAD, vpuMask vs channel energy),
 *  G4 continuity (frames/s, gaps, realtime vs cached batches).
 */
object ProbeAnalysis {
    private const val FRAME = 240

    /** Opus TOC (RFC 6716 §3.1). */
    data class Toc(val config: Int, val stereo: Boolean, val code: Int) {
        val mode: String get() = when (config) { in 0..11 -> "SILK"; in 12..15 -> "HYBRID"; else -> "CELT" }
    }
    fun toc(b: Byte): Toc { val v = b.toInt() and 0xff; return Toc(v ushr 3, (v ushr 2) and 1 == 1, v and 3) }

    /** Splits a binary payload into frames: fixed 240 B when it divides evenly, else one packet. */
    fun frames(binary: ByteArray): List<ByteArray> =
        if (binary.isNotEmpty() && binary.size % FRAME == 0) (0 until binary.size / FRAME).map { binary.copyOfRange(it * FRAME, (it + 1) * FRAME) }
        else if (binary.isNotEmpty()) listOf(binary) else emptyList()

    /** Bit i of an integer mask, or element i of an array mask. */
    fun maskBit(json: JSONObject, key: String, i: Int): Boolean? = when (val v = json.opt(key)) {
        is Number -> (v.toLong() ushr i) and 1L == 1L
        is JSONArray -> v.opt(i)?.let { it == true || (it as? Number)?.toInt() == 1 }
        is String -> v.getOrNull(i)?.let { it == '1' }
        else -> null
    }

    class Result(val report: JSONObject, val pcm: ByteArray, val channels: Int)

    fun analyze(records: List<ProbeRecord>, audioTypes: Set<Pair<Int, Int>>): Result {
        val report = JSONObject()
        report.put("records", records.size)
        report.put("byType", JSONObject().apply { records.groupBy { "${it.business}/${it.type}" }.forEach { (k, v) -> put(k, v.size) } })
        // Every JSON key seen per business/type with one example value (audio excluded) — documents the wire.
        val keys = JSONObject()
        records.groupBy { "${it.business}/${it.type}" }.forEach { (k, v) ->
            val o = JSONObject()
            v.forEach { r -> runCatching { JSONObject(r.json) }.getOrNull()?.let { j -> j.keys().forEach { key -> if (!o.has(key)) o.put(key, j.opt(key).toString().take(120)) } } }
            keys.put(k, o)
        }
        report.put("jsonKeys", keys)

        val audio = records.filter { (it.business to it.type) in audioTypes && it.binary.isNotEmpty() }
        val allFrames = audio.flatMap { r -> frames(r.binary).map { r to it } }
        report.put("audioRecords", audio.size).put("audioFrames", allFrames.size)
        if (allFrames.isEmpty()) return Result(report.put("verdict", "sem áudio recebido"), ByteArray(0), 1)

        val sizes = allFrames.groupingBy { it.second.size }.eachCount()
        report.put("frameSizes", JSONObject(sizes.mapKeys { it.key.toString() }))
        val tocs = allFrames.map { toc(it.second[0]) }
        report.put("tocConfigs", JSONObject(tocs.groupingBy { "${it.config}/${it.mode}/${if (it.stereo) "estéreo" else "mono"}/c${it.code}" }.eachCount()))
        val durations = allFrames.mapNotNull { runCatching { OpusPacketInfo.getNumSamples(it.second, 0, it.second.size, 48000) / 48 }.getOrNull() }
        report.put("frameDurationsMs", JSONObject(durations.groupingBy { it.toString() }.eachCount()))

        // Decode at 16 kHz with the channel count announced by the TOC; per-channel energy; phone-side VAD.
        val stereo = tocs.count { it.stereo } * 2 > tocs.size
        val channels = if (stereo) 2 else 1
        val decoder = OpusDecoder(16000, channels)
        val pcm = ShortArray(5760 * 2)
        val wav = ByteArrayOutputStream()
        var ok = 0; var failed = 0
        var left = 0.0; var right = 0.0
        val vad = OnlineVad()
        val phoneVad = ArrayList<Boolean>(); val frameEnergy = ArrayList<Double>()
        for ((_, f) in allFrames) {
            try {
                val n = decoder.decode(f, 0, f.size, pcm, 0, pcm.size / channels, false)
                ok++
                val mono = ShortArray(n) { if (channels == 2) ((pcm[2 * it] + pcm[2 * it + 1]) / 2).toShort() else pcm[it] }
                if (channels == 2) {
                    left += rms(ShortArray(n) { pcm[2 * it] }); right += rms(ShortArray(n) { pcm[2 * it + 1] })
                }
                val e = rms(mono); frameEnergy.add(e); phoneVad.add(vad.feed(e, 20))
                for (i in 0 until n * channels) { wav.write(pcm[i].toInt() and 0xff); wav.write((pcm[i].toInt() shr 8) and 0xff) }
            } catch (_: Exception) { failed++; phoneVad.add(false); frameEnergy.add(0.0) }
        }
        report.put("decode", JSONObject().put("channels", channels).put("ok", ok).put("failed", failed)
            .put("meanRmsLeft", left / maxOf(1, ok)).put("meanRmsRight", right / maxOf(1, ok))
            .put("speechFramesPhoneVad", phoneVad.count { it }))

        // Masks (G3): compare with INSTANTANEOUS frame energy (no hangover) against a noise-derived threshold,
        // otherwise the VAD hangover would count as disagreement after every utterance.
        val sorted = frameEnergy.sorted()
        val noiseFloor = sorted.getOrElse(sorted.size / 5) { 0.0 }
        val threshold = maxOf(450.0, noiseFloor * 3)
        val energyActive = frameEnergy.map { it > threshold }
        var idx = 0; var vadAgree = 0; var vadTotal = 0; var vpuOn = 0; var vpuEnergy = 0.0; var otherEnergy = 0.0; var otherN = 0
        for (r in audio) {
            val j = runCatching { JSONObject(r.json) }.getOrNull()
            val count = frames(r.binary).size
            for (i in 0 until count) {
                val g = idx + i
                if (j != null && g < phoneVad.size) {
                    maskBit(j, "vadMask", i)?.let { vadTotal++; if (it == energyActive[g]) vadAgree++ }
                    maskBit(j, "vpuMask", i)?.let { if (it) { vpuOn++; vpuEnergy += frameEnergy[g] } else { otherN++; otherEnergy += frameEnergy[g] } }
                }
            }
            idx += count
        }
        report.put("masks", JSONObject().put("energyThreshold", threshold).put("vadCompared", vadTotal).put("vadAgreementPct", if (vadTotal > 0) 100 * vadAgree / vadTotal else -1)
            .put("vpuOnFrames", vpuOn).put("vpuMeanRms", if (vpuOn > 0) vpuEnergy / vpuOn else -1.0)
            .put("nonVpuMeanRms", if (otherN > 0) otherEnergy / otherN else -1.0))

        // Continuity: frames per wall second while receiving, largest gap between audio envelopes.
        val first = audio.first().atMs; val last = audio.last().atMs
        val spanS = maxOf(1.0, (last - first) / 1000.0)
        val gaps = audio.zipWithNext { a, b -> b.atMs - a.atMs }
        report.put("continuity", JSONObject().put("seconds", spanS).put("framesPerSecond", allFrames.size / spanS)
            .put("expectedFramesPerSecond", 50).put("maxGapMs", gaps.maxOrNull() ?: 0)
            .put("audioDurationMs", allFrames.size * (durations.firstOrNull() ?: 20)))
        val verdict = when {
            failed > ok -> "áudio NÃO decodifica como Opus — formato diferente do esperado"
            sizes.keys.all { it == FRAME } && ok > 0 -> "quadros Opus de 240 B decodificam (${if (stereo) "estéreo" else "mono"}); ouça o WAV para confirmar a fala"
            ok > 0 -> "Opus decodifica, mas os quadros não têm 240 B fixos"
            else -> "indeterminado"
        }
        return Result(report.put("verdict", verdict), wav.toByteArray(), channels)
    }

    fun wav(pcm: ByteArray, channels: Int): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun i32(v: Int) { for (k in 0 until 4) out.write((v ushr (8 * k)) and 0xff) }
        fun i16(v: Int) { out.write(v and 0xff); out.write((v ushr 8) and 0xff) }
        out.write("RIFF".toByteArray()); i32(36 + pcm.size); out.write("WAVEfmt ".toByteArray()); i32(16); i16(1); i16(channels)
        i32(16000); i32(16000 * 2 * channels); i16(2 * channels); i16(16); out.write("data".toByteArray()); i32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }
}
