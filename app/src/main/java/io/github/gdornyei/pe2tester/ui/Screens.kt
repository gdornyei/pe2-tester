package io.github.gdornyei.pe2tester.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gdornyei.pe2tester.*
import io.github.gdornyei.pe2tester.ble.ConnState
import io.github.gdornyei.pe2tester.data.Limits
import io.github.gdornyei.pe2tester.data.Measurement
import io.github.gdornyei.pe2tester.data.Project
import io.github.gdornyei.pe2tester.data.ReportOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val dtFmt = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.ROOT)

/** Actions that need the Activity (permissions, share sheets, pickers). */
class UiActions(
    val runTest: () -> Unit,
    val shareText: (subject: String, text: String) -> Unit,
    val export: (Project, ReportOptions) -> Unit,
    val pickLogo: () -> Unit,
    val withBle: (() -> Unit) -> Unit,
    val shareFile: (java.io.File, String) -> Unit,
    val takePhoto: (pid: String, mid: String) -> Unit,
    val pickPhoto: (pid: String, mid: String) -> Unit,
    val driveSync: () -> Unit,
)

class Common(val vm: MainViewModel, val snack: SnackbarHostState, val onLog: () -> Unit)

@Composable
fun AppRoot(vm: MainViewModel, a: UiActions) {
    val stack by vm.backStack.collectAsState()
    val msg by vm.message.collectAsState()
    val snack = remember { SnackbarHostState() }
    var showLog by remember { mutableStateOf(false) }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.clearMessage() } }
    BackHandler(enabled = stack.size > 1) { vm.back() }

    val c = Common(vm, snack, onLog = { showLog = true })
    when (val s = stack.last()) {
        Screen.Projects -> ProjectsScreen(c, a)
        is Screen.ProjectView -> ProjectScreen(c, a, s.projectId)
        is Screen.MeasurementView -> MeasurementScreen(c, a, s.measurementId, s.projectId)
        Screen.Test -> TestScreen(c, a)
        Screen.Settings -> SettingsScreen(c, a)
        is Screen.Scan -> ScanScreen(c, a, s.projectId)
        is Screen.ScanView -> ScanViewScreen(c, a, s.projectId, s.scanId)
        Screen.Tools -> ToolsScreen(c, a)
        is Screen.PortMap -> PortMapScreen(c, a, s.projectId)
    }
    if (showLog) LogDialog(vm.log.collectAsState().value) { showLog = false }
}

// ===================================================================== chrome

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Frame(
    c: Common, title: String, subtitle: String? = null, back: Boolean = true,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit = {},
    fab: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val conn by c.vm.connState.collectAsState()
    val dev by c.vm.devInfo.collectAsState()
    var open by remember { mutableStateOf(false) }
    Scaffold(
        snackbarHost = { SnackbarHost(c.snack) },
        floatingActionButton = fab,
        topBar = {
            TopAppBar(
                navigationIcon = { if (back) IconButton(onClick = { c.vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        subtitle?.let { Text(it, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                },
                actions = {
                    ConnChip(conn, dev)
                    IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        menu { open = false }
                        DropdownMenuItem(text = { Text("Tools (adapter finder, toner, TDR…)") }, leadingIcon = { Icon(Icons.Filled.Build, null) },
                            onClick = { open = false; c.vm.push(Screen.Tools) })
                        DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Filled.Settings, null) },
                            onClick = { open = false; c.vm.push(Screen.Settings) })
                        DropdownMenuItem(text = { Text("Communication log") }, leadingIcon = { Icon(Icons.Filled.List, null) },
                            onClick = { open = false; c.onLog() })
                        if (conn == ConnState.CONNECTED) DropdownMenuItem(text = { Text("Disconnect") },
                            leadingIcon = { Icon(Icons.Filled.Close, null) }, onClick = { open = false; c.vm.disconnect() })
                        dev?.let { d ->
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("S/N ${d.serial} · FW ${d.stmVer}/${d.espVer}\n${d.mac}", fontSize = 12.sp, color = Muted) },
                                onClick = { open = false })
                        }
                    }
                },
            )
        },
        content = content,
    )
}

