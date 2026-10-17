package org.turboio.customai.device

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
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
 * The glasses' own notification card (business 21), as sent by this repository's iOS client (tested on real
 * glasses: README “实测”) and HarmonyOS client. Type 18 is the glasses' notification master switch (a persistent
 * setting); type 2 is one card; the glasses answer type 3 with the card state. Type 1 (other apps' ANCS text) is never
 * read or logged.
 */
class NativeNotifier(
    private val port: GlassesPort,
    @Suppress("unused") private val scope: CoroutineScope,
    private val random: Randomness,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
) : BusinessListener {
    enum class Outcome(val label: String) {
        SHOWN("exibida"), IDLE("aceita (sem exibição confirmada)"), NOT_WORN("óculos fora do rosto"), DND("modo não perturbe"),
        BUSY("óculos ocupados"), NO_REPLY("sem resposta dos óculos"), FAILED("falha no envio");
        val delivered get() = this == SHOWN || this == IDLE
    }

    @Volatile private var switchedOn = false
    private val waiting = HashMap<String, CompletableDeferred<Int>>()
    /** Nod/shake suggestion-card answers (type 34) are handed to this listener. */
    var suggestionListener: ((JSONObject) -> Unit)? = null

    init { port.addBusinessListener(this) }

    /** The master switch is re-sent after every reconnection (it may have been changed meanwhile). */
    fun connectionChanged() { switchedOn = false }

    override fun onBusiness(business: Int, envelope: Envelope) {
        if (business != Business.NOTIFICATION) return
        if (envelope.type == 1) return                                   // other apps' notification text: never parsed
        val j = runCatching { JSONObject(envelope.jsonText()) }.getOrNull() ?: return
        when (envelope.type) {
            3 -> synchronized(waiting) { waiting.remove(j.optString("notificationUID")) }?.complete(j.optInt("state", -1))
            18 -> if (j.optBoolean("notification", true) == false) { switchedOn = false; DiagnosticLog.i("NOTIF", "notificações desligadas nos óculos") }
            34 -> suggestionListener?.invoke(j)
        }
    }

    private fun uid(): String {
        val b = random.bytes(4); var v = 0L
        for (x in b) v = (v shl 8) or (x.toLong() and 0xff)
        return (v % 2147483646L + 1).toString()
    }

    fun body(uid: String, appName: String, title: String, content: String): String {
        val ts = Instant.ofEpochMilli(clock()).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"))
        return "{\"notificationUID\":${Json.quote(uid)},\"appId\":\"org.turboio.customai\",\"appName\":${Json.quote(appName.take(30))}," +
            "\"title\":${Json.quote(title.take(80))},\"subtitle\":\"\",\"content\":${Json.quote(content.take(500))},\"timestamp\":\"$ts\"," +
            "\"category\":0,\"reply\":false,\"type\":1}"
    }

    /** One card; waits up to [waitMs] for the glasses' state answer. */
    suspend fun show(title: String, content: String, appName: String = "Turbo IO", waitMs: Long = 5000): Outcome {
        if (!port.isReady) return Outcome.FAILED
        val t = title.trim().ifEmpty { appName }; val c = content.trim().ifEmpty { t }
        if (!switchedOn) {
            port.sendEnvelope(Business.NOTIFICATION, 18, "{\"notification\":true}")?.let { DiagnosticLog.w("NOTIF", "chave geral: $it"); return Outcome.FAILED }
            switchedOn = true
        }
        val id = uid()
        val waiter = CompletableDeferred<Int>()
        synchronized(waiting) { waiting[id] = waiter }
        port.sendEnvelope(Business.NOTIFICATION, 2, body(id, appName, t, c))?.let {
            synchronized(waiting) { waiting.remove(id) }
            DiagnosticLog.w("NOTIF", "cartão não enviado: $it"); return Outcome.FAILED
        }
        val state = withTimeoutOrNull(waitMs) { waiter.await() }
        synchronized(waiting) { waiting.remove(id) }
        val outcome = when (state) { null -> Outcome.NO_REPLY; 1 -> Outcome.SHOWN; 0 -> Outcome.IDLE; 2 -> Outcome.NOT_WORN; 3 -> Outcome.DND; 4 -> Outcome.BUSY; else -> Outcome.NO_REPLY }
        DiagnosticLog.i("NOTIF", "cartão de $appName: ${outcome.label}")
        return outcome
    }
}
