package io.github.gdornyei.pe2tester.proto

import kotlinx.serialization.Serializable

/** Parsers for raw Ethernet frames forwarded by the tester (DHCP ACK, LLDP, CDP). */

data class EthHeader(val dst: String, val src: String, val vlan: Int?, val etherType: Int, val payloadOffset: Int)

@Serializable
data class DhcpResult(
    val yourIp: String, val serverIp: String, val subnet: String?, val router: String?, val dns: List<String>,
    val domain: String?, val leaseSec: Long?, val serverMac: String, val vlan: Int?, val extra: Map<String, String>,
)

@Serializable
data class NeighborInfo(
    val protocol: String, val systemName: String?, val portId: String?, val portDescr: String?,
    val systemDescr: String?, val platform: String?, val mgmtIp: String?, val vlan: Int?,
    val chassisId: String?, val capabilities: String?, val srcMac: String, val extra: Map<String, String>,
)

private fun ByteArray.u8(o: Int) = this[o].toInt() and 0xFF
private fun ByteArray.be16(o: Int) = (u8(o) shl 8) or u8(o + 1)
private fun ByteArray.be32(o: Int) = (be16(o).toLong() shl 16) or be16(o + 2).toLong()
private fun ByteArray.mac(o: Int) = (0..5).joinToString(":") { "%02X".format(u8(o + it)) }
private fun ByteArray.ip4(o: Int) = (0..3).joinToString(".") { u8(o + it).toString() }
private fun ByteArray.ip6(o: Int) = (0..7).joinToString(":") { "%x".format(be16(o + 2 * it)) }
private fun ByteArray.text(o: Int, n: Int) =
    String(this, o, n, Charsets.UTF_8).trimEnd('\u0000').filter { it >= ' ' || it == '\n' }.trim()

object NetParsers {
    fun eth(f: ByteArray): EthHeader? {
        if (f.size < 14) return null
        var o = 12; var vlan: Int? = null
        var t = f.be16(o)
        if (t == 0x8100 || t == 0x88A8) {
            if (f.size < 18) return null
            vlan = f.be16(14) and 0x0FFF; o = 16; t = f.be16(o)
        }
        return EthHeader(f.mac(0), f.mac(6), vlan, t, o + 2)
    }

    // ------------------------------------------------------------- DHCP

    fun dhcp(f: ByteArray): DhcpResult? {
        val e = eth(f) ?: return null
        if (e.etherType != 0x0800) return null
        val ip = e.payloadOffset
        if (f.size < ip + 20) return null
        val ihl = (f.u8(ip) and 0x0F) * 4
        if (f.u8(ip + 9) != 17) return null
        val srcIp = f.ip4(ip + 12)
        val d = ip + ihl + 8
        if (f.size < d + 240) return null
        if (f.be32(d + 236) != 0x63825363L) return null
        val yiaddr = f.ip4(d + 16)
        var subnet: String? = null; var router: String? = null; var domain: String? = null; var lease: Long? = null
        var server = srcIp
        val dns = mutableListOf<String>(); val extra = linkedMapOf<String, String>()
        var o = d + 240
        while (o < f.size) {
            val code = f.u8(o)
            if (code == 255) break
            if (code == 0) { o++; continue }
            if (o + 1 >= f.size) break
            val len = f.u8(o + 1); val v = o + 2
            if (v + len > f.size) break
            when (code) {
                1 -> if (len >= 4) subnet = f.ip4(v)
                3 -> if (len >= 4) router = f.ip4(v)
                6 -> for (k in 0 until len / 4) dns += f.ip4(v + 4 * k)
                12 -> extra["Hostname"] = f.text(v, len)
                15 -> domain = f.text(v, len)
                28 -> if (len >= 4) extra["Broadcast"] = f.ip4(v)
                42 -> extra["NTP"] = (0 until len / 4).joinToString(", ") { f.ip4(v + 4 * it) }
                51 -> if (len >= 4) lease = f.be32(v)
                54 -> if (len >= 4) server = f.ip4(v)
                66 -> extra["TFTP server"] = f.text(v, len)
                67 -> extra["Boot file"] = f.text(v, len)
                119 -> extra["Search domain"] = "(${len} bytes)"
            }
            o = v + len
        }
        return DhcpResult(yiaddr, server, subnet, router, dns, domain, lease, e.src, e.vlan, extra)
    }

    // ------------------------------------------------------------- LLDP

