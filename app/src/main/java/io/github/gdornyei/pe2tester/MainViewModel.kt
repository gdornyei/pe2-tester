package io.github.gdornyei.pe2tester

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.gdornyei.pe2tester.ble.ConnState
import io.github.gdornyei.pe2tester.ble.Pe2Ble
import io.github.gdornyei.pe2tester.data.Measurement
import io.github.gdornyei.pe2tester.data.Project
import io.github.gdornyei.pe2tester.data.Store
import io.github.gdornyei.pe2tester.data.SettingsStore
import io.github.gdornyei.pe2tester.data.OwnerInfo
import io.github.gdornyei.pe2tester.data.Limits
import io.github.gdornyei.pe2tester.data.ReportOptions
import io.github.gdornyei.pe2tester.proto.Cmd
import io.github.gdornyei.pe2tester.proto.DevInfo
import io.github.gdornyei.pe2tester.proto.DevInfoParser
import io.github.gdornyei.pe2tester.proto.PT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface Screen {
    data object Projects : Screen
    data class ProjectView(val projectId: String) : Screen
    data class MeasurementView(val projectId: String, val measurementId: String) : Screen
    data object Test : Screen
    data object Settings : Screen
    data class Scan(val projectId: String) : Screen
    data class ScanView(val projectId: String, val scanId: String) : Screen
    data object Tools : Screen
    data class PortMap(val projectId: String) : Screen
}

