package org.turboio.customai.probe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.turboio.customai.ble.BusinessListener
import org.turboio.customai.ble.GlassesPort
import org.turboio.customai.ble.Randomness
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.display.sendEnvelope
import org.turboio.customai.protocol.Business
import org.turboio.customai.protocol.Envelope
import org.turboio.customai.protocol.Hex
import org.turboio.customai.protocol.Json

/** Persistent "LifeLog may still be ON on the glasses" flag (survives crashes; cleared only on confirmed OFF). */
interface PendingFlag { fun get(): Boolean; fun set(value: Boolean) }

/** Where a finished probe is written (files + exportable package). */
interface ProbeSink { fun save(kind: String, records: List<ProbeRecord>, analysis: ProbeAnalysis.Result, events: List<String>): String }

/** Wire documents for the LifeLog channel, as used by the iOS V1 probe (apps/RayNeoCompanion/AlwaysOnLocalProbe.swift). */
object LifeLogWire {
    fun launcher(command: String, enabled: Boolean): String {
        require(command == "life_log_guide" || command == "life_log_switch")
        val data = if (command == "life_log_switch" && enabled) "{\"delay\":2}" else ""
        return "{\"cmd\":\"$command\",\"payload\":{\"value\":${if (enabled) 1 else 0},\"mode\":0,\"data\":${Json.quote(data)}}}"
    }
    fun a2(taskId: String) = "{\"taskId\":${Json.quote(taskId)},\"idleTimeoutSec\":30}"
    const val A6 = "{\"rc\":2}"
    fun a8(realtimePage: Boolean) = "{\"inRealtimePage\":$realtimePage}"
    val SWITCH_ERRORS = mapOf(1 to "óculos sem permissão de microfone", 2 to "abra as hastes dos óculos", 3 to "bateria dos óculos baixa")
}

data class ProbeState(
    val kind: String = "",
    val phase: String = "inativo",
    val note: String = "Nenhum teste em andamento",
    val secondsLeft: Int = 0,
    val records: Int = 0,
    val audioBytes: Long = 0,
    val offConfirmed: Boolean = false,
    val pendingOff: Boolean = false,
    val verdict: String = "",
    val savedAt: String = "",
    val events: List<String> = emptyList(),
)

/**
 * Bounded, raw-capture validation probes for the glasses audio channels (phase 3, gates G1–G5 and G14).
 * Nothing is interpreted while capturing; [ProbeAnalysis] runs afterwards. LifeLog is always switched off at the
 * end, and on the next connection if a previous OFF was not confirmed.
 */
