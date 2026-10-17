package org.turboio.customai.device

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.turboio.customai.ble.BusinessListener
import org.turboio.customai.ble.GlassesPort
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.display.sendEnvelope
import org.turboio.customai.protocol.Business
import org.turboio.customai.protocol.Envelope
import org.turboio.customai.protocol.Json

/**
 * Glasses settings the official app changes, limited to commands and value ranges with evidence in this repository
 * (apps/RayNeoCompanion CompanionDeviceFeatures.swift): display height {1,3,5} and distance {1,2}, and the crown
 * double-tap action (8 = open To-do). Every write first reads the current values, sends the untouched keys back as
 * they were, and the first values ever read are kept so they can be restored.
 */
object SettingsWire {
    const val READ = """{"cmd":"request_general_settings","payload":{"value":0,"mode":0,"data":""}}"""
    fun display(height: Int, distance: Int) = command("display_config", """{"distance":$distance,"height":$height}""")
    fun crown(double: Int, longPress: Int) = command("crown_config", """{"double":$double,"longPress":$longPress}""")
    /** Head gestures on/off keeping the mode read from the glasses (iOS client form: value + mode, empty data). */
    fun head(enabled: Boolean, mode: Int) = "{\"cmd\":\"head_gestures\",\"payload\":{\"value\":${if (enabled) 1 else 0},\"mode\":$mode,\"data\":\"\"}}"
    private fun command(cmd: String, data: String) = "{\"cmd\":\"$cmd\",\"payload\":{\"value\":0,\"mode\":0,\"data\":${Json.quote(data)}}}"

    /** Values seen in the official app by other clients. */
    val HEIGHTS = listOf(1, 3, 5)
    val DISTANCES = listOf(1, 2)
    /** Offered as an experiment (the official app shows a third depth level): written, read back, undone if not applied. */
    const val TRY_DISTANCE = 3
    /** Hard bounds for anything sent (also enforced by the outbound content gate). */
    val HEIGHT_RANGE = 0..5
    val DISTANCE_RANGE = 0..3
}

/** Height/distance values these glasses reported themselves (e.g. after the user picked a level in the official app). */
interface LearnedDisplay {
    fun heights(): Set<Int>
    fun distances(): Set<Int>
    fun add(height: Int?, distance: Int?)
    object None : LearnedDisplay { override fun heights() = emptySet<Int>(); override fun distances() = emptySet<Int>(); override fun add(height: Int?, distance: Int?) {} }
}

data class GlassesConfig(val height: Int? = null, val distance: Int? = null, val crownDouble: Int? = null, val crownLongPress: Int? = null, val readAt: Long = 0,
                         /** Voice wake-up (“Hey RayNeo”) if the glasses report it; key name not documented, so several are tried. */
                         val wakeup: Boolean? = null, val keys: String = "",
                         /** Head gestures (screen on when looking up): on/off and mode, as the glasses report them. */
                         val headEnabled: Boolean? = null, val headMode: Int? = null) {
    val hasDisplay get() = height != null && distance != null
    val hasCrown get() = crownDouble != null && crownLongPress != null

    companion object {
        private fun int(o: JSONObject?, key: String): Int? = o?.opt(key)?.let { (it as? Number)?.toInt() ?: it.toString().toIntOrNull() }
        private fun obj(root: JSONObject, vararg keys: String): JSONObject? = keys.firstNotNullOfOrNull { k ->
            root.optJSONObject(k) ?: root.optString(k).takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
        }

        /** Reply to request_general_settings (15/4): {"generalSettings":{…}} or the settings at the root. */
        fun parse(json: JSONObject, at: Long): GlassesConfig {
            val root = json.optJSONObject("generalSettings") ?: json
            val display = obj(root, "displayConfig", "display_config")
            val crown = obj(root, "crownConfig", "crown_config")
            val head = obj(root, "headGestures", "head_gestures")
            return GlassesConfig(int(display, "height"), int(display, "distance"), int(crown, "double"), int(crown, "longPress"), at,
                wakeup(root), root.keys().asSequence().toList().sorted().joinToString(","),
                int(head, "enabled")?.let { it != 0 }, int(head, "mode"))
        }

        private val WAKE_KEYS = setOf("voiceWakeup", "voice_wakeup", "ai_voice_wakeup", "voiceWakeUp", "aiVoiceWakeup", "wakeup", "wakeUp", "enabled", "enable")

        /** First boolean-ish value under a wake-up key, searched one level deep. */
        fun wakeup(o: JSONObject): Boolean? {
            for (k in o.keys()) {
                val v = o.opt(k)
                if (k in WAKE_KEYS) when (v) { is Boolean -> return v; is Number -> return v.toInt() != 0 }
                // Strix OS 1.0.5.5 reports a "wakeupConfig" group; inside it, look for an on/off value.
                if (v is JSONObject && (k == "wakeupConfig" || k.contains("wake", ignoreCase = true))) wakeup(v)?.let { return it }
                else if (v is JSONObject && k != "headGestures") WAKE_KEYS.firstOrNull { v.has(it) && it.contains("wake", true) }?.let { wakeup(v)?.let { b -> return b } }
            }
            return null
        }
    }
}

