package org.turboio.customai.protocol

import java.security.MessageDigest

/**
 * Guest-mode peer proof used by the glasses' own pairing handshake (already implemented and
 * hardware-validated in harmony-sdk/turbo_core AuthCalculations.ts / GuestAuthExchange.ts).
 *
 * Only the *guest* branch is ported. Account-bound pairing (ECDH bond keys of the official account)
 * is intentionally NOT implemented: if the glasses report an account binding we stop and ask the
 * owner to unbind in the official app. Nothing here bypasses the device's own policy.
 */
object AuthCalculations {
    private val SALT = byteArrayOf(7, 18, 200.toByte(), 173.toByte(), 39, 116, 65, 149.toByte(), 222.toByte(), 95,
        161.toByte(), 234.toByte(), 109, 2, 95, 184.toByte())

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) digest.update(part)
        return digest.digest()
    }

    private fun check(random: ByteArray, phone: ByteArray, glasses: ByteArray) {
        if (random.size != 4 || phone.size != 6 || glasses.size != 6) throw ProtocolException("auth-length")
    }

    fun guestProof(random: ByteArray, phone: ByteArray, glasses: ByteArray): ByteArray {
        check(random, phone, glasses)
        return sha256(random, phone, glasses, SALT)
    }

    fun guestSessionKey(localRandom: ByteArray, peerRandom: ByteArray, phone: ByteArray, glasses: ByteArray): ByteArray {
        check(localRandom, phone, glasses)
        if (peerRandom.size != 4) throw ProtocolException("auth-length")
        return sha256(localRandom, peerRandom, phone, glasses, SALT)
    }

    /** Full-length comparison, no early exit. */
    fun proofMatches(left: ByteArray, right: ByteArray): Boolean {
        if (left.size != 32 || right.size != 32) return false
        var diff = 0
        for (i in 0 until 32) diff = diff or (left[i].toInt() xor right[i].toInt())
        return diff == 0
    }
}

object Hex {
    fun encode(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    fun decode(text: String): ByteArray {
        if (text.length % 2 != 0 || text.length > 8192 || !text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            throw ProtocolException("invalid-hex")
        }
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