@Composable
private fun PassChip(fails: Int, total: Int) {
    val (t, col) = if (fails == 0) "$total OK" to Ok else "$fails failed" to Fail
    Text(t, color = col, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun Field(label: String, v: String, onChange: (String) -> Unit, number: Boolean = false, lines: Int = 1) =
    OutlinedTextField(v, onChange, label = { Text(label) }, singleLine = lines == 1, minLines = lines,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth())

@Composable
private fun ProjectDialog(title: String, init: Project?, onDismiss: () -> Unit, onSave: (Project) -> Unit) {
    var name by remember { mutableStateOf(init?.name ?: "") }
    var client by remember { mutableStateOf(init?.client ?: "") }
    var site by remember { mutableStateOf(init?.site ?: "") }
    var address by remember { mutableStateOf(init?.address ?: "") }
    var note by remember { mutableStateOf(init?.note ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val base = init ?: Project(name = "")
                onSave(base.copy(name = name.trim(), client = client.trim(), site = site.trim(), address = address.trim(), note = note.trim()))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field("Project / job name", name, { name = it })
                Field("Client", client, { client = it })
                Field("Building / site", site, { site = it })
                Field("Address", address, { address = it })
                Field("Note", note, { note = it }, lines = 2)
            }
        },
    )
}

@Composable
private fun TargetDialog(title: String, init: TestTarget, confirm: String, onDismiss: () -> Unit, c: Common? = null, onOk: (TestTarget) -> Unit) {
    var label by remember { mutableStateOf(init.label) }
    var sw by remember { mutableStateOf(init.switchName) }
    var port by remember { mutableStateOf(init.switchPort) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        confirmButton = {
            TextButton(enabled = label.isNotBlank(), onClick = {
                onOk(init.copy(label = label.trim(), switchName = sw.trim(), switchPort = port.trim())); onDismiss()
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field("Outlet / port ID", label, { label = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1.4f)) { Field("Switch", sw, { sw = it }) }
                    Box(Modifier.weight(1f)) { Field("Switch port", port, { port = it }) }
                }
                if (c != null) { Text("Test type", fontSize = 12.sp, color = Muted); ProfileChips(c) }
                Text("Switch/port can be left empty – if the switch sends LLDP/CDP, the test fills them in. Numbers auto-increment for the next test.",
                    fontSize = 12.sp, color = Muted)
            }
        },
    )
}

@Composable
internal fun ConfirmDialog(text: String, onDismiss: () -> Unit, onOk: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, text = { Text(text) },
    confirmButton = { TextButton(onClick = { onOk(); onDismiss() }) { Text("Delete", color = Fail) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
)

@Composable
internal fun Choice(label: String, selected: Boolean, onClick: () -> Unit) =
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })

@Composable
private fun ExportDialog(init: ReportOptions, onDismiss: () -> Unit, onOk: (ReportOptions) -> Unit) {
    var o by remember { mutableStateOf(init) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Create report") },
        confirmButton = { TextButton(enabled = o.pdf || o.csv, onClick = { onOk(o); onDismiss() }) { Text("Create and share") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Format", fontSize = 13.sp, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("PDF", o.pdf) { o = o.copy(pdf = !o.pdf) }; Choice("CSV", o.csv) { o = o.copy(csv = !o.csv) }
                }
                Text("Scope", fontSize = 13.sp, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("Summary", !o.detailed) { o = o.copy(detailed = false) }
                    Choice("+ 1 page per port", o.detailed) { o = o.copy(detailed = true) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Port map in report", Modifier.weight(1f), fontSize = 14.sp)
                    Switch(o.portMap, { o = o.copy(portMap = it) })
                }
                Text("Tests", fontSize = 13.sp, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("All", o.filter == ReportOptions.ALL) { o = o.copy(filter = ReportOptions.ALL) }
                    Choice("Pass", o.filter == ReportOptions.PASS) { o = o.copy(filter = ReportOptions.PASS) }
                    Choice("Failed", o.filter == ReportOptions.FAIL) { o = o.copy(filter = ReportOptions.FAIL) }
                }
            }
        },
    )
}

// ===================================================================== Projects

@Composable
private fun ProjectsScreen(c: Common, a: UiActions) {
    val projects by c.vm.projects.collectAsState()
    val owner by c.vm.owner.collectAsState()
    var newDlg by remember { mutableStateOf(false) }
    Frame(c, "PE2 Tester", owner.company.ifEmpty { null }, back = false, menu = { close ->
        DropdownMenuItem(text = { Text("Google Drive sync") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) },
            onClick = { close(); a.driveSync() })
        HorizontalDivider()
    }, fab = {
        ExtendedFloatingActionButton(onClick = { newDlg = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New project") })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Quick test", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("Test one port without a project – saved under “Quick tests”.", fontSize = 13.sp, color = Muted)
                        Spacer(Modifier.height(8.dp))
                        ProfileChips(c)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { c.vm.prepareQuickTest(); a.runTest() }, modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Start test", fontSize = 17.sp)
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().clickable { c.vm.openScan(null) }, shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Search, null, tint = InfoC); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Network discovery", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text("Through the Pockethernet WiFi bridge: IP, hostname, MAC, open ports", fontSize = 12.sp, color = Muted)
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().clickable { c.vm.push(Screen.Tools) }, shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Build, null, tint = InfoC); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Tools", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text("Adapter finder, cable toner, port blinker, TDR graph, NVP calibration", fontSize = 12.sp, color = Muted)
                        }
                    }
                }
            }
            if (owner.engineer.isEmpty() && owner.company.isEmpty()) item {
                Card(Modifier.fillMaxWidth().clickable { c.vm.push(Screen.Settings) }, shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Info, null, tint = InfoC); Spacer(Modifier.width(10.dp))
                        Text("Enter your name, company and logo in Settings – they go into the report header.", fontSize = 13.sp)
                    }
                }
            }
            val sorted = projects.sortedWith(compareBy<Project> { it.quick }.thenByDescending { it.lastTs })
            if (sorted.isNotEmpty()) item { Text("Projects", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
            items(sorted, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth().clickable { c.vm.push(Screen.ProjectView(p.id)) }, shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = listOf(p.client, p.site).filter { it.isNotEmpty() }.joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.measurements.size} tests · ${dtFmt.format(Date(p.lastTs))}", fontSize = 12.sp, color = Muted)
                        }
                        if (p.measurements.isNotEmpty()) PassChip(p.failCount, p.measurements.size)
                    }
                }
            }
            if (projects.none { !it.quick }) item {
                Text("Create a project (client, building) and test port by port (outlet, switch, port). At the end you can send a PDF report and a CSV.",
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            }
        }
    }
    if (newDlg) ProjectDialog("New project", null, { newDlg = false }) { p -> c.vm.createProject(p); c.vm.push(Screen.ProjectView(p.id)) }
}

