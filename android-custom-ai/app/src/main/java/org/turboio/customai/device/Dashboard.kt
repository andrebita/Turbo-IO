package org.turboio.customai.device

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.turboio.customai.ble.BusinessListener
import org.turboio.customai.ble.GlassesPort
import org.turboio.customai.ble.Randomness
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.protocol.Business
import org.turboio.customai.protocol.Envelope
import org.turboio.customai.protocol.EnvelopeCodec
import org.turboio.customai.protocol.Json
import kotlin.coroutines.resume

/**
 * The glasses' home-screen dashboard (launcher 15/18), as driven by this repository's HarmonyOS client (text card seen
 * on the lens) and the official-app add-on (read → change → read back, no retry). Requests carry a sequence and mode 1;
 * the glasses answer 15/19 with the same sequence.
 */
object DashboardWire {
    const val CARD_ID = "turbo_ui_io"
    const val CARD_MAX = 20
    private const val CATALOG = "https://rayneo.com/a2ui/catalogs/glasses-base/v1/catalog.json"

    const val QUERY = """{"cmd":"dashboard_config","payload":{"version":1,"value":0}}"""

    /** One short Text widget (HarmonyOS installTextWidget format). */
    fun install(text: String): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        require(t.isNotEmpty() && t.length <= CARD_MAX) { "card-text" }
        val component = "{\"id\":\"root\",\"component\":\"Text\",\"text\":${Json.quote(t)},\"variant\":\"body\"}"
        val extra = "{\"widgetId\":\"$CARD_ID\",\"name\":\"IO\",\"uiContent\":{\"createSurface\":{\"surfaceId\":\"$CARD_ID\",\"catalogId\":\"$CATALOG\"}," +
            "\"updateComponents\":{\"surfaceId\":\"$CARD_ID\",\"components\":[$component]}}}"
        return "{\"cmd\":\"widget_install\",\"payload\":{\"data\":{\"type\":\"a2ui\",\"id\":\"$CARD_ID\",\"name\":\"IO\",\"extras\":${Json.quote(extra)}}}}"
    }

    /** "3 pend · 15:00 Ligar…" (≤ 20 characters): open count and the next item due today. */
    fun summary(open: Int, nextDue: Long?, nextText: String, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        if (open == 0) return "Sem pendências"
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val due = nextDue?.let { java.time.Instant.ofEpochMilli(it).atZone(zone) }
        if (due == null || due.toLocalDate() != today) return if (open == 1) "1 pendência" else "$open pendências".take(CARD_MAX)
        val head = "$open pend · %02d:%02d".format(due.hour, due.minute)
        val room = CARD_MAX - head.length - 1
        val word = nextText.trim().replace(Regex("\\s+"), " ")
        return if (room < 3 || word.isEmpty()) head else head + " " + (if (word.length <= room) word else word.take(room - 1).trimEnd() + "…")
    }

    const val UNINSTALL = """{"cmd":"widget_uninstall","payload":{"data":{"id":"$CARD_ID"}}}"""

    /** A full snapshot (value 0) built from a fresh read. */
    fun update(config: JSONObject): String =
        "{\"cmd\":\"dashboard_update\",\"payload\":{\"version\":1,\"value\":0,\"data\":${Json.quote(config.toString())}}}"

    /** The configuration inside a dashboard_config reply (data may be a JSON string or an object). */
    fun config(reply: JSONObject): JSONObject? {
        if (reply.optString("cmd") != "dashboard_config") return null
        val raw = reply.optJSONObject("payload")?.opt("data") ?: return null
        val c = when (raw) { is JSONObject -> raw; is String -> runCatching { JSONObject(raw) }.getOrNull(); else -> null } ?: return null
        return if (widgets(c) != null) c else null
    }

    data class Widget(val id: String, val type: String, val name: String)

    /**
     * Dashboard items. Two formats are known: `widgets_v2` (rows with id/type, official-app add-on) and, on Strix OS
     * 1.0.5.5, `widgets` as a list of numbers with `widgets_data` (feature test, 2026-10-17).
     */
    fun widgets(c: JSONObject): List<Widget>? {
        val v2 = c.optJSONArray("widgets_v2")
        if (v2 == null) {
            val ids = c.optJSONArray("widgets") ?: return null
            if (ids.length() > 32) return null
            return (0 until ids.length()).map { i -> ids.opt(i).toString().let { Widget(it, "nativo", NATIVE_NAMES[it] ?: "") } }
        }
        val rows = v2
        if (rows.length() > 32) return null
        val out = ArrayList<Widget>()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: return null
            val id = r.optString("id"); val type = r.optString("type")
            if (id.isEmpty() || id.length > 128 || type.isEmpty() || out.any { it.id == id }) return null
            out.add(Widget(id, type, r.optString("name")))
        }
        return out
    }

    /** Numeric widget ids are not documented; names are added here once confirmed on the lens. */
    private val NATIVE_NAMES = emptyMap<String, String>()

    /** simple_mode as stored by the glasses (boolean or 0/1); null when absent. */
    fun simpleMode(c: JSONObject): Boolean? = when (val v = c.opt("simple_mode")) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        else -> null
    }

    /** Same configuration with simple_mode flipped, keeping its original JSON type. */
    fun withSimpleMode(c: JSONObject, on: Boolean): JSONObject {
        val copy = JSONObject(c.toString())
        when (c.opt("simple_mode")) {
            is Number -> copy.put("simple_mode", if (on) 1 else 0)
            else -> copy.put("simple_mode", on)
        }
        return copy
    }

    private fun others(c: JSONObject, ownId: String): String {
        val rows = c.optJSONArray("widgets_v2") ?: JSONArray()
        val kept = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.filter { it.optString("id") != ownId }
        val rest = JSONObject(c.toString()); rest.remove("widgets_v2")
        return canonical(rest) + "|" + kept.joinToString(",") { canonical(it) }
    }

    /** Everything except our own card (and [ignoring] keys) is unchanged. */
    fun preserves(before: JSONObject, after: JSONObject, ownId: String = CARD_ID, ignoring: Set<String> = emptySet()): Boolean {
        fun strip(c: JSONObject) = JSONObject(c.toString()).also { x -> ignoring.forEach { x.remove(it) } }
        return others(strip(before), ownId) == others(strip(after), ownId)
    }

    /** Key-sorted rendering, so a re-serialised but equal configuration compares equal. */
    fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { k -> Json.quote(k) + ":" + canonical(v.get(k)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        is String -> Json.quote(v)
        JSONObject.NULL, null -> "null"
        else -> v.toString()
    }
}

