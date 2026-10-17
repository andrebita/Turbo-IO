package org.turboio.customai.probe

import org.json.JSONObject
import org.turboio.customai.diag.Redactor
import org.turboio.customai.protocol.Business
import org.turboio.customai.protocol.EnvelopeCodec
import org.turboio.customai.protocol.FileTransfer

/**
 * Glasses traffic captured while a feature test runs, as readable lines for the test package. Never stored: audio and
 * other binary data (sizes only), file contents, the text of other apps' notifications coming from the glasses (21/1)
 * and the title/text of notification cards sent to the glasses (lengths only). Everything else passes the log redactor.
 */
class TrafficRecorder(private val clock: () -> Long = System::currentTimeMillis, private val max: Int = 4000) {
    @Volatile private var startedAt = -1L
    private val lines = ArrayList<String>()
    private var last = ""
    private var repeats = 0

    val recording get() = startedAt >= 0

    fun start() = synchronized(lines) { lines.clear(); last = ""; repeats = 0; startedAt = clock() }

    fun stop(): List<String> = synchronized(lines) { startedAt = -1; flushRepeat(); lines.toList() }

    fun inbound(business: Int, payload: ByteArray) = add("←", business, payload)
    fun outbound(business: Int, payload: ByteArray) = add("→", business, payload)

    private fun add(dir: String, business: Int, payload: ByteArray) {
        val t0 = startedAt
        if (t0 < 0) return
        val text = "$dir " + describe(business, payload)
        synchronized(lines) {
            if (startedAt < 0) return
            // Streams of audio frames collapse into one line with a count.
            if (text == last && text.contains(" bin=") && !text.contains(" bin=0B")) { repeats++; return }
            flushRepeat()
            if (lines.size >= max) { if (lines.size == max) lines.add("… captura limitada a $max linhas"); return }
            lines.add("+%.3f s %s".format(java.util.Locale.US, (clock() - t0) / 1000.0, text))
            last = text
        }
    }

    private fun flushRepeat() {
        if (repeats > 0 && lines.isNotEmpty()) lines[lines.lastIndex] = lines.last() + "  (+$repeats iguais)"
        repeats = 0
    }

    companion object {
        private val CARD_TEXT = setOf("title", "subtitle", "content")

        fun describe(business: Int, payload: ByteArray): String {
            if (business == Business.FILE) {
                val m = runCatching { FileTransfer.decode(payload) }.getOrNull() ?: return "18 bruto ${payload.size} B"
                return "18 arquivo tipo=${m.type}" + when (m.type) {
                    1 -> " tamanho=${m.fileSize} md5=${m.md5.take(8)}…"
                    3, 4 -> " pedaço=${m.chunkId} início=${m.start} tamanho=${m.size}"
                    5 -> " estado=${m.state}" + if (m.reason.isNotEmpty()) " motivo=${m.reason}" else ""
                    6 -> if (m.reason.isNotEmpty()) " motivo=${m.reason}" else ""
                    else -> ""
                }
            }
            val e = runCatching { EnvelopeCodec.decode(payload) }.getOrNull() ?: return "$business bruto ${payload.size} B"
            val head = "$business/${e.type}" + (if (e.sequence != 0L) " seq=${e.sequence}" else "") + " json=${e.json.size}B bin=${e.binary.size}B"
            if (e.json.isEmpty()) return head
            if (business == Business.NOTIFICATION && e.type == 1) return "$head [notificação de outro app: conteúdo omitido]"
            var json = e.jsonText()
            if (business == Business.NOTIFICATION && e.type == 2) json = runCatching {
                val j = JSONObject(json)
                CARD_TEXT.forEach { k -> if (j.has(k)) j.put(k, "«${j.optString(k).length} caracteres»") }
                j.toString()
            }.getOrDefault("[cartão ilegível]")
            return "$head " + Redactor.redact(json).take(600)
        }
    }
}
