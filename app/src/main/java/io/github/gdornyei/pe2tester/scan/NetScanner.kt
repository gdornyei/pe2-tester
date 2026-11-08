package io.github.gdornyei.pe2tester.scan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class HostInfo(
    val ip: String,
    val hostname: String? = null,       // reverse DNS
    val netbiosName: String? = null,
    val workgroup: String? = null,
    val mdnsName: String? = null,
    val mac: String? = null,
    val openPorts: List<Int> = emptyList(),
    val httpTitle: String? = null,
    val httpServer: String? = null,
    val rttMs: Long? = null,
    val role: String? = null,           // "Gateway", "DNS", "This phone"
    val services: List<String> = emptyList(),   // mDNS service instances, e.g. "Living room TV (_googlecast)"
    val upnp: String? = null,                   // UPnP friendly name / model
    val pingOk: Boolean = false,
) {
    val name: String? get() = listOfNotNull(netbiosName, mdnsName?.removeSuffix(".local"), upnp?.substringBefore(" · "),
        services.firstOrNull()?.substringBefore(" ("), hostname?.takeIf { it != ip }).firstOrNull()
    val description: String get() = listOfNotNull(upnp, services.takeIf { it.isNotEmpty() }?.joinToString(", "), httpTitle, httpServer, guess())
        .distinct().joinToString(" · ")

    private fun guess(): String? = when {
        9100 in openPorts || 631 in openPorts || 515 in openPorts -> "printer"
        554 in openPorts -> "camera / RTSP"
        8291 in openPorts -> "MikroTik"
        502 in openPorts -> "Modbus TCP device"
        62078 in openPorts -> "iPhone / iPad"
        8008 in openPorts || 8009 in openPorts -> "Chromecast / Google TV"
        7000 in openPorts -> "AirPlay device"
        3389 in openPorts -> "Windows (RDP)"
        445 in openPorts || 139 in openPorts -> "Windows / SMB"
        1883 in openPorts -> "MQTT"
        22 in openPorts && openPorts.size <= 2 -> "Linux / SSH"
        else -> null
    }
}

@Serializable
data class NetScan(
    val id: String = java.util.UUID.randomUUID().toString(),
    val ts: Long = System.currentTimeMillis(),
    val range: String,
    val myIp: String = "",
    val gateway: String? = null,
    val dns: List<String> = emptyList(),
    val hosts: List<HostInfo> = emptyList(),
    val scanned: Int = 0,
    val label: String = "",
)

object IpRange {
    fun toLong(ip: String): Long? {
        val p = ip.trim().split('.')
        if (p.size != 4) return null
        var v = 0L
        for (x in p) { val n = x.toIntOrNull() ?: return null; if (n !in 0..255) return null; v = (v shl 8) or n.toLong() }
        return v
    }

    fun toIp(v: Long) = "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"

    /** "192.168.1.0/24", "10.0.0.5-10.0.0.40", "10.0.0.5-40", "10.0.0.7". Returns null if invalid or > max hosts. */
    fun parse(s: String, max: Int = 4096): List<String>? {
        val t = s.trim()
        val list = when {
            '/' in t -> {
                val (a, b) = t.split('/', limit = 2)
                val base = toLong(a) ?: return null; val pre = b.trim().toIntOrNull() ?: return null
                if (pre !in 8..32) return null
                val mask = if (pre == 32) 0xFFFFFFFFL else (0xFFFFFFFFL shl (32 - pre)) and 0xFFFFFFFFL
                val net = base and mask; val bc = net or (mask.inv() and 0xFFFFFFFFL)
                if (pre >= 31) (net..bc).toList() else ((net + 1) until bc).toList()
            }
            '-' in t -> {
                val (a, b) = t.split('-', limit = 2)
                val from = toLong(a) ?: return null
                val to = if ('.' in b) toLong(b) ?: return null else ((from and 0xFFFFFF00L) or (b.trim().toLongOrNull() ?: return null))
                if (to < from) return null
                (from..to).toList()
            }
            else -> listOf(toLong(t) ?: return null)
        }
        if (list.size > max) return null
        return list.map { toIp(it) }
    }

