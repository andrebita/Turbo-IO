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

data class DisplaySettings(val pageSeconds: Int = 5, val lingerSeconds: Int = 6, val stripAccents: Boolean = false, val pageChars: Int = 90)

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
        sid = newSid(random); closedByGlasses = false
        val waiter = CompletableDeferred<Int>().also { ack = it }
        _state.value = DisplayState.Opening(pages.size)
        DiagnosticLog.i("LENS", "abrindo exibição: ${pages.size} página(s), ${text.length} caracteres")
        port.sendEnvelope(Business.SUBTITLE, 7, Commands.displayStart(sid))?.let { sid = ""; return fail("Falha ao abrir exibição: $it") }
        val code = withTimeoutOrNull(10_000) { waiter.await() }
        if (code == null) { closeSession(); return fail("Os óculos não confirmaram a exibição (10 s)") }
        if (code != 1 && code != 2) { closeSession(); return fail("Os óculos recusaram a exibição (código $code)") }
        for ((index, page) in pages.withIndex()) {
            if (closedByGlasses) { sid = ""; return fail("Exibição encerrada nos óculos") }
            port.sendEnvelope(Business.SUBTITLE, 5, Commands.displayText(sid, page))?.let { closeSession(); return fail("Falha ao enviar texto: $it") }
            _state.value = DisplayState.Showing(index + 1, pages.size, page)
            delay(if (index == pages.lastIndex) settings.lingerSeconds * 1000L else settings.pageSeconds * 1000L)
        }
        closeSession()
        _state.value = DisplayState.Done(pages.size)
        return null
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
