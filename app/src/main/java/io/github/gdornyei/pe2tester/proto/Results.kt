package io.github.gdornyei.pe2tester.proto

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

@Serializable
enum class Status { OK, FAIL, INFO, NA }

data class DevInfo(
    val serial: Int, val hwVer: Int, val blVer: Int, val stmVer: Int, val espVer: Int, val mac: String,
)

object DevInfoParser {
    fun parse(p: Packet): DevInfo? {
        if (p.payload.size < 35) return null
        var m = 0L
        for (i in 0 until 6) m = (m shl 8) or p.u8(29 + i).toLong()
        m += 3
        val mac = (5 downTo 0).joinToString(":") { "%02X".format((m shr (8 * it)) and 0xFF) }
        return DevInfo(p.s32(0), p.s32(4), p.s32(8), p.s32(12), p.s32(24), mac)
    }
}

// ---------------------------------------------------------------- Link

@Serializable
data class LinkResult(
    val up: Boolean, val speed: Int, val fullDuplex: Boolean,
    val partner10: String, val partner100: String, val partner1000: String, val partnerMultiGig: String,
    val polarity: List<String>, val skewNs: List<Int>?, val lengthEstimate: Int?,
) {
    val status get() = if (up) Status.OK else Status.FAIL
    val summary get() = if (!up) "No link" else
        "${if (speed >= 1000) "${speed / 1000} Gbit" else "$speed Mbit"}, ${if (fullDuplex) "full" else "half"} duplex"
}

object LinkParser {
    private fun adv(v: Int) = when (v) { 1 -> "Half"; 2 -> "Full"; 3 -> "Half + Full"; else -> "none" }

    fun speedOf(p: Packet): Int {
        val s = p.u16(10)
        if (s and 64 == 0) return 0
        return if (s and 512 != 0) 1000 else if (s and 256 != 0) 100 else 10
    }

    fun parse(p: Packet): LinkResult {
        val lpa = p.u16(4); val gstat = p.u16(8); val st = p.u16(10); val pol = p.u16(12)
        val up = st and 64 != 0
        var mg = ""
        if (p.payload.size >= 26) {
            val x = p.u16(24)
            val l = mutableListOf<String>()
            if (x and 4096 != 0) l += "2.5G" else if (x and 16384 != 0) l += "NBase-T 2.5G"
            if (x and 2048 != 0) l += "5G" else if (x and 32768 != 0) l += "NBase-T 5G"
            if (x and 1 != 0) l += "10G"
            mg = l.joinToString(", ")
        }
        var skew: List<Int>? = null; var len: Int? = null
        if (p.payload.size > 29) {
            val s = p.u16(26)
            skew = (0..3).map { ((s shr (3 * it)) and 7) * 8 }
            len = p.s16(28)
        }
        return LinkResult(
            up = up, speed = speedOf(p), fullDuplex = st and 128 != 0,
            partner10 = adv((lpa shr 5) and 3), partner100 = adv((lpa shr 7) and 3),
            partner1000 = adv((gstat shr 10) and 3), partnerMultiGig = mg.ifEmpty { "none" },
            polarity = (0..3).map { if (pol and (1024 shl it) != 0) "inverted" else "normal" },
            skewNs = skew, lengthEstimate = len,
        )
    }
}

// ---------------------------------------------------------------- PoE

@Serializable
data class PoeResult(
    val present: Boolean, val openV: Int, val loadV: Int, val pseType: String, val pdClass: String,
    val polarity: String, val unbalanced: Boolean, val status: Status, val intraPair: List<Int>,
) {
    val summary get() = when {
        !present -> "No PoE"
        pseType.startsWith("Passive") -> "Passive PoE, $openV V / $loadV V loaded"
        else -> "${if (unbalanced) "Unbalanced! " else ""}PoE $pseType, class $pdClass, $loadV V"
    }
}

object PoeParser {
    private const val MIN_V = 10

