package io.github.gdornyei.pe2tester.proto

/** Packet type codes (little-endian u16 on the wire). */
object PT {
    const val ACK = 0xF0FA
    const val NACK = 0xF0F0
    const val MEAS_POE = 0xF050
    const val MEAS_RESP = 0xF052
    const val MEAS_REDC = 0xF053          // wiremap (DC resistance matrix)
    const val DEVINFO = 0xF104
    const val PHY_CONFIG = 0xF203
    const val PHY_LINKINFO = 0xF206
    const val TDR_AUTO = 0xF207
    const val TDR_ECHO = 0xF209
    const val TDR_4PAIR_SAME = 0xF210
    const val TDR_4PAIR_CROSS = 0xF211
    const val TONER = 0xF220
    const val DHCPV4_ACK = 0xF230
    const val DHCPV6_ACK = 0xF231
    const val SLAAC_ACK = 0xF232
    const val IFCONFIG_ETH = 0xF240
    const val ETH_FILTER_ADD = 0xF241
    const val VLAN_COLLECT = 0xF244
    const val IFCONFIG_WIFI = 0xF245
    const val BER = 0xF246
    const val ETH_FILTER_MATCH = 0xF250
    const val PING = 0xF260
    const val HTTP_REQ = 0xF270
    const val ETH_STOP = 0xF300

    fun name(t: Int): String = when (t) {
        ACK -> "ACK"; NACK -> "NACK"; MEAS_POE -> "POE"; MEAS_REDC -> "WIREMAP"; DEVINFO -> "DEVINFO"
        PHY_LINKINFO -> "LINKINFO"; TDR_4PAIR_SAME -> "TDR"; TDR_4PAIR_CROSS -> "TDR_X"
        DHCPV4_ACK -> "DHCPv4"; DHCPV6_ACK -> "DHCPv6"; SLAAC_ACK -> "SLAAC"; IFCONFIG_ETH -> "IFCONFIG"
        ETH_FILTER_MATCH -> "ETH_FRAME"; PING -> "PING"; HTTP_REQ -> "HTTP"; ETH_STOP -> "ETH_STOP"
        else -> "0x%04X".format(t)
    }
}

object IfFlag {
    const val UP = 1; const val BRIDGE = 2; const val KEEP_LINK_UP = 8; const val BLINKER = 16
    const val FILTER_CDP = 32; const val FILTER_LLDP = 64; const val FILTERS_RESET = 128; const val VLANTAG_COLLECT = 256
}

object IpFlag {
    const val UP = 1; const val DHCPC = 2; const val IPV6_LLADDR = 8; const val IPV6_SLAAC = 16; const val DHCPC_V6 = 32
}

object PhyConf {
    const val UP = 1; const val ISOLATE = 2; const val MDI = 4; const val MDIX = 8; const val DIAG_EN = 16
    const val AN_ADV = 32; const val SPEED_10 = 64; const val SPEED_100 = 128; const val SPEED_1000 = 256
    const val HALF = 512; const val FULL = 1024; const val LOOPBACK_EXT = 16384; const val XNEXTPAGE = 65536
}

private class Le(size: Int) {
    val b = ByteArray(size)
    fun u8(off: Int, v: Int) = apply { b[off] = v.toByte() }
    fun u16(off: Int, v: Int) = apply { b[off] = v.toByte(); b[off + 1] = (v shr 8).toByte() }
    fun u32(off: Int, v: Int) = apply { for (i in 0..3) b[off + i] = (v shr (8 * i)).toByte() }
    fun bytes(off: Int, src: ByteArray) = apply { System.arraycopy(src, 0, b, off, src.size) }
}

/** Command builders, byte-compatible with the vendor app. */
object Cmd {
    fun devInfo() = Packet(PT.DEVINFO)

    /** Wiremap: DC resistance measurement between all 9 conductors (8 wires + shield). */
    fun wiremap() = Packet(PT.MEAS_REDC, Le(8).u32(0, 36363).u32(4, 64).b)

    fun poe() = Packet(PT.MEAS_POE)

