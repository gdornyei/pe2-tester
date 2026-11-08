package io.github.gdornyei.pe2tester

import io.github.gdornyei.pe2tester.proto.*
import org.junit.Assert.*
import org.junit.Test

/** Verifies the protocol layer against a real BLE capture (btsnoop) of the vendor app talking to a PE2. */
class ProtocolTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun res(n: String) = javaClass.classLoader!!.getResource(n).readText().lines().filter { it.isNotBlank() }

    private fun decode(name: String): List<Packet> {
        val out = mutableListOf<Packet>(); var errs = 0
        val d = FrameDecoder({ out += it }, { errs++ })
        res(name).forEach { d.feed(hex(it)) }
        assertEquals("frame errors in $name", 0, errs)
        return out
    }

    private val rx by lazy { decode("rx.hex") }
    private val tx by lazy { decode("tx.hex") }
    private fun ByteArray.h() = joinToString("") { "%02x".format(it) }

    @Test fun commandsAreByteIdenticalToVendorApp() {
        val captured = tx.map { Framing.encode(it).h() }.toSet()
        listOf(Cmd.wiremap(), Cmd.tdr4Pair(), Cmd.poe(), Cmd.linkTest(), Cmd.networkUp(), Cmd.ifUpForTdr(),
            Cmd.ethStop(), Cmd.devInfo(), Cmd.ping("10.100.10.254"))
            .forEach { assertTrue("${PT.name(it.ptype)} differs", Framing.encode(it).h() in captured) }
    }

    @Test fun roundTrip() {
        for (p in rx) {
            val got = mutableListOf<Packet>()
            FrameDecoder({ got += it }).feed(Framing.encode(p))
            assertEquals(1, got.size); assertArrayEquals(p.payload, got[0].payload); assertEquals(p.ptype, got[0].ptype)
        }
    }

    @Test fun parsers() {
        val dev = DevInfoParser.parse(rx.first { it.ptype == PT.DEVINFO })!!
        assertEquals(100001, dev.serial)
        val link = LinkParser.parse(rx.first { it.ptype == PT.PHY_LINKINFO })
        assertTrue(link.up); assertEquals(1000, link.speed); assertTrue(link.fullDuplex)
        assertFalse(PoeParser.parse(rx.first { it.ptype == PT.MEAS_POE })!!.present)
        val w = Wiremap.parse(rx.first { it.ptype == PT.MEAS_REDC })!!
        assertEquals(Status.FAIL, w.status) // plugged into a switch port -> shorts
        val t = Tdr.parse(rx.first { it.ptype == PT.TDR_4PAIR_SAME })
        assertEquals(4, t.pairs.size); assertTrue(t.pairs.all { it.samples.size == 380 })
        val d = NetParsers.dhcp(rx.first { it.ptype == PT.DHCPV4_ACK }.payload)!!
        assertEquals("10.100.10.31", d.yourIp); assertEquals("10.100.10.254", d.router)
        val frames = rx.filter { it.ptype == PT.ETH_FILTER_MATCH }
        val lldp = frames.firstNotNullOf { NetParsers.lldp(it.payload) }
        assertEquals("SW-POE", lldp.systemName); assertEquals("Port 7", lldp.portDescr)
        val cdp = frames.firstNotNullOf { NetParsers.cdp(it.payload) }
        assertEquals("MikroTik", cdp.platform)
        assertEquals("10.100.10.254", PingParser.parse(rx.first { it.ptype == PT.PING }, "gw").resolvedIp)
        assertEquals("203.0.113.10", ExtIpParser.parse(rx.first { it.ptype == PT.HTTP_REQ })!!.ip)
    }
}