    fun cidrOf(ip: String, prefix: Int): String {
        val v = toLong(ip) ?: return ip
        val p = prefix.coerceIn(16, 30).let { if (it < 22) 24 else it }  // keep default scans small: /22 … /30, else /24
        val mask = (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        return "${toIp(v and mask)}/$p"
    }
}

/**
 * Host discovery without root: TCP connect probes (a refused connection also proves the host is alive),
 * then reverse DNS, NetBIOS node status (name + MAC), unicast mDNS reverse lookup and HTTP title.
 * Uses whatever network the process is bound to (the Pockethernet WiFi bridge).
 */
class NetScanner(
    private val newSocket: () -> Socket = { Socket() },
    private val bindUdp: (DatagramSocket) -> Unit = {},
    private val ping: (String, Int) -> Boolean = { ip, t -> runCatching { InetAddress.getByName(ip).isReachable(t) }.getOrDefault(false) },
) {
    companion object {
        /** Liveness probes (first wave): common ports incl. phones / media devices. */
        val QUICK = listOf(80, 443, 22, 445, 139, 135, 62078, 8080, 7000, 8008, 554, 9100, 5000, 49152)
        val PORTS = QUICK + listOf(3389, 53, 21, 23, 8443, 631, 515, 1883, 502, 8291, 8000, 8009, 161, 1900)
        val MDNS_SERVICES = listOf("_services._dns-sd._udp", "_http._tcp", "_https._tcp", "_googlecast._tcp", "_airplay._tcp", "_raop._tcp",
            "_companion-link._tcp", "_homekit._tcp", "_hap._tcp", "_ipp._tcp", "_ipps._tcp", "_printer._tcp", "_pdl-datastream._tcp",
            "_smb._tcp", "_afpovertcp._tcp", "_ssh._tcp", "_device-info._tcp", "_shelly._tcp", "_esphomelib._tcp", "_spotify-connect._tcp",
            "_sonos._tcp", "_workstation._tcp", "_mqtt._tcp", "_androidtvremote2._tcp", "_amzn-wplay._tcp", "_matter._tcp", "_hue._tcp")
    }

    /** What the multicast discovery found for one IP. */
    data class Seen(val names: MutableSet<String> = linkedSetOf(), val services: MutableSet<String> = linkedSetOf(), var upnp: String? = null, var location: String? = null)

    private sealed interface Probe { data class Open(val rtt: Long) : Probe; data class Alive(val rtt: Long) : Probe; data object Dead : Probe }

    private fun tcp(ip: String, port: Int, timeout: Int): Probe {
        val t0 = System.nanoTime()
        val s = newSocket()
        return try {
            s.connect(InetSocketAddress(ip, port), timeout); Probe.Open((System.nanoTime() - t0) / 1_000_000)
        } catch (e: ConnectException) {
            val msg = e.message ?: ""
            if (msg.contains("refused", true)) Probe.Alive((System.nanoTime() - t0) / 1_000_000) else Probe.Dead
        } catch (e: Exception) { Probe.Dead } finally { runCatching { s.close() } }
    }

    suspend fun scan(
        ips: List<String>, ports: List<Int> = PORTS, timeoutMs: Int = 600, parallel: Int = 48,
        onProgress: (done: Int, total: Int, found: List<HostInfo>) -> Unit,
    ): List<HostInfo> = coroutineScope {
        val sem = Semaphore(parallel)
        val done = AtomicInteger(0)
        val found = java.util.Collections.synchronizedList(mutableListOf<HostInfo>())
        // 1) multicast discovery (mDNS + SSDP) in parallel, ~4 s
        val seen = java.util.concurrent.ConcurrentHashMap<String, Seen>()
        val m1 = async(Dispatchers.IO) { runCatching { mdnsBrowse(seen) } }
        val m2 = async(Dispatchers.IO) { runCatching { ssdp(seen) } }
        m1.await(); m2.await()
        runCatching { upnpDetails(seen) }
        // 2) per-host probing
        ips.map { ip ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    ensureActive()
                    val h = probeHost(ip, ports, timeoutMs, seen[ip])
                    if (h != null) { found += h }
                    onProgress(done.incrementAndGet(), ips.size, synchronized(found) { found.toList() })
                }
            }
        }.awaitAll()
        synchronized(found) { found.sortedBy { IpRange.toLong(it.ip) ?: 0 } }
    }

    private suspend fun probeHost(ip: String, ports: List<Int>, timeoutMs: Int, seen: Seen?): HostInfo? = coroutineScope {
        // liveness: ICMP ping, quick TCP ports (a refused connection also counts), NetBIOS, or seen via mDNS/SSDP
        val pg = async(Dispatchers.IO) { ping(ip, 1200) || ping(ip, 800) }
        val first = ports.filter { it in QUICK }
        val r1 = first.map { p -> async(Dispatchers.IO) { p to tcp(ip, p, timeoutMs) } }.awaitAll()
        val nb = async(Dispatchers.IO) { netbios(ip) }
        val nbRes = nb.await()
        val pingOk = pg.await()
        val alive = pingOk || r1.any { it.second !is Probe.Dead } || nbRes != null || seen != null
        if (!alive) return@coroutineScope null
        val r2 = ports.filter { it !in QUICK }.map { p -> async(Dispatchers.IO) { p to tcp(ip, p, timeoutMs) } }.awaitAll()
        val all = r1 + r2
        val open = all.filter { it.second is Probe.Open }.map { it.first }.sorted()
        val rtt = all.mapNotNull { (it.second as? Probe.Open)?.rtt ?: (it.second as? Probe.Alive)?.rtt }.minOrNull()
        val rdns = async(Dispatchers.IO) { withTimeoutOrNull(2500) { runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull() } }
        val mdns = async(Dispatchers.IO) { mdnsReverse(ip) }
        val http = async(Dispatchers.IO) {
            when {
                80 in open -> httpInfo(ip, 80)
                8080 in open -> httpInfo(ip, 8080)
                8000 in open -> httpInfo(ip, 8000)
                else -> null
            }
        }
        val h = http.await()
        HostInfo(
            ip = ip, hostname = rdns.await()?.takeIf { it != ip }, netbiosName = nbRes?.first, workgroup = nbRes?.second,
            mac = nbRes?.third, mdnsName = mdns.await() ?: seen?.names?.firstOrNull(), openPorts = open, httpTitle = h?.first,
            httpServer = h?.second, rttMs = rtt, services = seen?.services?.toList() ?: emptyList(), upnp = seen?.upnp, pingOk = pingOk,
        )
    }

    // ---------------------------------------------------------------- NetBIOS node status (UDP 137)

    /** Returns (name, workgroup, mac) or null. */
    fun netbios(ip: String, timeoutMs: Int = 700): Triple<String?, String?, String?>? {
        val q = ByteArrayOutputStream()
        q.write(byteArrayOf(0x13, 0x37, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        q.write(0x20)
        val name = ByteArray(16).also { it[0] = '*'.code.toByte() }
        for (b in name) { val v = b.toInt() and 0xFF; q.write('A'.code + (v shr 4)); q.write('A'.code + (v and 15)) }
        q.write(byteArrayOf(0, 0, 0x21, 0, 1))
        val req = q.toByteArray()
        return try {
            DatagramSocket().use { s ->
                bindUdp(s); s.soTimeout = timeoutMs
                s.send(DatagramPacket(req, req.size, InetAddress.getByName(ip), 137))
                val buf = ByteArray(1024); val p = DatagramPacket(buf, buf.size); s.receive(p)
                parseNbstat(buf, p.length)
            }
        } catch (e: Exception) { null }
    }

    fun parseNbstat(b: ByteArray, len: Int): Triple<String?, String?, String?>? {
        if (len < 57) return null
        var o = 12
        while (o < len && b[o].toInt() != 0) o += (b[o].toInt() and 0xFF) + 1
        o += 1 + 2 + 2 + 4 + 2 // name end, type, class, ttl, rdlength
        if (o >= len) return null
        val n = b[o].toInt() and 0xFF; o++
        var host: String? = null; var group: String? = null
        for (i in 0 until n) {
            if (o + 18 > len) break
            val nm = String(b, o, 15, Charsets.ISO_8859_1).trim()
            val suffix = b[o + 15].toInt() and 0xFF
            val isGroup = (b[o + 16].toInt() and 0x80) != 0
            if (suffix == 0 && !isGroup && host == null) host = nm
            if (suffix == 0 && isGroup && group == null) group = nm
            o += 18
        }
        val mac = if (o + 6 <= len) (0 until 6).joinToString(":") { "%02X".format(b[o + it]) }.takeIf { it != "00:00:00:00:00:00" } else null
        return Triple(host, group, mac)
    }

    // ---------------------------------------------------------------- mDNS reverse lookup (unicast to host:5353)

    fun mdnsReverse(ip: String, timeoutMs: Int = 700): String? {
        val parts = ip.split('.').reversed() + listOf("in-addr", "arpa")
        val q = ByteArrayOutputStream()
        q.write(byteArrayOf(0x12, 0x34, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        for (p in parts) { q.write(p.length); q.write(p.toByteArray()) }
        q.write(byteArrayOf(0, 0, 12, 0x80.toByte(), 1)) // PTR, QU + IN
        val req = q.toByteArray()
        return try {
            DatagramSocket().use { s ->
                bindUdp(s); s.soTimeout = timeoutMs
                s.send(DatagramPacket(req, req.size, InetAddress.getByName(ip), 5353))
                val buf = ByteArray(1500); val p = DatagramPacket(buf, buf.size); s.receive(p)
                parsePtr(buf, p.length)
            }
        } catch (e: Exception) { null }
    }

    private fun readName(b: ByteArray, len: Int, start: Int): Pair<String, Int> {
        val sb = StringBuilder(); var o = start; var end = -1; var guard = 0
        while (o < len && guard++ < 64) {
            val l = b[o].toInt() and 0xFF
            if (l == 0) { o++; break }
            if (l and 0xC0 == 0xC0) {
                if (end < 0) end = o + 2
                o = ((l and 0x3F) shl 8) or (b[o + 1].toInt() and 0xFF); continue
            }
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(b, o + 1, minOf(l, len - o - 1), Charsets.UTF_8)); o += l + 1
        }
        return sb.toString() to (if (end >= 0) end else o)
    }

    fun parsePtr(b: ByteArray, len: Int): String? {
        if (len < 12) return null
        val qd = ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)
        val an = ((b[6].toInt() and 0xFF) shl 8) or (b[7].toInt() and 0xFF)
        var o = 12
        repeat(qd) { o = readName(b, len, o).second + 4 }
        repeat(an) {
            val (_, after) = readName(b, len, o); o = after
            if (o + 10 > len) return null
            val type = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
            val rdl = ((b[o + 8].toInt() and 0xFF) shl 8) or (b[o + 9].toInt() and 0xFF)
            o += 10
            if (type == 12) return readName(b, len, o).first.ifEmpty { null }
            o += rdl
        }
        return null
    }

    // ---------------------------------------------------------------- mDNS browse (multicast, legacy-unicast replies)

    private fun dnsQuery(names: List<String>, type: Int): ByteArray {
        val q = ByteArrayOutputStream()
        q.write(byteArrayOf(0, 0, 0, 0, (names.size shr 8).toByte(), names.size.toByte(), 0, 0, 0, 0, 0, 0))
        for (n in names) {
            for (p in "$n.local".split('.')) { val b = p.toByteArray(); q.write(b.size); q.write(b) }
            q.write(byteArrayOf(0, 0, type.toByte(), 0, 1))
        }
        return q.toByteArray()
    }

    /** Parses all records of a DNS message: A -> name/ip, PTR/SRV -> service instances. */
    fun parseMdns(b: ByteArray, len: Int, src: String, seen: MutableMap<String, Seen>) {
        if (len < 12) return
        fun u16(o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        val qd = u16(4); val total = u16(6) + u16(8) + u16(10)
        var o = 12
        repeat(qd) { o = readName(b, len, o).second + 4 }
        val aRec = mutableMapOf<String, String>()          // host.local -> ip
        val srv = mutableMapOf<String, String>()           // instance -> host.local
        val ptr = mutableListOf<String>()                  // instances
        repeat(total) {
            if (o + 10 > len) return@repeat
            val (name, after) = readName(b, len, o); o = after
            if (o + 10 > len) return@repeat
            val type = u16(o); val rdl = u16(o + 8); val rd = o + 10
            when (type) {
                1 -> if (rdl == 4 && rd + 4 <= len) aRec[name.lowercase()] = (0..3).joinToString(".") { (b[rd + it].toInt() and 0xFF).toString() }
                12 -> ptr += readName(b, len, rd).first
                33 -> if (rd + 6 <= len) srv[name] = readName(b, len, rd + 6).first.lowercase()
            }
            o = rd + rdl
        }
        for ((host, ip) in aRec) seen.getOrPut(ip) { Seen() }.names += host
        val instances = (ptr + srv.keys).filter { !it.startsWith("_") && it.contains("._") }.distinct()
        for (inst in instances) {
            val host = srv[inst]
            val ip = host?.let { aRec[it] } ?: src
            val label = inst.substringBefore("._")
            val svc = inst.substringAfter("._").substringBefore("._").let { "_" + it }
            seen.getOrPut(ip) { Seen() }.services += "$label ($svc)"
        }
        if (aRec.isEmpty() && instances.isEmpty() && ptr.isNotEmpty()) seen.getOrPut(src) { Seen() }
    }

    fun mdnsBrowse(seen: MutableMap<String, Seen>, waitMs: Long = 3500) {
        DatagramSocket().use { s ->
            bindUdp(s); s.soTimeout = 300
            val grp = InetAddress.getByName("224.0.0.251")
            val qs = listOf(dnsQuery(MDNS_SERVICES.take(14), 12), dnsQuery(MDNS_SERVICES.drop(14), 12))
            repeat(2) { qs.forEach { q -> runCatching { s.send(DatagramPacket(q, q.size, grp, 5353)) } }; Thread.sleep(120) }
            val end = System.currentTimeMillis() + waitMs
            val buf = ByteArray(9000)
            var followups = 0
            while (System.currentTimeMillis() < end) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (e: SocketTimeoutException) { continue }
                val src = p.address.hostAddress ?: continue
                parseMdns(buf, p.length, src, seen)
                // ask for the service types announced by _services._dns-sd (once each, bounded)
                if (followups < 6) {
                    val types = Regex("(_[a-z0-9-]+\\._(?:tcp|udp))").findAll(String(buf, 0, p.length, Charsets.ISO_8859_1)).map { it.value }.toSet()
                    val newTypes = types.filter { it !in MDNS_SERVICES }.take(10)
                    if (newTypes.isNotEmpty()) { followups++; val q = dnsQuery(newTypes, 12); runCatching { s.send(DatagramPacket(q, q.size, grp, 5353)) } }
                }
            }
        }
    }

    // ---------------------------------------------------------------- SSDP / UPnP

    fun ssdp(seen: MutableMap<String, Seen>, waitMs: Long = 3000) {
        DatagramSocket().use { s ->
            bindUdp(s); s.soTimeout = 300
            val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n".toByteArray()
            val grp = InetAddress.getByName("239.255.255.250")
            repeat(2) { runCatching { s.send(DatagramPacket(msg, msg.size, grp, 1900)) }; Thread.sleep(150) }
            val end = System.currentTimeMillis() + waitMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < end) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (e: SocketTimeoutException) { continue }
                val ip = p.address.hostAddress ?: continue
                val txt = String(buf, 0, p.length, Charsets.ISO_8859_1)
                val e = seen.getOrPut(ip) { Seen() }
                if (e.location == null) e.location = Regex("(?im)^LOCATION:\\s*(\\S+)").find(txt)?.groupValues?.get(1)
                if (e.upnp == null) e.upnp = Regex("(?im)^SERVER:\\s*(.+)$").find(txt)?.groupValues?.get(1)?.trim()
            }
        }
    }

    /** Fetches friendlyName / manufacturer / model from UPnP device descriptions (bounded). */
    fun upnpDetails(seen: Map<String, Seen>) {
        seen.entries.filter { it.value.location != null }.take(40).forEach { (_, e) ->
            runCatching {
                val u = java.net.URL(e.location)
                newSocket().use { s ->
                    s.connect(InetSocketAddress(u.host, if (u.port > 0) u.port else 80), 1200); s.soTimeout = 1500
                    s.getOutputStream().write("GET ${u.file.ifEmpty { "/" }} HTTP/1.0\r\nHost: ${u.host}\r\n\r\n".toByteArray())
                    val txt = s.getInputStream().readBytes().let { String(it, Charsets.UTF_8) }
                    fun tag(t: String) = Regex("<$t>([^<]+)</$t>").find(txt)?.groupValues?.get(1)?.trim()
                    val parts = listOfNotNull(tag("friendlyName"), listOfNotNull(tag("manufacturer"), tag("modelName")).joinToString(" ").ifEmpty { null })
                    if (parts.isNotEmpty()) e.upnp = parts.distinct().joinToString(" · ")
                }
            }
        }
    }

    // ---------------------------------------------------------------- HTTP title / server header

    fun httpInfo(ip: String, port: Int): Pair<String?, String?>? = try {
        newSocket().use { s ->
            s.connect(InetSocketAddress(ip, port), 1500); s.soTimeout = 2000
            s.getOutputStream().write("GET / HTTP/1.0\r\nHost: $ip\r\nUser-Agent: PE2-Tester\r\nConnection: close\r\n\r\n".toByteArray())
            val inp = s.getInputStream(); val buf = ByteArray(24 * 1024); var n = 0
            while (n < buf.size) { val r = try { inp.read(buf, n, buf.size - n) } catch (e: SocketTimeoutException) { -1 }; if (r <= 0) break; n += r }
            val txt = String(buf, 0, n, Charsets.ISO_8859_1)
            val server = Regex("(?im)^Server:\\s*(.+)$").find(txt)?.groupValues?.get(1)?.trim()
            val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(txt)?.groupValues?.get(1)
                ?.replace(Regex("\\s+"), " ")?.trim()?.take(80)?.let { fixUtf8(it) }
            (title?.ifEmpty { null }) to server
        }
    } catch (e: Exception) { null }

    private fun fixUtf8(s: String) = runCatching { String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8) }.getOrDefault(s)
}