    /** TDR, all 4 pairs measured against themselves (cable length / open / short). */
    fun tdr4Pair(a: Int = 3, b: Int = 3, c: Int = 0, d: Int = 0, e: Int = 189, f: Int = 64) =
        Packet(PT.TDR_4PAIR_SAME, Le(16).u16(0, a).u16(2, b).u16(4, c).u16(6, d).u16(8, e).u16(10, f).u16(12, 8).u16(14, 3).b)

    fun ifconfigEth(
        ifFlags: Int, phyConf: Int = 0, vlanId: Int = 0, ipFlags: Int = 0,
        mac: ByteArray? = null, ip: Int = 0, mask: Int = 0, gw: Int = 0, dns: Int = 0,
    ): Packet {
        val le = Le(53).u16(0, ifFlags).u16(2, ipFlags).u32(4, phyConf).u16(8, vlanId).u16(10, 0)
        if (mac != null && mac.size == 6) le.bytes(14, mac)
        le.u32(20, ip).u32(24, mask).u32(28, gw).u32(32, dns)
        return Packet(PT.IFCONFIG_ETH, le.b)
    }

    /** Interface up, PHY powered, no autoneg (used before TDR). */
    fun ifUpForTdr() = ifconfigEth(IfFlag.UP + IfFlag.KEEP_LINK_UP)

    /** Link test: advertise everything incl. 1000BASE-T. */
    fun linkTest() = ifconfigEth(
        IfFlag.UP + IfFlag.FILTERS_RESET,
        PhyConf.UP + PhyConf.SPEED_1000 + PhyConf.SPEED_100 + PhyConf.SPEED_10 + PhyConf.AN_ADV +
            PhyConf.FULL + PhyConf.HALF + PhyConf.MDI + PhyConf.MDIX + PhyConf.XNEXTPAGE,
    )

    /** Network tests (DHCP, CDP/LLDP) at max 100 Mbit, as the vendor app does. */
    /** Dotted IPv4 -> int with the first octet in the low byte (vendor format, ends up in network order on the wire). */
    fun ip4(s: String): Int {
        val p = s.trim().split('.'); if (p.size != 4) return 0
        var v = 0
        for (i in 0..3) { val n = p[i].toIntOrNull() ?: return 0; if (n !in 0..255) return 0; v = v or (n shl (8 * i)) }
        return v
    }

    fun mac(s: String): ByteArray? {
        val p = s.trim().split(':', '-'); if (p.size != 6) return null
        return ByteArray(6) { (p[it].toIntOrNull(16) ?: return null).toByte() }
    }

    fun networkUp(
        dhcp: Boolean = true, cdpLldp: Boolean = true, vlanId: Int = 0, vlanCollect: Boolean = false,
        mac: ByteArray? = null, ip: String = "", mask: String = "", gw: String = "", dns: String = "",
    ) = ifconfigEth(
        ifFlags = IfFlag.UP + IfFlag.KEEP_LINK_UP + (if (cdpLldp) IfFlag.FILTER_CDP + IfFlag.FILTER_LLDP else 0) +
            (if (vlanCollect) IfFlag.VLANTAG_COLLECT else 0),
        mac = mac, ip = if (dhcp) 0 else ip4(ip), mask = if (dhcp) 0 else ip4(mask), gw = if (dhcp) 0 else ip4(gw), dns = if (dhcp) 0 else ip4(dns),
        phyConf = PhyConf.UP + PhyConf.SPEED_10 + PhyConf.SPEED_100 + PhyConf.AN_ADV + PhyConf.FULL +
            PhyConf.HALF + PhyConf.MDI + PhyConf.MDIX,
        vlanId = vlanId,
        ipFlags = IpFlag.UP + (if (dhcp) IpFlag.DHCPC else 0),
    )

    fun ethStop() = Packet(PT.ETH_STOP)

    /**
     * Cable toner. pairMask: 1 = 1-2, 2 = 3-6, 4 = 4-5, 8 = 7-8, 15 = all; tone 1..4; loud = high volume.
     */
    fun toner(pairMask: Int, shield: Boolean, tone: Int, loud: Boolean): Packet {
        val (a, b, c) = when (tone) { 1 -> Triple(600, 1200, 67); 2 -> Triple(400, 700, 150); 3 -> Triple(650, 0, 400); else -> Triple(800, 0, 0) }
        return Packet(PT.TONER, Le(20).u32(0, pairMask + if (shield) 16 else 0).u32(4, if (loud) 2 else 1).u32(8, a).u32(12, b).u32(16, c).b)
    }