// ===================================================================== Project

@Composable
private fun ProjectScreen(c: Common, a: UiActions, id: String) {
    val projects by c.vm.projects.collectAsState()
    val p = projects.firstOrNull { it.id == id } ?: return
    val ropt by c.vm.reportOptions.collectAsState()
    var targetDlg by remember { mutableStateOf(false) }
    var editDlg by remember { mutableStateOf(false) }
    var delDlg by remember { mutableStateOf(false) }
    var exportDlg by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(0) }
    val sub = listOf(p.client, p.site).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null }
    Frame(c, p.name, sub, menu = { close ->
        DropdownMenuItem(text = { Text("Report (PDF / CSV)") }, leadingIcon = { Icon(Icons.Filled.Share, null) },
            enabled = p.measurements.isNotEmpty() || p.scans.isNotEmpty(), onClick = { close(); exportDlg = true })
        DropdownMenuItem(text = { Text("Port map (outlet ↔ switch port)") }, leadingIcon = { Icon(Icons.Filled.List, null) },
            enabled = p.measurements.isNotEmpty(), onClick = { close(); c.vm.push(Screen.PortMap(p.id)) })
        DropdownMenuItem(text = { Text("Network discovery") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
            onClick = { close(); c.vm.openScan(p.id) })
        if (!p.quick) DropdownMenuItem(text = { Text("Project details") }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
            onClick = { close(); editDlg = true })
        DropdownMenuItem(text = { Text("Delete project") }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
            onClick = { close(); delDlg = true })
        HorizontalDivider()
    }, fab = {
        ExtendedFloatingActionButton(onClick = { targetDlg = true }, icon = { Icon(Icons.Filled.PlayArrow, null) }, text = { Text("Test port") })
    }) { pad ->
        val all = p.measurements.sortedByDescending { it.ts }
        val ms = when (filter) { 1 -> all.filter { it.pass }; 2 -> all.filter { !it.pass }; else -> all }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                val ok = p.measurements.count { it.pass }
                Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Stat("${p.measurements.size}", "test"); Stat("$ok", "pass", Ok)
                    Stat("${p.measurements.size - ok}", "failed", if (ok < p.measurements.size) Fail else Muted)
                    Spacer(Modifier.weight(1f))
                    if (p.measurements.isNotEmpty() || p.scans.isNotEmpty()) FilledTonalButton(onClick = { exportDlg = true }) {
                        Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text("Report")
                    }
                }
            }
            if (p.address.isNotEmpty() || p.note.isNotEmpty()) item {
                Text(listOf(p.address, p.note).filter { it.isNotEmpty() }.joinToString("\n"), fontSize = 12.sp, color = Muted,
                    modifier = Modifier.padding(horizontal = 4.dp))
            }
            if (all.isNotEmpty()) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("All", filter == 0) { filter = 0 }; Choice("Pass", filter == 1) { filter = 1 }; Choice("Failed", filter == 2) { filter = 2 }
                }
            }
            if (all.isEmpty()) item {
                Text("No tests yet. Tap “Test port”, enter the outlet (and the switch/port if you know them) and go.",
                    color = Muted, modifier = Modifier.padding(8.dp))
            }
            if (p.scans.isNotEmpty()) {
                item { Text("Network discoveries", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, start = 4.dp)) }
                items(p.scans.sortedByDescending { it.ts }, key = { it.id }) { s ->
                    Card(Modifier.fillMaxWidth().clickable { c.vm.push(Screen.ScanView(p.id, s.id)) }, shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Search, null, tint = InfoC); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${s.range} · ${s.hosts.size} devices", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(dtFmt.format(Date(s.ts)), fontSize = 12.sp, color = Muted)
                            }
                        }
                    }
                }
                item { Text("Port tests", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, start = 4.dp)) }
            }
            items(ms, key = { it.id }) { m -> MeasurementRow(m) { c.vm.push(Screen.MeasurementView(p.id, m.id)) } }
        }
    }
    if (targetDlg) TargetDialog("Which port are you testing?", c.vm.suggestTarget(p.id), "Test", { targetDlg = false }, c) { t ->
        c.vm.prepareTest(t); a.runTest()
    }
    if (editDlg) ProjectDialog("Project details", p, { editDlg = false }) { c.vm.updateProject(it) }
    if (delDlg) ConfirmDialog("Delete project “${p.name}” and all its ${p.measurements.size} tests?", { delDlg = false }) { c.vm.deleteProject(p.id) }
    if (exportDlg) ExportDialog(ropt.copy(portMapOnly = false), { exportDlg = false }) { o -> c.vm.setReportOptions(o); a.export(p, o) }
}