    fun lldp(f: ByteArray): NeighborInfo? {
        val e = eth(f) ?: return null
        if (e.etherType != 0x88CC) return null
        var o = e.payloadOffset
        var chassis: String? = null; var port: String? = null; var portDescr: String? = null
        var sysName: String? = null; var sysDescr: String? = null; var mgmt: String? = null
        var vlan: Int? = null; var caps: String? = null
        val extra = linkedMapOf<String, String>()
        while (o + 2 <= f.size) {
            val h = f.be16(o); val type = h shr 9; val len = h and 0x1FF
            val v = o + 2
            if (type == 0 || v + len > f.size) break
            when (type) {
                1 -> chassis = idValue(f, v, len)
                2 -> port = idValue(f, v, len)
                4 -> portDescr = f.text(v, len)
                5 -> sysName = f.text(v, len)
                6 -> sysDescr = f.text(v, len)
                7 -> if (len >= 4) caps = capsText(f.be16(v + 2), lldp = true)
                8 -> if (len >= 2) {
                    val alen = f.u8(v); val sub = f.u8(v + 1)
                    if (sub == 1 && alen == 5) mgmt = mgmt ?: f.ip4(v + 2)
                    if (sub == 2 && alen == 17) mgmt = mgmt ?: f.ip6(v + 2)
                }
                127 -> if (len >= 4) {
                    val oui = (f.u8(v) shl 16) or (f.u8(v + 1) shl 8) or f.u8(v + 2); val sub = f.u8(v + 3)
                    when {
                        oui == 0x0080C2 && sub == 1 && len >= 6 -> vlan = f.be16(v + 4)
                        oui == 0x0080C2 && sub == 3 && len >= 7 -> {
                            val id = f.be16(v + 4); val nl = f.u8(v + 6)
                            if (v + 7 + nl <= f.size) extra["VLAN $id"] = f.text(v + 7, nl)
                        }
                        oui == 0x00120F && sub == 2 && len >= 7 -> extra["PoE (LLDP)"] = "class ${(f.u8(v + 6))}"
                    }
                }
            }
            o = v + len
        }
        return NeighborInfo("LLDP", sysName, port, portDescr, sysDescr, null, mgmt, vlan, chassis, caps, e.src, extra)
    }

    private fun idValue(f: ByteArray, v: Int, len: Int): String {
        if (len < 2) return ""
        val sub = f.u8(v); val n = len - 1
        return when {
            (sub == 4 || sub == 3) && n == 6 -> f.mac(v + 1)
            sub == 5 && n == 5 && f.u8(v + 1) == 1 -> f.ip4(v + 2)
            else -> f.text(v + 1, n)
        }
    }

    private fun capsText(c: Int, lldp: Boolean): String {
        val names = if (lldp) listOf("Other", "Repeater", "Bridge", "AP", "Router", "Telephone", "DOCSIS", "Station")
        else listOf("Router", "TB Bridge", "SR Bridge", "Switch", "Host", "IGMP", "Repeater", "Phone", "Remote", "CVTA", "MAC Relay")
        return names.indices.filter { c and (1 shl it) != 0 }.joinToString(", ") { names[it] }
    }

    // ------------------------------------------------------------- CDP

    fun cdp(f: ByteArray): NeighborInfo? {
        if (f.size < 22) return null
        if (!(f.u8(0) == 0x01 && f.u8(1) == 0x00 && f.u8(2) == 0x0C && f.u8(3) == 0xCC && f.u8(4) == 0xCC && f.u8(5) == 0xCC)) return null
        var o = 12
        var vlanTag: Int? = null
        if (f.be16(o) == 0x8100) { vlanTag = f.be16(14) and 0xFFF; o += 4 }
        o += 2 // 802.3 length
        // LLC/SNAP: AA AA 03 00 00 0C 20 00
        if (f.size < o + 8 || f.u8(o) != 0xAA || f.be16(o + 6) != 0x2000) return null
        o += 8
        o += 4 // version, ttl, checksum
        var dev: String? = null; var port: String? = null; var ver: String? = null; var plat: String? = null
        var addr: String? = null; var vlan: Int? = null; var caps: String? = null
        val extra = linkedMapOf<String, String>()
        while (o + 4 <= f.size) {
            val type = f.be16(o); val len = f.be16(o + 2)
            if (len < 4 || o + len > f.size) break
            val v = o + 4; val n = len - 4
            when (type) {
                0x0001 -> dev = f.text(v, n)
                0x0002, 0x0016 -> if (addr == null) addr = cdpAddr(f, v, n)
                0x0003 -> port = f.text(v, n)
                0x0004 -> if (n >= 4) caps = capsText(f.be32(v).toInt(), lldp = false)
                0x0005 -> ver = f.text(v, n)
                0x0006 -> plat = f.text(v, n)
                0x000A -> if (n >= 2) vlan = f.be16(v)
                0x000B -> if (n >= 1) extra["Duplex"] = if (f.u8(v) == 1) "full" else "half"
                0x000E -> if (n >= 3) extra["Voice VLAN"] = f.be16(v + 1).toString()
            }
            o += len
        }
        if (vlanTag != null) extra["Tagged VLAN"] = vlanTag.toString()
        return NeighborInfo("CDP", dev, port, null, ver, plat, addr, vlan, null, caps, f.mac(6), extra)
    }

    private fun cdpAddr(f: ByteArray, v: Int, n: Int): String? {
        if (n < 4) return null
        val cnt = f.be32(v).toInt(); var o = v + 4
        repeat(cnt) {
            if (o + 2 > v + n) return null
            val pl = f.u8(o + 1)
            val p0 = o + 2
            val al = f.be16(p0 + pl); val a = p0 + pl + 2
            if (pl == 1 && f.u8(p0) == 0xCC && al == 4) return f.ip4(a)
            o = a + al
        }
        return null
    }
}
