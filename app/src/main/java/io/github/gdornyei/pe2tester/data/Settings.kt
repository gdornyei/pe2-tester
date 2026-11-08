package io.github.gdornyei.pe2tester.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import io.github.gdornyei.pe2tester.Item
import io.github.gdornyei.pe2tester.ItemState
import io.github.gdornyei.pe2tester.Report
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Who did the measurement – printed on every report (like the "Owner" block of a NaviTEK report). */
@Serializable
data class OwnerInfo(
    val engineer: String = "", val company: String = "", val phone: String = "", val email: String = "",
    val address: String = "",
)

/** Pass/fail profile applied to every test. */
@Serializable
data class Limits(
    val shield: Int = SHIELD_ANY,          // 0 any, 1 UTP (shield must be open), 2 STP (shield required)
    val allowCrossover: Boolean = true,
    val allowTwoPair: Boolean = true,
    val minSpeed: Int = 0,                 // 0 / 100 / 1000 Mbit
    val maxLengthM: Int = 100,             // 0 = no limit
    val poeRequired: Boolean = false,
    val dhcpRequired: Boolean = true,
    val pingRequired: Boolean = true,
    val internetRequired: Boolean = false,
    /** Minimum PSE class required: -1 = not required, 0 = any PoE, 3 = af (15 W), 4 = at (30 W), 6 = bt (60 W), 8 = bt (90 W). */
    val poeMinClass: Int = -1,
) {
    val poeNeed: Int get() = if (poeMinClass >= 0) poeMinClass else if (poeRequired) 0 else -1
    companion object {
        const val SHIELD_ANY = 0; const val SHIELD_UTP = 1; const val SHIELD_STP = 2

    fun isTransformerPattern(sh: IntArray): Boolean {
            val pairs = setOf(1 to 2, 3 to 6, 4 to 5, 7 to 8)
            var n = 0
            for (i in 1..8) {
                val j = sh.getOrElse(i) { 0 }
                if (j == 0) continue
                if ((minOf(i, j) to maxOf(i, j)) !in pairs) return false
                n++
            }
            return n >= 2
        }
    }

    val describe: String get() = buildList {
        add(when (shield) { SHIELD_UTP -> "UTP"; SHIELD_STP -> "STP (shield required)"; else -> "UTP/STP" })
        if (!allowCrossover) add("crossover not allowed")
        if (!allowTwoPair) add("4 pairs required")
        if (minSpeed > 0) add("min. ${if (minSpeed >= 1000) "1 Gbit" else "$minSpeed Mbit"}")
        if (maxLengthM > 0) add("max. $maxLengthM m")
        when (poeNeed) { -1 -> {}; 0 -> add("PoE required"); 3 -> add("PoE min. af (15 W)"); 4 -> add("PoE min. at (30 W)"); 6 -> add("PoE min. bt (60 W)"); else -> add("PoE min. bt (90 W)") }
        if (dhcpRequired) add("DHCP required")
        if (internetRequired) add("internet required")
    }.joinToString(", ")

    fun poeClassName(c: Int) = when (c) { 3 -> "af (15 W)"; 4 -> "at (30 W)"; 6 -> "bt (60 W)"; 8 -> "bt (90 W)"; else -> "PoE" }

    /** Re-grades the items of a finished report according to this profile. */
    fun apply(r: Report): Report {
        fun Item.fail(why: String) = copy(state = ItemState.FAIL, summary = if (summary.isEmpty()) why else "$summary – $why")
        var wm = r.wiremap
        val w = r.wiremapRaw
        // An active Ethernet port (switch, PC) shorts each pair through its isolation transformer and keeps
        // transmitting, so wiremap/TDR are meaningless there – report it as information, not as a failure.
        val transformer = w != null && isTransformerPattern(w.shorts)
        val linkPartner = r.linkRaw?.up == true
        var tdr0 = r.tdr
        if (transformer || (linkPartner && w != null && w.shorts.any { it != 0 })) {
            wm = wm.copy(state = ItemState.INFO, summary = "Active Ethernet port – wiremap needs the remote adapter")
        }
        if ((transformer || linkPartner) && r.tdr.state != ItemState.SKIPPED && r.tdr.state != ItemState.WAITING) {
            tdr0 = r.tdr.copy(state = ItemState.INFO, summary = "TDR is not reliable on an active port",
                details = r.tdr.details)
        }
        if (w != null && wm.state == ItemState.OK) {
            val s = w.summary
            if (!allowCrossover && s.contains("crossover")) wm = wm.fail("crossover not allowed")
            else if (!allowTwoPair && s.startsWith("2-pair")) wm = wm.fail("4 pairs required")
            else if (shield == SHIELD_STP && w.connections[9] != 9) wm = wm.fail("shield not connected")
            else if (shield == SHIELD_UTP && w.connections[9] == 9) wm = wm.fail("shield connected")
        }
        var tdr = tdr0
        val d = r.tdrRaw?.avgDistanceM
        if (maxLengthM > 0 && d != null && tdr.state == ItemState.OK && d > maxLengthM) tdr = tdr.fail("longer than $maxLengthM m")
        var link = r.link
        val l = r.linkRaw
        if (minSpeed > 0 && l != null && l.up && l.speed < minSpeed) link = link.fail("min. ${if (minSpeed >= 1000) "1 Gbit" else "$minSpeed Mbit"} required")
        var poe = r.poe
        val pr = r.poeRaw
        if (poeNeed >= 0 && poe.state != ItemState.SKIPPED) {
            if (pr?.present != true) poe = poe.copy(state = ItemState.FAIL, summary = "No PoE – PoE required")
            else if (poeNeed > 0) {
                val cls = pr.pdClass.substringBefore(' ').toIntOrNull()
                val passive = pr.pseType.startsWith("Passive")
                if (passive || cls == null || cls < poeNeed) poe = poe.fail("min. ${poeClassName(poeNeed)} required")
            }
        }
        fun soften(i: Item, required: Boolean) = if (!required && i.state == ItemState.FAIL) i.copy(state = ItemState.INFO) else i
        return r.copy(wiremap = wm, tdr = tdr, link = link, poe = poe,
            dhcp = soften(r.dhcp, dhcpRequired), ping = soften(r.ping, pingRequired), extIp = soften(r.extIp, internetRequired))
    }
}

