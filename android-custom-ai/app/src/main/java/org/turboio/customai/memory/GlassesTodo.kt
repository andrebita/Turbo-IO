package org.turboio.customai.memory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.turboio.customai.ble.BusinessListener
import org.turboio.customai.ble.GlassesPort
import org.turboio.customai.ble.Randomness
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.display.sendEnvelope
import org.turboio.customai.protocol.Business
import org.turboio.customai.protocol.Envelope
import org.turboio.customai.protocol.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Open items mirrored into the glasses' native To-do app (business 22, type 2: one item per message, the wire used by
 * this repository's iOS and HarmonyOS clients; confirmed on the lens with the HarmonyOS client). There is no delete
 * on the wire: closed or dismissed items are sent as done. Only changes are sent, so a completion made on the glasses
 * is never overwritten by an older open state. Status changes made on the glasses (types 4 and 10) come back here.
 */
class GlassesTodoSync(
    private val port: GlassesPort,
    private val scope: CoroutineScope,
    private val store: () -> MemoryStore,
    private val random: Randomness,
    private val enabled: () -> Boolean,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val gapMs: Long = 150,
) : BusinessListener {
    @Volatile private var running = false
    @Volatile private var again = false
    /** Called after the glasses changed an item (refresh screens, reminders). */
    var onChanged: (Item) -> Unit = {}

    init { port.addBusinessListener(this) }

    /** Stable title (absolute date, not "hoje"/"amanhã"): a resend happens only when the item itself changes. */
    fun title(i: Item): String {
        val due = i.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("dd/MM HH:mm")) } ?: ""
        val suffix = if (due.isNotBlank()) " · $due" else ""
        val text = i.text.trim().replace(Regex("\\s+"), " ")
        return if (text.length + suffix.length <= MAX) text + suffix else text.take(MAX - suffix.length - 1).trimEnd() + "…" + suffix
    }

    fun important(i: Item, now: Long): Boolean {
        val due = i.dueAt ?: return false
        val endOfDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return due < endOfDay
    }

    private fun newWireId(): Long {
        val b = random.bytes(8)
        var v = 0L; for (x in b) v = (v shl 8) or (x.toLong() and 0xff)
        return 1_000_000_000_000L + Math.floorMod(v, 8_000_000_000_000L)
    }

    fun body(wireId: Long, i: Item, status: Int, title: String, now: Long): String =
        "{\"eventType\":1,\"eventID\":$wireId,\"createTime\":${i.createdAt / 1000},\"title\":${Json.quote(title)}," +
            "\"isImportant\":${important(i, now)},\"status\":$status,\"lastModifiedTime\":${now / 1000}}"

    /** Sends what changed since the last sync (safe to call often). */
    fun sync() {
        if (!enabled() || !port.isReady) return
        if (running) { again = true; return }
        running = true
        scope.launch {
            try {
                do {
                    again = false
                    var sent = 0
                    val now = clock()
                    for (w in store().wireCandidates(MAX_OPEN)) {
                        if (!port.isReady) break
                        val status = if (w.item.status == ItemStatus.OPEN) 0 else 1
                        val title = title(w.item)
                        if (w.wireId != null && w.status == status && (status == 1 || w.title == title)) continue
                        val id = w.wireId ?: newWireId()
                        val error = port.sendEnvelope(Business.TODO, 2, body(id, w.item, status, title, now))
                        if (error != null) { DiagnosticLog.w("TODO", "envio falhou: $error"); break }
                        store().setWire(w.item.id, id, status, title)
                        sent++
                        delay(gapMs)
                    }
                    if (sent > 0) DiagnosticLog.i("TODO", "$sent pendência(s) enviada(s) ao To-do dos óculos")
                } while (again)
            } finally { running = false }
        }
    }

    override fun onBusiness(business: Int, envelope: Envelope) {
        if (business != Business.TODO) return
        val j = runCatching { JSONObject(envelope.jsonText()) }.getOrNull() ?: return
        when (envelope.type) {
            4 -> apply(j)
            10 -> j.optJSONArray("eventList")?.let { a -> for (k in 0 until a.length()) a.optJSONObject(k)?.takeIf { it.optInt("eventType", 1) == 1 }?.let(::apply) }
            14 -> DiagnosticLog.i("TODO", "óculos: ${j.optInt("successCount")} operação(ões) aceita(s)")
        }
    }

    /** A status changed on the glasses (checked or unchecked). Unknown ids are ignored. */
    private fun apply(e: JSONObject) {
        if (!e.has("eventID") || !e.has("status")) return
        val wireId = e.optLong("eventID"); val status = e.optInt("status", -1)
        if (status !in 0..1) return
        scope.launch {
            val s = store()
            val item = s.itemByWire(wireId) ?: return@launch
            val now = clock()
            when {
                status == 1 && item.status == ItemStatus.OPEN -> s.setStatus(item.id, ItemStatus.DONE, now, "concluída nos óculos")
                status == 0 && item.status == ItemStatus.DONE -> s.setStatus(item.id, ItemStatus.OPEN, now)
                else -> { s.setWire(item.id, wireId, status, null); return@launch }
            }
            s.setWire(item.id, wireId, status, null)
            DiagnosticLog.i("TODO", if (status == 1) "pendência concluída nos óculos" else "pendência reaberta nos óculos")
            s.item(item.id)?.let(onChanged)
        }
    }

    companion object {
        const val MAX = 60          // HarmonyOS client limit (1–60), validated on the lens
        const val MAX_OPEN = 30
    }
}
