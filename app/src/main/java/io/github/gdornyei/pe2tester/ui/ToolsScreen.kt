package io.github.gdornyei.pe2tester.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PAIR_COLORS = listOf(Color(0xFFF08A24), Color(0xFF2FA84F), Color(0xFF2F6FD6), Color(0xFF8B5A2B))
private val XT_COLORS = listOf(Color(0xFFE05A5A), Color(0xFF9B59B6), Color(0xFF16A085), Color(0xFFD4AC0D), Color(0xFF34495E), Color(0xFFE67E22))
private val XT_NAMES = (1..6).map { "X$it" }

@Composable
internal fun LinesChart(series: List<List<Int>>, colors: List<Color>, names: List<String>, xMaxM: Float? = null, height: Int = 150) {
    val grid = Muted.copy(alpha = 0.3f)
    Column {
        Canvas(Modifier.fillMaxWidth().height(height.dp)) {
            val all = series.flatten()
            if (all.isEmpty()) return@Canvas
            val n = series.maxOf { it.size }
            val mx = maxOf(20, all.maxOf { kotlin.math.abs(it) }).toFloat()
            drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
            // ±20 guide lines (vendor: above ±20 = significant imperfection)
            val g = size.height / 2 - (20f / mx) * (size.height / 2 - 4)
            drawLine(grid, Offset(0f, g), Offset(size.width, g)); drawLine(grid, Offset(0f, size.height - g), Offset(size.width, size.height - g))
            series.forEachIndexed { si, s ->
                val path = Path()
                s.forEachIndexed { i, v ->
                    val x = size.width * i / (n - 1).coerceAtLeast(1)
                    val y = size.height / 2 - (v / mx) * (size.height / 2 - 4)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, colors[si % colors.size], style = Stroke(2.2f))
            }
        }
        if (xMaxM != null) Row(Modifier.fillMaxWidth()) {
            Text("0 m", fontSize = 10.sp, color = Muted, modifier = Modifier.weight(1f))
            Text("%.0f m".format(xMaxM), fontSize = 10.sp, color = Muted)
        }
        FlowRowCompat(names.indices.map { i -> names[i] to colors[i % colors.size] })
    }
}

@Composable
private fun FlowRowCompat(items: List<Pair<String, Color>>) {
    Column {
        items.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (s, c) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(4.dp))
                        Text(s, fontSize = 11.sp, color = Muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 12.sp, color = Muted)
            content()
        }
    }
}