/** Report layout, as on the NaviTEK: summary only, or summary + one page per test; all / passed / failed. */
@Serializable
data class ReportOptions(
    val detailed: Boolean = true, val filter: Int = ALL, val pdf: Boolean = true, val csv: Boolean = false,
    val portMap: Boolean = true, val portMapOnly: Boolean = false,
) {
    companion object { const val ALL = 0; const val PASS = 1; const val FAIL = 2 }
}

@Serializable
private data class SettingsFile(val owner: OwnerInfo = OwnerInfo(), val limits: Limits = Limits(), val report: ReportOptions = ReportOptions())

class SettingsStore(private val ctx: Context) {
    private val file = File(ctx.filesDir, "settings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var data: SettingsFile = runCatching { json.decodeFromString<SettingsFile>(file.readText()) }.getOrElse { SettingsFile() }

    val owner get() = data.owner
    val limits get() = data.limits
    val report get() = data.report

    private fun save() { runCatching { file.writeText(json.encodeToString(data)) } }
    fun setOwner(o: OwnerInfo) { data = data.copy(owner = o); save() }
    fun setLimits(l: Limits) { data = data.copy(limits = l); save() }
    fun setReport(r: ReportOptions) { data = data.copy(report = r); save() }

    val logoFile get() = File(ctx.filesDir, "logo.png")
    fun logo(): Bitmap? = if (logoFile.exists()) BitmapFactory.decodeFile(logoFile.path) else null

    /** Copies and downsizes a picked image to the app's logo file. */
    fun setLogo(uri: Uri?) {
        if (uri == null) { logoFile.delete(); return }
        val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return
        val scale = minOf(1f, 600f / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        logoFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
