package org.turboio.customai.protocol

/**
 * Payload-level guest handshake state machine (port of GuestAuthExchange.ts).
 * version request -> version reply (peer id must match) -> 0x18 proof -> 0x19 peer proof -> session key.
 * Never falls back from an account-bound peer to guest mode.
 */
class GuestAuthExchange {
    enum class Stage { IDLE, VERSION, PROOF, VERIFIED, FAILED }

    var stage: Stage = Stage.IDLE; private set
    var failure: String = ""; private set
    var generation: Long = 0; private set

    private var deadline = 0L
    private var lastTime = 0L
    private var phone = ByteArray(0)
    private var peer = ByteArray(0)
    private var random = ByteArray(0)
    private var session = ByteArray(0)

    private fun clear() {
        phone.fill(0); peer.fill(0); random.fill(0); session.fill(0)
        phone = ByteArray(0); peer = ByteArray(0); random = ByteArray(0); session = ByteArray(0)
    }

    private fun fail(reason: String) { clear(); stage = Stage.FAILED; failure = reason }

    fun begin(input: VersionRequest, expectedPeer: ByteArray, random4: ByteArray, now: Long, timeoutMs: Long = 5000): ByteArray {
        if (stage == Stage.VERSION || stage == Stage.PROOF || stage == Stage.VERIFIED) throw ProtocolException("exchange-busy")
        if (expectedPeer.size != 6 || random4.size != 4) throw ProtocolException("exchange-input-length")
        if (now < 0 || timeoutMs < 1 || timeoutMs > 30000) throw ProtocolException("exchange-time")
        val payload = VersionExchange.request(input)
        clear(); generation++; stage = Stage.VERSION; failure = ""
        phone = input.phoneIdentifier.copyOf(); peer = expectedPeer.copyOf(); random = random4.copyOf()
        lastTime = now; deadline = now + timeoutMs
        return payload
    }

    private fun current(token: Long, now: Long): Boolean {
        if (token != generation || (stage != Stage.VERSION && stage != Stage.PROOF && stage != Stage.VERIFIED)) return false
        if (now < lastTime) { fail("clock-regression"); return false }
        if (now >= deadline) { fail("timeout"); return false }
        lastTime = now
        return true
    }

    /** Returns the 0x18 auth payload to send, or null on failure (see [failure]). */
    fun receiveVersion(token: Long, now: Long, payload: ByteArray): ByteArray? {
        if (!current(token, now) || stage != Stage.VERSION) return null
        return try {
            val reply = VersionExchange.reply(payload)
            if (reply.protocolVersion != 1 && reply.protocolVersion != 2) { fail("unsupported-version"); return null }
            if (!reply.peerIdentifier.contentEquals(peer)) { fail("peer-identifier-mismatch"); return null }
            val account = reply.field(0x1a)
            if ((account != null && account.isNotEmpty()) || reply.field(0x19) != null) { fail("account-bound-peer"); return null }
            val proof = AuthCalculations.guestProof(random, phone, peer)
            try { Tlv.authRequest(random, proof).also { stage = Stage.PROOF } } finally { proof.fill(0) }
        } catch (_: Exception) { fail("version-invalid"); null }
    }

    fun receiveProof(token: Long, now: Long, payload: ByteArray): Boolean {
        if (!current(token, now) || stage != Stage.PROOF) return false
        return try {
            val fields = Tlv.authFields(payload, 0x19)
            val peerRandom = fields.first { it.tag == 0x10 }.value
            val proof = fields.first { it.tag == 0x11 }.value
            val expected = AuthCalculations.guestProof(peerRandom, phone, peer)
            val ok = AuthCalculations.proofMatches(proof, expected)
            expected.fill(0)
            if (!ok) { fail("proof-mismatch"); return false }
            val key = AuthCalculations.guestSessionKey(random, peerRandom, phone, peer)
            clear(); session = key; stage = Stage.VERIFIED
            true
        } catch (_: Exception) { fail("proof-invalid"); false }
    }

    fun takeSessionKey(token: Long, now: Long): ByteArray? {
        if (!current(token, now) || stage != Stage.VERIFIED || session.size != 32) return null
        val result = session.copyOf(); session.fill(0); session = ByteArray(0)
        return result
    }

    fun cancel() = fail("cancelled")
}