class Dashboard(private val port: GlassesPort, private val random: Randomness, private val timeoutMs: Long = 20_000) : BusinessListener {
    companion object { const val NOT_SUPPORTED = "Estes óculos não aceitam cartões próprios no painel (firmware sem widgets_v2); nada foi enviado" }
    private val lock = Mutex()
    @Volatile private var pendingSeq = 0L
    @Volatile private var pendingCmd = ""
    @Volatile private var reply: CompletableDeferred<JSONObject>? = null

    init { port.addBusinessListener(this) }

    override fun onBusiness(business: Int, envelope: Envelope) {
        if (business != Business.LAUNCHER || envelope.type != 19 || pendingSeq == 0L) return
        val j = runCatching { JSONObject(envelope.jsonText()) }.getOrNull() ?: return
        val cmd = j.optString("cmd")
        val matches = envelope.sequence == pendingSeq ||
            (envelope.sequence == 0L && (cmd == pendingCmd || (cmd.isEmpty() && j.has("code") && pendingCmd.startsWith("widget_"))))
        if (matches) reply?.complete(j)
    }

    private fun sequence(): Long {
        val b = random.bytes(4); var v = 0L
        for (x in b) v = (v shl 8) or (x.toLong() and 0xff)
        return 0x40000000L + Math.floorMod(v, 0x3fffffffL)
    }

    /** One request and its correlated reply; null = no reply in time. */
    private suspend fun request(cmd: String, json: String): Pair<JSONObject?, String?> {
        val seq = sequence()
        val waiter = CompletableDeferred<JSONObject>()
        reply = waiter; pendingSeq = seq; pendingCmd = cmd
        try {
            val bytes = try { EnvelopeCodec.encode(18, json, seq, 1) } catch (e: Exception) { return null to "Mensagem inválida" }
            val error = suspendCancellableCoroutine<String?> { cont -> port.send(Business.LAUNCHER, bytes) { e -> if (cont.isActive) cont.resume(e) } }
            if (error != null) return null to error
            return withTimeoutOrNull(timeoutMs) { waiter.await() } to null
        } finally { reply = null; pendingSeq = 0; pendingCmd = "" }
    }

    data class Snapshot(val config: JSONObject) {
        val widgets get() = DashboardWire.widgets(config).orEmpty()
        val simple get() = DashboardWire.simpleMode(config)
        val hasCard get() = widgets.any { it.id == DashboardWire.CARD_ID }
        fun describe(): String = widgets.mapIndexed { i, w -> "${i + 1}. ${w.name.ifBlank { "cartão ${w.id}" }} [${w.type}]" }.joinToString("\n").ifEmpty { "(nenhum cartão)" } +
            "\nTipo: " + when (simple) { true -> "simples"; false -> "completo"; null -> "não informado pelos óculos" }
    }

