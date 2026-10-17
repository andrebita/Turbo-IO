package org.turboio.customai.ble

/** Single-threaded event loop used by the session. Production: a HandlerThread. Tests: virtual time. */
interface Scheduler {
    fun now(): Long
    fun post(task: () -> Unit)
    fun postDelayed(delayMs: Long, task: () -> Unit): Cancellable
}

fun interface Cancellable { fun cancel() }

/** Callbacks are always delivered on the session [Scheduler] thread. */
interface LinkListener {
    fun onLinkReady(mtu: Int)
    fun onLinkBytes(bytes: ByteArray)
    fun onLinkClosed(reason: String)
}

/**
 * One GATT connection to the glasses' RayNeo service (0000b81d-…). Implementations must not retry
 * uncertain writes, persist received data or log payloads.
 */
interface GlassesLink {
    fun open(address: String, listener: LinkListener)
    /** Writes one complete frame (≤ mtu-3). [done] receives null on success or a reason. */
    fun write(frame: ByteArray, done: (String?) -> Unit)
    fun close()
}

/** System Bluetooth pairing (bond). Only creates a bond after the peer proof was verified. */
interface BondManager {
    fun isBonded(address: String): Boolean
    /** Requests system pairing; [result] true when BOND_BONDED, false on failure/timeout. */
    fun ensureBond(address: String, result: (Boolean) -> Unit): Cancellable
}

interface Randomness { fun bytes(count: Int): ByteArray }

object Uuids {
    const val SERVICE = "0000b81d-0000-1000-8000-00805f9b34fb"
    const val TX = "ea8b70d5-2bd3-49ab-9c31-9c38b2c3c4f9"
    const val RX = "7db3e235-3608-41f3-a03c-955fcbd2ea4b"
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
}

/** What feature modules need from a connected session (implemented by [GlassesSession]; faked in tests). */
interface GlassesPort {
    val isReady: Boolean
    fun send(business: Int, envelope: ByteArray, done: (String?) -> Unit)
    fun addBusinessListener(listener: BusinessListener)
    /** Every packet received after READY, before envelope decoding (observation probes, file messages). */
    fun addRawListener(listener: (business: Int, payload: ByteArray) -> Unit) {}
}