private val DETAIL_KEYS = listOf("displayConfig", "crownConfig", "headGestures", "wakeupConfig", "privacyConfig", "autoLockTime", "language")

/** Where the first values read from the glasses are kept (for "restore"). */
interface OriginalSettings { fun load(): GlassesConfig?; fun save(c: GlassesConfig) }

data class SettingsState(val config: GlassesConfig = GlassesConfig(), val original: GlassesConfig? = null, val note: String = "Toque em “Ler ajustes dos óculos”", val busy: Boolean = false,
                         /** Raw setting groups as read (no serial numbers), for diagnosis. */
                         val details: String = "")

class GlassesSettings(
    private val port: GlassesPort,
    private val scope: CoroutineScope,
    private val originals: OriginalSettings,
    private val clock: () -> Long = System::currentTimeMillis,
    private val learned: LearnedDisplay = LearnedDisplay.None,
) : BusinessListener {
    /** Selectable values: known from the official app plus those these glasses reported. */
    fun heights(): List<Int> = (SettingsWire.HEIGHTS + learned.heights()).filter { it in SettingsWire.HEIGHT_RANGE }.distinct().sorted()
    fun distances(): List<Int> = (SettingsWire.DISTANCES + learned.distances()).filter { it in SettingsWire.DISTANCE_RANGE }.distinct().sorted()

    private val _state = MutableStateFlow(SettingsState(original = originals.load()))
    val state: StateFlow<SettingsState> = _state
    private var settingsReply: CompletableDeferred<JSONObject>? = null
    private var commandReply: CompletableDeferred<Pair<Int, JSONObject>>? = null
    private var pendingCmd = ""

    init { port.addBusinessListener(this) }

    override fun onBusiness(business: Int, envelope: Envelope) {
        if (business != Business.LAUNCHER) return
        val j = runCatching { JSONObject(envelope.jsonText()) }.getOrNull() ?: return
        if (envelope.type == 4) { settingsReply?.complete(j); return }
        if (envelope.type in setOf(3, 6, 16, 17, 19) && j.optString("cmd") == pendingCmd && pendingCmd.isNotEmpty()) commandReply?.complete(envelope.type to j)
        if (envelope.type == 17) DiagnosticLog.i("AJUSTES", "15/17: ${j.toString().take(200)}")
    }

    private fun note(text: String, busy: Boolean = false) { _state.value = _state.value.copy(note = text, busy = busy); DiagnosticLog.i("AJUSTES", text) }

    fun read(done: (String?) -> Unit = {}) = scope.launch { done(readNow()) }

    private suspend fun readNow(): String? {
        if (!port.isReady) return "Conecte os óculos".also { note(it) }
        note("Lendo ajustes…", busy = true)
        val waiter = CompletableDeferred<JSONObject>().also { settingsReply = it }
        port.sendEnvelope(Business.LAUNCHER, 4, SettingsWire.READ)?.let { settingsReply = null; return "Falha ao enviar: $it".also { m -> note(m) } }
        val reply = withTimeoutOrNull(6000) { waiter.await() }
        settingsReply = null
        if (reply == null) return "Os óculos não responderam (6 s)".also { note(it) }
        val config = GlassesConfig.parse(reply, clock())
        if (config.hasDisplay) learned.add(config.height?.takeIf { it in SettingsWire.HEIGHT_RANGE }, config.distance?.takeIf { it in SettingsWire.DISTANCE_RANGE })
        var original = _state.value.original
        if (original == null || (!original.hasDisplay && config.hasDisplay) || (!original.hasCrown && config.hasCrown)) {
            original = GlassesConfig(original?.height ?: config.height, original?.distance ?: config.distance,
                original?.crownDouble ?: config.crownDouble, original?.crownLongPress ?: config.crownLongPress, clock())
            originals.save(original)
        }
        val root = reply.optJSONObject("generalSettings") ?: reply
        val details = DETAIL_KEYS.mapNotNull { k -> root.opt(k)?.let { "$k=$it" } }.joinToString(" · ").take(800)
        _state.value = _state.value.copy(config = config, original = original, details = details)
        DiagnosticLog.i("AJUSTES", "chaves dos ajustes: ${config.keys.take(300)}")
        DiagnosticLog.i("AJUSTES", "valores: $details")
        note(if (config.hasDisplay || config.hasCrown) "Ajustes lidos" else "Os óculos não informaram altura/distância nem o duplo toque")
        return null
    }

    /**
     * Height/distance: always both keys, the one not being changed sent back exactly as read. The result is read back;
     * if the glasses did not apply it (e.g. the experimental depth 3), the previous values are written again.
     */
    fun setDisplay(height: Int? = null, distance: Int? = null, done: (String?) -> Unit = {}) = scope.launch {
        var before: GlassesConfig? = null
        val error = write("display_config") { c ->
            if (!c.hasDisplay) return@write null
            before = c
            val h = height ?: c.height!!; val d = distance ?: c.distance!!
            val hOk = h in heights() || h == c.height
            val dOk = d in distances() || d == c.distance || d == SettingsWire.TRY_DISTANCE
            if (!hOk || !dOk) null else SettingsWire.display(h, d)
        }
        if (error != null) return@launch done(error)
        val after = _state.value.config; val prev = before
        val applied = (height == null || after.height == height) && (distance == null || after.distance == distance)
        if (applied || prev == null) {
            learned.add(height, distance)
            return@launch done(null)
        }
        var msg = "Os óculos não aplicaram esse valor (relido: altura ${after.height ?: "?"}, distância ${after.distance ?: "?"})"
        if (after.hasDisplay && (after.height != prev.height || after.distance != prev.distance)) {
            val back = write("display_config") { c -> if (c.hasDisplay) SettingsWire.display(prev.height!!, prev.distance!!) else null }
            msg += if (back == null) "; valores anteriores restaurados" else "; não foi possível restaurar: $back"
        }
        note(msg); done(msg)
    }

    /** Crown double-tap opens the native To-do (8); off restores the double-tap action read originally (or 0). */
    fun setDoubleTapTodo(on: Boolean, done: (String?) -> Unit = {}) = scope.launch {
        // Only when the double-tap had no action (0) or already opened To-do (8): anything else could not be restored here.
        val original = _state.value.original?.crownDouble
        if (original != null && original !in setOf(0, 8)) return@launch done("O duplo toque já tem outra função nos óculos; altere pelo app oficial")
        done(write("crown_config") { c ->
            if (!c.hasCrown || c.crownDouble !in setOf(0, 8)) return@write null
            SettingsWire.crown(if (on) 8 else 0, c.crownLongPress!!)
        })
    }

    /** Screen on when looking up (head gestures). The mode is sent back exactly as read; the result is read back. */
    fun setHeadGestures(enabled: Boolean, done: (String?) -> Unit = {}) = scope.launch {
        val error = write("head_gestures") { c ->
            if (c.headEnabled == null) return@write null
            SettingsWire.head(enabled, c.headMode ?: 0)
        }
        if (error != null) return@launch done(error)
        val after = _state.value.config.headEnabled
        done(if (after == enabled) null else "Os óculos não aplicaram (relido: ${when (after) { true -> "ligado"; false -> "desligado"; null -> "não informado" }})".also { note(it) })
    }

    /**
     * “Hey RayNeo”: the enable command the official app sends (15/16 set_ai_voice_wakeup, mode 1). No disable command is
     * known, so this cannot be undone from this app (use the glasses' menu or the official app).
     */
    fun enableWakeWord(done: (String?) -> Unit = {}) = scope.launch {
        if (!port.isReady) return@launch done("Conecte os óculos")
        note("Ativando a palavra de ativação…", busy = true)
        pendingCmd = "set_ai_voice_wakeup"
        val waiter = CompletableDeferred<Pair<Int, JSONObject>>().also { commandReply = it }
        port.sendEnvelope(Business.LAUNCHER, 16, Business.WAKEUP_ON)?.let { pendingCmd = ""; return@launch done("Falha ao enviar: $it".also { m -> note(m) }) }
        val reply = withTimeoutOrNull(6000) { waiter.await() }
        pendingCmd = ""; commandReply = null
        DiagnosticLog.i("AJUSTES", "palavra de ativação: resposta ${reply?.let { "${it.first} ${it.second.toString().take(160)}" } ?: "nenhuma"}")
        readNow()
        done(if (reply == null) "Enviado; os óculos não responderam. Diga “Hey RayNeo” para testar" else null)
        note(if (reply == null) "Ativação enviada sem resposta; teste dizendo “Hey RayNeo”" else "Óculos responderam; teste dizendo “Hey RayNeo”")
    }

    fun restore(done: (String?) -> Unit = {}) = scope.launch {
        val o = _state.value.original ?: return@launch done("Nada a restaurar: os ajustes originais ainda não foram lidos")
        var error: String? = null
        if (o.hasDisplay && o.height in SettingsWire.HEIGHT_RANGE && o.distance in SettingsWire.DISTANCE_RANGE)
            error = write("display_config") { c -> if (c.hasDisplay) SettingsWire.display(o.height!!, o.distance!!) else null }
        if (error == null && o.hasCrown && o.crownDouble in setOf(0, 8))
            error = write("crown_config") { c -> if (c.hasCrown) SettingsWire.crown(o.crownDouble!!, c.crownLongPress!!) else null }
        done(error)
    }

    /** Fresh read → build from current values → send → wait for the glasses' reply → read back. */
    private suspend fun write(cmd: String, build: (GlassesConfig) -> String?): String? {
        readNow()?.let { return it }
        val json = build(_state.value.config) ?: return "Os óculos não informaram esse ajuste; nada foi alterado".also { note(it) }
        note("Enviando $cmd…", busy = true)
        pendingCmd = cmd
        val waiter = CompletableDeferred<Pair<Int, JSONObject>>().also { commandReply = it }
        port.sendEnvelope(Business.LAUNCHER, 5, json)?.let { pendingCmd = ""; return "Falha ao enviar: $it".also { m -> note(m) } }
        val reply = withTimeoutOrNull(6000) { waiter.await() }
        pendingCmd = ""; commandReply = null
        readNow()
        return if (reply == null) "Os óculos não confirmaram (6 s); valores relidos acima".also { note(it) }
        else null.also { note("Óculos confirmaram $cmd; confira o efeito nas lentes") }
    }
}