    private suspend fun readLocked(): Pair<Snapshot?, String> {
        if (!port.isReady) return null to "Conecte os óculos"
        val (j, error) = request("dashboard_config", DashboardWire.QUERY)
        if (error != null) return null to "Falha ao enviar: $error"
        if (j == null) return null to "Os óculos não responderam à leitura do painel"
        val c = DashboardWire.config(j) ?: return null to "Resposta do painel não reconhecida"
        return Snapshot(c) to "lido"
    }

    suspend fun read(): Pair<Snapshot?, String> = lock.withLock { readLocked() }

    /** Installs (or replaces) our one text card; other cards are verified unchanged by reading back. */
    suspend fun showCard(text: String): String = lock.withLock {
        val install = runCatching { DashboardWire.install(text) }.getOrElse { return@withLock "Texto do cartão: 1 a ${DashboardWire.CARD_MAX} caracteres" }
        val (before, why) = readLocked(); before ?: return@withLock why
        // Strix OS 1.0.5.5 lists numeric widgets only and never answered widget_install (feature test, 2026-10-17).
        if (!before.config.has("widgets_v2")) return@withLock NOT_SUPPORTED
        before.widgets.firstOrNull { it.id == DashboardWire.CARD_ID }?.let { if (it.type != "a2ui") return@withLock "Já existe um cartão com o mesmo id que não é deste app; nada foi alterado" }
        val (r, error) = request("widget_install", install)
        if (error != null) return@withLock "Falha ao enviar: $error"
        if (r == null) return@withLock "Sem resposta à instalação; confira o painel nos óculos (não foi repetido)"
        if (r.optInt("code", -1) != 0) return@withLock "Os óculos recusaram o cartão (código ${r.opt("code")})"
        val (after, why2) = readLocked(); after ?: return@withLock "Cartão aceito, mas a releitura falhou: $why2"
        val own = after.widgets.firstOrNull { it.id == DashboardWire.CARD_ID }
        val outcome = when {
            own == null || own.type != "a2ui" -> "Cartão aceito, mas não aparece na releitura"
            !DashboardWire.preserves(before.config, after.config) -> "Cartão instalado, mas outros itens do painel mudaram; confira nos óculos"
            else -> "Cartão no painel e demais cartões preservados (confira nas lentes)"
        }
        DiagnosticLog.i("PAINEL", outcome); outcome
    }

    suspend fun removeCard(): String = lock.withLock {
        val (before, why) = readLocked(); before ?: return@withLock why
        if (!before.hasCard) return@withLock "O cartão do app não está no painel"
        val (r, error) = request("widget_uninstall", DashboardWire.UNINSTALL)
        if (error != null) return@withLock "Falha ao enviar: $error"
        if (r == null) return@withLock "Sem resposta à remoção; confira o painel nos óculos"
        if (r.optInt("code", -1) != 0) return@withLock "Os óculos recusaram a remoção (código ${r.opt("code")})"
        val (after, why2) = readLocked(); after ?: return@withLock "Remoção aceita, mas a releitura falhou: $why2"
        val outcome = if (!after.hasCard && DashboardWire.preserves(before.config, after.config)) "Cartão removido; demais cartões preservados"
            else "Remoção aceita, mas a releitura não confere; confira nos óculos"
        DiagnosticLog.i("PAINEL", outcome); outcome
    }

    /**
     * Dashboard type (simple/complete): sends the fresh configuration back with only simple_mode changed, then reads
     * back. [remember] receives the value read before the first change, for "restore".
     */
    suspend fun setSimpleMode(on: Boolean, remember: (Boolean) -> Unit = {}): String = lock.withLock {
        val (before, why) = readLocked(); before ?: return@withLock why
        val current = before.simple ?: return@withLock "Os óculos não informam o tipo de painel (simple_mode); nada foi alterado"
        if (current == on) return@withLock "O painel já está no tipo ${if (on) "simples" else "completo"}"
        remember(current)
        val (r, error) = request("dashboard_update", DashboardWire.update(DashboardWire.withSimpleMode(before.config, on)))
        if (error != null) return@withLock "Falha ao enviar: $error"
        if (r == null) return@withLock "Sem resposta à mudança; confira o painel nos óculos"
        val value = r.optJSONObject("payload")?.optInt("value", -1) ?: r.optInt("code", -1)
        if (value != 0) return@withLock "Os óculos recusaram a mudança (valor $value)"
        val (after, why2) = readLocked(); after ?: return@withLock "Mudança aceita, mas a releitura falhou: $why2"
        val outcome = when {
            after.simple != on -> "Os óculos aceitaram, mas o tipo lido continua ${if (current) "simples" else "completo"}"
            !DashboardWire.preserves(before.config, after.config, ignoring = setOf("simple_mode")) -> "Tipo alterado, mas outros itens do painel mudaram; confira nos óculos"
            else -> "Painel agora no tipo ${if (on) "simples" else "completo"} (confira nas lentes)"
        }
        DiagnosticLog.i("PAINEL", outcome); outcome
    }
}
