package io.github.gdornyei.pe2tester

import io.github.gdornyei.pe2tester.ble.Pe2Ble
import io.github.gdornyei.pe2tester.proto.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
enum class ItemState { WAITING, RUNNING, OK, FAIL, INFO, SKIPPED }

@Serializable
data class Item(val state: ItemState = ItemState.WAITING, val summary: String = "", val details: List<Pair<String, String>> = emptyList())

data class Report(
    val startedAt: Long = 0,
    val running: Boolean = false,
    val phase: String = "",
    val wiremap: Item = Item(), val tdr: Item = Item(), val poe: Item = Item(), val link: Item = Item(),
    val dhcp: Item = Item(), val neighbor: Item = Item(), val ping: Item = Item(), val extIp: Item = Item(),
    val vlan: Item = Item(ItemState.SKIPPED), val vlansRaw: List<Int> = emptyList(),
    val profileLabel: String = "",
    val wiremapRaw: WiremapResult? = null, val tdrRaw: TdrResult? = null,
    val linkRaw: LinkResult? = null, val poeRaw: PoeResult? = null, val dhcpRaw: DhcpResult? = null,
    val neighborsRaw: List<NeighborInfo> = emptyList(), val pingsRaw: List<PingResult> = emptyList(), val extIpRaw: ExtIpResult? = null,
) {
    val failCount get() = items.count { it.second.state == ItemState.FAIL }
    val items get() = listOf(
        "Wiremap" to wiremap, "Cable (TDR)" to tdr, "PoE" to poe, "Link" to link,
        "IP address (DHCP)" to dhcp, "Switch port (LLDP/CDP)" to neighbor, "VLAN" to vlan, "Ping" to ping, "Internet" to extIp,
    )
}

enum class Profile(val label: String, val hint: String) {
    AUTO("Automatic", "Decides from a quick wiremap: adapter → cable test, switch → network test, open/short → TDR fault distance"),
    CABLE("Cable", "With the wiremap adapter: wiring + length/fault distance (TDR). A few seconds."),
    NETWORK("Network port", "Cable plugged into a switch: PoE, link, DHCP, LLDP/CDP, VLAN, ping, internet"),
    FULL("Full", "Every test in sequence"),
}

data class TestOptions(
    val profile: Profile = Profile.AUTO,
    val wiremap: Boolean = true, val tdr: Boolean = true, val poe: Boolean = true, val link: Boolean = true,
    val network: Boolean = true, val ping: Boolean = true, val extIp: Boolean = true,
    val nvp: Int = 71, val vlanId: Int = 0, val neighborWaitSec: Int = 35, val dhcpWaitSec: Int = 48,
    val vlanCollect: Boolean = false, val staticIp: Boolean = false, val ip: String = "", val mask: String = "",
    val gw: String = "", val dns: String = "", val customMac: String = "",
)

/** One-button quicktest: the same measurement sequence as the vendor app, minus the clutter. */
class QuickTest(private val ble: Pe2Ble, private val scope: CoroutineScope) {
    private val _report = MutableStateFlow(Report())
    val report: StateFlow<Report> = _report
    private var job: Job? = null
    val isRunning get() = job?.isActive == true

    private val inbox = Channel<Packet>(Channel.UNLIMITED)
    private val neighbors = linkedMapOf<String, NeighborInfo>()
    private var dhcp: DhcpResult? = null
    private val vlans = sortedSetOf<Int>()

    init {
        scope.launch { ble.packets.collect { inbox.trySend(it) } }
    }

    /** Called once a run has ended (finished, stopped or failed) with the final report. */
    var onFinished: ((Report) -> Unit)? = null

    fun start(opt: TestOptions, limits: io.github.gdornyei.pe2tester.data.Limits = io.github.gdornyei.pe2tester.data.Limits()) {
        if (isRunning) return
        job = scope.launch {
            try {
                run(opt)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _report.update { it.copy(phase = "Stopped") }
            } catch (e: Exception) {
                _report.update { it.copy(phase = "Error: ${e.message}") }
            } finally {
                withContext(NonCancellable) { runCatching { ble.send(Cmd.ethStop()) } }
                _report.update { r ->
                    fun Item.fin() = if (state == ItemState.RUNNING || state == ItemState.WAITING) copy(state = ItemState.SKIPPED) else this
                    r.copy(
                        running = false,
                        phase = if (r.phase.startsWith("Failure") || r.phase == "Stopped") r.phase else "Done",
                        wiremap = r.wiremap.fin(), tdr = r.tdr.fin(), poe = r.poe.fin(), link = r.link.fin(),
                        dhcp = r.dhcp.fin(), neighbor = r.neighbor.fin(), ping = r.ping.fin(), extIp = r.extIp.fin(),
                    )
                }
                _report.update { limits.apply(it) }
                onFinished?.invoke(_report.value)
            }
        }
    }