class HardwareProbe(
    private val port: GlassesPort,
    private val scope: CoroutineScope,
    private val pending: PendingFlag,
    private val random: Randomness,
    private val sink: ProbeSink,
    private val clock: () -> Long = System::currentTimeMillis,
) : BusinessListener {
    private val _state = MutableStateFlow(ProbeState(pendingOff = pending.get()))
    val state: StateFlow<ProbeState> = _state
    private var kind = ""
    private var active = false
    private var stopping = false
    private var a2Sent = false
    private var taskId = ""
    private var recUuid = ""
    private var recCompleted = false
    private var startedAt = 0L
    private val records = ArrayList<ProbeRecord>()
    private val events = ArrayList<String>()
    private var bytes = 0L
    private var job: Job? = null

    init { port.addBusinessListener(this) }

    val busy: Boolean get() = active

    private fun event(text: String) {
        val t = (clock() - startedAt) / 100 / 10.0
        events.add("+${t}s $text"); if (events.size > 400) events.removeAt(0)
        DiagnosticLog.i("SONDA", text)
        _state.value = _state.value.copy(events = events.takeLast(60), records = records.size, audioBytes = bytes, note = text)
    }

    private suspend fun send(business: Int, type: Int, json: String): Boolean {
        val error = port.sendEnvelope(business, type, json)
        event("→ $business/$type ${json.take(90)}" + (error?.let { " ✗ $it" } ?: ""))
        return error == null
    }

    private fun begin(k: String): String? {
        if (active) return "Já há um teste em andamento"
        if (!port.isReady) return "Conecte os óculos primeiro"
        kind = k; active = true; stopping = false; a2Sent = false; recUuid = ""; recCompleted = false
        records.clear(); events.clear(); bytes = 0
        startedAt = clock()
        _state.value = ProbeState(kind = k, phase = "iniciando", pendingOff = pending.get())
        return null
    }

    // ------------------------------------------------------------------ LifeLog (G1–G5)
    fun startLifeLog(seconds: Int): String? {
        begin("lifelog")?.let { return it }
        taskId = Hex.encode(random.bytes(16))
        pending.set(true)                                   // before anything reaches the glasses
        job = scope.launch {
            _state.value = _state.value.copy(phase = "ligando LifeLog", pendingOff = true)
            send(Business.LAUNCHER, 20, LifeLogWire.launcher("life_log_guide", true))
            send(Business.LAUNCHER, 20, LifeLogWire.launcher("life_log_switch", true))
            countdown(seconds)
            stop("tempo do teste concluído")
        }
        return null
    }

    // ------------------------------------------------------------------ glasses-menu recording (G14)
    fun startRecording14(seconds: Int): String? {
        begin("recording14")?.let { return it }
        job = scope.launch {
            send(Business.RECORDING, 14, "{\"isAppForeground\":true}")
            _state.value = _state.value.copy(phase = "aguardando", note = "Inicie uma gravação pelo menu dos óculos (até 60 s)")
            var waited = 0
            while (recUuid.isEmpty() && waited < 60 && active) { delay(1000); waited++ }
            if (recUuid.isEmpty()) { finish("nenhuma gravação iniciada nos óculos em 60 s"); return@launch }
            countdown(seconds)
            stop("tempo do teste concluído")
        }
        return null
    }

    private suspend fun countdown(seconds: Int) {
        var left = seconds
        while (left > 0 && active && !stopping) {
            _state.value = _state.value.copy(phase = "capturando", secondsLeft = left, records = records.size, audioBytes = bytes)
            delay(1000); left--
        }
    }

    fun stop(reason: String = "parado pelo usuário") {
        if (!active || stopping) return
        stopping = true
        scope.launch {
            event("parando: $reason")
            if (kind == "lifelog") sendLifeLogOff() else if (recUuid.isNotEmpty()) send(Business.RECORDING, 4, "{\"uuid\":${Json.quote(recUuid)},\"action\":1,\"code\":2}")
            _state.value = _state.value.copy(phase = "recebendo últimos pacotes")
            var waited = 0
            val done = { if (kind == "lifelog") _state.value.offConfirmed else recCompleted || recUuid.isEmpty() }
            while (waited < 10 && !done()) { delay(1000); waited++ }
            finish(reason)
        }
    }

    private suspend fun sendLifeLogOff() {
        // Each command independently: a failure of one must not suppress the others.
        send(Business.AI_ASSISTANT, 166, LifeLogWire.A6)
        send(Business.LAUNCHER, 20, LifeLogWire.launcher("life_log_switch", false))
        send(Business.LAUNCHER, 20, LifeLogWire.launcher("life_log_guide", false))
    }

    /** Safety: called when the glasses become ready; re-sends OFF if a previous one was not confirmed (G5). */
    fun connectionReady() {
        if (!pending.get() || active) return
        scope.launch {
            DiagnosticLog.w("SONDA", "desligamento do LifeLog pendente; reenviando OFF")
            kind = "lifelog-off"; active = true; stopping = true; startedAt = clock(); records.clear(); events.clear()
            _state.value = _state.value.copy(phase = "desligando LifeLog pendente", pendingOff = true)
            sendLifeLogOff()
            var waited = 0
            while (waited < 10 && !_state.value.offConfirmed) { delay(1000); waited++ }
            active = false; stopping = false
            _state.value = _state.value.copy(phase = "inativo", note = if (_state.value.offConfirmed) "LifeLog desligado (confirmado)" else
                "OFF reenviado sem confirmação; verifique os óculos e tente \"Desligar LifeLog\" de novo")
        }
    }

    /** Manual safety button. */
    fun forceLifeLogOff() { if (!active) { pending.set(true); connectionReady() } }

    private fun finish(reason: String) {
        if (!active) return
        val audioTypes = if (kind == "lifelog") setOf(13 to 163, 13 to 164) else setOf(14 to 3)
        val ordered = if (kind == "recording14") orderedRecording() else records.toList()
        val analysis = ProbeAnalysis.analyze(ordered, audioTypes)
        analysis.report.put("kind", kind).put("reason", reason).put("durationMs", clock() - startedAt)
            .put("offConfirmed", _state.value.offConfirmed).put("a2Sent", a2Sent)
        if (kind == "recording14") analysis.report.put("coverage", coverage())
        val path = runCatching { sink.save(kind, ordered, analysis, events.toList()) }.getOrElse { "falha ao salvar: ${it.javaClass.simpleName}" }
        active = false; stopping = false; job?.cancel()
        _state.value = _state.value.copy(phase = "concluído", secondsLeft = 0, verdict = analysis.report.optString("verdict"),
            savedAt = path, note = "Teste concluído: $reason", pendingOff = pending.get(), events = events.takeLast(60))
        DiagnosticLog.i("SONDA", "concluído ($kind): ${analysis.report.optString("verdict")}")
    }

    // ------------------------------------------------------------------ receive path
    override fun onBusiness(business: Int, envelope: Envelope) {
        if (business != Business.AI_ASSISTANT && business != Business.LAUNCHER && business != Business.RECORDING) return
        val json = envelope.jsonText()
        val body = runCatching { JSONObject(json) }.getOrNull()
        val lifeLogLauncher = business == Business.LAUNCHER && body?.optString("cmd")?.startsWith("life_log_") == true
        if (lifeLogLauncher) handleSwitch(body!!)              // OFF confirmations also count outside a probe
        val relevant = (business == Business.AI_ASSISTANT && envelope.type in 161..168) || lifeLogLauncher || business == Business.RECORDING
        if (!active || !relevant) return
        if (bytes + envelope.binary.size > 40L * 1024 * 1024) { stop("limite de 40 MB atingido"); return }
        records.add(ProbeRecord(clock() - startedAt, business, envelope.type, json, envelope.binary.copyOf()))
        bytes += envelope.binary.size
        if (envelope.binary.isEmpty() || records.size % 50 == 1) event("← $business/${envelope.type} ${json.take(100)}${if (envelope.binary.isNotEmpty()) " +${envelope.binary.size}B" else ""}")
        scope.launch {
            when {
                business == Business.AI_ASSISTANT && envelope.type == 161 && kind == "lifelog" && !a2Sent && !stopping -> {
                    a2Sent = true
                    send(Business.AI_ASSISTANT, 162, LifeLogWire.a2(taskId))
                    send(Business.AI_ASSISTANT, 168, LifeLogWire.a8(false))
                }
                business == Business.AI_ASSISTANT && envelope.type == 165 && kind == "lifelog" -> stop("óculos encerraram (A5)")
                business == Business.RECORDING && kind == "recording14" -> handleRecording(envelope.type, body)
            }
        }
    }

    private fun handleSwitch(body: JSONObject) {
        val cmd = body.optString("cmd")
        val value = body.optJSONObject("payload")?.let { if (it.has("value")) it.optInt("value") else null }
        if (stopping && value == 0 && (cmd == "life_log_switch" || cmd == "life_log_switch_result")) {
            pending.set(false)
            _state.value = _state.value.copy(offConfirmed = true, pendingOff = false)
            DiagnosticLog.i("SONDA", "LifeLog OFF confirmado pelos óculos ($cmd)")
        }
        if (active && !stopping && kind == "lifelog" && cmd == "life_log_switch_result" && value != null && value != 0) {
            event("óculos recusaram: ${LifeLogWire.SWITCH_ERRORS[value] ?: "código $value"}")
            stop("óculos recusaram o LifeLog")
        }
    }

    private suspend fun handleRecording(type: Int, body: JSONObject?) {
        val uuid = body?.optString("uuid").orEmpty()
        if (!Regex("^[A-Za-z0-9-]{1,64}$").matches(uuid)) return
        val action = body!!.optInt("action", 0)
        when {
            type == 1 && action == 1 -> {
                if (recUuid.isEmpty()) recUuid = uuid
                if (uuid == recUuid) send(Business.RECORDING, 1, "{\"uuid\":${Json.quote(uuid)},\"action\":2,\"mode\":1,\"code\":1,\"type\":1}")
            }
            uuid != recUuid -> return
            type == 4 && action == 1 -> send(Business.RECORDING, 4, "{\"uuid\":${Json.quote(uuid)},\"action\":2,\"code\":${body.optInt("code", 1)}}")
            type in setOf(8, 9, 11) && action == 1 -> send(Business.RECORDING, type, "{\"uuid\":${Json.quote(uuid)},\"action\":2,\"code\":1}")
            type == 6 && body.optBoolean("completed") -> { recCompleted = true; event("gravação concluída pelos óculos"); if (!stopping) stop("gravação concluída") }
        }
    }

    /** Recording audio arrives with byte offsets; rebuild one ordered stream (gaps/overlaps reported separately). */
    private fun orderedRecording(): List<ProbeRecord> {
        val audio = records.filter { it.business == Business.RECORDING && it.type == 3 }
            .sortedBy { runCatching { JSONObject(it.json).optLong("offset") }.getOrDefault(0) }
        return records.filter { !(it.business == Business.RECORDING && it.type == 3) } + audio
    }

    private fun coverage(): JSONObject {
        val ranges = records.filter { it.business == Business.RECORDING && it.type == 3 }.mapNotNull {
            runCatching { JSONObject(it.json).getLong("offset") }.getOrNull()?.let { o -> o to o + it.binary.size }
        }.sortedBy { it.first }
        var end = 0L; var gaps = 0; var overlaps = 0
        for ((s, e) in ranges) { if (s > end) gaps++; if (s < end) overlaps++; end = maxOf(end, e) }
        return JSONObject().put("chunks", ranges.size).put("bytes", end).put("gaps", gaps).put("overlaps", overlaps)
            .put("contiguous", gaps == 0 && ranges.firstOrNull()?.first == 0L).put("multipleOf240", end % 240 == 0L)
    }
}
