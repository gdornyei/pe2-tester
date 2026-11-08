package io.github.gdornyei.pe2tester.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.gdornyei.pe2tester.proto.Tdr
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import io.github.gdornyei.pe2tester.ItemState
import io.github.gdornyei.pe2tester.data.Measurement
import io.github.gdornyei.pe2tester.data.OwnerInfo
import io.github.gdornyei.pe2tester.data.Project
import io.github.gdornyei.pe2tester.data.ReportOptions
import io.github.gdornyei.pe2tester.scan.NetScan
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Exporters {
    private val dt = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.ROOT)
    private val dOnly = SimpleDateFormat("yyyy.MM.dd", Locale.ROOT)
    private val tOnly = SimpleDateFormat("HH:mm", Locale.ROOT)
    private val fileTs = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT)
    private val PAIRS = listOf("1-2", "3-6", "4-5", "7-8")

    private fun safe(s: String) = s.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_').ifEmpty { "project" }
    private fun outDir(ctx: Context) = File(ctx.cacheDir, "exports").apply { mkdirs() }

    fun share(ctx: Context, files: List<File>, title: String) {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) })
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply {
            type = if (files[0].extension == "pdf") "application/pdf" else "text/csv"
            putExtra(Intent.EXTRA_STREAM, uris[0])
        } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        i.putExtra(Intent.EXTRA_SUBJECT, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun select(p: Project, o: ReportOptions): List<Measurement> = p.measurements.sortedWith(compareBy({ it.seq }, { it.ts }))
        .filter { when (o.filter) { ReportOptions.PASS -> it.pass; ReportOptions.FAIL -> !it.pass; else -> true } }

    private fun verdict(m: Measurement) = when { m.aborted -> "ABORTED"; m.pass -> "PASS"; else -> "FAIL" }

    private fun cell(m: Measurement, name: String): String {
        val it = m.item(name) ?: return ""
        return when (it.state) {
            ItemState.SKIPPED -> if (it.summary.isEmpty()) "–" else it.summary
            ItemState.FAIL -> "FAIL: ${it.summary}"
            else -> it.summary
        }
    }

    private fun lengthOf(m: Measurement) = m.tdr?.avgDistanceM?.let { "%.1f m".format(it) }
        ?: m.tdr?.pairs?.filter { it.status.isNotEmpty() }?.maxOfOrNull { it.distanceM }?.let { "≤%.1f m".format(it) } ?: ""

    private fun speedOf(m: Measurement) = m.link?.let { if (!it.up) "none" else if (it.speed >= 1000) "1G" else "${it.speed}M" } ?: ""

    private fun poeOf(m: Measurement) = m.poe?.let {
        if (!it.present) "–" else if (it.pseType.startsWith("Passive")) "passive ${it.openV}V" else "${it.pseType} c${it.pdClass.substringBefore(' ')}"
    } ?: ""

    // ================================================================== CSV

    fun csv(ctx: Context, p: Project, o: ReportOptions): File {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"").replace('\n', ' ') + "\""
        val head = listOf("Project", "Client", "Building", "Test", "Outlet", "Result", "Date", "Time", "Switch", "Port",
            "Wiremap", "Length", "Cable (TDR)", "Link", "PoE", "IP address", "Gateway", "DNS", "VLAN", "Switch (LLDP/CDP)", "Ping",
            "Public IP", "Note", "Device")
        if (o.portMapOnly) {
            val sbm = StringBuilder("\uFEFF").append(listOf("Switch", "Port", "Outlet", "VLAN", "Link", "PoE", "Result", "Test", "Date")
                .joinToString(";") { q(it) }).append("\r\n")
            for (m in io.github.gdornyei.pe2tester.data.Store.portMap(p)) sbm.append(listOf(m.switchShown, m.portShown, m.label, m.vlanText, m.speedText,
                m.poeText, verdict(m), m.testId, dt.format(Date(m.ts))).joinToString(";") { q(it) }).append("\r\n")
            return File(outDir(ctx), "${safe(p.name)}_port_map_${fileTs.format(Date())}.csv").apply { writeText(sbm.toString()) }
        }
        val sb = StringBuilder("\uFEFF").append(head.joinToString(";") { q(it) }).append("\r\n")
        for (m in select(p, o)) {
            val row = listOf(p.name, p.client, p.site, m.testId, m.label, verdict(m), dOnly.format(Date(m.ts)), tOnly.format(Date(m.ts)),
                m.switchShown, m.portShown, cell(m, Measurement.N_WIREMAP), lengthOf(m), cell(m, Measurement.N_TDR),
                cell(m, Measurement.N_LINK), cell(m, Measurement.N_POE), m.dhcp?.yourIp ?: "", m.dhcp?.router ?: "",
                m.dhcp?.dns?.joinToString(", ") ?: "", m.neighbors.firstNotNullOfOrNull { it.vlan }?.toString() ?: "",
                cell(m, Measurement.N_NEIGHBOR), cell(m, Measurement.N_PING), m.extIp?.ip ?: "", m.note, m.device ?: "")
            sb.append(row.joinToString(";") { q(it) }).append("\r\n")
        }
        return File(outDir(ctx), "${safe(p.name)}_${fileTs.format(Date())}.csv").apply { writeText(sb.toString()) }
    }

    fun scanCsv(ctx: Context, s: NetScan, project: String): File {
        fun q(x: String) = "\"" + x.replace("\"", "\"\"") + "\""
        val sb = StringBuilder("\uFEFF")
        sb.append(listOf("Project", "Discovery", "Date/time", "IP", "Name", "DNS name", "NetBIOS", "Workgroup", "mDNS", "MAC",
            "Role", "Open ports", "Web page", "HTTP server", "Description", "Response time (ms)").joinToString(";") { q(it) }).append("\r\n")
        for (h in s.hosts) sb.append(listOf(project, s.label.ifEmpty { s.range }, dt.format(Date(s.ts)), h.ip, h.name ?: "", h.hostname ?: "",
            h.netbiosName ?: "", h.workgroup ?: "", h.mdnsName ?: "", h.mac ?: "", h.role ?: "", h.openPorts.joinToString(" "),
            h.httpTitle ?: "", h.httpServer ?: "", h.description, h.rttMs?.toString() ?: "").joinToString(";") { q(it) }).append("\r\n")
        return File(outDir(ctx), "${safe(project)}_discovery_${fileTs.format(Date())}.csv").apply { writeText(sb.toString()) }
    }

    // ================================================================== PDF

    private val BLUE = Color.rgb(28, 111, 181)
    private val OK = Color.rgb(46, 158, 91)
    private val FAIL = Color.rgb(214, 69, 69)
    private val GREY = Color.rgb(120, 125, 135)
    private val LINE = Color.rgb(214, 218, 226)
    private val BAND = Color.rgb(239, 242, 247)
    private val WIRES = intArrayOf(Color.rgb(240, 138, 36), Color.rgb(240, 138, 36), Color.rgb(47, 168, 79), Color.rgb(47, 111, 214),
        Color.rgb(47, 111, 214), Color.rgb(47, 168, 79), Color.rgb(139, 90, 43), Color.rgb(139, 90, 43))
    private val STRIPED = booleanArrayOf(true, false, true, false, true, false, true, false)

    private class Pdf(val doc: PdfDocument, val logo: Bitmap?, val owner: OwnerInfo, val footer: String) {
        fun tr(s: String) = s
        fun dt(s: String, x: Float, y: Float, p: Paint) = c.drawText(tr(s), x, y, p)
        val W = 595f; val H = 842f; val M = 36f; val TW = W - 2 * M
        var page: PdfDocument.Page? = null; var y = 0f; var no = 0
        val c get() = page!!.canvas

        fun p(size: Float, color: Int = Color.rgb(30, 32, 36), bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val tx = p(8.6f); val txB = p(8.6f, bold = true); val mu = p(8f, GREY); val muB = p(8f, GREY, true)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.7f; color = LINE }

        fun lh(pt: Paint) = pt.textSize + 2.6f

        fun newPage(title: String) {
            finish(); no++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), no).create())
            if (logo != null) {
                val s = minOf(120f / logo.width, 42f / logo.height)
                c.drawBitmap(logo, null, RectF(M, M - 6, M + logo.width * s, M - 6 + logo.height * s), Paint(Paint.FILTER_BITMAP_FLAG))
            } else if (owner.company.isNotEmpty()) dt(owner.company, M, M + 14, p(13f, BLUE, true))
            val tp = p(15f, Color.rgb(20, 24, 30), true)
            val tt = tr(title)
            c.drawText(tt, W - M - tp.measureText(tt), M + 12, tp)
            val sub = tr("Pockethernet 2 · PE2 Tester")
            c.drawText(sub, W - M - mu.measureText(sub), M + 25, mu)
            fill.color = BLUE; c.drawRect(M, M + 34, W - M, M + 35.6f, fill)
            dt(footer, M, H - 20, mu)
            val pg = "Page $no"; c.drawText(pg, W - M - mu.measureText(pg), H - 20, mu)
            y = M + 46
        }

        fun finish() { page?.let { doc.finishPage(it) }; page = null }
        fun room(px: Float) = y + px <= H - 40
        fun need(px: Float, title: String) { if (page == null || !room(px)) newPage(title) }

        fun wrap(s: String, pt: Paint, maxW: Float): List<String> {
            val out = mutableListOf<String>()
            for (para in s.split('\n')) {
                var cur = ""
                for (word in para.split(' ')) {
                    val t = if (cur.isEmpty()) word else "$cur $word"
                    if (pt.measureText(t) <= maxW) cur = t else { if (cur.isNotEmpty()) out += cur; cur = word }
                    while (pt.measureText(cur) > maxW && cur.length > 1) {
                        var k = cur.length; while (k > 1 && pt.measureText(cur.substring(0, k)) > maxW) k--
                        out += cur.substring(0, k); cur = cur.substring(k)
                    }
                }
                out += cur
            }
            return out
        }

        fun text(s0: String, x: Float, yTop: Float, pt: Paint, maxW: Float): Float {
            val ls = wrap(tr(s0), pt, maxW)
            ls.forEachIndexed { i, l -> c.drawText(l, x, yTop + pt.textSize + i * lh(pt), pt) }
            return ls.size * lh(pt)
        }

        fun height(s: String, pt: Paint, maxW: Float) = wrap(tr(s), pt, maxW).size * lh(pt)

        fun icon(state: Boolean?, cx: Float, cy: Float, r: Float) {
            fill.color = when (state) { true -> OK; false -> FAIL; null -> GREY }
            c.drawCircle(cx, cy, r, fill)
            val w = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = r * 0.28f; strokeCap = Paint.Cap.ROUND }
            when (state) {
                true -> c.drawPath(Path().apply { moveTo(cx - r * 0.45f, cy); lineTo(cx - r * 0.1f, cy + r * 0.38f); lineTo(cx + r * 0.5f, cy - r * 0.35f) }, w)
                false -> {
                    c.drawLine(cx - r * 0.38f, cy - r * 0.38f, cx + r * 0.38f, cy + r * 0.38f, w)
                    c.drawLine(cx + r * 0.38f, cy - r * 0.38f, cx - r * 0.38f, cy + r * 0.38f, w)
                }
                null -> c.drawLine(cx - r * 0.4f, cy, cx + r * 0.4f, cy, w)
            }
        }

        fun kvColumns(left: List<Pair<String, String>>, right: List<Pair<String, String>>, title: String) {
            val colW = TW / 2 - 8; val kw = 78f
            fun colH(l: List<Pair<String, String>>) = l.sumOf { height(it.second, tx, colW - kw).toDouble() }.toFloat()
            val h = maxOf(colH(left), colH(right))
            need(h + 6, title)
            var yy = y
            for ((k, v) in left) { dt(k, M, yy + tx.textSize, muB); yy += text(v, M + kw, yy, tx, colW - kw) }
            yy = y
            val rx = M + TW / 2 + 8
            for ((k, v) in right) { dt(k, rx, yy + tx.textSize, muB); yy += text(v, rx + kw, yy, tx, colW - kw) }
            y += h + 6
        }

        fun section(name: String, state: ItemState?, summary: String, title: String, minBody: Float = 30f) {
            need(22f + minBody, title)
            fill.color = BAND; c.drawRoundRect(RectF(M, y, W - M, y + 18), 3f, 3f, fill)
            icon(when (state) { ItemState.OK -> true; ItemState.FAIL -> false; else -> null }, M + 9, y + 9, 5.5f)
            val np = p(9.4f, bold = true)
            dt(name, M + 20, y + 12.5f, np)
            val sp = p(8.6f, if (state == ItemState.FAIL) FAIL else Color.rgb(60, 64, 70))
            val s = wrap(tr(summary), sp, TW - np.measureText(tr(name)) - 40).firstOrNull() ?: ""
            c.drawText(s, W - M - 6 - sp.measureText(s), y + 12.5f, sp)
            y += 22f
        }

        fun table(header: List<String>, rows: List<List<String>>, widths: List<Float>, title: String,
                  x0: Float = M, tw: Float = TW, colorCol: Int = -1, okFn: (List<String>) -> Boolean? = { null }) {
            fun rowH(r: List<String>, pt: Paint) = (r.mapIndexed { i, s -> height(s, pt, tw * widths[i] - 6) }.maxOrNull() ?: 10f) + 3f
            fun drawHeader() {
                val h = rowH(header, muB)
                fill.color = BAND; c.drawRect(x0, y, x0 + tw, y + h, fill)
                var x = x0; header.forEachIndexed { i, s -> text(s, x + 3, y + 1, muB, tw * widths[i] - 6); x += tw * widths[i] }
                y += h
            }
            need(rowH(header, muB) + (rows.firstOrNull()?.let { rowH(it, tx) } ?: 0f), title)
            drawHeader()
            for (r in rows) {
                val h = rowH(r, tx)
                if (!room(h)) { newPage(title); drawHeader() }
                var x = x0
                r.forEachIndexed { i, s ->
                    if (i == colorCol) {
                        val ok = okFn(r)
                        icon(ok, x + 8, y + 7, 4.2f)
                        text(s, x + 15, y + 1, Paint(txB).apply { color = when (ok) { true -> OK; false -> FAIL; null -> GREY } }, tw * widths[i] - 18)
                    } else text(s, x + 3, y + 1, tx, tw * widths[i] - 6)
                    x += tw * widths[i]
                }
                y += h
                c.drawLine(x0, y, x0 + tw, y, stroke)
            }
            y += 6
        }

        fun kvTable(rows: List<Pair<String, String>>, title: String) {
            val kw = 150f
            for ((k, v) in rows) {
                if (v.isEmpty()) { need(14f, title); dt(k, M + 4, y + mu.textSize + 2, muB); y += 14f; continue }
                val h = height(v, tx, TW - kw - 8) + 3
                need(h, title)
                dt(k, M + 4, y + tx.textSize + 1, mu); text(v, M + kw, y + 1, tx, TW - kw - 8)
                y += h; c.drawLine(M, y, W - M, y, stroke)
            }
            y += 6
        }
    }

    fun pdf(ctx: Context, p: Project, o: ReportOptions, owner: OwnerInfo, logo: Bitmap?): File {
        val doc = PdfDocument()
        val ms = select(p, o)
        val pdf = Pdf(doc, logo, owner, "${p.name} · created: ${dt.format(Date())}")
        val sumTitle = if (o.portMapOnly) "Port map" else "Test report"

        pdf.newPage(sumTitle)
        jobBlock(pdf, p, owner, ms, sumTitle)
        if (o.portMapOnly) {
            portMapTable(pdf, p, sumTitle)
            return finishPdf(ctx, doc, pdf, p, "port_map")
        }

        val pass = ms.count { it.pass }; val fail = ms.size - pass
        pdf.need(52f, sumTitle)
        val bw = (pdf.TW - 16) / 3
        listOf(Triple("Ports tested", ms.size.toString(), BLUE), Triple("Pass", pass.toString(), OK), Triple("Fail", fail.toString(), FAIL))
            .forEachIndexed { i, (l, v, col) ->
                val x = pdf.M + i * (bw + 8)
                pdf.fill.color = Color.argb(28, Color.red(col), Color.green(col), Color.blue(col))
                pdf.c.drawRoundRect(RectF(x, pdf.y, x + bw, pdf.y + 42), 6f, 6f, pdf.fill)
                pdf.dt(v, x + 10, pdf.y + 24, pdf.p(19f, col, true))
                pdf.dt(l, x + 10, pdf.y + 36, pdf.mu)
            }
        pdf.y += 52
        if (ms.size > 1) {
            pdf.need(12f, sumTitle)
            val split = pdf.M + pdf.TW * pass / ms.size
            pdf.fill.color = OK; pdf.c.drawRect(pdf.M, pdf.y, split, pdf.y + 5, pdf.fill)
            pdf.fill.color = FAIL; pdf.c.drawRect(split, pdf.y, pdf.M + pdf.TW, pdf.y + 5, pdf.fill)
            pdf.y += 14
        }

        pdf.need(30f, sumTitle); pdf.dt("Summary", pdf.M, pdf.y + 11, pdf.p(11f, bold = true)); pdf.y += 18
        val rows = ms.map { m ->
            listOf(m.testId, m.label, if (m.aborted) "aborted" else if (m.pass) "OK" else "FAIL", dOnly.format(Date(m.ts)), tOnly.format(Date(m.ts)),
                m.switchShown, m.portShown, speedOf(m), lengthOf(m), poeOf(m), m.dhcp?.yourIp ?: "")
        }
        pdf.table(listOf("Test", "Outlet", "Result", "Date", "Time", "Switch", "Port", "Link", "Length", "PoE", "IP"), rows,
            listOf(0.065f, 0.13f, 0.09f, 0.09f, 0.055f, 0.14f, 0.085f, 0.055f, 0.075f, 0.085f, 0.13f), sumTitle,
            colorCol = 2, okFn = { r -> when (r[2]) { "OK" -> true; "FAIL" -> false; else -> null } })
        if (fail > 0) {
            pdf.need(30f, sumTitle); pdf.dt("Failures", pdf.M, pdf.y + 11, pdf.p(11f, FAIL, true)); pdf.y += 18
            pdf.table(listOf("Test", "Outlet", "Failure"), ms.filter { !it.pass }.map { m ->
                listOf(m.testId, m.label, if (m.aborted) "Test aborted" else m.items.filter { it.item.state == ItemState.FAIL }
                    .joinToString("; ") { "${it.name.substringBefore(" (")}: ${it.item.summary}" })
            }, listOf(0.08f, 0.2f, 0.72f), sumTitle)
        }

        if (o.portMap && p.measurements.any { it.switchShown.isNotEmpty() }) {
            pdf.need(60f, sumTitle); pdf.dt("Port map", pdf.M, pdf.y + 11, pdf.p(11f, bold = true)); pdf.y += 18
            portMapTable(pdf, p, sumTitle)
        }

        if (o.detailed) for (m in ms) testPage(ctx, pdf, p, owner, m)

        for (s in p.scans.sortedBy { it.ts }) {
            val t = "Network discovery"
            pdf.newPage(t)
            pdf.kvColumns(listOf("Project" to p.name, "Range" to s.range, "Date/time" to dt.format(Date(s.ts))) +
                (if (s.label.isNotEmpty()) listOf("Label" to s.label) else emptyList()),
                listOf("Devices found" to "${s.hosts.size} / ${s.scanned} addresses", "Gateway" to (s.gateway ?: "–"),
                    "DNS" to s.dns.joinToString(", ").ifEmpty { "–" }), t)
            pdf.y += 4
            pdf.table(listOf("IP address", "Name", "MAC", "Open ports", "Description"), s.hosts.map { h ->
                listOf(h.ip, listOfNotNull(h.name, h.role).joinToString(" · ").ifEmpty { "–" }, h.mac ?: "–",
                    h.openPorts.joinToString(", ").ifEmpty { "–" }, h.description.ifEmpty { "–" })
            }, listOf(0.15f, 0.25f, 0.17f, 0.17f, 0.26f), t)
        }

        pdf.finish()
        val f = File(outDir(ctx), "${safe(p.name)}_${fileTs.format(Date())}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    private fun portMapTable(pdf: Pdf, p: Project, title: String) {
        val rows = io.github.gdornyei.pe2tester.data.Store.portMap(p).map { m ->
            listOf(m.switchShown.ifEmpty { "–" }, m.portShown.ifEmpty { "–" }, m.label, m.vlanText.ifEmpty { "–" }, m.speedText.ifEmpty { "–" },
                m.poeText.ifEmpty { "–" }, if (m.aborted) "aborted" else if (m.pass) "OK" else "FAIL")
        }
        pdf.table(listOf("Switch", "Port", "Outlet", "VLAN", "Link", "PoE", "Result"), rows,
            listOf(0.24f, 0.13f, 0.2f, 0.1f, 0.08f, 0.13f, 0.12f), title,
            colorCol = 6, okFn = { r -> when (r[6]) { "OK" -> true; "FAIL" -> false; else -> null } })
    }

    private fun finishPdf(ctx: Context, doc: PdfDocument, pdf: Pdf, p: Project, suffix: String): File {
        pdf.finish()
        val f = File(outDir(ctx), "${safe(p.name)}_${suffix}_${fileTs.format(Date())}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    private fun jobBlock(pdf: Pdf, p: Project, owner: OwnerInfo, ms: List<Measurement>, title: String) {
        val left = buildList {
            add("Project" to p.name)
            if (p.client.isNotEmpty()) add("Client" to p.client)
            if (p.site.isNotEmpty()) add("Building" to p.site)
            if (p.address.isNotEmpty()) add("Address" to p.address)
            if (ms.isNotEmpty()) add("Test period" to "${dOnly.format(Date(ms.minOf { it.ts }))} – ${dOnly.format(Date(ms.maxOf { it.ts }))}")
            if (p.note.isNotEmpty()) add("Note" to p.note)
        }
        val right = buildList {
            if (owner.engineer.isNotEmpty()) add("Tested by" to owner.engineer)
            if (owner.company.isNotEmpty()) add("Company" to owner.company)
            if (owner.address.isNotEmpty()) add("Address" to owner.address)
            listOf(owner.phone, owner.email).filter { it.isNotEmpty() }.joinToString(" · ").takeIf { it.isNotEmpty() }?.let { add("Contact" to it) }
            ms.mapNotNull { it.device }.distinct().takeIf { it.isNotEmpty() }?.let { add("Test device" to "Pockethernet 2 (${it.joinToString(", ")})") }
            ms.map { it.limits }.filter { it.isNotEmpty() }.distinct().takeIf { it.isNotEmpty() }?.let { add("Limits" to it.joinToString(" | ")) }
        }
        pdf.kvColumns(left, right, title)
        pdf.y += 4
    }

    private fun testPage(ctx: Context, pdf: Pdf, p: Project, owner: OwnerInfo, m: Measurement) {
        val title = "Port test · ${m.testId}"
        pdf.newPage(title)
        val vcol = when { m.aborted -> GREY; m.pass -> OK; else -> FAIL }
        val vtxt = pdf.tr(verdict(m))
        val vp = pdf.p(15f, vcol, true)
        val boxW = maxOf(150f, vp.measureText(vtxt) + 46)
        val bx = pdf.W - pdf.M - boxW
        pdf.fill.color = Color.argb(26, Color.red(vcol), Color.green(vcol), Color.blue(vcol))
        pdf.c.drawRoundRect(RectF(bx, pdf.y, pdf.W - pdf.M, pdf.y + 50), 8f, 8f, pdf.fill)
        pdf.icon(if (m.aborted) null else m.pass, bx + 18, pdf.y + 25, 10f)
        pdf.dt(vtxt, bx + 34, pdf.y + 23, vp)
        pdf.dt(m.testId, bx + 34, pdf.y + 38, pdf.muB)
        var yy = pdf.y
        for ((k, v) in listOf("Outlet" to m.label, "Switch" to m.switchShown.ifEmpty { "–" },
            "Switch port" to m.portShown.ifEmpty { "–" }, "Date/time" to dt.format(Date(m.ts)))) {
            pdf.dt(k, pdf.M, yy + 10, pdf.muB)
            val vp2 = if (k == "Outlet") pdf.p(11f, bold = true) else pdf.tx
            pdf.dt(pdf.wrap(v, vp2, bx - pdf.M - 80).first(), pdf.M + 70, yy + 10, vp2)
            yy += 13
        }
        pdf.y = maxOf(yy, pdf.y + 50) + 8
        val left = listOfNotNull("Project" to p.name, p.client.takeIf { it.isNotEmpty() }?.let { "Client" to it },
            p.site.takeIf { it.isNotEmpty() }?.let { "Building" to it })
        val right = listOfNotNull(owner.engineer.takeIf { it.isNotEmpty() }?.let { "Tested by" to it },
            m.device?.let { "Test device" to "Pockethernet 2, $it" }, m.profile.takeIf { it.isNotEmpty() }?.let { "Test type" to it },
            m.limits.takeIf { it.isNotEmpty() }?.let { "Limits" to it })
        pdf.kvColumns(left, right, title)
        pdf.c.drawLine(pdf.M, pdf.y, pdf.W - pdf.M, pdf.y, pdf.stroke); pdf.y += 8
        p.previousOf(m)?.let { prev ->
            val d = m.diff(prev)
            if (d.isNotEmpty()) {
                pdf.section("Changes since the previous test", null, "Previous test: ${dt.format(Date(prev.ts))}", title, 14f * d.size)
                pdf.table(listOf("", "Previous", "Now"), d.map { (k, a, b) -> listOf(k, a, b) }, listOf(0.25f, 0.375f, 0.375f), title)
            }
        }

        // ---- Cable: wiremap drawing + TDR pair table
        val wmItem = m.item(Measurement.N_WIREMAP); val tdrItem = m.item(Measurement.N_TDR)
        if (wmItem?.state != ItemState.SKIPPED || tdrItem?.state != ItemState.SKIPPED) {
            val l = listOfNotNull(wmItem, tdrItem)
            val worst = if (l.any { it.state == ItemState.FAIL }) ItemState.FAIL else if (l.any { it.state == ItemState.OK }) ItemState.OK else ItemState.INFO
            pdf.section("Cable", worst, l.map { it.summary }.filter { it.isNotEmpty() }.joinToString(" · "), title, 140f)
            val top = pdf.y
            val rowH = 11f
            val w = m.wiremap
            if (w != null) {
                val x0 = pdf.M + 30; val x1 = pdf.M + 190
                pdf.dt("Local", x0 - 14, top + 8, pdf.mu); pdf.dt("Remote", x1 - 10, top + 8, pdf.mu)
                fun ry(i: Int) = top + 18 + (i - 1) * rowH
                for (i in 1..9) {
                    val col = if (i == 9) GREY else WIRES[i - 1]
                    val ln = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3.2f; strokeCap = Paint.Cap.ROUND; color = col }
                    val t = w.connections[i]; val s = w.shorts[i]
                    when {
                        s != 0 -> {
                            ln.color = FAIL
                            pdf.c.drawLine(x0, ry(i), x0 + 25, (ry(i) + ry(s)) / 2, ln); pdf.c.drawLine(x0, ry(s), x0 + 25, (ry(i) + ry(s)) / 2, ln)
                        }
                        t in 1..9 -> {
                            pdf.c.drawLine(x0, ry(i), x1, ry(t), ln)
                            if (i <= 8 && STRIPED[i - 1]) pdf.c.drawLine(x0, ry(i), x1, ry(t), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.WHITE; strokeWidth = 1.2f; pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f) })
                        }
                        t == 255 -> pdf.c.drawLine(x0, ry(i), (x0 + x1) / 2, ry(i), ln.apply { pathEffect = DashPathEffect(floatArrayOf(5f, 3f), 0f) })
                        else -> pdf.c.drawLine(x0, ry(i), x0 + 30, ry(i), ln.apply { alpha = 110 })
                    }
                    val lbl = if (i == 9) "S" else "$i"
                    pdf.dt(lbl, x0 - 14, ry(i) + 3, pdf.mu); pdf.dt(lbl, x1 + 8, ry(i) + 3, pdf.mu)
                    pdf.fill.color = col; pdf.c.drawCircle(x0, ry(i), 3f, pdf.fill); pdf.c.drawCircle(x1, ry(i), 3f, pdf.fill)
                }
                pdf.dt(w.summary, x0 - 14, ry(9) + 18, pdf.txB)
                if (w.remoteId > 0) pdf.dt("Remote ID: ${w.remoteId}", x0 - 14, ry(9) + 30, pdf.mu)
            } else pdf.dt("Wiremap not tested", pdf.M + 10, top + 20, pdf.mu)
            val tblX = pdf.M + 250; val tblW = pdf.TW - 250
            pdf.y = top
            val tdr = m.tdr
            if (tdr != null) {
                pdf.table(listOf("Pair", "Status", "Length (m)"),
                    tdr.pairs.mapIndexed { i, pr -> listOf(PAIRS[i], pr.status.ifEmpty { "–" }, if (pr.status.isEmpty()) "–" else "%.1f".format(pr.distanceM)) },
                    listOf(0.25f, 0.45f, 0.3f), title, tblX, tblW)
                tdr.avgDistanceM?.let { pdf.dt("Average cable length: %.1f m".format(it), tblX, pdf.y + 8, pdf.txB); pdf.y += 14 }
            } else pdf.dt("TDR not tested", tblX, top + 20, pdf.mu)
            pdf.y = maxOf(pdf.y, top + 18 + 8 * rowH + 40) + 6
            if (tdr != null && tdr.pairs.any { it.samples.isNotEmpty() }) {
                pdf.need(96f, title)
                val gx = pdf.M; val gw = pdf.TW; val gh = 80f; val gy = pdf.y
                pdf.c.drawRect(gx, gy, gx + gw, gy + gh, pdf.stroke)
                val mx = maxOf(20, tdr.pairs.flatMap { it.samples }.maxOf { kotlin.math.abs(it) }).toFloat()
                pdf.c.drawLine(gx, gy + gh / 2, gx + gw, gy + gh / 2, pdf.stroke)
                val cols = intArrayOf(WIRES[1], WIRES[5], WIRES[3], WIRES[7])
                tdr.pairs.forEachIndexed { pi, pr ->
                    val s = pr.samples; if (s.size < 2) return@forEachIndexed
                    val path = Path()
                    s.forEachIndexed { i, v ->
                        val x = gx + gw * i / (s.size - 1); val yv = gy + gh / 2 - v / mx * (gh / 2 - 3)
                        if (i == 0) path.moveTo(x, yv) else path.lineTo(x, yv)
                    }
                    pdf.c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.1f; color = cols[pi] })
                }
                pdf.dt("TDR impedance along the cable (0 – %.0f m)".format(Tdr.distance(tdr.pairs[0].samples.size - 1)), gx + 4, gy + 10, pdf.mu)
                pdf.y = gy + gh + 8
            }
        }

        // ---- Link
        m.item(Measurement.N_LINK)?.takeIf { it.state != ItemState.SKIPPED || it.summary.isNotEmpty() }?.let { li ->
            pdf.section("Link", li.state, li.summary, title)
            m.link?.let { l ->
                pdf.table(listOf("Parameter", "Setup", "Result"), listOf(
                    listOf("Port", "RJ45", "RJ45"),
                    listOf("Speed", "Auto", if (l.up) (if (l.speed >= 1000) "1000 Mb/s" else "${l.speed} Mb/s") else "no link"),
                    listOf("Duplex", "Auto", if (l.up) (if (l.fullDuplex) "Full" else "Half") else "–"),
                    listOf("Partner advertises", "", "10M: ${l.partner10} · 100M: ${l.partner100} · 1000M: ${l.partner1000}"),
                    listOf("2.5/5/10G", "", l.partnerMultiGig),
                    listOf("Polarity", "", PAIRS.indices.joinToString("  ") { "${PAIRS[it]}: ${l.polarity[it]}" }),
                ) + (l.skewNs?.let { s -> listOf(listOf("Delay skew", "", s.joinToString(" / ") { "$it ns" })) } ?: emptyList()),
                    listOf(0.22f, 0.18f, 0.6f), title)
            }
        }

        // ---- PoE
        m.item(Measurement.N_POE)?.takeIf { it.state != ItemState.SKIPPED }?.let { pi ->
            pdf.section("PoE", pi.state, pi.summary, title, 12f)
            val po = m.poe
            if (po != null && po.present) pdf.table(listOf("Type", "Class", "Open circuit (V)", "Loaded (V)", "Polarity"),
                listOf(listOf(po.pseType, po.pdClass, "${po.openV}", "${po.loadV}", po.polarity.ifEmpty { "–" })),
                listOf(0.2f, 0.16f, 0.16f, 0.16f, 0.32f), title)
        }

        // ---- IPv4
        m.item(Measurement.N_DHCP)?.takeIf { it.state != ItemState.SKIPPED }?.let { di ->
            pdf.section("IPv4 (DHCP)", di.state, di.summary, title, 12f)
            m.dhcp?.let { d ->
                pdf.table(listOf("Setup", "IP address", "Mask", "Gateway", "DNS", "DHCP server"),
                    listOf(listOf("DHCP", d.yourIp, d.subnet ?: "–", d.router ?: "–", d.dns.joinToString(", ").ifEmpty { "–" }, d.serverIp)),
                    listOf(0.1f, 0.15f, 0.15f, 0.15f, 0.22f, 0.23f), title)
            }
        }

        // ---- Switch (LLDP / CDP)
        m.item(Measurement.N_NEIGHBOR)?.takeIf { it.state != ItemState.SKIPPED }?.let { ni ->
            pdf.section("Switch (LLDP / CDP)", ni.state, ni.summary, title, 40f)
            for (n in m.neighbors) pdf.kvTable(buildList {
                add(n.protocol to "")
                n.systemName?.let { add("System name" to it) }
                n.systemDescr?.let { add("Description" to it) }
                n.platform?.let { add("Platform" to it) }
                n.mgmtIp?.let { add("IP address" to it) }
                n.portId?.let { add("Port ID" to it) }
                n.portDescr?.let { add("Port description" to it) }
                add("VLAN" to (n.vlan?.toString() ?: "–"))
                n.capabilities?.let { add("Capabilities" to it) }
                n.extra.forEach { (k, v) -> add(k to v) }
            }, title)
        }

        // ---- VLAN
        m.item(Measurement.N_VLAN)?.takeIf { it.state != ItemState.SKIPPED }?.let { vi ->
            pdf.section("VLAN", vi.state, vi.summary, title, 6f)
        }

        // ---- Ping
        m.item(Measurement.N_PING)?.takeIf { it.state != ItemState.SKIPPED }?.let { pg ->
            pdf.section("Ping", pg.state, pg.summary, title, 12f)
            if (m.pings.isNotEmpty()) {
                val gw = m.dhcp?.router; val dns = m.dhcp?.dns?.firstOrNull()
                pdf.table(listOf("Target", "Address", "Tx", "Rx", "Time (ms)", "Result"), m.pings.map { r ->
                    listOf(when (r.target) { gw -> "Gateway"; dns -> "DNS"; else -> "Internet" }, r.resolvedIp,
                        if (r.sent > 0) "${r.sent}" else "–", if (r.sent > 0) "${r.received}" else "–",
                        r.avgMs?.toString() ?: "–", if (r.ok) "OK" else "FAIL")
                }, listOf(0.16f, 0.26f, 0.1f, 0.1f, 0.16f, 0.22f), title, colorCol = 5, okFn = { it[5] == "OK" })
            }
        }

        // ---- Internet
        m.item(Measurement.N_EXTIP)?.takeIf { it.state != ItemState.SKIPPED }?.let { ei ->
            pdf.section("Internet", ei.state, ei.summary, title, 12f)
            m.extIp?.let { e -> pdf.kvTable(listOf("Public IP" to e.ip, "ISP" to e.isp, "AS" to e.asn, "Location" to e.location), title) }
        }

        if (m.photos.isNotEmpty()) {
            pdf.section("Photos", null, "${m.photos.size}", title, 160f)
            var x = pdf.M; var rowH = 0f
            for (name in m.photos) {
                val f = File(File(ctx.filesDir, "photos"), name)
                val bmp = runCatching { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 }) }.getOrNull() ?: continue
                val h = 170f; val w = bmp.width * h / bmp.height
                val ww = minOf(w, pdf.TW); val hh = h * ww / w
                if (x + ww > pdf.W - pdf.M) { x = pdf.M; pdf.y += rowH + 6; rowH = 0f }
                if (!pdf.room(hh)) { pdf.newPage(title); x = pdf.M; rowH = 0f }
                pdf.c.drawBitmap(bmp, null, RectF(x, pdf.y, x + ww, pdf.y + hh), Paint(Paint.FILTER_BITMAP_FLAG))
                x += ww + 6; rowH = maxOf(rowH, hh)
            }
            pdf.y += rowH + 8
        }

        if (m.note.isNotEmpty()) {
            pdf.section("Note", null, "", title, 20f)
            pdf.need(pdf.height(m.note, pdf.tx, pdf.TW), title)
            pdf.y += pdf.text(m.note, pdf.M + 4, pdf.y, pdf.tx, pdf.TW - 8) + 6
        }
    }

    fun measurementText(m: Measurement, projectName: String?): String {
        val sb = StringBuilder()
        sb.append("Pockethernet 2 test – ${m.testId} ${m.label}\n")
        projectName?.let { sb.append("Project: $it\n") }
        if (m.switchShown.isNotEmpty()) sb.append("Switch: ${m.switchShown}  Port: ${m.portShown}\n")
        sb.append("Date/time: ${dt.format(Date(m.ts))}\n")
        m.device?.let { sb.append("Device: $it\n") }
        sb.append("Result: ${verdict(m)}\n\n")
        for (n in m.items) {
            val it = n.item
            if (it.state == ItemState.SKIPPED && it.summary.isEmpty()) continue
            val mark = when (it.state) { ItemState.OK -> "✔"; ItemState.FAIL -> "✘"; ItemState.INFO -> "ℹ"; else -> "–" }
            sb.append("$mark ${n.name}: ${it.summary}\n")
            for ((k, v) in it.details) sb.append(if (v.isEmpty()) "    $k\n" else "    $k: $v\n")
        }
        if (m.note.isNotEmpty()) sb.append("\nNote: ${m.note}\n")
        return sb.toString()
    }
}