    fun stop() { job?.cancel() }

    private fun set(f: (Report) -> Report) = _report.update(f)

    private fun drain() { while (inbox.tryReceive().isSuccess) Unit }

    /** Waits for a packet accepted by [match]; anything else is handled as an async network packet. */
    private suspend fun await(timeoutMs: Long, match: (Packet) -> Boolean): Packet? = withTimeoutOrNull(timeoutMs) {
        var r: Packet? = null
        while (r == null) {
            val p = inbox.receive()
            if (match(p)) r = p else handleAsync(p)
        }
        r
    }

    private suspend fun cmd(p: Packet, expect: Int, timeoutMs: Long): Packet? {
        ble.send(p)
        return await(timeoutMs) { it.ptype == expect || it.ptype == PT.NACK }?.takeIf { it.ptype == expect }
    }

    private fun handleAsync(p: Packet) {
        when (p.ptype) {
            PT.DHCPV4_ACK -> NetParsers.dhcp(p.payload)?.let { d -> dhcp = d; set { it.copy(dhcpRaw = d) }; publishDhcp(d) }
            PT.VLAN_COLLECT -> {
                var added = false
                for (k in 0 until p.payload.size / 2) { val v = p.u16(2 * k); if (v != 0 && vlans.add(v)) added = true }
                if (added) set { it.copy(vlansRaw = vlans.sorted(), vlan = Item(ItemState.OK, vlans.sorted().joinToString(", "), listOf("Detected VLAN IDs" to vlans.sorted().joinToString(", ")))) }
            }
            PT.ETH_FILTER_MATCH -> {
                val n = NetParsers.lldp(p.payload) ?: NetParsers.cdp(p.payload) ?: return
                if (neighbors.put(n.protocol, n) == null) { set { it.copy(neighborsRaw = neighbors.values.toList()) }; publishNeighbors() }
            }
        }
    }

    private fun publishDhcp(d: DhcpResult) = set {
        it.copy(dhcp = Item(ItemState.OK, "${d.yourIp}  (gw ${d.router ?: "-"})", buildList {
            add("IP address" to d.yourIp); d.subnet?.let { s -> add("Mask" to s) }; d.router?.let { r -> add("Gateway" to r) }
            if (d.dns.isNotEmpty()) add("DNS" to d.dns.joinToString(", "))
            add("DHCP server" to "${d.serverIp} (${d.serverMac})")
            d.domain?.let { x -> add("Domain" to x) }
            d.leaseSec?.let { l -> add("Lease" to if (l >= 3600) "${l / 3600} h" else "$l s") }
            d.vlan?.let { v -> add("VLAN tag" to v.toString()) }
            d.extra.forEach { (k, v) -> add(k to v) }
        }))
    }

    private fun publishNeighbors() = set {
        val list = neighbors.values.toList()
        val first = list.first()
        val sum = listOfNotNull(first.systemName, first.portDescr ?: first.portId).joinToString(" / ")
        it.copy(neighbor = Item(ItemState.OK, sum.ifEmpty { first.protocol }, list.flatMap { n ->
            buildList {
                add("── ${n.protocol}" to "")
                n.systemName?.let { v -> add("Device" to v) }
                n.portId?.let { v -> add("Port ID" to v) }
                n.portDescr?.let { v -> add("Port description" to v) }
                n.vlan?.let { v -> add("VLAN (PVID)" to v.toString()) }
                n.mgmtIp?.let { v -> add("Management IP" to v) }
                n.platform?.let { v -> add("Platform" to v) }
                n.systemDescr?.let { v -> add("Description" to v) }
                n.capabilities?.let { v -> add("Capabilities" to v) }
                add("MAC" to n.srcMac)
                n.extra.forEach { (k, v) -> add(k to v) }
            }
        }))
    }

