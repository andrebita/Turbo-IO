package org.turboio.customai.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.protocol.Advertisement
import org.turboio.customai.protocol.Hex
import java.security.SecureRandom
import java.util.UUID

class HandlerScheduler(name: String) : Scheduler {
    private val thread = HandlerThread(name).apply { start() }
    val handler = Handler(thread.looper)
    override fun now(): Long = SystemClock.elapsedRealtime()
    override fun post(task: () -> Unit) { handler.post(task) }
    override fun postDelayed(delayMs: Long, task: () -> Unit): Cancellable {
        val runnable = Runnable(task)
        handler.postDelayed(runnable, delayMs)
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}

class SecureRandomness : Randomness {
    private val random = SecureRandom()
    override fun bytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }
}

/** Saved verified glasses (private app storage; address and peer id are never logged/exported). */
class PrefsPeerStore(context: Context) : PeerStore {
    private val prefs = context.getSharedPreferences("turboio_glasses_v1", Context.MODE_PRIVATE)
    override fun load(): SavedGlasses? {
        val address = prefs.getString("address", null) ?: return null
        val peer = prefs.getString("peer", null) ?: return null
        if (!Regex("^[0-9a-f]{12}$").matches(peer)) return null
        return SavedGlasses(address, peer, prefs.getString("name", "RayNeo iO") ?: "RayNeo iO", prefs.getBoolean("auto", true))
    }
    override fun save(value: SavedGlasses) {
        prefs.edit().putString("address", value.address).putString("peer", value.peerHex)
            .putString("name", value.name).putBoolean("auto", value.autoConnect).apply()
    }
    override fun clear() { prefs.edit().clear().apply() }
}

/** App-generated 6-byte protocol identifier (not a hardware MAC). Persisted before first use. */
class PhoneIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("turboio_identity_v1", Context.MODE_PRIVATE)
    @Synchronized fun get(): ByteArray {
        val saved = prefs.getString("phone_identifier", null)
        if (saved != null && Regex("^[0-9a-f]{12}$").matches(saved)) return Hex.decode(saved)
        val bytes = SecureRandomness().bytes(6)
        prefs.edit().putString("phone_identifier", Hex.encode(bytes)).commit()
        return bytes
    }
}

fun bluetoothAdapter(context: Context): BluetoothAdapter? =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

/**
 * Android GATT implementation of [GlassesLink]. Mirrors BleHandshakeTransport.ets: exactly one RayNeo
 * service with a write-without-response TX and a notify RX characteristic, MTU 512 request with a
 * 2 s fallback to 23, CCCD subscription, one write in flight at a time with a 1.5 s timeout.
 */
@SuppressLint("MissingPermission")
class GattLink(private val context: Context, private val scheduler: HandlerScheduler) : GlassesLink {
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var listener: LinkListener? = null
    private var mtu = 23
    private var stage = "idle"
    private var closed = false
    private var mtuTimer: Cancellable? = null
    private var writeTimer: Cancellable? = null
    private var pendingWrite: ((String?) -> Unit)? = null

