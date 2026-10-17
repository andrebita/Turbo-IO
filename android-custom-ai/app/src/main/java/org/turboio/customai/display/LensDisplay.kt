package org.turboio.customai.display

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.turboio.customai.ble.BusinessListener
import org.turboio.customai.ble.GlassesPort
import org.turboio.customai.ble.Randomness
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.protocol.Business
import org.turboio.customai.protocol.Commands
import org.turboio.customai.protocol.Envelope
import org.turboio.customai.protocol.EnvelopeCodec
import org.turboio.customai.protocol.Hex
import kotlin.coroutines.resume

/** Sends one envelope; returns null on accepted radio write or a pt-BR failure reason. */
suspend fun GlassesPort.sendEnvelope(business: Int, type: Int, json: String): String? =
    suspendCancellableCoroutine { cont ->
        val envelope = try { EnvelopeCodec.encode(type, json) } catch (e: Exception) { cont.resume("Mensagem inválida: ${e.message}"); return@suspendCancellableCoroutine }
        send(business, envelope) { error -> if (cont.isActive) cont.resume(error) }
    }

fun newSid(random: Randomness): String = Hex.encode(random.bytes(16))

data class DisplaySettings(val pageSeconds: Int = 5, val lingerSeconds: Int = 6, val stripAccents: Boolean = false, val pageChars: Int = 90,
                           val fontSize: Int = 2, val position: String = "center", val maxLines: Int = 5,
                           val textFormat: String = "legacy",
                           /** Re-send the page every 2 s while it is shown (the glasses may drop the first one while Subtitles opens). */
                           val resend: Boolean = true) {
    val standardLayout get() = fontSize == 2 && position == "center" && maxLines == 5
}

sealed class DisplayState {
    data object Idle : DisplayState()
    data class Opening(val pages: Int) : DisplayState()
    data class Showing(val page: Int, val pages: Int, val text: String) : DisplayState()
    data class Done(val pages: Int) : DisplayState()
    data class Failed(val reason: String) : DisplayState()
}

/**
 * Text-only lens display using the SUBTITLE(19) temporary session of the original firmware
 * (start type 7 → glasses ack type 8 code 1/2 → text type 5 (≤96 chars) → stop type 3).
 * Long answers are paged. The microphone is not started.
 */
class LensDisplay(
    private val port: GlassesPort,
    private val scope: CoroutineScope,
    private val random: Randomness,
) : BusinessListener {
    private val _state = MutableStateFlow<DisplayState>(DisplayState.Idle)
    val state: StateFlow<DisplayState> = _state
    private var sid = ""
    private var ack: CompletableDeferred<Int>? = null
    private var closedByGlasses = false
    private var job: Job? = null
    val active: Boolean get() = sid.isNotEmpty()

    init { port.addBusinessListener(this) }

    override fun onBusiness(business: Int, envelope: Envelope) {
        if (business != Business.SUBTITLE) return
        scope.launch {
            val body = try { JSONObject(envelope.jsonText()) } catch (_: Exception) { return@launch }
            if (sid.isEmpty() || body.optString("sid") != sid) return@launch
            when (envelope.type) {
                8 -> ack?.complete(body.optInt("code", -1))
                3 -> { closedByGlasses = true; DiagnosticLog.i("LENS", "óculos encerraram a exibição") }
                4 -> { closedByGlasses = true; DiagnosticLog.i("LENS", "óculos detectaram áudio; exibição interrompida") }
            }
        }
    }

    /** Safe from any thread; [onFinish] receives null on success or a failure reason. */
    fun show(raw: String, settings: DisplaySettings, onFinish: (String?) -> Unit = {}) {
        scope.launch {
            job?.cancel()
            job = scope.launch { onFinish(run(raw, settings)) }
        }
    }

    fun stop() { scope.launch { job?.cancel(); closeSession() } }

    private suspend fun run(raw: String, settings: DisplaySettings): String? {
        if (!port.isReady) return fail("Óculos não conectado")
        val text = DisplayText.clean(raw, settings.stripAccents)
        val pages = DisplayText.pages(text, settings.pageChars.coerceIn(20, 96))
        if (pages.isEmpty()) return fail("Nada para exibir")
        if (sid.isNotEmpty()) closeSession()
        _state.value = DisplayState.Opening(pages.size)
        DiagnosticLog.i("LENS", "abrindo exibição: ${pages.size} página(s), ${text.length} caracteres")
        var code = open(settings.fontSize, settings.position, settings.maxLines) ?: return fail("Falha ao abrir exibição")
        if (code != 1 && code != 2 && !settings.standardLayout) {
            // Experimental layout refused: fall back to the layout known to work.
            DiagnosticLog.w("LENS", "layout tamanho=${settings.fontSize} posição=${settings.position} linhas=${settings.maxLines} recusado ($code); usando o padrão")
            closeSession()
            code = open(2, "center", 5) ?: return fail("Falha ao abrir exibição")
        }
        if (code == -1) { closeSession(); return fail("Os óculos não confirmaram a exibição (10 s)") }
        if (code != 1 && code != 2) { closeSession(); return fail("Os óculos recusaram a exibição (código $code)") }
        delay(1200)  // the glasses open their Subtitles app at the same moment; text sent at once may be lost
        for ((index, page) in pages.withIndex()) {
            if (closedByGlasses) { sid = ""; return fail("Exibição encerrada nos óculos") }
            val total = if (index == pages.lastIndex) settings.lingerSeconds * 1000L else settings.pageSeconds * 1000L
            var shown = 0L
            while (shown < total) {
                if (closedByGlasses) { sid = ""; return fail("Exibição encerrada nos óculos") }
                // Caption formats: the page first as a sentence in progress (status 0), then final on every re-send.
                port.sendEnvelope(Business.SUBTITLE, 5, Commands.displayText(sid, page, settings.textFormat, final = shown > 0 || !settings.resend))
                    ?.let { closeSession(); return fail("Falha ao enviar texto: $it") }
                if (shown == 0L) _state.value = DisplayState.Showing(index + 1, pages.size, page)
                val step = if (settings.resend) minOf(2000L, total - shown) else total - shown
                delay(step); shown += step
            }
        }
        closeSession()
        _state.value = DisplayState.Done(pages.size)
        return null
    }

    /** Opens a session; the glasses' ack code, -1 on timeout, null when the request could not be sent. */
    private suspend fun open(fontSize: Int, position: String, maxLines: Int): Int? {
        sid = newSid(random); closedByGlasses = false
        val waiter = CompletableDeferred<Int>().also { ack = it }
        port.sendEnvelope(Business.SUBTITLE, 7, Commands.displayStart(sid, fontSize, position, maxLines))?.let { DiagnosticLog.w("LENS", "abertura: $it"); sid = ""; return null }
        return withTimeoutOrNull(10_000) { waiter.await() } ?: -1
    }

    private suspend fun closeSession() {
        val current = sid
        if (current.isEmpty()) return
        sid = ""; ack = null
        if (!closedByGlasses && port.isReady) {
            port.sendEnvelope(Business.SUBTITLE, 3, Commands.displayStop(current))?.let { DiagnosticLog.w("LENS", "encerramento não enviado: $it") }
        }
    }

    private fun fail(reason: String): String { DiagnosticLog.w("LENS", reason); _state.value = DisplayState.Failed(reason); return reason }
}
