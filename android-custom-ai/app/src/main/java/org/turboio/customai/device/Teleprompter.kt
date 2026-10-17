package org.turboio.customai.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.turboio.customai.ble.BusinessListener
import org.turboio.customai.ble.GlassesPort
import org.turboio.customai.ble.Randomness
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.display.sendEnvelope
import org.turboio.customai.protocol.Business
import org.turboio.customai.protocol.Envelope
import org.turboio.customai.protocol.FileMessage
import org.turboio.customai.protocol.FileTransfer
import org.turboio.customai.protocol.OutboundFile
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * The glasses' own teleprompter, driven from the phone (port of harmony-sdk TeleprompterSession, same wire as the iOS
 * client's NewsTeleprompter). Session control goes over business 20 (JSON envelopes); the script itself is a temporary
 * file that the glasses pull chunk by chunk. Nothing is installed or changed on the glasses: the file channel is open
 * only while a session is active ([active] is the session's file lease).
 *
 * Flow: 20/2 prepare → glasses 20/2 action 2 code 1 → file info → glasses request chunks (3 → 4) → 5 success +
 * 20/2 code 7 → ready → 20/3 start, 20/4 pause, 20/5 resume, 20/7 speed → 20/6 exit.
 *
 * Which file business the teleprompter listens on is not known for Strix OS 1.0.5.5 (18 with the session id as the name
 * was ignored). The info is therefore announced in [ROUTES] order, the next one only when the previous got no answer
 * in [retryInfoMs]: first as the official app 1.0.7 uploads (business 17, "task_…" id, plain .txt name).
 */
class Teleprompter(
    private val port: GlassesPort,
    private val scope: CoroutineScope,
    private val random: Randomness,
    private val busy: () -> String? = { null },
    private val prepareMs: Long = 30_000,
    private val transferMs: Long = 120_000,
    private val controlMs: Long = 10_000,
    private val retryInfoMs: Long = 6_000,
) : BusinessListener {
    enum class Phase(val label: String) {
        IDLE("parado"), PREPARING("preparando"), TRANSFERRING("enviando roteiro"), READY("pronto"), CONTROLLING("aguardando óculos"),
        RUNNING("rolando"), PAUSED("pausado"), CLOSING("saindo"), ERROR("erro")
    }

    data class State(val phase: Phase = Phase.IDLE, val note: String = "Cole o roteiro e envie aos óculos",
                     val progress: Int = 0, val offset: Int = 0, val speed: Int = 120)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    val active get() = _state.value.phase != Phase.IDLE

    private val lock = Mutex()
    private var epoch = 0
    private var timer: Job? = null
    private var did = ""
    private var total = 0
    private var checksum = ""
    private var file: OutboundFile? = null
    /** Every announced form of the file; the first one the glasses answer becomes [file]. */
    private val attempts = ArrayList<Pair<FileRoute, OutboundFile>>()
    private var chosen: FileRoute? = null
    private var fileStarted = false
    private var businessReady = false
    private var pendingControl = 0
    private var beforeControl = Phase.READY
    private var queued = 0

    init {
        port.addBusinessListener(this)
        port.addRawListener { business, payload -> if (business in FileTransfer.BUSINESSES) receive(business, payload.copyOf(), null) }
    }

    /** All state lives on the glasses thread (the scope's dispatcher), like the listeners that feed it. */
    private val here get() = scope.coroutineContext.minusKey(Job)

    private fun update(f: (State) -> State) { _state.value = f(_state.value) }
    private fun note(text: String) = update { it.copy(note = text) }

    private fun deadline(ms: Long, message: String) {
        timer?.cancel()
        val e = epoch
        timer = scope.launch { delay(ms); if (e == epoch && active) fail(message) }
    }

    private suspend fun command(type: Int, body: JSONObject): String? = port.sendEnvelope(Business.TELEPROMPTER, type, body.toString())

    private suspend fun sendFile(payload: ByteArray, business: Int): String? = suspendCancellableCoroutine { cont ->
        port.sendFile(payload, business) { error -> if (cont.isActive) cont.resume(error) }
    }

    /** Validates and announces the script; null = request sent (the transfer then runs on the glasses' requests). */
    suspend fun prepare(text: String, speed: Int): String? = withContext(here) { prepareHere(text, speed) }

    private suspend fun prepareHere(text: String, speed: Int): String? {
        if (active) return "Já existe uma sessão; saia dela primeiro"
        if (!port.isReady) return "Conecte os óculos"
        busy()?.let { return it }
        if (speed !in SPEEDS || text.isBlank() || text.length > MAX_CHARS) return "Roteiro de até $MAX_CHARS caracteres; velocidade 60–240"
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > FileTransfer.MAX_FILE) { bytes.fill(0); return "Roteiro grande demais (${bytes.size} bytes; máximo ${FileTransfer.MAX_FILE})" }
        val e = ++epoch
        did = uuid(); total = bytes.size; checksum = checksum(bytes); fileStarted = false; businessReady = false
        file = OutboundFile(did, bytes, md5(bytes)); bytes.fill(0)
        attempts.clear(); chosen = null
        _state.value = State(Phase.PREPARING, "Pedindo aos óculos para abrir o teleprompter", 0, 0, speed)
        deadline(prepareMs, "Os óculos não responderam ao preparo; saia e tente de novo")
        val error = lock.withLock {
            if (e != epoch) null
            else command(2, JSONObject().put("did", did).put("action", 1).put("total", total).put("checksum", checksum).put("scroll", 2)
                .put("speed", speed).put("pageOffset", 0).put("highLightOffset", 0))
        }
        if (error != null) { if (e == epoch) fail("Preparo não enviado: $error"); return error }
        if (e == epoch) note("Aguardando os óculos aceitarem o roteiro")
        DiagnosticLog.i(TAG, "preparo enviado (${total} bytes, velocidade $speed)")
        return null
    }

    override fun onBusiness(business: Int, envelope: Envelope) {
        if (business == Business.TELEPROMPTER) receive(business, null, envelope)
    }

    private fun receive(business: Int, payload: ByteArray?, envelope: Envelope?) {
        if (!active) return
        if (queued >= 16) { fail("Fila de mensagens do teleprompter cheia; saia desta sessão"); return }
        val e = epoch; queued++
        scope.launch {
            try { lock.withLock { if (e == epoch) handle(business, payload, envelope, e) } }
            catch (x: Exception) { if (e == epoch) fail("Falha no envio ou na resposta dos óculos (${x.message ?: x.javaClass.simpleName})") }
            finally { queued--; payload?.fill(0) }
        }
    }

    private suspend fun handle(business: Int, payload: ByteArray?, envelope: Envelope?, e: Int) {
        val phase = _state.value.phase
        if (business in FileTransfer.BUSINESSES) {
            if (!fileStarted || phase == Phase.ERROR || phase == Phase.CLOSING || payload == null) return
            // Business 17 may carry other traffic: only well-formed messages for one of our ids are handled.
            val message = runCatching { FileTransfer.decode(payload) }.getOrElse { DiagnosticLog.d(TAG, "mensagem de arquivo ignorada em $business"); return }
            val (route, f) = attempts.firstOrNull { it.first.business == business && it.second.uuid == message.uuid } ?: return
            if (chosen == null) {
                chosen = route
                attempts.filter { it.second !== f }.forEach { it.second.close() }
                file?.close(); file = f
                DiagnosticLog.i(TAG, "óculos responderam ao arquivo pela rota ${route.label}")
            } else if (route != chosen) return
            val chunk = f.receive(message)
            if (chunk != null) {
                val bytes = FileTransfer.encode(chunk)
                val error = sendFile(bytes, route.business); bytes.fill(0); chunk.data.fill(0)
                if (error != null) throw IllegalStateException(error)
                if (e != epoch) return
                f.committed(chunk)
            }
            if (e != epoch) return
            val progress = if (total == 0) 0 else f.sentBytes * 100 / total
            update { it.copy(progress = progress, note = if (f.complete) "Roteiro recebido pelos óculos; aguardando o teleprompter" else "Enviando roteiro: $progress%") }
            ready()
            return
        }
        val j = runCatching { JSONObject(envelope!!.jsonText()) }.getOrNull() ?: return
        if (j.optString("did") != did) return
        val type = envelope!!.type; val action = j.optInt("action"); val code = j.optInt("code", -1)
        if (type == 6 && (action == 1 || (action == 2 && code == 1))) {
            if (action == 1) command(6, JSONObject().put("did", did).put("action", 2).put("code", 1))
            if (e == epoch) clear("Teleprompter encerrado; o roteiro continua no celular")
            return
        }
        if (type == 6 && action == 2) {
            // Seen on 1.0.5.5: code 3 about 30 s after a prepare whose file never arrived.
            if (e == epoch) clear("Os óculos encerraram o teleprompter (código $code)" + if (chosen == null && fileStarted) "; não responderam a nenhuma forma de envio do arquivo" else "")
            return
        }
        if (phase == Phase.ERROR || phase == Phase.CLOSING) return
        if (type == 2 && action == 2) {
            if (code != 1 && code != 7) throw IllegalStateException("preparo recusado (código $code)")
            if (!fileStarted && code != 1) throw IllegalStateException("conclusão antes do arquivo")
            if (!fileStarted) {
                if (file == null) throw IllegalStateException("arquivo indisponível")
                fileStarted = true
                update { it.copy(phase = Phase.TRANSFERRING) }
                deadline(transferMs, "Os óculos não confirmaram o roteiro em 2 minutos; saia e tente de novo")
                announce(0, e)
            } else if (code == 7) { businessReady = true; ready() }
        }
        if (type in setOf(3, 4, 5, 7) && action == 2 && phase == Phase.CONTROLLING && type == pendingControl) {
            if (code != 1) throw IllegalStateException("comando recusado (código $code)")
            timer?.cancel(); timer = null
            val next = when (type) { 3, 5 -> Phase.RUNNING; 4 -> Phase.PAUSED; else -> beforeControl }
            pendingControl = 0
            update { it.copy(phase = next, note = when (next) { Phase.RUNNING -> "Rolando nos óculos"; Phase.PAUSED -> "Pausado"; else -> "Velocidade alterada" }) }
        }
        if (type in setOf(4, 5, 7, 8) && action == 1) {
            // Changes made on the glasses (touchpad) and reading position reports.
            if (type == 8) j.optInt("pageOffset", -1).takeIf { it in 0..total }?.let { o -> update { it.copy(offset = o) } }
            if (type == 4) update { it.copy(phase = Phase.PAUSED) }
            if (type == 5) update { it.copy(phase = Phase.RUNNING) }
            if (type == 7) j.optInt("speed", -1).takeIf { it in SPEEDS }?.let { s -> update { it.copy(speed = s) } }
            command(type, JSONObject().put("did", did).put("action", 2).put("code", 1))
            if (e == epoch && type != 8) note("Alterado nos óculos: ${_state.value.phase.label}")
        }
    }

    /** Announces the file in the form [ROUTES][index]; with no answer in [retryInfoMs], tries the next form. */
    private suspend fun announce(index: Int, e: Int) {
        val base = file ?: return
        val route = ROUTES[index]
        val f = base.copy(if (route.taskId) taskId() else did, FILE_NAME, route.business)
        attempts.add(route to f)
        val info = f.info()
        val error = sendFile(info, route.business); info.fill(0)
        DiagnosticLog.i(TAG, if (error == null) "arquivo anunciado pela rota ${route.label}" else "anúncio pela rota ${route.label} falhou: $error")
        if (error != null && index == 0) throw IllegalStateException(error)
        if (e == epoch) note(if (index == 0) "Informações do arquivo enviadas; os óculos vão pedir o roteiro em partes"
            else "Sem resposta dos óculos; tentando de outra forma (${index + 1} de ${ROUTES.size})")
        if (index == ROUTES.lastIndex) return
        scope.launch {
            delay(retryInfoMs)
            if (e != epoch || chosen != null || _state.value.phase != Phase.TRANSFERRING) return@launch
            lock.withLock { if (e == epoch && chosen == null) announce(index + 1, e) }
        }
    }

    private fun ready() {
        val s = _state.value
        if (s.phase == Phase.TRANSFERRING && businessReady && file?.complete == true) {
            timer?.cancel(); timer = null
            update { it.copy(phase = Phase.READY, progress = 100, note = "Roteiro pronto nos óculos; toque em Iniciar") }
            DiagnosticLog.i(TAG, "roteiro confirmado pelos óculos")
        }
    }

    /** 3 start, 4 pause, 5 resume, 7 speed. Null = sent (the glasses' confirmation updates the state). */
    suspend fun control(type: Int, speed: Int = _state.value.speed): String? = withContext(here) { controlHere(type, speed) }

    private suspend fun controlHere(type: Int, speed: Int): String? {
        val phase = _state.value.phase
        if (type !in setOf(3, 4, 5, 7) || phase !in setOf(Phase.READY, Phase.RUNNING, Phase.PAUSED)) return "Aguarde o roteiro ficar pronto ou a resposta anterior"
        if ((type == 3 && phase != Phase.READY) || (type == 4 && phase != Phase.RUNNING) || (type == 5 && phase != Phase.PAUSED)) return "Não disponível agora (${phase.label})"
        if (speed !in SPEEDS) return "Velocidade 60–240"
        val body = JSONObject().put("did", did).put("action", 1)
        when (type) {
            3 -> body.put("total", total).put("checksum", checksum).put("scroll", 2).put("speed", _state.value.speed).put("pageOffset", 0).put("highLightOffset", 0)
            4 -> body.put("offset", _state.value.offset).put("code", 1).put("isCompleted", false)
            7 -> { body.put("scroll", 2).put("speed", speed); update { it.copy(speed = speed) } }
        }
        beforeControl = phase; pendingControl = type
        update { it.copy(phase = Phase.CONTROLLING, note = "Aguardando os óculos confirmarem") }
        val e = epoch
        deadline(controlMs, "Os óculos não confirmaram o comando; saia desta sessão")
        val error = lock.withLock { if (e == epoch) command(type, body) else null }
        if (error != null && e == epoch) fail("Comando não enviado: $error")
        return error
    }

    /** Ends the session (cancels the file if it was started, then 20/6). */
    suspend fun stop() = withContext(here) { stopHere() }

    private suspend fun stopHere() {
        if (!active) return
        val id = did; val started = fileStarted; val e = ++epoch
        val announced = attempts.filter { chosen == null || it.first == chosen }.map { it.first.business to it.second.uuid }
        closeFiles()
        update { it.copy(phase = Phase.CLOSING, note = "Saindo; aguardando os óculos") }
        timer?.cancel()
        timer = scope.launch { delay(controlMs); if (e == epoch && active) clear("Saída não confirmada pelos óculos; se o teleprompter continuar aberto, saia pelo toque nos óculos") }
        lock.withLock {
            if (e != epoch) return@withLock
            if (started) for ((business, uuid) in announced) {
                val bytes = runCatching { FileTransfer.encode(FileMessage(6, uuid, reason = "user-stop")) }.getOrNull()
                if (bytes != null) { sendFile(bytes, business); bytes.fill(0) }
            }
            command(6, JSONObject().put("did", id).put("action", 1))
        }
    }

    fun disconnected() { if (active) clear("Conexão perdida; o teleprompter não é retomado sozinho") }

    private fun fail(text: String) {
        epoch++; timer?.cancel(); timer = null; closeFiles()
        update { it.copy(phase = Phase.ERROR, note = text) }
        DiagnosticLog.w(TAG, text)
    }

    private fun clear(text: String) {
        epoch++; timer?.cancel(); timer = null; closeFiles(); file = null; attempts.clear(); chosen = null
        did = ""; fileStarted = false; businessReady = false; pendingControl = 0
        update { it.copy(phase = Phase.IDLE, note = text, progress = 0) }
        DiagnosticLog.i(TAG, text)
    }

    private fun closeFiles() { file?.close(); attempts.forEach { it.second.close() } }

    /** Transfer id in the official app's form, "task_<counter>_<milliseconds>". */
    private fun taskId(): String = "task_${++tasks}_${System.currentTimeMillis()}"

    private fun uuid(): String {
        val r = random.bytes(16)
        r[6] = ((r[6].toInt() and 0x0f) or 0x40).toByte(); r[8] = ((r[8].toInt() and 0x3f) or 0x80).toByte()
        val h = r.joinToString("") { "%02x".format(it) }; r.fill(0)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    /** One way of addressing the file: [business] 17 or 18 (also the info's busId); [taskId] = "task_…" id, else the session id. */
    data class FileRoute(val business: Int, val taskId: Boolean, val label: String)

    companion object {
        private const val TAG = "PROMPTER"
        private var tasks = 0
        const val FILE_NAME = "roteiro.txt"
        val ROUTES = listOf(
            FileRoute(Business.SMALL_FILE, true, "17 · task"),
            FileRoute(Business.SMALL_FILE, false, "17 · sessão"),
            FileRoute(Business.FILE, true, "18 · task"),
        )
        const val MAX_CHARS = 12_000
        val SPEEDS = 60..240

        /** UTF-8 FNV-1a, 8 hex digits (same contract as the iOS and HarmonyOS clients; distinct from the file MD5). */
        fun checksum(bytes: ByteArray): String {
            var hash = 2166136261L
            for (b in bytes) hash = ((hash xor (b.toLong() and 0xff)) * 16777619L) and 0xffffffffL
            return "%08x".format(hash)
        }

        fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