    fun tonerOff() = Packet(PT.TONER, Le(4).u32(0, 0).b)

    /** Port blinker / manual link. speed 10/100/1000 or 0 = auto; duplex 1 half, 2 full, 3 auto; mdi 1 mdi, 2 mdix, 3 auto. */
    fun manualLink(speed: Int, duplex: Int, mdi: Int, blink: Boolean): Packet {
        var phy = PhyConf.UP
        phy += when (speed) { 10 -> PhyConf.SPEED_10; 100 -> PhyConf.SPEED_100; 1000 -> PhyConf.SPEED_1000; else -> PhyConf.SPEED_10 + PhyConf.SPEED_100 + PhyConf.SPEED_1000 }
        phy += when (duplex) { 1 -> PhyConf.HALF; 2 -> PhyConf.FULL; else -> PhyConf.HALF + PhyConf.FULL }
        if (speed == 0 || duplex == 3) phy += PhyConf.AN_ADV
        phy += when (mdi) { 1 -> PhyConf.MDI; 2 -> PhyConf.MDIX; else -> PhyConf.MDI + PhyConf.MDIX }
        phy += PhyConf.XNEXTPAGE
        return ifconfigEth(IfFlag.UP + IfFlag.KEEP_LINK_UP + (if (blink) IfFlag.BLINKER else 0), phy)
    }

    /** TDR crosstalk between pairs (graph). */
    fun tdrCross() = Packet(PT.TDR_4PAIR_CROSS, Le(16).u16(0, 3).u16(2, 4).u16(4, 0).u16(6, 0).u16(8, 189).u16(10, 64).u16(12, 8).u16(14, 2).b)

    /** Ethernet side of the WiFi bridge (vendor app: 10 Mbit, no IP stack on the tester). */
    fun bridgeEthUp() = ifconfigEth(
        ifFlags = IfFlag.UP + IfFlag.KEEP_LINK_UP + IfFlag.BRIDGE,
        phyConf = PhyConf.UP + PhyConf.SPEED_10 + PhyConf.AN_ADV + PhyConf.FULL + PhyConf.HALF + PhyConf.MDI + PhyConf.MDIX,
    )

    /** WiFi access point of the bridge. on=false switches the AP off. */
    fun wifiBridge(on: Boolean, ssid: String = "Pockethernet", pass: String = "Pockethernet"): Packet {
        val le = Le(144)
        if (on) {
            val s = ssid.toByteArray(Charsets.US_ASCII).take(32).toByteArray()
            val p = pass.toByteArray(Charsets.US_ASCII).take(63).toByteArray()
            le.u16(0, IfFlag.UP + IfFlag.BRIDGE).u16(2, 0).bytes(4, s).u8(36, s.size).u8(37, p.size).u8(38, 1)
            if (p.isNotEmpty()) le.u8(39, 3)
            le.bytes(40, p).u8(103, 1)
        } else {
            le.u8(38, 1).u8(103, 1)
        }
        return Packet(PT.IFCONFIG_WIFI, le.b)
    }

    fun ping(host: String, count: Int = 3, ipv6: Boolean = false): Packet {
        val h = host.trim().toByteArray(Charsets.US_ASCII)
        val le = Le(h.size + 13).u16(0, 100).u16(2, 500).u16(4, 64).u32(6, 2000)
        le.u8(8, count).u8(9, 0).u8(10, 0xFF).u8(11, if (ipv6) 1 else 0).bytes(12, h)
        return Packet(PT.PING, le.b)
    }

    fun httpGet(url: String, userAgent: String = "PE", timeoutMs: Int = 10000): Packet {
        val u = url.toByteArray(Charsets.US_ASCII); val ua = userAgent.toByteArray(Charsets.US_ASCII)
        require(u.size in 1..127 && ua.size in 1..32)
        return Packet(PT.HTTP_REQ, Le(u.size + 37).u16(0, timeoutMs).bytes(3, ua).bytes(36, u).b)
    }
}
