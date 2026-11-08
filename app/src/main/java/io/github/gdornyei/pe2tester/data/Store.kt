package io.github.gdornyei.pe2tester.data

import android.content.Context
import android.util.Log
import io.github.gdornyei.pe2tester.Item
import io.github.gdornyei.pe2tester.ItemState
import io.github.gdornyei.pe2tester.Report
import io.github.gdornyei.pe2tester.proto.TdrResult
import io.github.gdornyei.pe2tester.proto.DhcpResult
import io.github.gdornyei.pe2tester.proto.ExtIpResult
import io.github.gdornyei.pe2tester.proto.LinkResult
import io.github.gdornyei.pe2tester.proto.NeighborInfo
import io.github.gdornyei.pe2tester.proto.PingResult
import io.github.gdornyei.pe2tester.proto.PoeResult
import io.github.gdornyei.pe2tester.scan.NetScan
import io.github.gdornyei.pe2tester.proto.WiremapResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class NamedItem(val name: String, val item: Item)

@Serializable
data class Measurement(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val ts: Long,
    val device: String? = null,
    val items: List<NamedItem>,
    val wiremap: WiremapResult? = null,
    val tdr: TdrResult? = null,
    val note: String = "",
    val aborted: Boolean = false,
    val seq: Int = 0,
    /** Switch / port as entered by the user; falls back to what LLDP/CDP reported. */
    val switchName: String = "",
    val switchPort: String = "",
    val link: LinkResult? = null,
    val poe: PoeResult? = null,
    val dhcp: DhcpResult? = null,
    val neighbors: List<NeighborInfo> = emptyList(),
    val pings: List<PingResult> = emptyList(),
    val extIp: ExtIpResult? = null,
    val limits: String = "",
    val vlans: List<Int> = emptyList(),
    /** Photo file names (in filesDir/photos). */
    val photos: List<String> = emptyList(),
    /** Earlier results of this port (kept when re-measured). */
    val history: List<Snapshot> = emptyList(),
    val profile: String = "",
) {
    val speedText get() = link?.let { if (!it.up) "none" else if (it.speed >= 1000) "1G" else "${it.speed}M" } ?: ""
    val lengthText get() = tdr?.avgDistanceM?.let { "%.1f m".format(it) }
        ?: tdr?.pairs?.filter { it.status.isNotEmpty() }?.maxOfOrNull { it.distanceM }?.let { "≤%.1f m".format(it) } ?: ""
    val poeText get() = poe?.let {
        if (!it.present) "none" else if (it.pseType.startsWith("Passive")) "passive ${it.openV}V" else "${it.pseType} c${it.pdClass.substringBefore(' ')}"
    } ?: ""
    val vlanText get() = (neighbors.mapNotNull { it.vlan } + vlans).distinct().joinToString(", ")

    /** Key figures used for re-measure comparison. */
    fun metrics(): Map<String, String> = linkedMapOf(
        "Result" to (if (aborted) "aborted" else if (pass) "pass" else "fail"),
        "Wiremap" to (item(N_WIREMAP)?.summary ?: ""),
        "Link" to speedText, "Length" to lengthText, "PoE" to poeText,
        "Switch" to switchShown, "Switch port" to portShown, "VLAN" to vlanText, "IP address" to (dhcp?.yourIp ?: ""),
    )

    fun snapshot() = Snapshot(ts, metrics())

    /** Changed key figures compared to an earlier snapshot: name -> (old, new). Length changes under 1 m are ignored. */
    fun diff(prev: Snapshot): List<Triple<String, String, String>> {
        val now = metrics()
        return now.mapNotNull { (k, v) ->
            val o = prev.metrics[k] ?: return@mapNotNull null
            if (o == v || (o.isEmpty() && v.isEmpty())) return@mapNotNull null
            if (k == "IP address") return@mapNotNull null
            if (k == "Length") {
                val a = o.filter { it.isDigit() || it == '.' }.toFloatOrNull(); val b = v.filter { it.isDigit() || it == '.' }.toFloatOrNull()
                if (a != null && b != null && kotlin.math.abs(a - b) < 1f) return@mapNotNull null
            }
            Triple(k, o.ifEmpty { "–" }, v.ifEmpty { "–" })
        }
    }
    val testId get() = "T%04d".format(seq)
    val pass get() = failCount == 0 && !aborted
    val switchShown get() = switchName.ifEmpty { neighbors.firstNotNullOfOrNull { it.systemName } ?: "" }
    val portShown get() = switchPort.ifEmpty { neighbors.firstNotNullOfOrNull { it.portDescr ?: it.portId } ?: "" }
    val failCount get() = items.count { it.item.state == ItemState.FAIL }
    fun item(name: String) = items.firstOrNull { it.name == name }?.item

    /** One-line digest for lists: link · length · switch port. */
    val digest: String get() = listOfNotNull(
        item(N_LINK)?.takeIf { it.state == ItemState.OK }?.summary,
        item(N_TDR)?.takeIf { it.state == ItemState.OK }?.summary,
        item(N_NEIGHBOR)?.takeIf { it.state == ItemState.OK }?.summary,
        item(N_DHCP)?.takeIf { it.state == ItemState.OK }?.summary?.substringBefore("  "),
    ).joinToString(" · ")

    companion object {
        const val N_WIREMAP = "Wiremap"; const val N_TDR = "Cable (TDR)"; const val N_POE = "PoE"
        const val N_LINK = "Link"; const val N_DHCP = "IP address (DHCP)"; const val N_NEIGHBOR = "Switch port (LLDP/CDP)"
        const val N_PING = "Ping"; const val N_EXTIP = "Internet"; const val N_VLAN = "VLAN"

        fun from(r: Report, label: String, device: String?, seq: Int, switchName: String, switchPort: String, limits: String): Measurement = Measurement(
            label = label, ts = r.startedAt, device = device,
            items = r.items.map { NamedItem(it.first, it.second) },
            wiremap = r.wiremapRaw, tdr = r.tdrRaw,
            aborted = r.phase == "Stopped" || r.phase.startsWith("Failure"),
            seq = seq, switchName = switchName.trim(), switchPort = switchPort.trim(),
            link = r.linkRaw, poe = r.poeRaw, dhcp = r.dhcpRaw, neighbors = r.neighborsRaw, pings = r.pingsRaw, extIp = r.extIpRaw,
            limits = limits, vlans = r.vlansRaw, profile = r.profileLabel,
        )
    }
}

