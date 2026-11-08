package io.github.gdornyei.pe2tester.ble

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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import io.github.gdornyei.pe2tester.proto.FrameDecoder
import io.github.gdornyei.pe2tester.proto.Framing
import io.github.gdornyei.pe2tester.proto.PT
import io.github.gdornyei.pe2tester.proto.Packet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

enum class ConnState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

/** BLE transport for the Pockethernet 2 (no pairing needed). */
@SuppressLint("MissingPermission")
class Pe2Ble(private val ctx: Context) {
    companion object {
        private const val TAG = "Pe2Ble"
        val SERVICE: UUID = UUID.fromString("0b7beb07-13fa-47b6-909e-a05daad11552")
        val CHAR_NOTIFY: UUID = UUID.fromString("c9eddcb6-04a5-4d58-abbe-a98da1b3f3d0")
        val CHAR_WRITE: UUID = UUID.fromString("f63cdc3f-9652-460c-94b1-cc1afc117269")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val adapter: BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    val state: StateFlow<ConnState> = _state

    private val _packets = MutableSharedFlow<Packet>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val packets: SharedFlow<Packet> = _packets

    private val _log = MutableSharedFlow<String>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val log: SharedFlow<String> = _log

    var deviceName: String? = null; private set

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private val writeMutex = Mutex()
    private var writeDone: CompletableDeferred<Boolean>? = null
    private var setupDone: CompletableDeferred<Boolean>? = null

    private val decoder = FrameDecoder(
        onPacket = { p -> log("← ${PT.name(p.ptype)} (${p.payload.size} B)"); _packets.tryEmit(p) },
        onError = { log("RX error: $it") },
    )

    private fun log(s: String) { Log.d(TAG, s); _log.tryEmit(s) }

    val bluetoothEnabled get() = adapter?.isEnabled == true

    /** Scan for the tester and connect. Throws on failure. */
    suspend fun connect(scanTimeoutMs: Long = 10_000) {
        val a = adapter ?: error("No Bluetooth adapter")
        if (!a.isEnabled) error("Turn on Bluetooth")
        disconnect()
        _state.value = ConnState.SCANNING
        log("Searching…")
        val dev = withTimeoutOrNull(scanTimeoutMs) { scan(a) }
        if (dev == null) { _state.value = ConnState.DISCONNECTED; error("Pockethernet not found. Is it switched on?") }
        deviceName = dev.name ?: dev.address
        log("Found: $deviceName")
        _state.value = ConnState.CONNECTING
        val done = CompletableDeferred<Boolean>()
        setupDone = done
        decoder.reset()
        gatt = if (Build.VERSION.SDK_INT >= 26) dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        else dev.connectGatt(ctx, false, callback)
        val ok = try { withTimeout(15_000) { done.await() } } catch (e: Exception) { false }
        if (!ok) { disconnect(); error("Could not connect") }
        _state.value = ConnState.CONNECTED
        log("Connected, MTU $mtu")
    }

    private suspend fun scan(a: BluetoothAdapter): BluetoothDevice = suspendCancellableCoroutine { cont ->
        val scanner = a.bluetoothLeScanner
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (cont.isActive) { runCatching { scanner.stopScan(this) }; cont.resume(result.device) }
            }
            override fun onScanFailed(errorCode: Int) { log("Scan error: $errorCode") }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, cb)
        cont.invokeOnCancellation { runCatching { scanner.stopScan(cb) } }
    }

    fun disconnect() {
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null; writeChar = null
        setupDone?.complete(false); writeDone?.complete(false)
        if (_state.value != ConnState.DISCONNECTED) log("Disconnected")
        _state.value = ConnState.DISCONNECTED
    }

    /** Sends one packet, chunked to the negotiated MTU, waiting for each write response. */
    suspend fun send(p: Packet) = writeMutex.withLock {
        val g = gatt ?: error("Not connected"); val c = writeChar ?: error("Not connected")
        log("→ ${PT.name(p.ptype)} (${p.payload.size} B)")
        val bytes = Framing.encode(p)
        val chunk = (mtu - 3).coerceAtLeast(20)
        var off = 0
        while (off < bytes.size) {
            val part = bytes.copyOfRange(off, minOf(bytes.size, off + chunk))
            val d = CompletableDeferred<Boolean>(); writeDone = d
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, part, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
            } else {
                @Suppress("DEPRECATION")
                run { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = part; g.writeCharacteristic(c) }
            }
            if (!started) error("BLE write failed")
            if (withTimeoutOrNull(3000) { d.await() } != true) error("BLE write timeout")
            off += part.size
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("GATT connected, discovering services…")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Connection lost ($status)")
                setupDone?.complete(false); writeDone?.complete(false)
                runCatching { g.close() }
                if (gatt === g) { gatt = null; writeChar = null }
                _state.value = ConnState.DISCONNECTED
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val s = g.getService(SERVICE)
            if (s == null) { log("Service missing"); setupDone?.complete(false); return }
            writeChar = s.getCharacteristic(CHAR_WRITE)
            if (!g.requestMtu(128)) enableNotify(g)
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            enableNotify(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == CCCD) setupDone?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == CHAR_NOTIFY) decoder.feed(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33 && c.uuid == CHAR_NOTIFY) decoder.feed(c.value ?: return)
        }
    }

    private fun enableNotify(g: BluetoothGatt) {
        val c = g.getService(SERVICE)?.getCharacteristic(CHAR_NOTIFY)
        if (c == null) { setupDone?.complete(false); return }
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD)
        if (d == null) { setupDone?.complete(true); return }
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
        }
    }
}