@Composable
private fun Stat(v: String, label: String, col: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Column { Text(v, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = col); Text(label, fontSize = 12.sp, color = Muted) }
}

@Composable
private fun MeasurementRow(m: Measurement, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(when { m.aborted -> ItemState.SKIPPED; m.pass -> ItemState.OK; else -> ItemState.FAIL })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.testId, fontSize = 11.sp, color = Muted)
                }
                val sw = listOf(m.switchShown, m.portShown).filter { it.isNotEmpty() }.joinToString(" / ")
                Text(listOfNotNull(sw.ifEmpty { null }, dtFmt.format(Date(m.ts))).joinToString(" · "), fontSize = 12.sp, color = Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val d = m.digest
                if (d.isNotEmpty()) Text(d, fontSize = 12.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!m.pass && !m.aborted) Text(m.items.filter { it.item.state == ItemState.FAIL }
                    .joinToString(" · ") { "${it.name.substringBefore(" (")}: ${it.item.summary}" },
                    fontSize = 12.sp, color = Fail, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (m.note.isNotEmpty()) Text("Note: ${m.note}", fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ===================================================================== Measurement detail

@Composable
private fun KV(k: String, v: String) = Row {
    Text(k, fontSize = 13.sp, color = Muted, modifier = Modifier.width(96.dp)); Text(v, fontSize = 13.sp)
}

@Composable
private fun MeasurementScreen(c: Common, a: UiActions, mid: String, pid: String) {
    val projects by c.vm.projects.collectAsState()
    val p = projects.firstOrNull { it.id == pid } ?: return
    val m = p.measurements.firstOrNull { it.id == mid } ?: return
    var editDlg by remember { mutableStateOf(false) }
    var delDlg by remember { mutableStateOf(false) }
    Frame(c, m.label, "${m.testId} · ${p.name}", menu = { close ->
        DropdownMenuItem(text = { Text("Share as text") }, leadingIcon = { Icon(Icons.Filled.Share, null) },
            onClick = { close(); a.shareText("Test: ${m.label}", io.github.gdornyei.pe2tester.export.Exporters.measurementText(m, p.name)) })
        DropdownMenuItem(text = { Text("PDF of this port") }, leadingIcon = { Icon(Icons.Filled.Share, null) },
            onClick = { close(); a.export(p.copy(measurements = listOf(m), scans = emptyList()), ReportOptions(detailed = true, pdf = true, csv = false)) })
        DropdownMenuItem(text = { Text("Re-test (overwrites)") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) },
            onClick = { close(); c.vm.prepareTest(TestTarget(p.id, m.label, m.switchName, m.switchPort, replaceId = m.id)); a.runTest() })
        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { close(); delDlg = true })
        HorizontalDivider()
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        val (t, col) = when {
                            m.aborted -> "Aborted test" to Muted
                            m.pass -> "Pass" to Ok
                            else -> "Fail (${m.failCount})" to Fail
                        }
                        Text(t, color = col, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        KV("Date/time", dtFmt.format(Date(m.ts)))
                        KV("Switch", m.switchShown.ifEmpty { "–" } + if (m.switchName.isEmpty() && m.switchShown.isNotEmpty()) "  (LLDP/CDP)" else "")
                        KV("Switch port", m.portShown.ifEmpty { "–" })
                        m.device?.let { KV("Test device", it) }
                        if (m.limits.isNotEmpty()) KV("Limits", m.limits)
                        if (m.note.isNotEmpty()) KV("Note", m.note)
                        TextButton(onClick = { editDlg = true }, contentPadding = PaddingValues(0.dp)) {
                            Icon(Icons.Filled.Edit, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Edit details, note")
                        }
                    }
                }
            }
            p.previousOf(m)?.let { prev ->
                val d = m.diff(prev)
                item {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = (if (d.isEmpty()) Ok else InfoC).copy(alpha = 0.10f))) {
                        Column(Modifier.padding(14.dp)) {
                            Text(if (d.isEmpty()) "No change since the previous test" else "Changes since the previous test",
                                fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("Previous test: ${dtFmt.format(Date(prev.ts))}", fontSize = 12.sp, color = Muted)
                            d.forEach { (k, o, n) ->
                                Row { Text(k, fontSize = 13.sp, color = Muted, modifier = Modifier.width(96.dp)); Text("$o  →  $n", fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                            }
                        }
                    }
                }
            }
            item { PhotoStrip(c, a, p.id, m) }
            items(m.items, key = { it.name }) { ni ->
                ResultCard(ni.name, ni.item,
                    wiremap = if (ni.name == Measurement.N_WIREMAP) m.wiremap else null,
                    tdr = if (ni.name == Measurement.N_TDR) m.tdr else null)
            }
        }
    }
    if (editDlg) {
        var label by remember { mutableStateOf(m.label) }
        var sw by remember { mutableStateOf(m.switchName) }
        var port by remember { mutableStateOf(m.switchPort) }
        var note by remember { mutableStateOf(m.note) }
        AlertDialog(onDismissRequest = { editDlg = false }, title = { Text("Test details") },
            confirmButton = {
                TextButton(enabled = label.isNotBlank(), onClick = {
                    c.vm.updateMeasurement(p.id, m.copy(label = label.trim(), switchName = sw.trim(), switchPort = port.trim(), note = note.trim()))
                    editDlg = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editDlg = false }) { Text("Cancel") } },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field("Outlet / port ID", label, { label = it })
                    Field("Switch (empty = from LLDP/CDP)", sw, { sw = it })
                    Field("Switch port", port, { port = it })
                    Field("Note", note, { note = it }, lines = 3)
                }
            })
    }
    if (delDlg) ConfirmDialog("Delete test “${m.label}”?", { delDlg = false }) { c.vm.deleteMeasurement(p.id, m.id) }
}

