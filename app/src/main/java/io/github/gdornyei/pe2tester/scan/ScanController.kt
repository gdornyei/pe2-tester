package io.github.gdornyei.pe2tester.scan

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import io.github.gdornyei.pe2tester.ble.ConnState
import io.github.gdornyei.pe2tester.ble.Pe2Ble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ScanPhase { IDLE, STARTING, READY, SCANNING, DONE, ERROR }

data class ScanUi(
    val phase: ScanPhase = ScanPhase.IDLE,
    val log: List<String> = emptyList(),
    val net: BridgeNet? = null,
    val range: String = "",
    val done: Int = 0, val total: Int = 0,
    val hosts: List<HostInfo> = emptyList(),
    val result: NetScan? = null,
    val error: String? = null,
)

class ScanController(ctx: Context, private val ble: Pe2Ble, private val scope: CoroutineScope) {
    private val app = ctx.applicationContext
    private val bridge = Bridge(app, ble)
    private val _ui = MutableStateFlow(ScanUi())
    val ui: StateFlow<ScanUi> = _ui
    private var job: Job? = null
    private var mcLock: WifiManager.MulticastLock? = null

    var ssid = "Pockethernet"; var pass = "Pockethernet"
    var onSaved: ((NetScan) -> Unit)? = null

    private fun log(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        _ui.update { it.copy(log = (it.log + "$t  $s").takeLast(60)) }
    }

    fun setRange(r: String) = _ui.update { it.copy(range = r) }

    /** Connects BLE if needed, switches the tester to bridge mode and joins the phone to it. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            _ui.value = ScanUi(phase = ScanPhase.STARTING)
            try {
                if (Build.VERSION.SDK_INT < 29) error("The WiFi bridge needs Android 10 or newer")
                if (ble.state.value != ConnState.CONNECTED) { log("Connecting to the Pockethernet…"); ble.connect() }
                bridge.startTester(ssid, pass, ::log)
                log("Joining the phone to “$ssid” WiFi… (allow it if Android asks)")
                val net = bridge.joinWifi(ssid, pass) ?: error("Could not join the bridge WiFi")
                if (net.ip.isEmpty()) error("Connected, but no IP address from the network (DHCP)")
                log("On network: ${net.ip}/${net.prefix}, gateway ${net.gateway ?: "–"}")
                runCatching {
                    mcLock = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("pe2scan").apply { acquire() }
                }
                _ui.update { it.copy(phase = ScanPhase.READY, net = net, range = it.range.ifEmpty { IpRange.cidrOf(net.ip, net.prefix) }) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Error: ${e.message}")
                _ui.update { it.copy(phase = ScanPhase.ERROR, error = e.message) }
                withContext(NonCancellable) { runCatching { bridge.stop() } }
            }
        }
    }

    fun scan(label: String = "") {
        val net = _ui.value.net ?: return
        val ips = IpRange.parse(_ui.value.range)
        if (ips == null) { _ui.update { it.copy(error = "Invalid or too large range (max. 4096 addresses), e.g. 192.168.1.0/24") }; return }
        if (job?.isActive == true) return
        job = scope.launch {
            _ui.update { it.copy(phase = ScanPhase.SCANNING, done = 0, total = ips.size, hosts = emptyList(), error = null, result = null) }
            log("Discovery: ${_ui.value.range} (${ips.size} addresses) – mDNS/UPnP, ping, ports")
            val scanner = NetScanner(newSocket = { net.network.socketFactory.createSocket() }, bindUdp = { s -> runCatching { net.network.bindSocket(s) } })
            try {
                val hosts = scanner.scan(ips) { d, t, f -> _ui.update { it.copy(done = d, total = t, hosts = f) } }
                    .map { h ->
                        h.copy(role = when (h.ip) {
                            net.gateway -> "Gateway"; net.ip -> "This phone"; in net.dns -> "DNS"; else -> null
                        })
                    }
                val res = NetScan(range = _ui.value.range, myIp = net.ip, gateway = net.gateway, dns = net.dns, hosts = hosts,
                    scanned = ips.size, label = label)
                log("Done: ${hosts.size} devices")
                _ui.update { it.copy(phase = ScanPhase.DONE, hosts = hosts, result = res) }
                onSaved?.invoke(res)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _ui.update { it.copy(phase = ScanPhase.READY) }
                throw e
            } catch (e: Exception) {
                log("Error: ${e.message}")
                _ui.update { it.copy(phase = ScanPhase.READY, error = e.message) }
            }
        }
    }

    fun cancelScan() { if (_ui.value.phase == ScanPhase.SCANNING) job?.cancel() }

    /** Stops the bridge and returns the tester to normal mode. */
    fun stop() {
        job?.cancel()
        runCatching { mcLock?.release() }; mcLock = null
        val wasActive = _ui.value.phase != ScanPhase.IDLE
        _ui.value = ScanUi()
        if (wasActive) scope.launch { withContext(NonCancellable) { runCatching { bridge.stop() } } }
    }

}
