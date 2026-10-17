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
    private fun command(cmd: String, data: String) = "{\"cmd\":\"$cmd\",\"payload\":{\"value\":0,\"mode\":0,\"data\":${Json.quote(data)}}}"

    val HEIGHTS = listOf(1, 3, 5)
    val DISTANCES = listOf(1, 2)
}

data class GlassesConfig(val height: Int? = null, val distance: Int? = null, val crownDouble: Int? = null, val crownLongPress: Int? = null, val readAt: Long = 0) {
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
            return GlassesConfig(int(display, "height"), int(display, "distance"), int(crown, "double"), int(crown, "longPress"), at)
        }
    }
}

/** Where the first values read from the glasses are kept (for "restore"). */
interface OriginalSettings { fun load(): GlassesConfig?; fun save(c: GlassesConfig) }

data class SettingsState(val config: GlassesConfig = GlassesConfig(), val original: GlassesConfig? = null, val note: String = "Toque em “Ler ajustes dos óculos”", val busy: Boolean = false)

class GlassesSettings(
    private val port: GlassesPort,
    private val scope: CoroutineScope,
    private val originals: OriginalSettings,
    private val clock: () -> Long = System::currentTimeMillis,
) : BusinessListener {
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
        if (envelope.type in setOf(3, 6, 17, 19) && j.optString("cmd") == pendingCmd && pendingCmd.isNotEmpty()) commandReply?.complete(envelope.type to j)
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
        var original = _state.value.original
        if (original == null || (!original.hasDisplay && config.hasDisplay) || (!original.hasCrown && config.hasCrown)) {
            original = GlassesConfig(original?.height ?: config.height, original?.distance ?: config.distance,
                original?.crownDouble ?: config.crownDouble, original?.crownLongPress ?: config.crownLongPress, clock())
            originals.save(original)
        }
        _state.value = _state.value.copy(config = config, original = original)
        note(if (config.hasDisplay || config.hasCrown) "Ajustes lidos" else "Os óculos não informaram altura/distância nem o duplo toque")
        return null
    }

    /** Height/distance: always both keys, the one not being changed sent back exactly as read. */
    fun setDisplay(height: Int? = null, distance: Int? = null, done: (String?) -> Unit = {}) = scope.launch {
        done(write("display_config") { c ->
            if (!c.hasDisplay) return@write null
            val h = height ?: c.height!!; val d = distance ?: c.distance!!
            if (h !in SettingsWire.HEIGHTS || d !in SettingsWire.DISTANCES) null else SettingsWire.display(h, d)
        })
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

    fun restore(done: (String?) -> Unit = {}) = scope.launch {
        val o = _state.value.original ?: return@launch done("Nada a restaurar: os ajustes originais ainda não foram lidos")
        var error: String? = null
        if (o.hasDisplay && o.height in SettingsWire.HEIGHTS && o.distance in SettingsWire.DISTANCES)
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