    override fun open(address: String, listener: LinkListener) {
        this.listener = listener
        val adapter = bluetoothAdapter(context)
        if (adapter == null || !adapter.isEnabled) { finish("bluetooth-off"); return }
        val device = try { adapter.getRemoteDevice(address) } catch (_: IllegalArgumentException) { finish("connect-failed"); return }
        stage = "connecting"
        gatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) { finish("permission-denied"); return }
        if (gatt == null) finish("connect-failed")
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = scheduler.post {
            if (closed) return@post
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS && stage == "connecting") {
                stage = "discovering"
                // Small settle delay helps several Android stacks (incl. Samsung) before discovery.
                scheduler.postDelayed(250) { if (!closed && !g.discoverServices()) finish("discovery-failed") }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                finish(if (stage == "connecting") "connection-failed" else "disconnected")
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                finish("gatt-error")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = scheduler.post {
            if (closed || stage != "discovering") return@post
            val services = g.services.filter { it.uuid == UUID.fromString(Uuids.SERVICE) }
            if (status != BluetoothGatt.GATT_SUCCESS || services.size != 1) { finish("service-missing"); return@post }
            val service = services[0]
            val txs = service.characteristics.filter { it.uuid == UUID.fromString(Uuids.TX) }
            val rxs = service.characteristics.filter { it.uuid == UUID.fromString(Uuids.RX) }
            if (txs.size != 1 || rxs.size != 1 ||
                txs[0].properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0 ||
                rxs[0].properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
            ) { finish("characteristic-mismatch"); return@post }
            tx = txs[0]
            stage = "mtu"
            mtuTimer = scheduler.postDelayed(2000) { if (stage == "mtu") subscribe(g, rxs[0], 23) }
            if (!g.requestMtu(512)) subscribe(g, rxs[0], 23)
        }

        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) = scheduler.post {
            if (closed || stage != "mtu") return@post
            val rx = g.getService(UUID.fromString(Uuids.SERVICE))?.getCharacteristic(UUID.fromString(Uuids.RX))
                ?: run { finish("characteristic-mismatch"); return@post }
            if (status != BluetoothGatt.GATT_SUCCESS || value < 23 || value > 517) subscribe(g, rx, 23) else subscribe(g, rx, value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = scheduler.post {
            if (closed || stage != "subscribing") return@post
            if (status != BluetoothGatt.GATT_SUCCESS) { finish("subscription-failed"); return@post }
            stage = "ready"
            listener?.onLinkReady(mtu)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = scheduler.post {
            val done = pendingWrite ?: return@post
            pendingWrite = null; writeTimer?.cancel(); writeTimer = null
            done(if (status == BluetoothGatt.GATT_SUCCESS) null else "write-failed")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(characteristic, value.copyOf())
        }

        @Deprecated("Android < 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            deliver(characteristic, characteristic.value?.copyOf() ?: return)
        }
    }

    private fun deliver(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) = scheduler.post {
        if (closed || stage != "ready" || characteristic.uuid != UUID.fromString(Uuids.RX)) return@post
        if (bytes.isEmpty() || bytes.size > mtu - 3) { finish("receive-limit"); return@post }
        listener?.onLinkBytes(bytes)
    }

    @Suppress("DEPRECATION")
    private fun subscribe(g: BluetoothGatt, rx: BluetoothGattCharacteristic, negotiated: Int) {
        if (stage != "mtu") return
        mtuTimer?.cancel(); mtuTimer = null
        mtu = negotiated
        stage = "subscribing"
        val descriptor = rx.getDescriptor(UUID.fromString(Uuids.CCCD))
        if (!g.setCharacteristicNotification(rx, true) || descriptor == null) { finish("subscription-failed"); return }
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(descriptor)
        }
        if (!ok) finish("subscription-failed")
    }

    @Suppress("DEPRECATION")
    override fun write(frame: ByteArray, done: (String?) -> Unit) = scheduler.post {
        val g = gatt; val characteristic = tx
        if (closed || stage != "ready" || g == null || characteristic == null) { done("transport-not-ready"); return@post }
        if (pendingWrite != null) { done("write-busy"); return@post }
        if (frame.size > mtu - 3) { done("mtu-too-small-no-fragmentation"); return@post }
        pendingWrite = done
        writeTimer = scheduler.postDelayed(1500) {
            if (pendingWrite != null) { pendingWrite = null; done("write-timeout"); finish("write-timeout") }
        }
        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(characteristic, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            characteristic.value = frame
            g.writeCharacteristic(characteristic)
        }
        if (!accepted) {
            writeTimer?.cancel(); writeTimer = null; pendingWrite = null
            done("write-rejected")
        }
    }

    override fun close() = scheduler.post { finish(null) }

    private fun finish(reason: String?) {
        if (closed) return
        closed = true
        mtuTimer?.cancel(); writeTimer?.cancel()
        pendingWrite?.let { pendingWrite = null; it("transport-closed") }
        val g = gatt; gatt = null; tx = null
        try { g?.disconnect() } catch (_: SecurityException) {}
        try { g?.close() } catch (_: SecurityException) {}
        stage = "closed"
        val l = listener; listener = null
        if (reason != null) l?.onLinkClosed(reason)
    }
}

@SuppressLint("MissingPermission")
class AndroidBondManager(private val context: Context) : BondManager {
    override fun isBonded(address: String): Boolean = try {
        bluetoothAdapter(context)?.getRemoteDevice(address)?.bondState == BluetoothDevice.BOND_BONDED
    } catch (_: Exception) { false }

