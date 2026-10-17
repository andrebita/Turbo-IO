package org.turboio.customai.ambient

import io.github.jaredmdobson.concentus.OpusPacketInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.turboio.customai.ble.BusinessListener
import org.turboio.customai.ble.GlassesPort
import org.turboio.customai.ble.Randomness
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.display.sendEnvelope
import org.turboio.customai.probe.LifeLogWire
import org.turboio.customai.probe.PendingFlag
import org.turboio.customai.protocol.Business
import org.turboio.customai.protocol.Envelope
import org.turboio.customai.protocol.Hex

/**
 * The glasses' own microphones through the firmware LifeLog channel (phase F4). Format measured on hardware
 * (docs/TESTES.md, 2026-10-17): 240-byte SILK wideband 60 ms mono Opus. With `inRealtimePage:true` the glasses send
 * one frame per envelope (~16.7/s, no loss); with `false` they send ~20 s cached bursts and drop part of the cache
 * when switched off, so [realtime] is the default. vadMask/vpuMask arrive per frame (bit i = frame i), about 1 s
 * late with hangover; vpuMask follows the wearer's voice at utterance level.
 *
 * The owner forwards LAUNCHER and AI_ASSISTANT envelopes to [onBusiness] (the port has no listener removal).
 */
class LifeLogSource(
    private val port: GlassesPort,
    private val scope: CoroutineScope,
    private val pending: PendingFlag,
    private val random: Randomness,
    private val realtime: Boolean = true,
    /** The glasses already started LifeLog from their menu and sent A1: accept that task instead of switching on. */
    private val startedOnGlasses: Boolean = false,
    /** Leave the "settings complete" guide flag on at stop, so the glasses menu keeps working. */
    private val keepGuide: () -> Boolean = { false },
    private val onEnded: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : AudioSource, BusinessListener {
    override val id = "glasses_lifelog"
    override val providesWearer = true
    override val headMounted = true

    private var sink: FrameSink? = null
    @Volatile var running = false; private set
    /** OFF sent, confirmation not yet received: the owner keeps forwarding launcher traffic until it arrives. */
    @Volatile var stopping = false; private set
    private var taskId = ""
    private var a2Sent = false
    private var anchorWall = -1L
    private var framesSoFar = 0L
    private var frameMs = 60
    private var channels = 1
    private var lastEnvelopeAt = 0L
    private val buffer = ArrayList<ByteArray>()
    private val vad = ArrayList<Boolean>()
    private val vpu = ArrayList<Boolean>()
    private var bufferCached = false
    var framesReceived = 0L; private set

    @Synchronized override fun start(sink: FrameSink) {
        this.sink = sink
        running = true; stopping = false; a2Sent = false; anchorWall = -1L; framesSoFar = 0
        taskId = Hex.encode(random.bytes(16))
        pending.set(true)                                    // before anything reaches the glasses
        scope.launch {
            if (startedOnGlasses) acceptTask()
            else {
                send(Business.LAUNCHER, 20, LifeLogWire.launcher("life_log_guide", true))
                send(Business.LAUNCHER, 20, LifeLogWire.launcher("life_log_switch", true))
            }
        }
    }

    @Synchronized override fun stop() {
        if (!running) return
        running = false; stopping = true
        flush()
        scope.launch { switchOff() }
    }

    private suspend fun acceptTask() {
        if (a2Sent) return
        a2Sent = true
        send(Business.AI_ASSISTANT, 162, LifeLogWire.a2(taskId))
        send(Business.AI_ASSISTANT, 168, LifeLogWire.a8(realtime))
    }

    private suspend fun switchOff() {
        send(Business.AI_ASSISTANT, 166, LifeLogWire.A6)
        send(Business.LAUNCHER, 20, LifeLogWire.launcher("life_log_switch", false))
        if (!keepGuide()) send(Business.LAUNCHER, 20, LifeLogWire.launcher("life_log_guide", false))
    }

    private suspend fun send(business: Int, type: Int, json: String) {
        port.sendEnvelope(business, type, json)?.let { DiagnosticLog.w("AMBIENTE", "envio $business/$type falhou: $it") }
    }

    @Synchronized override fun onBusiness(business: Int, envelope: Envelope) {
        if (business == Business.LAUNCHER) { launcher(envelope); return }
        if (business != Business.AI_ASSISTANT || envelope.type !in 161..168) return
        when (envelope.type) {
            161 -> if (running && !a2Sent) { if (anchorWall < 0) anchorWall = clock(); scope.launch { acceptTask() } }
            163, 164 -> if (running) audio(envelope)
            165 -> if (running) ended("óculos encerraram o LifeLog")
        }
    }

    private fun launcher(envelope: Envelope) {
        val body = runCatching { JSONObject(envelope.jsonText()) }.getOrNull() ?: return
        val cmd = body.optString("cmd")
        if (!cmd.startsWith("life_log_")) return
        val value = body.optJSONObject("payload")?.let { if (it.has("value")) it.optInt("value") else null }
        when {
            stopping && value == 0 && (cmd == "life_log_switch" || cmd == "life_log_switch_result") -> {
                stopping = false; pending.set(false)
                DiagnosticLog.i("AMBIENTE", "LifeLog desligado (confirmado pelos óculos)")
            }
            running && cmd == "life_log_switch" && value == 0 -> { pending.set(false); ended("LifeLog desligado nos óculos") }
            running && !startedOnGlasses && cmd == "life_log_switch_result" && value != null && value != 0 ->
                ended("óculos recusaram: ${LifeLogWire.SWITCH_ERRORS[value] ?: "código $value"}")
        }
    }

    private fun ended(reason: String) {
        if (!running) return
        DiagnosticLog.i("AMBIENTE", reason)
        running = false; stopping = true
        flush()
        scope.launch { switchOff() }                        // harmless if already off; guarantees the OFF state
        onEnded(reason)
    }

    private fun audio(envelope: Envelope) {
        val bin = envelope.binary
        if (bin.isEmpty()) return
        val json = runCatching { JSONObject(envelope.jsonText()) }.getOrNull()
        val frames = if (bin.size % FRAME == 0) (0 until bin.size / FRAME).map { bin.copyOfRange(it * FRAME, (it + 1) * FRAME) } else listOf(bin)
        val first = frames[0]
        val ms = runCatching { OpusPacketInfo.getNumSamples(first, 0, first.size, 48000) / 48 }.getOrDefault(frameMs)
        val ch = if ((first[0].toInt() ushr 2) and 1 == 1) 2 else 1
        val cached = json?.optInt("mode", 0) == 1
        if ((ms != frameMs || ch != channels || cached != bufferCached) && buffer.isNotEmpty()) flush()
        if (ms in setOf(10, 20, 40, 60)) frameMs = ms
        channels = ch; bufferCached = cached
        val now = clock()
        // Realtime stream interrupted (Bluetooth loss): close the segment at the hole instead of shifting time.
        if (!cached && lastEnvelopeAt > 0 && now - lastEnvelopeAt > GAP_MS) { flush(); sink?.gap("lifelog-pausa"); anchorWall = -1L; framesSoFar = 0 }
        lastEnvelopeAt = now
        for ((i, f) in frames.withIndex()) {
            buffer.add(f)
            vad.add(json?.let { bit(it, "vadMask", i) } ?: false)
            vpu.add(json?.let { bit(it, "vpuMask", i) } ?: false)
        }
        framesReceived += frames.size
        if (cached || buffer.size * frameMs >= BATCH_MS) flush()
    }

    private fun bit(json: JSONObject, key: String, i: Int): Boolean = (json.optLong(key, 0L) ushr i) and 1L == 1L

    /** Hands buffered frames to the sink, timed from the task start (capture time, also for cached bursts). */
    private fun flush() {
        if (buffer.isEmpty()) return
        val n = buffer.size
        val now = clock()
        if (anchorWall < 0) anchorWall = now - n.toLong() * frameMs
        var end = anchorWall + (framesSoFar + n) * frameMs
        if (end > now + 500 || (!bufferCached && now - end > DRIFT_MS)) {   // re-anchor on clock drift
            anchorWall = now - (framesSoFar + n) * frameMs; end = now
        }
        sink?.frames(OpusBatch(ArrayList(buffer), channels, frameMs, end, vad.toBooleanArray(), vpu.toBooleanArray(), bufferCached))
        framesSoFar += n
        buffer.clear(); vad.clear(); vpu.clear()
    }

    companion object {
        private const val FRAME = 240
        private const val BATCH_MS = 1000
        private const val GAP_MS = 3000L
        private const val DRIFT_MS = 3000L
    }
}