// ===================================================================== Test run

@Composable
private fun TestScreen(c: Common, a: UiActions) {
    val vm = c.vm
    val report by vm.test.report.collectAsState()
    val conn by vm.connState.collectAsState()
    val target by vm.target.collectAsState()
    val saved by vm.lastSaved.collectAsState()
    val projects by vm.projects.collectAsState()
    val proj = projects.firstOrNull { it.id == target?.projectId }
    var nextDlg by remember { mutableStateOf(false) }
    val busy = conn == ConnState.SCANNING || conn == ConnState.CONNECTING
    val done = report.startedAt > 0 && !report.running && saved != null
    val swp = target?.let { t -> listOf(t.switchName, t.switchPort).filter { it.isNotEmpty() }.joinToString(" / ") } ?: ""
    val sub = listOfNotNull(proj?.name, swp.ifEmpty { null }).joinToString(" · ")

    Frame(c, target?.label ?: "Test", sub.ifEmpty { null }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (done) {
                            val f = report.failCount
                            val stopped = report.phase == "Stopped" || report.phase.startsWith("Failure")
                            Text(if (stopped) report.phase else if (f == 0) "Pass" else "Fail ($f)",
                                color = if (stopped) Muted else if (f == 0) Ok else Fail, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            Text("Saved: ${proj?.name ?: ""}", color = Muted, fontSize = 13.sp)
                            Spacer(Modifier.height(16.dp))
                        } else if (report.running) {
                            Text(report.phase, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                            Spacer(Modifier.height(16.dp))
                        }
                        if (report.profileLabel.isNotEmpty() && (report.running || done))
                            Text(report.profileLabel, fontSize = 12.sp, color = InfoC, modifier = Modifier.padding(bottom = 8.dp))
                        if (!report.running && !busy) { ProfileChips(c, showHint = false); Spacer(Modifier.height(10.dp)) }
                        if (done && proj != null && !proj.quick) {
                            val next = vm.suggestTarget(proj.id)
                            Button(onClick = { nextDlg = true }, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(16.dp)) {
                                Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp))
                                Text("Next: ${next.label}", fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    val s = saved; val t = target
                                    if (s != null && t != null) vm.prepareTest(t.copy(projectId = s.first, replaceId = s.second))
                                    a.runTest()
                                }) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Again") }
                                OutlinedButton(onClick = { saved?.let { (pp, mm) -> vm.replaceTop(Screen.MeasurementView(pp, mm)) } }) { Text("Details") }
                            }
                        } else {
                            Button(
                                onClick = {
                                    val t = target
                                    if (done && t != null) vm.prepareTest(t.copy(label = if (proj?.quick == true) vm.quickLabel() else t.label, replaceId = null))
                                    a.runTest()
                                },
                                enabled = !busy, modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(16.dp),
                                colors = if (report.running) ButtonDefaults.buttonColors(containerColor = Fail) else ButtonDefaults.buttonColors(),
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(Modifier.width(12.dp)); Text(if (conn == ConnState.SCANNING) "Searching…" else "Connecting…", fontSize = 18.sp)
                                } else if (report.running) {
                                    Icon(Icons.Filled.Close, null); Spacer(Modifier.width(8.dp)); Text("Stop", fontSize = 18.sp)
                                } else {
                                    Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp))
                                    Text(if (done) "New quick test" else "Start test", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            if (done) TextButton(onClick = { saved?.let { (pp, mm) -> vm.replaceTop(Screen.MeasurementView(pp, mm)) } }) {
                                Text("Details, share")
                            }
                        }
                    }
                }
            }
            if (report.startedAt > 0 && (report.running || done)) {
                items(report.items, key = { it.first }) { (name, item) ->
                    ResultCard(name, item,
                        wiremap = if (name == Measurement.N_WIREMAP) report.wiremapRaw else null,
                        tdr = if (name == Measurement.N_TDR) report.tdrRaw else null)
                }
            } else if (!report.running) item {
                Text("Plug in the cable, switch on the Pockethernet and press the button. For wiremap, put the wiremap adapter on the far end.",
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            }
        }
    }
    if (nextDlg && proj != null) TargetDialog("Next port", vm.suggestTarget(proj.id), "Test", { nextDlg = false }, c) { t ->
        vm.prepareTest(t); a.runTest()
    }
}