    private fun applyProfile(o0: TestOptions) = when (o0.profile) {
        Profile.CABLE -> o0.copy(wiremap = true, tdr = true, poe = false, link = false, network = false)
        Profile.NETWORK -> o0.copy(wiremap = false, tdr = false, poe = true, link = true, network = true)
        Profile.FULL -> o0.copy(wiremap = true, tdr = true, poe = true, link = true, network = true)
        Profile.AUTO -> o0.copy(wiremap = true, tdr = true, poe = true, link = true, network = true)
    }

    private fun skipped(o: TestOptions, r: Report): Report {
        fun Item.keep(on: Boolean) = if (on) this else Item(ItemState.SKIPPED)
        return r.copy(tdr = r.tdr.keep(o.tdr), poe = r.poe.keep(o.poe), link = r.link.keep(o.link || o.network),
            dhcp = r.dhcp.keep(o.network), neighbor = r.neighbor.keep(o.network), vlan = r.vlan.keep(o.network && o.vlanCollect),
            ping = r.ping.keep(o.network && o.ping), extIp = r.extIp.keep(o.network && o.extIp))
    }

    private suspend fun run(o0: TestOptions) {
        var o = applyProfile(o0)
        neighbors.clear(); dhcp = null; vlans.clear(); drain()
        set {
            Report(startedAt = System.currentTimeMillis(), running = true, phase = "Starting…", profileLabel = o0.profile.label,
                wiremap = if (o.wiremap) Item() else Item(ItemState.SKIPPED),
                tdr = if (o.tdr) Item() else Item(ItemState.SKIPPED),
                poe = if (o.poe) Item() else Item(ItemState.SKIPPED),
                link = if (o.link || o.network) Item() else Item(ItemState.SKIPPED),
                dhcp = if (o.network) Item() else Item(ItemState.SKIPPED),
                neighbor = if (o.network) Item() else Item(ItemState.SKIPPED),
                vlan = if (o.network && o.vlanCollect) Item() else Item(ItemState.SKIPPED),
                ping = if (o.network && o.ping) Item() else Item(ItemState.SKIPPED),
                extIp = if (o.network && o.extIp) Item() else Item(ItemState.SKIPPED))
        }

        // ---- Wiremap
        if (o.wiremap) {
            set { it.copy(phase = "Testing wiremap…", wiremap = Item(ItemState.RUNNING)) }
            val p = cmd(Cmd.wiremap(), PT.MEAS_REDC, 5000)
            val w = p?.let { Wiremap.parse(it) }
            set {
                it.copy(wiremapRaw = w, wiremap = if (w == null) Item(ItemState.FAIL, "No response") else Item(
                    if (w.status == Status.OK) ItemState.OK else ItemState.FAIL, w.summary, wiremapDetails(w)))
            }
        }

        // ---- Automatic: decide from the wiremap what else makes sense
        if (o0.profile == Profile.AUTO) {
            val w = _report.value.wiremapRaw
            val kind = when {
                w == null -> "unknown"
                io.github.gdornyei.pe2tester.data.Limits.isTransformerPattern(w.shorts) -> "port"
                w.shorts.any { it != 0 } -> "short"
                w.connections.any { it != 0 } -> "adapter"
                else -> "open"
            }
            o = when (kind) {
                "adapter" -> o.copy(tdr = true, poe = false, link = false, network = false)
                "port" -> o.copy(tdr = false, poe = true, link = true, network = true)
                "short" -> o.copy(tdr = true, poe = false, link = false, network = false)
                else -> o.copy(tdr = true, poe = true, link = true, network = true)
            }
            val label = when (kind) {
                "adapter" -> "Automatic → cable (adapter)"; "port" -> "Automatic → network port"
                "short" -> "Automatic → locating short"; else -> "Automatic → open / unknown"
            }
            set { skipped(o, it).copy(profileLabel = label, phase = label) }
        }

        // ---- TDR
        if (o.tdr) {
            set { it.copy(phase = "Testing cable (TDR)…", tdr = Item(ItemState.RUNNING)) }
            val ack = cmd(Cmd.ifUpForTdr(), PT.ACK, 3000)
            val p = if (ack != null) cmd(Cmd.tdr4Pair(), PT.TDR_4PAIR_SAME, 6000) else null
            val t = p?.let { Tdr.parse(it, o.nvp) }
            set {
                it.copy(tdrRaw = t, tdr = if (t == null) Item(ItemState.FAIL, "No response") else Item(
                    if (t.status == Status.OK) ItemState.OK else ItemState.FAIL, t.summary,
                    t.pairs.mapIndexed { i, pr -> "Pair ${PAIRS[i]}" to (if (pr.status.isEmpty()) "–" else "${pr.status} @ ${"%.1f".format(pr.distanceM)} m") }))
            }
        }

        // ---- PoE
        if (o.poe) {
            set { it.copy(phase = "Testing PoE…", poe = Item(ItemState.RUNNING)) }
            val r = cmd(Cmd.poe(), PT.MEAS_POE, 15000)?.let { PoeParser.parse(it) }
            set {
                it.copy(poeRaw = r, poe = when {
                    r == null -> Item(ItemState.FAIL, "No response")
                    !r.present -> Item(ItemState.INFO, "No PoE")
                    else -> Item(if (r.status == Status.OK) ItemState.OK else ItemState.FAIL, r.summary, buildList {
                        add("Type" to r.pseType); add("Class" to r.pdClass)
                        add("Open-circuit voltage" to "${r.openV} V"); add("Load voltage" to "${r.loadV} V")
                        if (r.polarity.isNotEmpty()) add("Polarity" to r.polarity)
                    })
                })
            }
        }

        // ---- Link
        var linkUp = false
        if (o.link || o.network) {
            set { it.copy(phase = "Bringing up link…", link = Item(ItemState.RUNNING)) }
            if (cmd(Cmd.linkTest(), PT.ACK, 3000) != null) {
                val li = await(10_000) { it.ptype == PT.PHY_LINKINFO && LinkParser.speedOf(it) > 0 }
                if (li != null) {
                    linkUp = true
                    val l = LinkParser.parse(li)
                    set { it.copy(linkRaw = l, link = Item(ItemState.OK, l.summary, linkDetails(l))) }
                }
            }
            if (!linkUp) set { it.copy(link = Item(ItemState.FAIL, "No link")) }
        }

        // ---- Network
        if (o.network && linkUp) {
            set { it.copy(phase = "Network: DHCP, LLDP/CDP…", dhcp = Item(ItemState.RUNNING), neighbor = Item(ItemState.RUNNING),
                vlan = if (o.vlanCollect) Item(ItemState.RUNNING) else it.vlan) }
            val up = Cmd.networkUp(dhcp = !o.staticIp, vlanId = o.vlanId, vlanCollect = o.vlanCollect, mac = Cmd.mac(o.customMac),
                ip = o.ip, mask = o.mask, gw = o.gw, dns = o.dns)
            if (cmd(up, PT.ACK, 3000) != null) {
                await(10_000) { it.ptype == PT.PHY_LINKINFO && LinkParser.speedOf(it) > 0 }
                if (o.staticIp) {
                    val d = DhcpResult(o.ip, "", o.mask.ifEmpty { null }, o.gw.ifEmpty { null }, listOf(o.dns).filter { it.isNotEmpty() },
                        null, null, "", if (o.vlanId > 0) o.vlanId else null, mapOf("Mode" to "static IP"))
                    dhcp = d
                    set { it.copy(dhcpRaw = d, dhcp = Item(ItemState.INFO, "Static: ${o.ip}", listOf("IP address" to o.ip, "Mask" to o.mask, "Gateway" to o.gw, "DNS" to o.dns))) }
                }
                val t0 = System.currentTimeMillis()
                while (true) {
                    val el = System.currentTimeMillis() - t0
                    val dhcpDone = dhcp != null || el > o.dhcpWaitSec * 1000L
                    val nbDone = neighbors.isNotEmpty() || el > o.neighborWaitSec * 1000L
                    val vlanDone = !o.vlanCollect || el > 30_000L
                    if (dhcpDone && nbDone && vlanDone) break
                    set { it.copy(phase = "Network: DHCP, LLDP/CDP… ${el / 1000} s") }
                    await(1000) { false }
                }
            }
            set {
                it.copy(
                    dhcp = if (dhcp == null) Item(ItemState.FAIL, "No DHCP response") else it.dhcp,
                    neighbor = if (neighbors.isEmpty()) Item(ItemState.INFO, "No LLDP/CDP received") else it.neighbor,
                    vlan = if (o.vlanCollect && vlans.isEmpty()) Item(ItemState.INFO, "No VLAN-tagged traffic seen") else it.vlan,
                )
            }

            val d = dhcp
            if (d != null && o.ping) {
                val targets = listOfNotNull(d.router, d.dns.firstOrNull(), "1.1.1.1").distinct()
                set { it.copy(phase = "Ping…", ping = Item(ItemState.RUNNING)) }
                val res = mutableListOf<PingResult>()
                for (t in targets) {
                    val p = cmd(Cmd.ping(t), PT.PING, 6000)
                    res += if (p != null) PingParser.parse(p, t) else PingResult(t, t, null)
                    set { it.copy(ping = Item(ItemState.RUNNING, "", pingDetails(res))) }
                }
                val ok = res.all { it.ok }
                set { it.copy(pingsRaw = res.toList(), ping = Item(if (ok) ItemState.OK else ItemState.FAIL,
                    if (ok) "All reachable (${res.size})" else "${res.count { !it.ok }} target(s) not responding", pingDetails(res))) }
            } else if (o.ping) set { it.copy(ping = Item(ItemState.SKIPPED, "No IP address")) }

            if (d != null && o.extIp) {
                set { it.copy(phase = "Checking internet…", extIp = Item(ItemState.RUNNING)) }
                val p = cmd(Cmd.httpGet(EXTIP_URL), PT.HTTP_REQ, 15000)
                val e = p?.let { ExtIpParser.parse(it) }
                set {
                    it.copy(extIpRaw = e, extIp = if (e == null) Item(ItemState.FAIL, "No internet (HTTP error)") else Item(ItemState.OK, e.ip,
                        listOf("Public IP" to e.ip, "ISP" to e.isp, "AS" to e.asn, "Location" to e.location)))
                }
            } else if (o.extIp) set { it.copy(extIp = Item(ItemState.SKIPPED, "No IP address")) }
        } else if (o.network) {
            set { it.copy(dhcp = Item(ItemState.SKIPPED, "No link"), neighbor = Item(ItemState.SKIPPED, "No link"),
                ping = Item(ItemState.SKIPPED, "No link"), extIp = Item(ItemState.SKIPPED, "No link"),
                vlan = if (o.vlanCollect) Item(ItemState.SKIPPED, "No link") else it.vlan) }
        }
    }

