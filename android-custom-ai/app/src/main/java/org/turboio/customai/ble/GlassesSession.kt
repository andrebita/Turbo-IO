package org.turboio.customai.ble

import org.json.JSONObject
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.protocol.Business
import org.turboio.customai.protocol.Commands
import org.turboio.customai.protocol.Envelope
import org.turboio.customai.protocol.EnvelopeCodec
import org.turboio.customai.protocol.FrameCodec
import org.turboio.customai.protocol.FramePacket
import org.turboio.customai.protocol.FrameStream
import org.turboio.customai.protocol.GuestAuthExchange
import org.turboio.customai.protocol.Hex
import org.turboio.customai.protocol.VersionExchange
import org.turboio.customai.protocol.VersionReply
import org.turboio.customai.protocol.VersionRequest

data class SavedGlasses(val address: String, val peerHex: String, val name: String, val autoConnect: Boolean = true)

interface PeerStore {
    fun load(): SavedGlasses?
    fun save(value: SavedGlasses)
    fun clear()
}

class DeviceInfo(val phoneName: String, val manufacturer: String, val model: String)

enum class Phase { IDLE, CONNECTING, AUTHENTICATING, PAIRING, INITIALIZING, READY, RETRYING, FAILED }

data class SessionStatus(
    val phase: Phase = Phase.IDLE,
    val note: String = "Nenhum óculos conectado",
    val deviceName: String = "",
    val battery: Int = -1,
    val mtu: Int = 23,
    val protocolVersion: Int = -1,
    val versionShape: String = "",
    val readySince: Long = 0,
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val lastReason: String = "",
    val retries: Int = 0,
    val framesIn: Long = 0,
    val framesOut: Long = 0,
    val statusReplies: Int = 0,
    /** Version-like fields reported by the glasses' general status (e.g. firmware), if any. */
    val glassesVersions: String = "",
    /** Key names (not values) present in the last general status reply. */
    val statusKeys: String = "",
)

fun interface BusinessListener { fun onBusiness(business: Int, envelope: Envelope) }

/**
 * Connection orchestrator for the ORIGINAL firmware, reproducing the HarmonyOS client sequence that was
 * validated on hardware (ProbeSession.ets + BleVersionProbe + BleGuestAuthProbe):
 *
 *  1. version-only probe on a first GATT link (learns the 6-byte protocol peer id), link closed;
 *  2. second link: version + guest proof exchange (peer proof verified before anything else);
 *  3. system Bluetooth bond (only after the proof), 4. LAUNCHER status query, 5. READY.
 *
 * All state is confined to [scheduler]. Unauthenticated links have strict byte/time limits. Outbound
 * business traffic is restricted by [Business.OUTBOUND]; OTA/file-transfer IDs can never be sent.
 */
