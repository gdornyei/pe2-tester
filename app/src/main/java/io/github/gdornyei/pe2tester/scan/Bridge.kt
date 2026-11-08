package io.github.gdornyei.pe2tester.scan

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import io.github.gdornyei.pe2tester.ble.Pe2Ble
import io.github.gdornyei.pe2tester.proto.Cmd
import io.github.gdornyei.pe2tester.proto.PT
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address

data class BridgeNet(val network: Network, val ip: String, val prefix: Int, val gateway: String?, val dns: List<String>)

/**
 * Turns the Pockethernet into a transparent Ethernet↔WiFi bridge and joins the phone to it
 * (app-only network, the phone's normal WiFi/mobile data keep working for everything else).
 */
class Bridge(private val ctx: Context, private val ble: Pe2Ble) {
    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    var active: BridgeNet? = null; private set

    private suspend fun sendAck(p: io.github.gdornyei.pe2tester.proto.Packet, timeoutMs: Long = 4000): Boolean = coroutineScope {
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            ble.packets.first { it.ptype == PT.ACK || it.ptype == PT.NACK }.ptype == PT.ACK
        }
        ble.send(p)
        val r = withTimeoutOrNull(timeoutMs) { waiter.await() }
        waiter.cancel()
        r == true
    }

    /** Step 1: tester side. Returns false if the tester did not acknowledge. */
    suspend fun startTester(ssid: String, pass: String, log: (String) -> Unit): Boolean {
        log("Switching Ethernet side to bridge mode…")
        val a = sendAck(Cmd.bridgeEthUp())
        log(if (a) "Ethernet: OK" else "Ethernet: no ACK, continuing")
        log("Starting WiFi access point ($ssid)…")
        val b = sendAck(Cmd.wifiBridge(true, ssid, pass), 6000)
        log(if (b) "WiFi: OK" else "WiFi: no ACK, continuing")
        delay(2500)
        return true
    }

    /** Step 2: phone joins the bridge WiFi (system asks for confirmation the first time). */
    @SuppressLint("MissingPermission")
    suspend fun joinWifi(ssid: String, pass: String, timeoutMs: Long = 60_000): BridgeNet? {
        if (Build.VERSION.SDK_INT < 29) return null
        releaseNetwork()
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(pass).build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val got = CompletableDeferred<Network?>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { got.complete(network) }
            override fun onUnavailable() { got.complete(null) }
            override fun onLost(network: Network) { if (active?.network == network) active = null }
        }
        callback = cb
        cm.requestNetwork(req, cb)
        val net = withTimeoutOrNull(timeoutMs) { got.await() } ?: return null
        cm.bindProcessToNetwork(net)
        // wait for DHCP from the bridged LAN
        var lp: LinkProperties? = null
        for (i in 0 until 40) {
            lp = cm.getLinkProperties(net)
            if (lp?.linkAddresses?.any { it.address is Inet4Address } == true) break
            delay(500)
        }
        val la = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address } ?: return BridgeNet(net, "", 24, null, emptyList())
        val gw = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val dns = lp?.dnsServers?.filterIsInstance<Inet4Address>()?.mapNotNull { it.hostAddress } ?: emptyList()
        return BridgeNet(net, la.address.hostAddress ?: "", la.prefixLength, gw, dns).also { active = it }
    }

    fun releaseNetwork() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        runCatching { cm.bindProcessToNetwork(null) }
        active = null
    }

    suspend fun stop() {
        releaseNetwork()
        runCatching { ble.send(Cmd.wifiBridge(false)) }
        delay(150)
        runCatching { ble.send(Cmd.ethStop()) }
    }
}

