package org.turboio.customai.device

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.turboio.customai.ble.GlassesPort
import org.turboio.customai.ble.Randomness
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.display.sendEnvelope
import org.turboio.customai.protocol.Business
import org.turboio.customai.protocol.Json

/**
 * Yes/no cards answered with a head nod or shake (business 21: type 33 add/delete, 34 the glasses' answer, 35 our
 * acknowledgement), as in this repository's iOS head-control test page. One card at a time; an answer is consumed once.
 * An unanswered card is removed after the timeout.
 */
class SuggestionCards(private val port: GlassesPort, private val random: Randomness) {
    enum class Answer(val label: String) { YES("aceno (sim)"), NO("balanço (não)"), TIMEOUT("sem resposta"), FAILED("não enviado") }

    private val lock = Mutex()
    @Volatile private var pending: Pair<String, CompletableDeferred<Answer>>? = null

    /** Handed the glasses' type-34 messages (wired through [NativeNotifier.suggestionListener]). */
    fun onAnswer(j: JSONObject) {
        val (uid, waiter) = pending ?: return
        if (j.optString("suggestUID") != uid || j.optInt("suggestType") != TODO) return
        when (j.optInt("cmd")) {
            1 -> waiter.complete(Answer.YES)
            2 -> waiter.complete(Answer.NO)
        }
    }

    private fun uid(): String {
        val r = random.bytes(16)
        r[6] = ((r[6].toInt() and 0x0f) or 0x40).toByte(); r[8] = ((r[8].toInt() and 0x3f) or 0x80).toByte()
        val h = r.joinToString("") { "%02x".format(it) }
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    fun add(uid: String, title: String, source: String, content: String) =
        "{\"suggestUID\":\"$uid\",\"suggestType\":$TODO,\"type\":1,\"title\":${Json.quote(title.take(40))}," +
            "\"source\":${Json.quote(source.take(40))},\"content\":${Json.quote(content.take(120))}}"

    fun delete(uid: String) = "{\"suggestUID\":\"$uid\",\"suggestType\":$TODO,\"type\":2,\"title\":\"\",\"source\":\"\",\"content\":\"\"}"

    fun ack(uid: String) = "{\"suggestUID\":\"$uid\",\"suggestType\":$TODO,\"code\":0}"

    suspend fun ask(title: String, content: String, source: String = "Turbo IO", timeoutMs: Long = 30_000): Answer = lock.withLock {
        if (!port.isReady) return@withLock Answer.FAILED
        val id = uid(); val waiter = CompletableDeferred<Answer>()
        pending = id to waiter
        try {
            port.sendEnvelope(Business.NOTIFICATION, 33, add(id, title.trim(), source, content.trim()))?.let {
                DiagnosticLog.w("ACENO", "cartão não enviado: $it"); return@withLock Answer.FAILED
            }
            val answer = withTimeoutOrNull(timeoutMs) { waiter.await() } ?: Answer.TIMEOUT
            pending = null                                        // a duplicate answer can no longer count
            port.sendEnvelope(Business.NOTIFICATION, 33, delete(id))
            if (answer == Answer.YES || answer == Answer.NO) port.sendEnvelope(Business.NOTIFICATION, 35, ack(id))
            DiagnosticLog.i("ACENO", "resposta: ${answer.label}")
            answer
        } finally { pending = null }
    }

    companion object { const val TODO = 2 }
}