/** Where the current / last test run gets saved. */
data class TestTarget(
    val projectId: String, val label: String, val switchName: String = "", val switchPort: String = "",
    val replaceId: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val ble = Pe2Ble(app)
    val test = QuickTest(ble, viewModelScope)
    val store = Store(app, viewModelScope)
    val settings = SettingsStore(app)
    val scan = io.github.gdornyei.pe2tester.scan.ScanController(app, ble, viewModelScope)
    private var scanProjectId: String? = null

    fun openScan(projectId: String?) {
        val pid = projectId ?: store.quickProject().id
        scanProjectId = pid
        scan.onSaved = { r -> scanProjectId?.let { store.addScan(it, r) } }
        push(Screen.Scan(pid))
    }
    fun deleteScan(pid: String, sid: String) { store.deleteScan(pid, sid); back() }

    // ---------------------------------------------------------------- Google Drive
    private val _driveBusy = MutableStateFlow(false); val driveBusy: StateFlow<Boolean> = _driveBusy
    private val _driveInfo = MutableStateFlow(app.getSharedPreferences("pe2", 0).getString("drive.last", "") ?: ""); val driveInfo: StateFlow<String> = _driveInfo
    private val _driveReports = MutableStateFlow(app.getSharedPreferences("pe2", 0).getBoolean("drive.reports", false)); val driveReports: StateFlow<Boolean> = _driveReports
    fun setDriveReports(v: Boolean) { _driveReports.value = v; prefs.edit().putBoolean("drive.reports", v).apply() }

    fun driveSync(token: String) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        if (_driveBusy.value) return@launch
        _driveBusy.value = true
        try {
            val r = io.github.gdornyei.pe2tester.sync.DriveSync(token).sync(store.projects.value, store.deletedProjects, photoDir) { }
            store.replaceAll(r.projects, store.deletedProjects)
            val t = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.ROOT).format(Date())
            val info = "Last sync: $t (up: ${r.uploaded}, down: ${r.downloaded}, photos up/down: ${r.photosUp}/${r.photosDown})"
            _driveInfo.value = info; prefs.edit().putString("drive.last", info).apply()
            _message.value = "Drive sync done"
        } catch (e: io.github.gdornyei.pe2tester.sync.DriveSync.DriveException) {
            _message.value = if (e.code == 403 && e.message?.contains("accessNotConfigured") == true || e.message?.contains("has not been used") == true)
                "The Google Drive API is not enabled in the Google Cloud project" else "Drive error: ${e.message}"
        } catch (e: Exception) {
            _message.value = "Drive error: ${e.message}"
        } finally { _driveBusy.value = false }
    }

    fun driveUpload(token: String, files: List<java.io.File>) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        try { val d = io.github.gdornyei.pe2tester.sync.DriveSync(token); files.forEach { d.uploadReport(it) }; _message.value = "Report uploaded to Drive" }
        catch (e: Exception) { _message.value = "Drive upload error: ${e.message}" }
    }
    val tools = Tools(ble, viewModelScope).also { t ->
        t.onAdapterFound = {
            val ctx = getApplication<Application>()
            runCatching {
                val v = if (android.os.Build.VERSION.SDK_INT >= 31)
                    (ctx.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
                else @Suppress("DEPRECATION") (ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator)
                v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180, 120, 400), -1))
            }
            runCatching {
                val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
                tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 600)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tg.release() }, 1000)
            }
        }
    }

    val photoDir get() = java.io.File(getApplication<Application>().filesDir, "photos").apply { mkdirs() }
    fun newPhotoFile() = java.io.File(photoDir, "p_${System.currentTimeMillis()}.jpg")
    /** Downscales and stores a photo for a measurement. */
    fun addPhoto(pid: String, mid: String, src: android.net.Uri?, file: java.io.File?) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val app = getApplication<Application>()
            val bmp = when {
                file != null && file.exists() -> android.graphics.BitmapFactory.decodeFile(file.path)
                src != null -> app.contentResolver.openInputStream(src)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                else -> null
            } ?: return@launch
            val sc = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
            val out = if (sc < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true) else bmp
            val dst = newPhotoFile()
            dst.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
            file?.takeIf { it != dst }?.delete()
            store.addPhoto(pid, mid, dst.name)
        } catch (e: Exception) { _message.value = "Photo error: ${e.message}" }
    }
    fun removePhoto(pid: String, mid: String, name: String) { store.removePhoto(pid, mid, name); java.io.File(photoDir, name).delete() }
    private val _owner = MutableStateFlow(settings.owner); val owner: StateFlow<OwnerInfo> = _owner
    private val _limits = MutableStateFlow(settings.limits); val limits: StateFlow<Limits> = _limits
    private val _reportOpt = MutableStateFlow(settings.report); val reportOptions: StateFlow<ReportOptions> = _reportOpt
    private val _logoVer = MutableStateFlow(0); val logoVersion: StateFlow<Int> = _logoVer
    fun setOwner(o: OwnerInfo) { settings.setOwner(o); _owner.value = o }
    fun setLimits(l: Limits) { settings.setLimits(l); _limits.value = l }
    fun setReportOptions(r: ReportOptions) { settings.setReport(r); _reportOpt.value = r }
    fun setLogo(uri: android.net.Uri?) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { settings.setLogo(uri) }.onFailure { _message.value = "Logo error: ${it.message}" }
        _logoVer.value++
    }
    val connState: StateFlow<ConnState> = ble.state
    val projects: StateFlow<List<Project>> = store.projects

    private val _screen = MutableStateFlow<List<Screen>>(listOf(Screen.Projects))
    val backStack: StateFlow<List<Screen>> = _screen

    private val _target = MutableStateFlow<TestTarget?>(null)
    val target: StateFlow<TestTarget?> = _target
    private val _lastSaved = MutableStateFlow<Pair<String, String>?>(null) // projectId to measurementId
    val lastSaved: StateFlow<Pair<String, String>?> = _lastSaved

    private val _devInfo = MutableStateFlow<DevInfo?>(null)
    val devInfo: StateFlow<DevInfo?> = _devInfo
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val prefs = app.getSharedPreferences("pe2", 0)
    private val _options = MutableStateFlow(loadOptions())
    val options: StateFlow<TestOptions> = _options

    init {
        viewModelScope.launch {
            ble.packets.collect { p -> if (p.ptype == PT.DEVINFO) _devInfo.value = DevInfoParser.parse(p) }
        }
        viewModelScope.launch {
            ble.log.collect { s ->
                val t = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
                _log.value = (_log.value + "$t  $s").takeLast(400)
            }
        }
        test.onFinished = { r -> save(r) }
    }

    // ---------------------------------------------------------------- navigation

    fun push(s: Screen) { _screen.value = _screen.value + s }
    fun back(): Boolean {
        if (_screen.value.size <= 1) return false
        if (_screen.value.last() is Screen.Scan) scan.stop()
        if (_screen.value.last() == Screen.Tools) tools.stopAll()
        _screen.value = _screen.value.dropLast(1); return true
    }
    fun replaceTop(s: Screen) { _screen.value = _screen.value.dropLast(1) + s }

    // ---------------------------------------------------------------- messages

    fun clearMessage() { _message.value = null }
    fun showMessage(m: String) { _message.value = m }

    // ---------------------------------------------------------------- projects

    fun createProject(p: Project): Project = store.createProject(p)
    fun updateProject(p: Project) = store.updateProject(p)
    fun deleteProject(id: String) { store.deleteProject(id); _screen.value = listOf(Screen.Projects) }
    fun deleteMeasurement(pid: String, mid: String) { store.deleteMeasurement(pid, mid); back() }
    fun setNote(pid: String, m: Measurement, note: String) = store.updateMeasurement(pid, m.copy(note = note))
    fun updateMeasurement(pid: String, m: Measurement) = store.updateMeasurement(pid, m)

    fun suggestLabel(projectId: String): String {
        val p = store.project(projectId) ?: return "Port 1"
        return Store.nextLabel(p.measurements.maxByOrNull { it.ts }?.label, p.measurements.size)
    }

    /** Next outlet label, same switch, next switch port – so a patch-panel run is one tap per port. */
    fun suggestTarget(projectId: String): TestTarget {
        val last = store.project(projectId)?.measurements?.maxByOrNull { it.ts }
        return TestTarget(projectId, suggestLabel(projectId), last?.switchName ?: "",
            last?.switchName?.takeIf { it.isNotEmpty() }?.let { Store.increment(last.switchPort) } ?: "")
    }

    fun quickLabel(): String = "Quick test " + SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date())

    // ---------------------------------------------------------------- test runs

    /** Opens the test screen for [t]; the run itself is started by [runOrStop] after permission checks. */
    fun prepareTest(t: TestTarget) {
        _target.value = t
        _lastSaved.value = null
        if (_screen.value.lastOrNull() != Screen.Test) push(Screen.Test)
    }

    fun prepareQuickTest() = prepareTest(TestTarget(store.quickProject().id, quickLabel()))

    private fun save(r: Report) {
        val t = _target.value ?: return
        if (r.startedAt == 0L || r.items.all { it.second.state == ItemState.SKIPPED || it.second.state == ItemState.WAITING }) return
        val dev = devInfo.value?.let { "S/N ${it.serial}" }
        val p = store.project(t.projectId) ?: return
        val old = p.measurements.firstOrNull { it.id == t.replaceId }
        val m = Measurement.from(r, t.label, dev, old?.seq ?: p.nextSeq, t.switchName, t.switchPort, _limits.value.describe)
        if (t.replaceId != null) {
            store.updateMeasurement(t.projectId, m.copy(id = t.replaceId, note = old?.note ?: "", photos = old?.photos ?: emptyList(),
                history = (old?.history ?: emptyList()) + listOfNotNull(old?.takeIf { !it.aborted }?.snapshot())))
            _lastSaved.value = t.projectId to t.replaceId
        } else {
            store.addMeasurement(t.projectId, m)
            _lastSaved.value = t.projectId to m.id
        }
    }

    fun connect(thenRun: Boolean = false) = viewModelScope.launch {
        try {
            ble.connect()
            ble.send(Cmd.devInfo())
            if (thenRun) test.start(_options.value, _limits.value)
        } catch (e: Exception) {
            _message.value = e.message ?: "Connection error"
        }
    }

    fun disconnect() { test.stop(); ble.disconnect(); _devInfo.value = null }

    fun runOrStop() {
        if (test.isRunning) { test.stop(); return }
        _lastSaved.value = null
        if (connState.value == ConnState.CONNECTED) test.start(_options.value, _limits.value) else connect(thenRun = true)
    }

    fun setProfile(p: Profile) = setOptions(_options.value.copy(profile = p))

    fun setOptions(o: TestOptions) {
        _options.value = o
        prefs.edit()
            .putBoolean("wiremap", o.wiremap).putBoolean("tdr", o.tdr).putBoolean("poe", o.poe)
            .putBoolean("network", o.network).putBoolean("ping", o.ping).putBoolean("extip", o.extIp)
            .putInt("nvp", o.nvp).putInt("vlan", o.vlanId).putBoolean("vlancollect", o.vlanCollect).putString("profile", o.profile.name)
            .putBoolean("static", o.staticIp).putString("ip", o.ip).putString("mask", o.mask).putString("gw", o.gw)
            .putString("dns", o.dns).putString("mac", o.customMac).apply()
    }

    private fun loadOptions() = TestOptions(
        wiremap = prefs.getBoolean("wiremap", true), tdr = prefs.getBoolean("tdr", true), poe = prefs.getBoolean("poe", true),
        network = prefs.getBoolean("network", true), ping = prefs.getBoolean("ping", true), extIp = prefs.getBoolean("extip", true),
        nvp = prefs.getInt("nvp", 71), vlanId = prefs.getInt("vlan", 0), vlanCollect = prefs.getBoolean("vlancollect", false),
        profile = runCatching { Profile.valueOf(prefs.getString("profile", "AUTO")!!) }.getOrDefault(Profile.AUTO),
        staticIp = prefs.getBoolean("static", false), ip = prefs.getString("ip", "") ?: "", mask = prefs.getString("mask", "") ?: "",
        gw = prefs.getString("gw", "") ?: "", dns = prefs.getString("dns", "") ?: "", customMac = prefs.getString("mac", "") ?: "",
    )
}
