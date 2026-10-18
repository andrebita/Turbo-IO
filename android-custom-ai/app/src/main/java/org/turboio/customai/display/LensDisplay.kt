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
    /** Waiting for the glasses' 19/2 (start result): code and the session id they use. */
    private var started: CompletableDeferred<Pair<Int, String>>? = null
    private var closedByGlasses = false
    /** Caption session the glasses accepted (19/2 code 1). On 1.0.5.5 it stays open after 19/3, so later texts reuse it. */
    private var lastSession = ""
    /** The current [sid] is a caption session (closed with reason 2) rather than a preview (reason 10). */
    private var inSession = false
    /** Layout last saved on the glasses with the persistent 19/7; null = never changed by this app. */
    private var savedLayout: Triple<Int, String, Int>? = null
    private var job: Job? = null
    val active: Boolean get() = sid.isNotEmpty()

    init { port.addBusinessListener(this) }

    override fun onBusiness(business: Int, envelope: Envelope) {
        if (business != Business.SUBTITLE) return
        scope.launch {
            val body = try { JSONObject(envelope.jsonText()) } catch (_: Exception) { return@launch }
            // The start result may carry the glasses' own protocol session id.
            if (envelope.type == 2) { started?.let { w -> DiagnosticLog.i("LENS", "19/2 código ${body.optInt("code", -1)}"); w.complete(body.optInt("code", -1) to body.optString("sid")) }; return@launch }
            if (envelope.type == 3 && body.optString("sid") == lastSession && lastSession.isNotEmpty()) { lastSession = ""; DiagnosticLog.i("LENS", "óculos encerraram a sessão de legendas") }
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
        var format = settings.textFormat
        // Official phone-started caption session (19/1 → glasses 19/2 → text), without 19/7: that one is the display
        // preview, which shows the Subtitles intro screen ("During conversations, live captions…").
        if (Commands.isSession(format)) saveLayout(settings)
        val session = Commands.isSession(format) && startSession()
        if (session) format = Commands.sessionTextFormat(format)
        else {
            if (Commands.isSession(format)) { DiagnosticLog.w("LENS", "sessão de legendas não iniciada; usando o formato antigo"); format = "legacy" }
            openPreview(settings)?.let { return it }
        }
        delay(if (session) 300 else 1200)  // with the preview the glasses open their Subtitles app at the same moment; text sent at once may be lost
        for ((index, page) in pages.withIndex()) {
            if (closedByGlasses) { sid = ""; lastSession = ""; return fail("Exibição encerrada nos óculos") }
            val total = if (index == pages.lastIndex) settings.lingerSeconds * 1000L else settings.pageSeconds * 1000L
            var shown = 0L
            while (shown < total) {
                if (closedByGlasses) { sid = ""; lastSession = ""; return fail("Exibição encerrada nos óculos") }
                // Caption formats: the page first as a sentence in progress (status 0), then final on every re-send.
                port.sendEnvelope(Business.SUBTITLE, 5, Commands.displayText(sid, page, format, final = shown > 0 || !settings.resend))
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

    /**
     * Size/position for caption sessions: saved on the glasses with the official persistent 19/7 (scope global,
     * is_display false: no preview), only when it differs from the standard or from what this app saved before.
     */
    private suspend fun saveLayout(settings: DisplaySettings) {
        val layout = Triple(settings.fontSize, settings.position, settings.maxLines)
        if (layout == savedLayout || (savedLayout == null && settings.standardLayout)) return
        sid = newSid(random); closedByGlasses = false
        val waiter = CompletableDeferred<Int>().also { ack = it }
        val error = port.sendEnvelope(Business.SUBTITLE, 7, Commands.displayStart(sid, settings.fontSize, settings.position, settings.maxLines, persist = true))
        val code = if (error == null) withTimeoutOrNull(5_000) { waiter.await() } else null
        DiagnosticLog.i("LENS", "layout salvo nos óculos: ${code?.let { "código $it" } ?: error ?: "sem resposta"}")
        if (code == 1 || code == 2) savedLayout = layout
        sid = ""; ack = null
    }

    /** 19/1 → 19/2. True when the glasses accepted (their sid adopted) or an earlier accepted session is still open. */
    private suspend fun startSession(): Boolean {
        repeat(2) { attempt ->
            sid = newSid(random); closedByGlasses = false
            val waiter = CompletableDeferred<Pair<Int, String>>().also { started = it }
            port.sendEnvelope(Business.SUBTITLE, 1, Commands.captionStart(sid))?.let { started = null; sid = ""; DiagnosticLog.w("LENS", "início não enviado: $it"); return false }
            val result = withTimeoutOrNull(10_000) { waiter.await() }
            started = null
            DiagnosticLog.i("LENS", "início da sessão de legendas: ${result?.let { "código ${it.first}" } ?: "sem resposta (10 s)"}")
            when {
                result?.first == 1 -> { if (Regex("^[0-9a-f]{32}$").matches(result.second)) sid = result.second; lastSession = sid; inSession = true; return true }
                // "not_idle_state": our previous session is still open on the glasses; keep using it.
                result?.first == 4 && lastSession.isNotEmpty() -> { sid = lastSession; inSession = true; DiagnosticLog.i("LENS", "sessão anterior ainda aberta; reaproveitada"); return true }
                // Just closed (19/3 reason 2) and not idle yet: one more try a moment later.
                result?.first == 4 && attempt == 0 -> delay(1_000)
                else -> { sid = ""; return false }
            }
        }
        sid = ""; return false
    }

    /** 19/7 display preview with the chosen layout (the older path); null when open, else the failure reason. */
    private suspend fun openPreview(settings: DisplaySettings): String? {
        var code = open(settings.fontSize, settings.position, settings.maxLines) ?: return fail("Falha ao abrir exibição")
        if (code != 1 && code != 2 && !settings.standardLayout) {
            // Experimental layout refused: fall back to the layout known to work.
            DiagnosticLog.w("LENS", "layout tamanho=${settings.fontSize} posição=${settings.position} linhas=${settings.maxLines} recusado ($code); usando o padrão")
            closeSession()
            code = open(2, "center", 5) ?: return fail("Falha ao abrir exibição")
        }
        if (code == -1) { closeSession(); return fail("Os óculos não confirmaram a exibição (10 s)") }
        if (code != 1 && code != 2) { closeSession(); return fail("Os óculos recusaram a exibição (código $code)") }
        return null
    }

    /** Opens a session; the glasses' ack code, -1 on timeout, null when the request could not be sent. */
    private suspend fun open(fontSize: Int, position: String, maxLines: Int): Int? {
        sid = newSid(random); closedByGlasses = false; inSession = false
        val waiter = CompletableDeferred<Int>().also { ack = it }
        port.sendEnvelope(Business.SUBTITLE, 7, Commands.displayStart(sid, fontSize, position, maxLines))?.let { DiagnosticLog.w("LENS", "abertura: $it"); sid = ""; return null }
        return withTimeoutOrNull(10_000) { waiter.await() } ?: -1
    }

    private suspend fun closeSession() {
        val current = sid
        if (current.isEmpty()) return
        val session = inSession
        sid = ""; ack = null; inSession = false
        if (session) lastSession = ""
        if (!closedByGlasses && port.isReady) {
            // Session: reason 2 (closed by the user in the app), the official normal end. Preview: reason 10.
            port.sendEnvelope(Business.SUBTITLE, 3, Commands.displayStop(current, session))?.let { DiagnosticLog.w("LENS", "encerramento não enviado: $it") }
        }
    }

    private fun fail(reason: String): String { DiagnosticLog.w("LENS", reason); _state.value = DisplayState.Failed(reason); return reason }
}