// ===================================================================== Settings

@Composable
private fun SectionHeader(s: String) =
    Text(s, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))

@Composable
private fun SwitchRow(label: String, v: Boolean, f: (Boolean) -> Unit) = Row(
    Modifier.fillMaxWidth().clickable { f(!v) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
) { Text(label, Modifier.weight(1f)); Switch(v, f) }

@Composable
private fun SettingsScreen(c: Common, a: UiActions) {
    val vm = c.vm
    val owner by vm.owner.collectAsState()
    val limits by vm.limits.collectAsState()
    val opts by vm.options.collectAsState()
    val logoVer by vm.logoVersion.collectAsState()
    var o by remember { mutableStateOf(owner) }
    var l by remember { mutableStateOf(limits) }
    var t by remember { mutableStateOf(opts) }
    var maxLen by remember { mutableStateOf(if (limits.maxLengthM == 0) "" else limits.maxLengthM.toString()) }
    var nvp by remember { mutableStateOf(opts.nvp.toString()) }
    var vlan by remember { mutableStateOf(if (opts.vlanId == 0) "" else opts.vlanId.toString()) }
    val logo = remember(logoVer) { vm.settings.logoFile.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }

    val save = {
        vm.setOwner(o)
        vm.setLimits(l.copy(maxLengthM = maxLen.toIntOrNull()?.coerceIn(0, 500) ?: 0))
        vm.setOptions(t.copy(nvp = nvp.toIntOrNull()?.coerceIn(50, 99) ?: 71, vlanId = vlan.toIntOrNull()?.coerceIn(0, 4094) ?: 0))
    }
    val latestSave by rememberUpdatedState(save)
    DisposableEffect(Unit) { onDispose { latestSave() } }

    Frame(c, "Settings") { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionHeader("Report header (tester)")
            Field("Tested by (name)", o.engineer, { o = o.copy(engineer = it) })
            Field("Company", o.company, { o = o.copy(company = it) })
            Field("Address", o.address, { o = o.copy(address = it) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { Field("Phone", o.phone, { o = o.copy(phone = it) }) }
                Box(Modifier.weight(1.3f)) { Field("E-mail", o.email, { o = o.copy(email = it) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (logo != null) Image(logo.asImageBitmap(), "Logo", Modifier.height(44.dp).widthIn(max = 140.dp))
                else Text("No logo", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = a.pickLogo) { Text(if (logo == null) "Choose logo" else "Replace") }
                if (logo != null) TextButton(onClick = { vm.setLogo(null) }) { Text("Delete", color = Fail) }
            }

            SectionHeader("Google Drive")
            val driveBusy by vm.driveBusy.collectAsState()
            val driveInfo by vm.driveInfo.collectAsState()
            val driveRep by vm.driveReports.collectAsState()
            Text("Projects, tests and photos sync to the “PE2 Tester” folder of your own Google Drive (two-way, merged). Another phone with the same account sees the same data.",
                fontSize = 12.sp, color = Muted)
            Button(onClick = a.driveSync, enabled = !driveBusy, modifier = Modifier.fillMaxWidth()) {
                if (driveBusy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
                Text(if (driveBusy) "Syncing…" else "Sync now")
            }
            if (driveInfo.isNotEmpty()) Text(driveInfo, fontSize = 12.sp, color = Muted)
            SwitchRow("Also save reports (PDF/CSV) to Drive", driveRep) { vm.setDriveReports(it) }

            SectionHeader("Limits (pass / fail)")
            Text("Shield", fontSize = 13.sp, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Any", l.shield == Limits.SHIELD_ANY) { l = l.copy(shield = Limits.SHIELD_ANY) }
                Choice("UTP", l.shield == Limits.SHIELD_UTP) { l = l.copy(shield = Limits.SHIELD_UTP) }
                Choice("STP", l.shield == Limits.SHIELD_STP) { l = l.copy(shield = Limits.SHIELD_STP) }
            }
            Text("Minimum link speed", fontSize = 13.sp, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Any", l.minSpeed == 0) { l = l.copy(minSpeed = 0) }
                Choice("100 Mbit", l.minSpeed == 100) { l = l.copy(minSpeed = 100) }
                Choice("1 Gbit", l.minSpeed == 1000) { l = l.copy(minSpeed = 1000) }
            }
            Field("Max. cable length (m, empty = none)", maxLen, { maxLen = it.filter(Char::isDigit).take(3) }, number = true)
            SwitchRow("Crossover allowed", l.allowCrossover) { l = l.copy(allowCrossover = it) }
            SwitchRow("2-pair wiring allowed", l.allowTwoPair) { l = l.copy(allowTwoPair = it) }
            Text("PoE requirement", fontSize = 13.sp, color = Muted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("None" to -1, "Any" to 0, "af 15W" to 3, "at 30W" to 4, "bt 60W" to 6, "bt 90W" to 8).forEach { (n, v) ->
                    Choice(n, l.poeNeed == v) { l = l.copy(poeMinClass = v, poeRequired = v >= 0) }
                }
            }
            SwitchRow("DHCP required", l.dhcpRequired) { l = l.copy(dhcpRequired = it) }
            SwitchRow("Ping required", l.pingRequired) { l = l.copy(pingRequired = it) }
            SwitchRow("Internet required", l.internetRequired) { l = l.copy(internetRequired = it) }

            SectionHeader("Tests")
            SwitchRow("Wiremap", t.wiremap) { t = t.copy(wiremap = it) }
            SwitchRow("Cable length (TDR)", t.tdr) { t = t.copy(tdr = it) }
            SwitchRow("PoE", t.poe) { t = t.copy(poe = it) }
            SwitchRow("Network (link, DHCP, LLDP/CDP)", t.network) { t = t.copy(network = it) }
            SwitchRow("Ping", t.ping) { t = t.copy(ping = it) }
            SwitchRow("Internet / public IP", t.extIp) { t = t.copy(extIp = it) }
            SwitchRow("VLAN detection (+30 s)", t.vlanCollect) { t = t.copy(vlanCollect = it) }
            SwitchRow("Static IP (instead of DHCP)", t.staticIp) { t = t.copy(staticIp = it) }
            if (t.staticIp) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { Field("IP address", t.ip, { t = t.copy(ip = it.trim()) }) }
                    Box(Modifier.weight(1f)) { Field("Mask", t.mask, { t = t.copy(mask = it.trim()) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { Field("Gateway", t.gw, { t = t.copy(gw = it.trim()) }) }
                    Box(Modifier.weight(1f)) { Field("DNS", t.dns, { t = t.copy(dns = it.trim()) }) }
                }
            }
            Field("Custom MAC (empty = factory)", t.customMac, { t = t.copy(customMac = it.trim()) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { Field("NVP % (default 71)", nvp, { nvp = it.filter(Char::isDigit).take(2) }, number = true) }
                Box(Modifier.weight(1f)) { Field("Outgoing VLAN tag", vlan, { vlan = it.filter(Char::isDigit).take(4) }, number = true) }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { save(); vm.showMessage("Saved"); vm.back() }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}

@Composable
private fun PhotoStrip(c: Common, a: UiActions, pid: String, m: Measurement) {
    var del by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (m.photos.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            m.photos.forEach { name ->
                val bmp = remember(name) {
                    runCatching {
                        val f = java.io.File(c.vm.photoDir, name)
                        val o = BitmapFactory.Options().apply { inSampleSize = 4 }
                        BitmapFactory.decodeFile(f.path, o)
                    }.getOrNull()
                }
                if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.height(110.dp).clip(RoundedCornerShape(10.dp)).clickable { del = name })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { a.takePhoto(pid, m.id) }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Photo") }
            OutlinedButton(onClick = { a.pickPhoto(pid, m.id) }) { Text("Gallery") }
        }
    }
    del?.let { n -> ConfirmDialog("Delete this photo?", { del = null }) { c.vm.removePhoto(pid, m.id, n) } }
}

@Composable
private fun PortMapScreen(c: Common, a: UiActions, pid: String) {
    val projects by c.vm.projects.collectAsState()
    val p = projects.firstOrNull { it.id == pid } ?: return
    val ropt by c.vm.reportOptions.collectAsState()
    val rows = io.github.gdornyei.pe2tester.data.Store.portMap(p)
    Frame(c, "Port map", p.name, menu = { close ->
        DropdownMenuItem(text = { Text("Share (PDF/CSV)") }, leadingIcon = { Icon(Icons.Filled.Share, null) },
            onClick = { close(); a.export(p, ropt.copy(portMapOnly = true)) })
        HorizontalDivider()
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 32.dp)) {
            item {
                Text("Latest test per outlet, sorted by switch and port. Switch/port come from manual entry, otherwise from LLDP/CDP.",
                    fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 8.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    listOf("Outlet" to 1.2f, "Switch" to 1.3f, "Port" to 0.8f, "VLAN" to 0.6f, "Link" to 0.6f, "PoE" to 0.8f).forEach { (h, w) ->
                        Text(h, fontSize = 11.sp, color = Muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(w))
                    }
                }
                HorizontalDivider()
            }
            var lastSw: String? = null
            rows.forEach { m ->
                val sw = m.switchShown
                if (sw != lastSw) {
                    lastSw = sw
                    item(key = "h_" + sw + m.id) { Text(sw.ifEmpty { "Unknown switch" }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        color = InfoC, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) }
                }
                item(key = m.id) {
                    Row(Modifier.fillMaxWidth().clickable { c.vm.push(Screen.MeasurementView(p.id, m.id)) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1.2f), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (m.pass) Ok else Fail)); Spacer(Modifier.width(6.dp))
                            Text(m.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(sw.ifEmpty { "–" }, fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1.3f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(m.portShown.ifEmpty { "–" }, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.8f), maxLines = 1)
                        Text(m.vlanText.ifEmpty { "–" }, fontSize = 12.sp, modifier = Modifier.weight(0.6f), maxLines = 1)
                        Text(m.speedText.ifEmpty { "–" }, fontSize = 12.sp, modifier = Modifier.weight(0.6f))
                        Text(m.poeText.ifEmpty { "–" }, fontSize = 12.sp, modifier = Modifier.weight(0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider(color = Muted.copy(alpha = 0.15f))
                }
            }
        }
    }
}

@Composable
internal fun ProfileChips(c: Common, showHint: Boolean = true) {
    val o by c.vm.options.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Profile.entries.forEach { p -> Choice(p.label, o.profile == p) { c.vm.setProfile(p) } }
        }
        if (showHint) Text(o.profile.hint, fontSize = 12.sp, color = Muted)
    }
}
