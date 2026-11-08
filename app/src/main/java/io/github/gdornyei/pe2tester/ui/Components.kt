package io.github.gdornyei.pe2tester.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gdornyei.pe2tester.*
import io.github.gdornyei.pe2tester.ble.ConnState
import io.github.gdornyei.pe2tester.proto.DevInfo
import io.github.gdornyei.pe2tester.proto.TdrResult
import io.github.gdornyei.pe2tester.proto.WiremapResult

val Ok = Color(0xFF2E9E5B)
val Fail = Color(0xFFD64545)
val InfoC = Color(0xFF3B82C4)
val Muted = Color(0xFF8A8F98)

// T568B conductor colours (stripe, solid)
private val WIRE = listOf(
    Color(0xFFF08A24) to true, Color(0xFFF08A24) to false, Color(0xFF2FA84F) to true, Color(0xFF2F6FD6) to false,
    Color(0xFF2F6FD6) to true, Color(0xFF2FA84F) to false, Color(0xFF8B5A2B) to true, Color(0xFF8B5A2B) to false,
)

@Composable
internal fun ConnChip(c: ConnState, d: DevInfo?) {
    val (txt, col) = when (c) {
        ConnState.CONNECTED -> (d?.let { "S${it.serial}" } ?: "Connected") to Ok
        ConnState.SCANNING -> "Searching…" to InfoC
        ConnState.CONNECTING -> "Connecting…" to InfoC
        ConnState.DISCONNECTED -> "Not connected" to Muted
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(col))
        Spacer(Modifier.width(6.dp))
        Text(txt, fontSize = 12.sp, color = col, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun StatusDot(s: ItemState) {
    val (c, icon) = when (s) {
        ItemState.OK -> Ok to Icons.Filled.Check
        ItemState.FAIL -> Fail to Icons.Filled.Close
        ItemState.INFO -> InfoC to Icons.Filled.Info
        else -> Muted to null
    }
    Box(Modifier.size(34.dp).clip(CircleShape).background(c.copy(alpha = if (s == ItemState.WAITING || s == ItemState.SKIPPED) 0.12f else 0.16f)),
        contentAlignment = Alignment.Center) {
        when {
            s == ItemState.RUNNING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            icon != null -> Icon(icon, null, tint = c, modifier = Modifier.size(20.dp))
            else -> Text("–", color = c)
        }
    }
}

@Composable
internal fun ResultCard(name: String, item: Item, wiremap: WiremapResult?, tdr: TdrResult?) {
    var open by remember(name) { mutableStateOf(false) }
    val hasExtra = wiremap != null || tdr != null
    val canOpen = item.details.isNotEmpty() || hasExtra
    val alpha = if (item.state == ItemState.SKIPPED) 0.55f else 1f
    Card(
        Modifier.fillMaxWidth().clickable(enabled = canOpen) { open = !open },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(item.state)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, fontSize = 12.sp, color = Muted.copy(alpha = alpha))
                    Text(
                        item.summary.ifEmpty { when (item.state) { ItemState.WAITING -> "waiting"; ItemState.RUNNING -> "testing…"; ItemState.SKIPPED -> "skipped"; else -> "" } },
                        fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    )
                }
                if (canOpen) Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = Muted)
            }
            AnimatedVisibility(open && canOpen) {
                Column(Modifier.padding(top = 12.dp, start = 46.dp)) {
                    if (wiremap != null) WiremapDiagram(wiremap)
                    if (tdr != null) TdrChart(tdr)
                    if (hasExtra && item.details.isNotEmpty()) Spacer(Modifier.height(10.dp))
                    item.details.forEach { (k, v) ->
                        if (v.isEmpty()) Text(k, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
                        else Row(Modifier.padding(vertical = 2.dp)) {
                            Text(k, fontSize = 13.sp, color = Muted, modifier = Modifier.width(130.dp))
                            Text(v, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun WiremapDiagram(w: WiremapResult) {
    val on = MaterialTheme.colorScheme.onSurface
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text("Local", fontSize = 11.sp, color = Muted, modifier = Modifier.weight(1f))
            Text("Remote", fontSize = 11.sp, color = Muted)
        }
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            val rows = 9; val h = size.height / rows; val r = h * 0.28f
            val xl = r + 2; val xr = size.width - r - 2
            fun y(i: Int) = h * (i - 1) + h / 2
            for (i in 1..9) {
                val c = if (i == 9) Muted else WIRE[i - 1].first
                val target = w.connections[i]
                if (w.shorts[i] != 0) {
                    val j = w.shorts[i]
                    drawLine(Fail, Offset(xl, y(i)), Offset(xl + 40f, (y(i) + y(j)) / 2), strokeWidth = 5f)
                    drawLine(Fail, Offset(xl, y(j)), Offset(xl + 40f, (y(i) + y(j)) / 2), strokeWidth = 5f)
                } else if (target in 1..9) {
                    drawLine(c, Offset(xl, y(i)), Offset(xr, y(target)), strokeWidth = 6f)
                    if (i <= 8 && WIRE[i - 1].second)
                        drawLine(Color.White, Offset(xl, y(i)), Offset(xr, y(target)), strokeWidth = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
                } else if (target == 255) {
                    drawLine(c, Offset(xl, y(i)), Offset(size.width / 2, y(i)), strokeWidth = 6f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f)))
                } else {
                    drawLine(c.copy(alpha = 0.5f), Offset(xl, y(i)), Offset(xl + size.width * 0.2f, y(i)), strokeWidth = 6f)
                }
            }
            for (i in 1..9) {
                val c = if (i == 9) Muted else WIRE[i - 1].first
                drawCircle(c, r, Offset(xl, y(i)))
                drawCircle(c, r, Offset(xr, y(i)), style = Stroke(3f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("1–8 = conductors (T568B colours), bottom = shield", fontSize = 11.sp, color = Muted)
        }
    }
}

@Composable
internal fun TdrChart(t: TdrResult) {
    val cols = listOf(Color(0xFFF08A24), Color(0xFF2FA84F), Color(0xFF2F6FD6), Color(0xFF8B5A2B))
    val grid = Muted.copy(alpha = 0.3f)
    Column {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val all = t.pairs.flatMap { it.samples }
            if (all.isEmpty()) return@Canvas
            val n = t.pairs.maxOf { it.samples.size }
            val mx = maxOf(1, all.maxOf { kotlin.math.abs(it) }).toFloat()
            drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
            t.pairs.forEachIndexed { pi, p ->
                val path = Path()
                p.samples.forEachIndexed { i, v ->
                    val x = size.width * i / (n - 1).coerceAtLeast(1)
                    val y = size.height / 2 - (v / mx) * (size.height / 2 - 4)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, cols[pi], style = Stroke(2.5f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickTest.PAIRS.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(cols[i])); Spacer(Modifier.width(4.dp))
                    Text(s, fontSize = 11.sp, color = Muted)
                }
            }
        }
    }
}

@Composable
internal fun SettingsDialog(o: TestOptions, onDismiss: () -> Unit, onSave: (TestOptions) -> Unit) {
    var s by remember { mutableStateOf(o) }
    var nvp by remember { mutableStateOf(o.nvp.toString()) }
    var vlan by remember { mutableStateOf(if (o.vlanId == 0) "" else o.vlanId.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onSave(s.copy(nvp = nvp.toIntOrNull()?.coerceIn(50, 99) ?: 71, vlanId = vlan.toIntOrNull()?.coerceIn(0, 4094) ?: 0))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("What to test?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                @Composable fun sw(label: String, v: Boolean, f: (Boolean) -> Unit) = Row(
                    Modifier.fillMaxWidth().clickable { f(!v) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                ) { Text(label, Modifier.weight(1f)); Switch(v, f) }
                sw("Wiremap", s.wiremap) { s = s.copy(wiremap = it) }
                sw("Cable length (TDR)", s.tdr) { s = s.copy(tdr = it) }
                sw("PoE", s.poe) { s = s.copy(poe = it) }
                sw("Network (link, DHCP, LLDP/CDP)", s.network) { s = s.copy(network = it) }
                sw("Ping", s.ping) { s = s.copy(ping = it) }
                sw("Internet / public IP", s.extIp) { s = s.copy(extIp = it) }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(nvp, { nvp = it.filter(Char::isDigit).take(2) }, label = { Text("NVP % (cable type, default 71)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(vlan, { vlan = it.filter(Char::isDigit).take(4) }, label = { Text("VLAN tag (empty = none)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
        },
    )
}

@Composable
internal fun LogDialog(lines: List<String>, onDismiss: () -> Unit) {
    val st = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) st.scrollToItem(lines.size - 1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Log") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), state = st) {
                items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        },
    )
}