    companion object {
        val PAIRS = listOf("1-2", "3-6", "4-5", "7-8")
        const val EXTIP_URL = "http://ip-api.com/json/?fields=status,country,regionName,city,isp,as,query"

        fun wiremapDetails(w: WiremapResult): List<Pair<String, String>> = buildList {
            for (i in 1..9) {
                val name = if (i == 9) "Shield" else "Wire $i"
                val c = w.connections[i]; val s = w.shorts[i]
                val v = when {
                    s != 0 -> "short → ${if (s == 9) "shield" else s}"
                    c == 0 -> "open"
                    c == 255 -> "connected (unidentified)"
                    c >= 10 -> "uncertain (${c / 10}/${c % 10})"
                    else -> "→ ${if (c == 9) "shield" else c}"
                }
                add(name to v)
            }
            if (w.remoteId > 0) add("Remote ID" to w.remoteId.toString())
        }

        fun linkDetails(l: LinkResult): List<Pair<String, String>> = buildList {
            add("Speed" to l.summary)
            add("Partner 10M" to l.partner10); add("Partner 100M" to l.partner100); add("Partner 1000M" to l.partner1000)
            add("Partner 2.5/5/10G" to l.partnerMultiGig)
            add("Polarity" to PAIRS.indices.joinToString("  ") { "${PAIRS[it]}: ${l.polarity[it]}" })
            l.skewNs?.let { s -> add("Delay skew" to PAIRS.indices.joinToString("  ") { "${PAIRS[it]}: ${s[it]} ns" }) }
        }

        fun pingDetails(r: List<PingResult>) = r.map { it.target to if (!it.ok) "no reply" else "${it.resolvedIp}, ${it.avgMs ?: 0} ms" }
    }
}