    override fun ensureBond(address: String, result: (Boolean) -> Unit): Cancellable {
        val device = try { bluetoothAdapter(context)?.getRemoteDevice(address) } catch (_: Exception) { null }
            ?: run { result(false); return Cancellable { } }
        var finished = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val changed: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (changed?.address != address || finished) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> { finished = true; unregister(this); result(true) }
                    BluetoothDevice.BOND_NONE -> { finished = true; unregister(this); result(false) }
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (device.bondState == BluetoothDevice.BOND_BONDED) { finished = true; unregister(receiver); result(true) }
        else if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) { finished = true; unregister(receiver); result(false) }
        return Cancellable { if (!finished) { finished = true; unregister(receiver) } }
    }

    private fun unregister(receiver: BroadcastReceiver) { runCatching { context.unregisterReceiver(receiver) } }
}

data class Candidate(val address: String, val name: String, val rssi: Int, val serviceMatched: Boolean,
                     /** Product type from the manufacturer data (company 0x5716, first byte); null when not advertised. */
                     val deviceType: Int? = null) {
    val model get() = RayNeoModel.name(deviceType)
}

/** RayNeo product types (official SDK table j4/L); this app is made for the iO only (6 = VENUS, 7 = VENUSINTL). */
object RayNeoModel {
    const val COMPANY = 0x5716
    private val NAMES = mapOf(1 to "RayNeo X2", 2 to "RayNeo V3", 3 to "RayNeo X3", 4 to "RayNeo V3 Slim", 5 to "RayNeo V4",
        6 to "RayNeo iO", 7 to "RayNeo iO (internacional)")
    fun isIo(type: Int?) = type == 6 || type == 7
    fun name(type: Int?): String = when (type) { null -> "modelo não anunciado"; else -> NAMES[type] ?: "RayNeo tipo $type (desconhecido)" }
}

/** 15 s low-power scan filtered by the RayNeo 16-bit service (B81D) or name; never connects. */
@SuppressLint("MissingPermission")
class GlassesScanner(private val context: Context) {
    private var callback: ScanCallback? = null
    private val main = Handler(context.mainLooper)

    fun start(onUpdate: (List<Candidate>, Boolean) -> Unit): String? {
        stop()
        val adapter = bluetoothAdapter(context) ?: return "Bluetooth indisponível neste aparelho"
        if (!adapter.isEnabled) return "Ative o Bluetooth nas configurações do Android"
        val scanner = adapter.bluetoothLeScanner ?: return "Scanner BLE indisponível"
        val found = LinkedHashMap<String, Candidate>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val bytes = result.scanRecord?.bytes ?: ByteArray(0)
                val info = Advertisement.inspect(bytes)
                val reported = Advertisement.displayName(try { result.device.name ?: "" } catch (_: SecurityException) { "" })
                val name = (if (info.malformed) "" else info.localName).ifEmpty { reported }
                if (!Advertisement.candidateName(name) && (!info.hasRayNeoService || info.malformed)) return
                if (found.size >= 32 && !found.containsKey(result.device.address)) return
                val previous = found[result.device.address]
                val type = result.scanRecord?.getManufacturerSpecificData(RayNeoModel.COMPANY)?.firstOrNull()?.toInt()?.and(0xff)
                found[result.device.address] = Candidate(result.device.address, name.ifEmpty { previous?.name ?: "RayNeo (sem nome)" },
                    result.rssi, (previous?.serviceMatched ?: false) || (!info.malformed && info.hasRayNeoService), type ?: previous?.deviceType)
                onUpdate(found.values.toList(), true)
            }
            override fun onScanFailed(errorCode: Int) {
                DiagnosticLog.w("BLE", "scan falhou: código $errorCode")
                onUpdate(found.values.toList(), false)
            }
        }
        callback = cb
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        } catch (_: SecurityException) { callback = null; return "Permissão de Bluetooth negada" }
        main.postDelayed({ if (callback === cb) { stop(); onUpdate(found.values.toList(), false) } }, 15000)
        DiagnosticLog.i("BLE", "scan iniciado (15 s)")
        return null
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        try { bluetoothAdapter(context)?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
    }
}