    fun parse(p: Packet): PoeResult? {
        if (p.payload.size < 32) return null
        val events = p.payload[0].toInt(); val cls = p.payload[1].toInt(); val sig = p.payload[2].toInt()
        val openRaw = p.s16(4); val loadRaw = p.s16(6)
        val intra = (0..3).map { (p.s16(24 + 2 * it) / 10.0).roundToInt() }
        if (openRaw == 0) return PoeResult(false, 0, 0, "", "", "", false, Status.FAIL, intra)
        val openV = (openRaw / 10.0).roundToInt(); val loadV = (loadRaw / 10.0).roundToInt()
        val adc = IntArray(4) { p.u16(16 + 2 * it) }
        val modeA = abs(abs(adc[0]) - abs(adc[1])) * 0.002393809f
        val modeB = abs(abs(adc[2]) - abs(adc[3])) * 0.002393809f
        val pol = buildList {
            if (modeA > MIN_V) add(if (adc[1] > adc[0]) "1-2: −  3-6: +" else "1-2: +  3-6: −")
            if (modeB > MIN_V) add(if (adc[3] > adc[2]) "4-5: −  7-8: +" else "4-5: +  7-8: −")
        }.joinToString("  |  ")
        if (sig == 0) return PoeResult(true, openV, loadV, "Passive / non-standard", "-", pol, false, Status.OK, intra)
        val type = if (sig < 80) { if (cls != 4 && events <= 1) "AF/AT" else "AT" } else { if (cls > 7 || events > 4) "BT" else "AT/BT" }
        val clsTxt = when (cls) {
            0 -> "0 (15 W)"; 1 -> "1 (4 W)"; 2 -> "2 (7 W)"; 3 -> "3 (15 W)"; 4 -> "4 (30 W)"
            5 -> "5 (45 W)"; 6 -> "6 (60 W)"; 7 -> "7 (75 W)"; 8 -> "8 (100 W)"; else -> cls.toString()
        }
        val unb = modeA > MIN_V && modeB > MIN_V && abs(modeA - modeB) * 100.0 / modeA > 10.0
        val ok = if (type == "AF/AT") openV >= 37 else openV >= 42
        return PoeResult(true, openV, loadV, type, clsTxt, pol, unb, if (ok) Status.OK else Status.FAIL, intra)
    }
}

// ---------------------------------------------------------------- Ping

@Serializable
data class PingResult(
    val target: String, val resolvedIp: String, val avgMs: Int?,
    val sent: Int = 0, val received: Int = 0, val rttMs: Long? = null,
) {
    val ok get() = avgMs != null && (sent == 0 || received > 0)
}

object PingParser {
    fun parse(p: Packet, target: String): PingResult {
        val ip = when (p.u8(11)) {
            0 -> (0..3).joinToString(".") { p.u8(12 + it).toString() }
            6 -> (0..7).joinToString(":") { "%x".format((p.u8(12 + 2 * it) shl 8) or p.u8(13 + 2 * it)) }
            else -> "resolve error"
        }
        val t = p.u16(6)
        val sent = p.u8(8); val recv = p.u8(9)
        // only the u16 @6 (average, as used by the vendor app) is reliable; @0..5 meaning unknown
        return PingResult(target, ip, if (t >= 65535) null else t, sent, recv, null)
    }
}

// ---------------------------------------------------------------- External IP (ip-api.com JSON body)

@Serializable
data class ExtIpResult(val ip: String, val isp: String, val asn: String, val location: String)

object ExtIpParser {
    private fun field(json: String, key: String): String =
        Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1) ?: ""

    fun parse(p: Packet): ExtIpResult? {
        val s = String(p.payload, Charsets.UTF_8)
        val ip = field(s, "query")
        if (ip.isEmpty()) return null
        val loc = listOf(field(s, "city"), field(s, "regionName").ifEmpty { field(s, "region") }, field(s, "country"))
            .filter { it.isNotEmpty() }.joinToString(", ")
        return ExtIpResult(ip, field(s, "isp"), field(s, "as"), loc)
    }
}