class GlassesSession(
    private val scheduler: Scheduler,
    private val linkFactory: () -> GlassesLink,
    private val bond: BondManager,
    private val random: Randomness,
    private val identity: () -> ByteArray,
    private val device: DeviceInfo,
    private val store: PeerStore,
) : GlassesPort {
    private val tag = "BLE"
    @Volatile var status = SessionStatus(); private set
    var onStatus: (SessionStatus) -> Unit = {}
    private val businessListeners = ArrayList<BusinessListener>()

    private var intent = 0L
    private var link: GlassesLink? = null
    private var target: SavedGlasses? = null
    private var peer: ByteArray? = null
    private var authenticated = false
    private var exchange = GuestAuthExchange()
    private val stream = FrameStream()
    private var messageNumber = 0
    private var writeBusy = false
    private val rxQueue = ArrayDeque<ByteArray>()
    private val txQueue = ArrayDeque<Pending>()
    private val timers = ArrayList<Cancellable>()
    private var retryTimer: Cancellable? = null
    private var bondRequest: Cancellable? = null
    private var retries = 0
    private var unauthenticatedTx = 0
    private var unauthenticatedRx = 0
    private var stageDeadline: Cancellable? = null

    private class Pending(val frame: ByteArray, val done: (String?) -> Unit, val group: Any? = null)

    /** Sees every message accepted for sending (feature test capture); called on the glasses thread. */
    @Volatile var outboundTap: ((business: Int, payload: ByteArray) -> Unit)? = null

    /** Business 18 is accepted only while this returns true (an active teleprompter session). */
    @Volatile var fileLease: () -> Boolean = { false }

    /**
     * A file-transfer message larger than one ATT write is sent as ONE frame split across consecutive writes (the
     * producer behaviour of the official Android app, also used by the HarmonyOS client). The parts are queued together
     * so nothing interleaves; a failed part drops the rest of that frame.
     */
    override fun sendFile(payload: ByteArray, done: (String?) -> Unit) = scheduler.post {
        val reason = when {
            status.phase != Phase.READY || link == null -> "Óculos não conectado"
            !fileLease() -> "Canal de arquivo fechado (só durante o teleprompter)"
            else -> org.turboio.customai.protocol.FileTransfer.outboundProblem(payload)
        }
        if (reason != null) { DiagnosticLog.w(tag, "arquivo recusado: $reason"); done(reason); return@post }
        outboundTap?.let { tap -> runCatching { tap(Business.FILE, payload) } }
        val frame = FrameCodec.encode(FramePacket(messageNumber, 0, null, ByteArray(0), Business.FILE, payload))
        messageNumber = (messageNumber + 1) and 0xffff
        enqueueSplit(frame, 128, done)
    }

    /** One frame across consecutive ATT writes, queued together; a failed part drops the rest of that frame. */
    private fun enqueueSplit(frame: ByteArray, maxParts: Int, done: (String?) -> Unit) {
        val size = status.mtu - 3
        val parts = (0 until frame.size step size).map { frame.copyOfRange(it, minOf(frame.size, it + size)) }
        if (parts.size > maxParts || txQueue.size + parts.size > 200) { done("Conteúdo grande demais para o MTU atual (${status.mtu})"); return }
        val group = Any(); var failed = false
        parts.forEachIndexed { i, part ->
            val partDone: (String?) -> Unit = partDone@{ error ->
                if (failed) return@partDone
                if (error != null) { failed = true; txQueue.removeAll { it.group === group }; done(error) }
                else if (i == parts.lastIndex) done(null)
            }
            txQueue.addLast(Pending(part, partDone, group))
        }
        pump()
    }

    override fun addBusinessListener(listener: BusinessListener) = scheduler.post { businessListeners.add(listener) }
    private val rawListeners = ArrayList<(Int, ByteArray) -> Unit>()
    override fun addRawListener(listener: (business: Int, payload: ByteArray) -> Unit) { scheduler.post { rawListeners.add(listener) } }
    override val isReady: Boolean get() = status.phase == Phase.READY

    private fun publish(transform: (SessionStatus) -> SessionStatus) {
        status = transform(status)
        onStatus(status)
    }

    private fun phase(phase: Phase, note: String) {
        if (status.phase != phase) DiagnosticLog.i(tag, "fase ${status.phase} -> $phase")
        publish { it.copy(phase = phase, note = note) }
    }

    // ---------------------------------------------------------------- public API (any thread)

    fun connect(address: String, name: String) = scheduler.post {
        val saved = store.load()
        if (saved != null && saved.address != address) {
            phase(Phase.FAILED, "Já existe um óculos salvo. Use \"Esquecer óculos\" antes de trocar de dispositivo.")
            return@post
        }
        stopInternal("novo pedido de conexão")
        target = saved ?: SavedGlasses(address, "", Advertisementless(name), true)
        retries = 0
        beginVersionProbe()
    }

    fun reconnectSaved(): Boolean {
        val saved = store.load() ?: return false
        connect(saved.address, saved.name)
        return true
    }

    fun disconnect() = scheduler.post { stopInternal("desconexão pelo usuário"); phase(Phase.IDLE, "Desconectado pelo usuário") }

    /** Forgets the saved glasses (needed before re-binding the official app). Does not touch the system bond. */
    fun forget() = scheduler.post {
        stopInternal("esquecer óculos"); store.clear()
        publish { SessionStatus(note = "Óculos esquecido. Remova também o pareamento nas configurações de Bluetooth do Android para voltar ao app oficial.") }
    }

    /**
     * Sends one business envelope after READY. [done] receives null on success (radio write accepted —
     * NOT a device acknowledgement) or a pt-BR reason.
     */
    override fun send(business: Int, envelope: ByteArray, done: (String?) -> Unit) = scheduler.post {
        val reason = admit(business, envelope)
        if (reason != null) { DiagnosticLog.w(tag, "envio recusado business=$business: $reason"); done(reason); return@post }
        outboundTap?.let { tap -> runCatching { tap(business, envelope) } }
        val frame = FrameCodec.encode(FramePacket(messageNumber, 0, null, ByteArray(0), business, envelope))
        messageNumber = (messageNumber + 1) and 0xffff
        if (frame.size > status.mtu - 3) {
            // Home-screen configuration (15/18, e.g. a dashboard snapshot) may exceed one write: split like file frames.
            if (business == Business.LAUNCHER && EnvelopeCodec.decode(envelope).type == 18) { enqueueSplit(frame, 16, done); return@post }
            done("Conteúdo excede o quadro BLE atual (MTU ${status.mtu}); encurte o texto"); return@post
        }
        if (txQueue.size >= 32) { done("Fila de envio cheia; tente novamente"); return@post }
        txQueue.addLast(Pending(frame, done))
        pump()
    }

    /** Communication test: re-sends the read-only LAUNCHER status query; the reply increments statusReplies. */
    fun requestStatus(done: (String?) -> Unit) = send(Business.LAUNCHER, Commands.generalStatusQuery(), done)

    private fun admit(business: Int, envelope: ByteArray): String? {
        if (status.phase != Phase.READY || link == null) return "Óculos não conectado"
        val decoded = try { EnvelopeCodec.decode(envelope) } catch (_: Exception) { return "Envelope inválido" }
        val type = decoded.type
        if (!Business.outboundAllowed(business, type) || !Business.contentAllowed(business, type, decoded.jsonText())) {
            return "Comando não permitido (business=$business type=$type)"
        }
        return null
    }

    // ---------------------------------------------------------------- step 1: version probe

    private fun beginVersionProbe() {
        val target = target ?: return
        val token = ++intent
        phase(Phase.CONNECTING, "Conectando e verificando a identidade dos óculos…")
        val request = VersionExchange.request(versionInput(identity()))
        var sent = false
        var reply: VersionReply? = null
        var writeDone = false
        openLink(token, target.address, onReady = {
            sent = true
            val seq = random.bytes(2)
            val frame = FrameCodec.encode(FramePacket(((seq[0].toInt() and 0xff) shl 8) or (seq[1].toInt() and 0xff), 0, null, ByteArray(0), Business.HANDSHAKE, request))
            deadline(token, 5000, "version-timeout")
            writeRaw(token, frame) { error ->
                if (error != null) fail(token, "version-send-failed") else { writeDone = true; reply?.let { versionAccepted(token, it) } }
            }
        }, onPacket = { packet ->
            if (!sent || reply != null || packet.businessId != Business.HANDSHAKE || packet.flags != 0 || packet.address != null || packet.slices.isNotEmpty()) {
                throw IllegalStateException("response-profile")
            }
            reply = VersionExchange.reply(packet.payload)
            if (writeDone) versionAccepted(token, reply!!)
        })
    }

    private fun versionAccepted(token: Long, reply: VersionReply) {
        if (token != intent) return
        if (stream.pendingBytes() != 0) { fail(token, "version-trailing-data"); return }
        val hex = Hex.encode(reply.peerIdentifier)
        val saved = target!!
        if (saved.peerHex.isNotEmpty() && saved.peerHex != hex) { fail(token, "peer-mismatch"); return }
        peer = reply.peerIdentifier
        publish { it.copy(protocolVersion = reply.protocolVersion, versionShape = reply.shape()) }
        DiagnosticLog.i(tag, "versão recebida: protocolo=${reply.protocolVersion} campos=${reply.shape()}")
        clearTimers() // the probe's deadlines must not fire during the pause before authentication
        closeLink()
        // Yield before reopening, as the reference client does.
        scheduler.postDelayed(600) { if (token == intent) beginAuthentication() }
    }

    // ---------------------------------------------------------------- step 2: guest authentication

    private fun beginAuthentication() {
        val target = target ?: return
        val expected = peer ?: return
        val token = ++intent
        phase(Phase.AUTHENTICATING, "Identidade confirmada; autenticando…")
        exchange = GuestAuthExchange()
        openLink(token, target.address, onReady = {
            val seq = random.bytes(2)
            messageNumber = ((seq[0].toInt() and 0xff) shl 8) or (seq[1].toInt() and 0xff)
            val nonce = random.bytes(4)
            val payload = try { exchange.begin(versionInput(identity()), expected, nonce, scheduler.now()) } finally { nonce.fill(0) }
            deadline(token, 5000, "auth-timeout")
            sendHandshake(token, payload)
        }, onPacket = { packet -> authPacket(token, packet) })
    }

    private fun sendHandshake(token: Long, payload: ByteArray) {
        val frame = FrameCodec.encode(FramePacket(messageNumber, 0, null, ByteArray(0), Business.HANDSHAKE, payload))
        messageNumber = (messageNumber + 1) and 0xffff
        payload.fill(0)
        if (frame.size > status.mtu - 3) { fail(token, "mtu-too-small-no-fragmentation"); return }
        writeRaw(token, frame) { error -> if (error != null) fail(token, "send-failed") }
    }

    private fun authPacket(token: Long, packet: FramePacket) {
        if (authenticated) { readyPacket(packet); return }
        if (packet.businessId != Business.HANDSHAKE || packet.flags != 0 || packet.address != null || packet.slices.isNotEmpty()) {
            throw IllegalStateException("response-profile")
        }
        val generation = exchange.generation
        when (exchange.stage) {
            GuestAuthExchange.Stage.VERSION -> {
                val auth = exchange.receiveVersion(generation, scheduler.now(), packet.payload)
                if (auth == null) { fail(token, exchange.failure.ifEmpty { "unexpected-version" }); return }
                sendHandshake(token, auth)
            }
            GuestAuthExchange.Stage.PROOF -> {
                if (!exchange.receiveProof(generation, scheduler.now(), packet.payload)) { fail(token, exchange.failure.ifEmpty { "unexpected-proof" }); return }
                val key = exchange.takeSessionKey(generation, scheduler.now())
                if (key == null) { fail(token, "session-handoff-failed"); return }
                key.fill(0) // Business frames on the original firmware are sent with flags=0 (no encryption).
                authenticated = true
                clearTimers()
                DiagnosticLog.i(tag, "prova do par verificada; iniciando pareamento do sistema")
                beginPairing(token)
            }
            else -> throw IllegalStateException("unexpected-state")
        }
    }

    // ---------------------------------------------------------------- step 3/4: bond + initialization

    private fun beginPairing(token: Long) {
        val target = target ?: return
        phase(Phase.PAIRING, "Autenticado. Confirme o pareamento Bluetooth se o Android solicitar…")
        if (bond.isBonded(target.address)) { initialize(token); return }
        deadline(token, 60000, "system-pair-timeout")
        bondRequest = bond.ensureBond(target.address) { ok ->
            scheduler.post {
                if (token != intent) return@post
                if (ok) { clearTimers(); initialize(token) } else fail(token, "system-pair-failed")
            }
        }
    }

    private fun initialize(token: Long) {
        phase(Phase.INITIALIZING, "Pareado. Lendo estado e bateria dos óculos…")
        deadline(token, 12000, "status-timeout")
        val frame = FrameCodec.encode(FramePacket(messageNumber, 0, null, ByteArray(0), Business.LAUNCHER, Commands.generalStatusQuery()))
        messageNumber = (messageNumber + 1) and 0xffff
        writeRaw(token, frame) { error -> if (error != null) fail(token, "send-failed") }
    }

    private fun readyPacket(packet: FramePacket) {
        publish { it.copy(framesIn = it.framesIn + 1) }
        if (packet.flags != 0 || packet.address != null || packet.slices.isNotEmpty()) {
            DiagnosticLog.d(tag, "quadro ignorado business=${packet.businessId} flags=${packet.flags}")
            return
        }
        if (packet.businessId == Business.HANDSHAKE) { DiagnosticLog.d(tag, "handshake tardio bytes=${packet.payload.size}"); return }
        if (status.phase == Phase.READY) rawListeners.forEach { runCatching { it(packet.businessId, packet.payload) } }
        val envelope = try { EnvelopeCodec.decode(packet.payload) } catch (_: Exception) {
            DiagnosticLog.d(tag, "envelope não reconhecido business=${packet.businessId} bytes=${packet.payload.size}"); return
        }
        DiagnosticLog.d(tag, "RX business=${packet.businessId} type=${envelope.type} json=${envelope.json.size}B bin=${envelope.binary.size}B")
        if (packet.businessId == Business.LAUNCHER && envelope.type == 1) launcherStatus(envelope)
        if (status.phase == Phase.READY) businessListeners.forEach { runCatching { it.onBusiness(packet.businessId, envelope) } }
    }

    private fun launcherStatus(envelope: Envelope) {
        var battery = -1; var versions = ""; var keys = ""
        try {
            val body = JSONObject(envelope.jsonText())
            val state = body.optJSONObject("generalStatus") ?: body
            battery = state.optInt("battery", -1).takeIf { it in 0..100 } ?: -1
            keys = state.keys().asSequence().take(40).joinToString(",")
            versions = state.keys().asSequence().filter { it.contains("version", true) || it.contains("firmware", true) || it == "ver" }
                .take(6).joinToString(" · ") { k -> "$k=" + state.optString(k).take(40).replace(Regex("[^\\w.\\- ]"), "") }
        } catch (_: Exception) { }
        publish { it.copy(battery = if (battery >= 0) battery else it.battery, statusReplies = it.statusReplies + 1,
            glassesVersions = versions.ifEmpty { it.glassesVersions }, statusKeys = keys.ifEmpty { it.statusKeys }) }
        if (status.phase == Phase.INITIALIZING) {
            clearTimers()
            retries = 0
            val t = target!!
            val saved = SavedGlasses(t.address, Hex.encode(peer!!), t.name, t.autoConnect)
            runCatching { store.save(saved) }.onFailure { DiagnosticLog.w(tag, "falha ao salvar óculos verificado") }
            target = saved
            publish { it.copy(readySince = scheduler.now(), retries = 0, deviceName = t.name) }
            phase(Phase.READY, "Conectado · autenticado · bateria ${if (status.battery >= 0) "${status.battery}%" else "?"}")
        }
    }

    // ---------------------------------------------------------------- link plumbing

    private fun openLink(token: Long, address: String, onReady: () -> Unit, onPacket: (FramePacket) -> Unit) {
        closeLink()
        stream.reset(); rxQueue.clear(); writeBusy = false; authenticated = false
        unauthenticatedTx = 0; unauthenticatedRx = 0
        val created = linkFactory()
        link = created
        // Lifetime limit for an unauthenticated link (cleared once the peer proof is verified).
        timers.add(scheduler.postDelayed(20000) { if (token == intent && !authenticated) fail(token, "transport-timeout") })
        created.open(address, object : LinkListener {
            override fun onLinkReady(mtu: Int) {
                if (token != intent || link !== created) return
                publish { it.copy(mtu = mtu) }
                DiagnosticLog.i(tag, "GATT pronto, MTU=$mtu")
                runGuarded(token) { onReady() }
            }

            override fun onLinkBytes(bytes: ByteArray) {
                if (token != intent || link !== created) return
                publish { it.copy(rxBytes = it.rxBytes + bytes.size) }
                if (!authenticated) {
                    unauthenticatedRx += bytes.size
                    if (unauthenticatedRx > 8192) { fail(token, "receive-limit"); return }
                }
                if (rxQueue.size >= 64) { fail(token, "receive-queue-limit"); return }
                rxQueue.addLast(bytes.copyOf())
                drain(token, onPacket)
            }

            override fun onLinkClosed(reason: String) {
                if (token != intent || link !== created) return
                link = null
                fail(token, if (reason.isEmpty()) "disconnected" else reason)
            }
        })
        pendingPacketHandler = onPacket
    }

    private var pendingPacketHandler: ((FramePacket) -> Unit)? = null

    private fun drain(token: Long, onPacket: (FramePacket) -> Unit) {
        // During the handshake, replies are processed only after our own write completed.
        while (token == intent && (!writeBusy || status.phase == Phase.READY) && rxQueue.isNotEmpty()) {
            val bytes = rxQueue.removeFirst()
            val packets = try { stream.feed(bytes) } catch (_: Exception) { fail(token, "response-frame-invalid"); return }
            for (packet in packets) {
                if (token != intent) return
                if (!runGuarded(token) { onPacket(packet) }) return
            }
        }
    }

    private fun runGuarded(token: Long, block: () -> Unit): Boolean = try { block(); true } catch (e: Exception) {
        if (token == intent) fail(token, e.message?.takeIf { it.matches(Regex("[a-z-]+")) } ?: "protocol-error")
        false
    }

    private fun writeRaw(token: Long, frame: ByteArray, done: (String?) -> Unit) {
        val current = link ?: run { done("transport-closed"); return }
        if (!authenticated) {
            unauthenticatedTx += frame.size
            if (unauthenticatedTx > 1024) { fail(token, "transmit-limit"); return }
        }
        writeBusy = true
        current.write(frame) { error ->
            scheduler.post {
                if (token != intent) return@post
                writeBusy = false
                if (error == null) publish { it.copy(txBytes = it.txBytes + frame.size, framesOut = it.framesOut + 1) }
                done(error)
                pendingPacketHandler?.let { drain(token, it) }
                pump()
            }
        }
    }

    private fun pump() {
        if (writeBusy || txQueue.isEmpty() || status.phase != Phase.READY) return
        val next = txQueue.removeFirst()
        writeRaw(intent, next.frame) { error ->
            if (error != null) DiagnosticLog.w(tag, "escrita BLE falhou: $error")
            next.done(error?.let { "Falha na escrita Bluetooth ($it)" })
        }
    }

    private fun deadline(token: Long, ms: Long, reason: String) {
        stageDeadline?.cancel()
        stageDeadline = scheduler.postDelayed(ms) { if (token == intent) fail(token, reason) }
        timers.add(stageDeadline!!)
    }

    private fun clearTimers() { timers.forEach { it.cancel() }; timers.clear(); stageDeadline = null }

    private fun closeLink() {
        val current = link ?: return
        link = null
        runCatching { current.close() }
    }

    private fun versionInput(phone: ByteArray) = VersionRequest(
        phoneIdentifier = phone,
        phoneName = device.phoneName.toByteArray(Charsets.UTF_8).take(248).toByteArray(),
        manufacturer = device.manufacturer.toByteArray(Charsets.UTF_8).take(128).toByteArray(),
        model = device.model.toByteArray(Charsets.UTF_8).take(128).toByteArray(),
    )

    // ---------------------------------------------------------------- failure / retry

    private fun transient(reason: String) = reason in setOf(
        "transport-timeout", "version-timeout", "auth-timeout", "status-timeout", "disconnected", "connection-failed",
        "connect-failed", "version-send-failed", "send-failed", "discovery-failed", "subscription-failed", "gatt-error",
    )

    private fun fail(token: Long, reason: String) {
        if (token != intent) return
        val wasReady = status.phase == Phase.READY
        intent++
        DiagnosticLog.w(tag, "conexão encerrada: $reason (fase ${status.phase})")
        val queued = ArrayList(txQueue); txQueue.clear()
        queued.forEach { runCatching { it.done("Conexão perdida antes do envio") } }
        stopRadio()
        publish { it.copy(lastReason = reason, readySince = 0) }
        val saved = store.load()
        val delays = longArrayOf(2000, 5000, 10000, 20000, 30000, 60000)
        if ((transient(reason) || wasReady) && saved != null && saved.autoConnect && retries < delays.size) {
            val delay = delays[retries++]
            publish { it.copy(retries = retries) }
            phase(Phase.RETRYING, "Conexão interrompida (${describe(reason)}). Nova tentativa em ${delay / 1000}s ($retries/${delays.size})")
            val retryToken = intent
            retryTimer = scheduler.postDelayed(delay) {
                if (retryToken != intent) return@postDelayed
                target = store.load() ?: return@postDelayed
                beginVersionProbe()
            }
        } else {
            phase(Phase.FAILED, describe(reason))
        }
    }

    private fun stopRadio() {
        clearTimers()
        bondRequest?.cancel(); bondRequest = null
        exchange.cancel(); stream.reset(); rxQueue.clear(); writeBusy = false; authenticated = false
        peer?.fill(0); peer = null
        closeLink()
    }

    private fun stopInternal(why: String) {
        intent++
        retryTimer?.cancel(); retryTimer = null
        val queued = ArrayList(txQueue); txQueue.clear()
        queued.forEach { runCatching { it.done("Envio cancelado: $why") } }
        stopRadio()
        retries = 0
    }

    companion object {
        fun describe(reason: String): String = when (reason) {
            "account-bound-peer" -> "Os óculos ainda estão vinculados a uma conta do app oficial. Desvincule-os no app RayNeo e coloque-os em modo de pareamento."
            "peer-mismatch", "peer-identifier-mismatch" -> "A identidade dos óculos não corresponde à salva. Conexão interrompida por segurança."
            "proof-mismatch", "proof-invalid" -> "Falha na verificação de autenticidade dos óculos."
            "unsupported-version" -> "Versão de protocolo não suportada por este app."
            "system-pair-failed", "system-pair-timeout" -> "Pareamento Bluetooth do sistema não concluído."
            "mtu-too-small-no-fragmentation" -> "MTU Bluetooth insuficiente; reinicie o Bluetooth e tente de novo."
            "status-timeout" -> "Os óculos não responderam à leitura de estado."
            "disconnected" -> "Bluetooth desconectado"
            "transport-timeout", "version-timeout", "auth-timeout" -> "Tempo esgotado ao conectar"
            "service-missing" -> "Serviço RayNeo não encontrado neste dispositivo."
            else -> "Falha de conexão ($reason)"
        }

        @Suppress("FunctionName")
        private fun Advertisementless(name: String) = name.ifBlank { "RayNeo iO" }.take(64)
    }
}
