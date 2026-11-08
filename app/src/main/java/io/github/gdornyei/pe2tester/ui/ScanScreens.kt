package io.github.gdornyei.pe2tester.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gdornyei.pe2tester.Screen
import io.github.gdornyei.pe2tester.export.Exporters
import io.github.gdornyei.pe2tester.scan.HostInfo
import io.github.gdornyei.pe2tester.scan.NetScan
import io.github.gdornyei.pe2tester.scan.ScanPhase
import java.util.Date

private val PORT_NAMES = mapOf(
    21 to "FTP", 22 to "SSH", 23 to "Telnet", 53 to "DNS", 80 to "HTTP", 135 to "RPC", 139 to "NetBIOS", 161 to "SNMP",
    443 to "HTTPS", 445 to "SMB", 502 to "Modbus", 515 to "LPD", 554 to "RTSP", 631 to "IPP", 1883 to "MQTT",
    3389 to "RDP", 5000 to "5000", 8000 to "8000", 8080 to "HTTP-alt", 8291 to "Winbox", 8443 to "HTTPS-alt",
    9100 to "JetDirect", 62078 to "iOS",
)

@Composable
private fun HostCard(h: HostInfo) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { open = !open }, shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h.ip, fontFamily = FontFamily.Monospace, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(128.dp))
                Text(h.name ?: "–", fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                h.role?.let {
                    Text(it, fontSize = 11.sp, color = InfoC, modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(InfoC.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            val desc = h.description
            if (desc.isNotEmpty()) Text(desc, fontSize = 12.sp, color = Muted, maxLines = if (open) 4 else 1, overflow = TextOverflow.Ellipsis)
            if (h.openPorts.isNotEmpty()) Text(h.openPorts.joinToString("  ") { PORT_NAMES[it] ?: it.toString() }, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary, maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis)
            if (open) {
                Spacer(Modifier.height(6.dp))
                listOfNotNull(
                    h.hostname?.let { "DNS name" to it }, h.netbiosName?.let { "NetBIOS" to it }, h.workgroup?.let { "Workgroup" to it },
                    h.mdnsName?.let { "mDNS" to it }, h.mac?.let { "MAC" to it }, h.upnp?.let { "UPnP" to it },
                    h.services.takeIf { it.isNotEmpty() }?.let { "Services" to it.joinToString(", ") },
                    ("Ping" to if (h.pingOk) "replies" else "no reply"), h.httpServer?.let { "HTTP server" to it },
                    h.httpTitle?.let { "Web page" to it }, h.rttMs?.let { "Response time" to "$it ms" },
                    ("Open ports" to h.openPorts.joinToString(", ").ifEmpty { "–" }),
                ).forEach { (k, v) ->
                    Row { Text(k, fontSize = 12.sp, color = Muted, modifier = Modifier.width(110.dp)); Text(v, fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
fun ScanScreen(c: Common, a: UiActions, projectId: String) {
    val vm = c.vm
    val ui by vm.scan.ui.collectAsState()
    val projects by vm.projects.collectAsState()
    val p = projects.firstOrNull { it.id == projectId }
    val ctx = LocalContext.current
    var label by remember { mutableStateOf("") }
    Frame(c, "Network discovery", p?.name) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (ui.phase) {
                            ScanPhase.IDLE, ScanPhase.ERROR -> {
                                Text("The Pockethernet acts as a WiFi bridge into the network under test, and the phone scans the given IP range.",
                                    fontSize = 13.sp, color = Muted)
                                Text("Plug the Pockethernet into the cable. The phone joins the “Pockethernet” WiFi – Android asks for permission the first time. The phone's normal internet keeps working meanwhile.",
                                    fontSize = 12.sp, color = Muted)
                                ui.error?.let { Text(it, color = Fail, fontSize = 13.sp) }
                                Button(onClick = { a.withBle { vm.scan.start() } }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(14.dp)) {
                                    Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Start WiFi bridge", fontSize = 17.sp)
                                }
                            }
                            ScanPhase.STARTING -> {
                                Text("Starting bridge…", fontWeight = FontWeight.Medium)
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            ScanPhase.READY, ScanPhase.SCANNING, ScanPhase.DONE -> {
                                ui.net?.let { n ->
                                    Text("On network: ${n.ip}/${n.prefix}", fontWeight = FontWeight.Medium)
                                    Text("Gateway: ${n.gateway ?: "–"}   DNS: ${n.dns.joinToString(", ").ifEmpty { "–" }}", fontSize = 12.sp, color = Muted)
                                }
                                Field("IP range (e.g. 192.168.1.0/24 or 10.0.0.10-60)", ui.range, { vm.scan.setRange(it) })
                                if (p != null && !p.quick) Field("Label (e.g. 2nd floor, office VLAN)", label, { label = it })
                                ui.error?.let { Text(it, color = Fail, fontSize = 13.sp) }
                                if (ui.phase == ScanPhase.SCANNING) {
                                    Text("Scanning: ${ui.done}/${ui.total} addresses · ${ui.hosts.size} devices", fontSize = 13.sp)
                                    LinearProgressIndicator({ if (ui.total == 0) 0f else ui.done.toFloat() / ui.total }, Modifier.fillMaxWidth())
                                    OutlinedButton(onClick = { vm.scan.cancelScan() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                                } else {
                                    Button(onClick = { vm.scan.scan(label) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                                        Icon(Icons.Filled.Search, null); Spacer(Modifier.width(8.dp))
                                        Text(if (ui.phase == ScanPhase.DONE) "Scan again" else "Start discovery", fontSize = 16.sp)
                                    }
                                }
                                if (ui.phase == ScanPhase.DONE) ui.result?.let { r ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Saved: ${p?.name ?: ""}", fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f).align(Alignment.CenterVertically))
                                        OutlinedButton(onClick = { a.shareFile(Exporters.scanCsv(ctx, r, p?.name ?: ""), "Network discovery") }) {
                                            Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text("CSV")
                                        }
                                    }
                                }
                                TextButton(onClick = { vm.scan.stop() }) { Text("Stop bridge", color = Fail) }
                            }
                        }
                    }
                }
            }
            if (ui.hosts.isNotEmpty()) item { Text("${ui.hosts.size} devices", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp)) }
            items(ui.hosts, key = { it.ip }) { HostCard(it) }
            if (ui.log.isNotEmpty()) item {
                Text(ui.log.takeLast(8).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Muted,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
fun ScanViewScreen(c: Common, a: UiActions, pid: String, sid: String) {
    val projects by c.vm.projects.collectAsState()
    val p = projects.firstOrNull { it.id == pid } ?: return
    val s = p.scans.firstOrNull { it.id == sid } ?: return
    val ctx = LocalContext.current
    var del by remember { mutableStateOf(false) }
    Frame(c, s.label.ifEmpty { "Network discovery" }, "${s.range} · ${dtFmt.format(Date(s.ts))}", menu = { close ->
        DropdownMenuItem(text = { Text("Share CSV") }, leadingIcon = { Icon(Icons.Filled.Share, null) },
            onClick = { close(); a.shareFile(Exporters.scanCsv(ctx, s, p.name), "Network discovery") })
        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { close(); del = true })
        HorizontalDivider()
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("${s.hosts.size} devices of ${s.scanned} addresses · phone: ${s.myIp} · gateway: ${s.gateway ?: "–"}", fontSize = 13.sp, color = Muted)
            }
            items(s.hosts, key = { it.ip }) { HostCard(it) }
        }
    }
    if (del) ConfirmDialog("Delete this discovery?", { del = false }) { c.vm.deleteScan(pid, sid) }
}