@Composable
fun ToolsScreen(c: Common, a: UiActions) {
    val t = c.vm.tools
    val u by t.ui.collectAsState()
    val opts by c.vm.options.collectAsState()
    Frame(c, "Tools") { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            u.error?.let { Text(it, color = Fail, fontSize = 13.sp) }

            val view = androidx.compose.ui.platform.LocalView.current
            DisposableEffect(u.hunt) { view.keepScreenOn = u.hunt; onDispose { view.keepScreenOn = false } }
            ToolCard("Adapter finder", "Tests the port continuously. Move the Pockethernet from port to port on the patch panel – when the wiremap adapter is on the far end, the phone vibrates, beeps and turns green.") {
                if (u.hunt) {
                    val (bg, fg) = when (u.huntState) {
                        io.github.gdornyei.pe2tester.HuntState.FOUND -> Ok to Color.White
                        io.github.gdornyei.pe2tester.HuntState.SHORT -> Fail to Color.White
                        io.github.gdornyei.pe2tester.HuntState.ACTIVE_PORT -> InfoC.copy(alpha = 0.18f) to MaterialTheme.colorScheme.onSurface
                        else -> Muted.copy(alpha = 0.15f) to MaterialTheme.colorScheme.onSurface
                    }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(when (u.huntState) {
                            io.github.gdornyei.pe2tester.HuntState.FOUND -> "ADAPTER FOUND"
                            io.github.gdornyei.pe2tester.HuntState.ACTIVE_PORT -> "Active port"
                            io.github.gdornyei.pe2tester.HuntState.SHORT -> "Short"
                            io.github.gdornyei.pe2tester.HuntState.NOTHING -> "No adapter"
                            else -> "Searching…"
                        }, color = fg, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text(u.huntText, color = fg, fontSize = 13.sp)
                        if (u.huntState == io.github.gdornyei.pe2tester.HuntState.FOUND && u.huntRemoteId > 0)
                            Text("Remote ID: ${u.huntRemoteId}", color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Test #${u.huntCount} · refreshes every ~2–3 s", fontSize = 11.sp, color = Muted)
                    OutlinedButton(onClick = { t.stopHunt() }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
                } else {
                    Button(onClick = { a.withBle { t.startHunt() } }, enabled = !u.busy, modifier = Modifier.fillMaxWidth()) { Text("Start adapter finder") }
                }
            }

            ToolCard("Cable toner", "Sends a signal on the cable that can be traced with any analog (inductive) probe.") {
                Text("Pair", fontSize = 12.sp, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("1-2" to 1, "3-6" to 2, "4-5" to 4, "7-8" to 8, "All" to 15).forEach { (n, m) ->
                        Choice(n, u.tonerPair == m) { t.update { it.copy(tonerPair = m) }; t.tonerChanged() }
                    }
                }
                Text("Tone", fontSize = 12.sp, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..4).forEach { n -> Choice("$n", u.tonerTone == n) { t.update { it.copy(tonerTone = n) }; t.tonerChanged() } }
                    Spacer(Modifier.width(6.dp))
                    Choice("Low", !u.tonerLoud) { t.update { it.copy(tonerLoud = false) }; t.tonerChanged() }
                    Choice("High", u.tonerLoud) { t.update { it.copy(tonerLoud = true) }; t.tonerChanged() }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Also on shield", Modifier.weight(1f), fontSize = 14.sp)
                    Switch(u.tonerShield, { v -> t.update { it.copy(tonerShield = v) }; t.tonerChanged() })
                }
                Button(onClick = { a.withBle { t.setToner(!u.toner) } }, enabled = !u.busy, modifier = Modifier.fillMaxWidth(),
                    colors = if (u.toner) ButtonDefaults.buttonColors(containerColor = Fail) else ButtonDefaults.buttonColors()) {
                    Text(if (u.toner) "Toner off" else "Toner on")
                }
            }

            ToolCard("Port blinker", "Shows which switch port the cable goes to: the Link LED blinks or stays on. At lower speed many switches use a different LED colour.") {
                Text("Speed", fontSize = 12.sp, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Auto" to 0, "10" to 10, "100" to 100, "1000" to 1000).forEach { (n, v) -> Choice(n, u.linkSpeed == v) { t.update { it.copy(linkSpeed = v) } } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Off" to 0, "Steady" to 1, "Blink" to 2).forEach { (n, m) ->
                        if (u.link == m) Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(n) }
                        else OutlinedButton(onClick = { a.withBle { t.setLink(m) } }, enabled = !u.busy, modifier = Modifier.weight(1f)) { Text(n) }
                    }
                }
                u.linkInfo?.let { l -> Text(if (l.up) "Link: ${l.summary}" else "No link", fontSize = 13.sp, color = if (l.up) Ok else Muted) }
            }

            ToolCard("TDR graph and crosstalk", "Impedance along the cable (positive: open, negative: short) plus crosstalk between pairs. Deviations above ±20 indicate a cable defect. Not meaningful on an active port.") {
                Button(onClick = { a.withBle { t.runTdrGraph(opts.nvp) } }, enabled = !u.busy, modifier = Modifier.fillMaxWidth()) {
                    if (u.tdrBusy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
                    Text(if (u.tdrBusy) "Testing…" else "Start test")
                }
                u.tdr?.let { r ->
                    Text(r.summary, fontWeight = FontWeight.Medium)
                    val n = r.pairs.firstOrNull()?.samples?.size ?: 0
                    LinesChart(r.pairs.map { it.samples }, PAIR_COLORS, r.pairs.mapIndexed { i, p ->
                        "${listOf("1-2", "3-6", "4-5", "7-8")[i]}${if (p.status.isNotEmpty()) " ${p.status} %.1f m".format(p.distanceM) else ""}"
                    }, xMaxM = if (n > 0) io.github.gdornyei.pe2tester.proto.Tdr.distance(n - 1, opts.nvp) else null)
                }
                if (u.xtalk.isNotEmpty()) {
                    Text("Crosstalk", fontWeight = FontWeight.Medium)
                    val n = u.xtalk.first().size
                    LinesChart(u.xtalk, XT_COLORS, XT_NAMES, xMaxM = if (n > 0) io.github.gdornyei.pe2tester.proto.Tdr.distance(n - 1, opts.nvp) else null, height = 120)
                }
            }

            ToolCard("Cable length calibration (NVP)", "Plug in a cable of known length with its far end free (e.g. a 50 m reel), enter the length, and the app calculates the NVP of that cable type – making length and fault-distance results more accurate.") {
                Field("Known length (m)", u.calLen, { v -> t.update { it.copy(calLen = v.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(6)) } }, number = true)
                Button(onClick = { a.withBle { t.calibrate() } }, enabled = !u.busy, modifier = Modifier.fillMaxWidth()) {
                    if (u.calBusy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
                    Text(if (u.calBusy) "Testing…" else "Calibrate")
                }
                u.calInfo?.let { Text(it, fontSize = 13.sp) }
                u.calNvp?.let { n ->
                    Text("Current NVP: ${opts.nvp}%", fontSize = 12.sp, color = Muted)
                    OutlinedButton(onClick = { c.vm.setOptions(opts.copy(nvp = n)); c.vm.showMessage("NVP set: $n%") }, modifier = Modifier.fillMaxWidth()) {
                        Text("Save NVP = $n%")
                    }
                }
            }

            Text("VLAN detection, static IP and custom MAC are in Settings → Tests; they become part of the test run.", fontSize = 12.sp, color = Muted)
        }
    }
}
