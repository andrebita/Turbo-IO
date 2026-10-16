package org.turboio.customai.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.turboio.customai.protocol.AuthCalculations
import org.turboio.customai.protocol.Business
import org.turboio.customai.protocol.Commands
import org.turboio.customai.protocol.EnvelopeCodec
import org.turboio.customai.protocol.FrameCodec
import org.turboio.customai.protocol.FramePacket
import org.turboio.customai.protocol.Hex
import org.turboio.customai.protocol.Tlv
import org.turboio.customai.protocol.TlvField
import java.util.PriorityQueue

/** Deterministic virtual-time event loop. */
class VirtualScheduler : Scheduler {
    private class Task(val at: Long, val seq: Long, val run: () -> Unit) { var cancelled = false }
    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.seq }))
    private var time = 0L
    private var seq = 0L
    override fun now(): Long = time
    override fun post(task: () -> Unit) { queue.add(Task(time, seq++, task)) }
    override fun postDelayed(delayMs: Long, task: () -> Unit): Cancellable {
        val t = Task(time + delayMs, seq++, task); queue.add(t); return Cancellable { t.cancelled = true }
    }
    fun advance(ms: Long) {
        val end = time + ms
        while (true) {
            val next = queue.peek() ?: break
            if (next.at > end) break
            queue.poll(); time = next.at
            if (!next.cancelled) next.run()
        }
        time = end
    }
}

/**
 * Emulates the glasses side of the original-firmware handshake as implemented by the HarmonyOS
 * reference: version reply, guest proof verification, LAUNCHER status and business sinks.
 */
class EmulatedGlasses(private val scheduler: VirtualScheduler) {
    /** Reply latency on the first (version-probe) link only. */
    var probeDelayMs = 10L
    var peer = Hex.decode("a1b2c3d4e5f6")
    var protocolVersion = 2
    var accountBound = false
    var badProof = false
    var silent = false
    var battery = 87
    var opens = 0
    val received = ArrayList<FramePacket>()
    var current: Link? = null
    private val peerRandom = Hex.decode("0a0b0c0d")

    inner class Link : GlassesLink {
        private var listener: LinkListener? = null
        private var closed = false
        private var phone: ByteArray? = null
        override fun open(address: String, listener: LinkListener) {
            opens++; this.listener = listener; current = this
            scheduler.postDelayed(50) { if (!closed) listener.onLinkReady(512) }
        }
        override fun write(frame: ByteArray, done: (String?) -> Unit) {
            scheduler.postDelayed(5) {
                if (closed) { done("transport-closed"); return@postDelayed }
                done(null)
                val packet = FrameCodec.decode(frame)
                received.add(packet)
                if (!silent) respond(packet)
            }
        }
        override fun close() { closed = true; if (current === this) current = null }
        fun drop() { closed = true; listener?.onLinkClosed("disconnected") }

        private fun reply(business: Int, payload: ByteArray) {
            val bytes = FrameCodec.encode(FramePacket(0x4242, 0, null, ByteArray(0), business, payload))
            // Deliver in two notifications to exercise reassembly.
            val half = bytes.size / 2
            val delay = if (opens == 1) probeDelayMs else 10L
            scheduler.postDelayed(delay) { if (!closed) listener?.onLinkBytes(bytes.copyOfRange(0, half)) }
            scheduler.postDelayed(delay + 1) { if (!closed) listener?.onLinkBytes(bytes.copyOfRange(half, bytes.size)) }
        }

        private fun respond(packet: FramePacket) {
            when (packet.businessId) {
                Business.HANDSHAKE -> {
                    val outer = Tlv.decode(packet.payload)[0]
                    if (outer.tag == 0x11) {
                        phone = Tlv.decode(outer.value).first { it.tag == 0x11 }.value
                        val fields = mutableListOf(TlvField(0x10, byteArrayOf(protocolVersion.toByte())), TlvField(0x11, peer), TlvField(0x13, byteArrayOf(1)))
                        if (accountBound) fields.add(TlvField(0x1a, "conta".toByteArray()))
                        reply(Business.HANDSHAKE, Tlv.encode(listOf(TlvField(0x11, Tlv.encode(fields)))))
                    } else if (outer.tag == 0x18) {
                        val auth = Tlv.authFields(packet.payload, 0x18)
                        val random = auth.first { it.tag == 0x10 }.value
                        check(AuthCalculations.proofMatches(auth.first { it.tag == 0x11 }.value, AuthCalculations.guestProof(random, phone!!, peer)))
                        val proof = AuthCalculations.guestProof(peerRandom, phone!!, peer)
                        if (badProof) proof[0] = (proof[0].toInt() xor 1).toByte()
                        reply(Business.HANDSHAKE, Tlv.encode(listOf(TlvField(0x19, Tlv.encode(listOf(TlvField(0x10, peerRandom), TlvField(0x11, proof)))))))
                    }
                }
                Business.LAUNCHER -> reply(Business.LAUNCHER, EnvelopeCodec.encode(1,
                    "{\"generalStatus\":{\"battery\":$battery,\"version\":\"1.0.5.5\",\"wear\":1}}"))
            }
        }
    }
}

class MemoryStore : PeerStore {
    var value: SavedGlasses? = null
    override fun load() = value
    override fun save(value: SavedGlasses) { this.value = value }
    override fun clear() { value = null }
}