@Serializable
data class Snapshot(val ts: Long, val metrics: Map<String, String>)

@Serializable
data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val client: String = "",
    /** Building / site. */
    val site: String = "",
    val address: String = "",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val quick: Boolean = false,
    val measurements: List<Measurement> = emptyList(),
    val scans: List<NetScan> = emptyList(),
    val updatedAt: Long = 0,
    /** Tombstones so a sync does not bring deleted items back. */
    val deletedIds: List<String> = emptyList(),
) {
    val failCount get() = measurements.count { it.failCount > 0 }
    val lastTs get() = measurements.maxOfOrNull { it.ts } ?: createdAt
    val nextSeq get() = (measurements.maxOfOrNull { it.seq } ?: 0) + 1

    /** The previous result for the same port: its own history, or an earlier measurement with the same label. */
    fun previousOf(m: Measurement): Snapshot? = m.history.lastOrNull()
        ?: measurements.filter { it.id != m.id && it.label.equals(m.label, true) && it.ts < m.ts }.maxByOrNull { it.ts }?.snapshot()
}

@Serializable
internal data class StoreFile(val version: Int = 1, val projects: List<Project> = emptyList(), val deletedProjects: List<String> = emptyList())

/** Simple JSON-file store; small data (hundreds of measurements), written atomically. */
class Store(ctx: Context, private val scope: CoroutineScope) {
    private val file = File(ctx.filesDir, "projects.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val ioLock = Mutex()

    private val initial: StoreFile = load()
    private val _projects = MutableStateFlow(initial.projects)
    val projects: StateFlow<List<Project>> = _projects
    private val _deleted = MutableStateFlow(initial.deletedProjects)
    val deletedProjects: List<String> get() = _deleted.value

    private fun load(): StoreFile = try {
        if (file.exists()) json.decodeFromString<StoreFile>(file.readText()) else StoreFile()
    } catch (e: Exception) {
        Log.e("Store", "load failed", e)
        runCatching { file.copyTo(File(file.parentFile, "projects.broken.${System.currentTimeMillis()}.json")) }
        StoreFile()
    }

    private fun persist() {
        val snapshot = _projects.value
        scope.launch(Dispatchers.IO) {
            ioLock.withLock {
                val tmp = File(file.parentFile, "projects.json.tmp")
                tmp.writeText(json.encodeToString(StoreFile(projects = snapshot, deletedProjects = _deleted.value)))
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
    }

    private fun edit(f: (List<Project>) -> List<Project>) {
        _projects.update { old ->
            val om = old.associateBy { it.id }
            val now = System.currentTimeMillis()
            f(old).map { p -> if (om[p.id] != p) p.copy(updatedAt = now) else p }
        }
        persist()
    }

    /** Replaces the whole state after a sync merge (no updatedAt bump). */
    fun replaceAll(projects: List<Project>, deleted: List<String>) {
        _deleted.value = deleted.distinct()
        _projects.value = projects
        persist()
    }

    fun project(id: String?) = _projects.value.firstOrNull { it.id == id }

    fun createProject(p: Project): Project { edit { it + p }; return p }

    fun updateProject(p: Project) = edit { l -> l.map { if (it.id == p.id) p.copy(measurements = it.measurements, scans = it.scans) else it } }

    fun deleteProject(id: String) { _deleted.value = _deleted.value + id; edit { l -> l.filterNot { it.id == id } } }

    /** The implicit project collecting quick tests made outside any project. */
    fun quickProject(): Project = _projects.value.firstOrNull { it.quick } ?: Project(name = "Quick tests", quick = true).also { p -> edit { it + p } }

    fun addMeasurement(projectId: String, m: Measurement) =
        edit { l -> l.map { if (it.id == projectId) it.copy(measurements = it.measurements + m) else it } }

    fun updateMeasurement(projectId: String, m: Measurement) =
        edit { l -> l.map { p -> if (p.id == projectId) p.copy(measurements = p.measurements.map { if (it.id == m.id) m else it }) else p } }

    fun addScan(projectId: String, s: NetScan) =
        edit { l -> l.map { if (it.id == projectId) it.copy(scans = it.scans + s) else it } }

    fun deleteScan(projectId: String, sid: String) =
        edit { l -> l.map { p -> if (p.id == projectId) p.copy(scans = p.scans.filterNot { it.id == sid }, deletedIds = p.deletedIds + sid) else p } }

    fun addPhoto(projectId: String, mid: String, name: String) =
        edit { l -> l.map { p -> if (p.id == projectId) p.copy(measurements = p.measurements.map { if (it.id == mid) it.copy(photos = it.photos + name) else it }) else p } }

    fun removePhoto(projectId: String, mid: String, name: String) =
        edit { l -> l.map { p -> if (p.id == projectId) p.copy(measurements = p.measurements.map { if (it.id == mid) it.copy(photos = it.photos - name) else it }) else p } }

    fun deleteMeasurement(projectId: String, mid: String) =
        edit { l -> l.map { p -> if (p.id == projectId) p.copy(measurements = p.measurements.filterNot { it.id == mid }, deletedIds = p.deletedIds + mid) else p } }

    companion object {
        /** Merges two versions of the same project: union of measurements/scans, newer wins per item, tombstones respected. */
        fun merge(a: Project, b: Project): Project {
            val newer = if (a.updatedAt >= b.updatedAt) a else b
            val del = (a.deletedIds + b.deletedIds).distinct()
            val ms = (a.measurements + b.measurements).filter { it.id !in del }.groupBy { it.id }
                .map { (_, v) -> v.maxWith(compareBy<Measurement>({ it.ts }, { it.history.size }, { it.note.length + it.photos.size })) }
            val sc = (a.scans + b.scans).filter { it.id !in del }.distinctBy { it.id }
            return newer.copy(measurements = ms, scans = sc, deletedIds = del, updatedAt = maxOf(a.updatedAt, b.updatedAt))
        }

        /** Natural ordering: "Port 2" < "Port 10", "Gi1/0/2" < "Gi1/0/10". */
        val natural: Comparator<String> = Comparator { a, b ->
            val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
            val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(ra.size, rb.size)) {
                val x = ra[i]; val y = rb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) (x.toBigInteger().compareTo(y.toBigInteger())) else x.compareTo(y)
                if (c != 0) return@Comparator c
            }
            ra.size - rb.size
        }

        /** Port map rows: latest measurement per outlet, sorted by switch then port. */
        fun portMap(p: Project): List<Measurement> = p.measurements.groupBy { it.label.lowercase() }.values.map { g -> g.maxBy { it.ts } }
            .sortedWith { a, b ->
                val sa = a.switchShown.ifEmpty { "\uFFFF" }; val sb = b.switchShown.ifEmpty { "\uFFFF" }
                natural.compare(sa, sb).takeIf { it != 0 } ?: natural.compare(a.portShown.ifEmpty { "\uFFFF" }, b.portShown.ifEmpty { "\uFFFF" })
                    .takeIf { it != 0 } ?: natural.compare(a.label, b.label)
            }

        /** Increments the last number in a label; empty stays empty. */
        fun increment(s: String): String {
            val m = Regex("^(.*?)(\\d+)(\\D*)$").find(s) ?: return s
            val (pre, num, post) = m.destructured
            return pre + (num.toLong() + 1).toString().padStart(num.length, '0') + post
        }

        /** "AP-12" -> "AP-13", "Floor3-07" -> "Floor3-08", otherwise "Port N". */
        fun nextLabel(last: String?, count: Int): String {
            if (last != null) {
                val m = Regex("^(.*?)(\\d+)(\\D*)$").find(last)
                if (m != null) {
                    val (pre, num, post) = m.destructured
                    val next = (num.toLong() + 1).toString().padStart(num.length, '0')
                    return pre + next + post
                }
            }
            return "Port ${count + 1}"
        }
    }
}