class FakeBond(var accept: Boolean = true) : BondManager {
    var requests = 0
    var bonded = false
    override fun isBonded(address: String) = bonded
    override fun ensureBond(address: String, result: (Boolean) -> Unit): Cancellable { requests++; bonded = accept; result(accept); return Cancellable { } }
}

class GlassesSessionTest {
    private val scheduler = VirtualScheduler()
    private val glasses = EmulatedGlasses(scheduler)
    private val store = MemoryStore()
    private val bond = FakeBond()
    private var counter = 0
    private val session = GlassesSession(
        scheduler, { glasses.Link() }, bond,
        object : Randomness { override fun bytes(count: Int) = ByteArray(count) { (counter++ * 37 + it).toByte() } },
        { Hex.decode("001122334455") }, DeviceInfo("Turbo IO", "samsung", "SM-TEST"), store,
    )
    private val address = "00:11:22:33:44:55"

    private fun connected() { session.connect(address, "RayNeo iO"); scheduler.advance(5000) }

    @Test fun fullHandshakeReachesReadyOnOriginalFirmwareProfile() {
        connected()
        val s = session.status
        assertEquals(Phase.READY, s.phase)
        assertEquals(87, s.battery)
        assertEquals(2, s.protocolVersion)
        assertEquals(2, glasses.opens)           // version probe link + authenticated link
        assertEquals(1, bond.requests)           // system bond only after the peer proof
        assertTrue(s.glassesVersions.contains("1.0.5.5"))
        assertEquals("a1b2c3d4e5f6", store.value!!.peerHex)
        // Exact wire order: version(probe) → version(auth) → 0x18 proof → LAUNCHER status query.
        assertEquals(listOf(0x10, 0x10, 0x10, 15), glasses.received.map { it.businessId })
        assertTrue(glasses.received.all { it.flags == 0 && it.address == null && it.slices.isEmpty() })
    }

    @Test fun accountBoundGlassesAreNotTakenOver() {
        glasses.accountBound = true
        connected()
        assertEquals(Phase.FAILED, session.status.phase)
        assertEquals("account-bound-peer", session.status.lastReason)
        assertEquals(0, bond.requests); assertNull(store.value)
    }

    @Test fun wrongPeerProofIsRejectedBeforePairing() {
        glasses.badProof = true
        connected()
        assertEquals("proof-mismatch", session.status.lastReason)
        assertEquals(0, bond.requests); assertNull(store.value)
    }

    @Test fun savedIdentityMismatchStops() {
        connected()
        session.disconnect(); scheduler.advance(100)
        glasses.peer = Hex.decode("ffffffffffff")
        session.connect(address, "RayNeo iO"); scheduler.advance(5000)
        assertEquals(Phase.FAILED, session.status.phase)
        assertEquals("peer-mismatch", session.status.lastReason)
    }

    @Test fun droppedLinkReconnectsAutomatically() {
        connected()
        glasses.current!!.drop()
        scheduler.advance(10)
        assertEquals(Phase.RETRYING, session.status.phase)
        scheduler.advance(10000)
        assertEquals(Phase.READY, session.status.phase)
        assertEquals(4, glasses.opens)
    }

    @Test fun silentGlassesTimeOutWithoutHanging() {
        glasses.silent = true
        session.connect(address, "RayNeo iO"); scheduler.advance(8000)
        assertEquals(Phase.FAILED, session.status.phase)
        assertEquals("version-timeout", session.status.lastReason)
    }

    @Test fun outboundPolicyAndDelivery() {
        connected()
        val results = HashMap<Int, String?>()
        session.send(9, EnvelopeCodec.encode(1, "{\"ota\":1}")) { results[0] = it }          // firmware update: refused
        session.send(18, EnvelopeCodec.encode(1, "{\"file\":1}")) { results[1] = it }         // file transfer: refused
        session.send(13, EnvelopeCodec.encode(32, Commands.answerChunk("Olá!", "a".repeat(32), "oi", 1))) { results[2] = it }
        session.send(19, EnvelopeCodec.encode(5, "{\"x\":\"" + "y".repeat(600) + "\"}")) { results[3] = it } // > MTU
        scheduler.advance(1000)
        assertTrue(results[0]!!.contains("não permitido"))
        assertTrue(results[1]!!.contains("não permitido"))
        assertEquals(4, results.size)
        assertNull(results[2])
        assertTrue(results[3]!!.contains("MTU"))
        assertEquals(1, glasses.received.count { it.businessId == 13 })
        assertTrue(glasses.received.none { it.businessId == 9 || it.businessId == 18 })
    }

    @Test fun communicationTestReadsStatusAgain() {
        connected()
        val before = session.status.statusReplies
        glasses.battery = 55
        var error: String? = "pending"
        session.requestStatus { error = it }
        scheduler.advance(500)
        assertNull(error)
        assertEquals(before + 1, session.status.statusReplies)
        assertEquals(55, session.status.battery)
    }

    @Test fun slowButInTimeRepliesStillConnect() {
        glasses.probeDelayMs = 4900   // version reply just inside the 5 s deadline; pause must not inherit it
        session.connect(address, "RayNeo iO"); scheduler.advance(30000)
        assertEquals(Phase.READY, session.status.phase)
    }

    @Test fun forgetClearsSavedIdentity() {
        connected()
        session.forget(); scheduler.advance(100)
        assertNull(store.value); assertEquals(Phase.IDLE, session.status.phase)
    }
}
