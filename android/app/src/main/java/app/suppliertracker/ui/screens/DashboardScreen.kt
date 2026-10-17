@file:OptIn(ExperimentalLayoutApi::class)

package app.suppliertracker.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.suppliertracker.data.MIME_PPTX
import app.suppliertracker.data.shareBytes
import app.suppliertracker.data.shareFile
import app.suppliertracker.data.shareText
import app.suppliertracker.model.NO_REASON
import app.suppliertracker.model.ReportPeriod
import app.suppliertracker.model.Status
import app.suppliertracker.model.TrendWeek
import app.suppliertracker.model.VendorTrend
import app.suppliertracker.model.WITHIN_TOLERANCE
import app.suppliertracker.model.dispatchLogCsv
import app.suppliertracker.model.monthlySummaryCsv
import app.suppliertracker.model.niceDate
import app.suppliertracker.model.niceRange
import app.suppliertracker.model.num
import app.suppliertracker.model.pct
import app.suppliertracker.model.periodReport
import app.suppliertracker.model.shareSummary
import app.suppliertracker.model.signed
import app.suppliertracker.ui.AppViewModel
import app.suppliertracker.ui.DateRangeDialog
import app.suppliertracker.ui.Kpi
import app.suppliertracker.ui.Muted
import app.suppliertracker.ui.ProgressBars
import app.suppliertracker.ui.SectionCard
import app.suppliertracker.ui.theme.LocalStatusPalette
import kotlinx.coroutines.launch

/** Line colours for vendors in the trend chart (told apart by lightness too, not hue alone). */
private val LINES_LIGHT = listOf(Color(0xFF1F4E79), Color(0xFF6D28D9), Color(0xFF374151), Color(0xFF0F766E), Color(0xFF9A3412), Color(0xFFBE185D))
private val LINES_DARK = listOf(Color(0xFF9CC8F2), Color(0xFFC4B5FD), Color(0xFFE5E7EB), Color(0xFF5EEAD4), Color(0xFFFDBA74), Color(0xFFF9A8D4))

/** All vendors at a glance for a period: headline figures, 6-week trend, vendors, gap causes, the PowerPoint. */
@Composable
fun DashboardScreen(vm: AppViewModel, toast: (String) -> Unit, onOpenVendor: (String) -> Unit) {
    val data = vm.data
    val period = vm.period
    val r = remember(data, period) { periodReport(data, period) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pal = LocalStatusPalette.current
    var building by remember { mutableStateOf(false) }
    var pickingRange by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("Dashboard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Muted("${period.title} · as of ${niceDate(period.asOf, true)}. Tap a vendor for detail.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            ReportPeriod.Kind.entries.forEach { k ->
                FilterChip(
                    selected = vm.reportKind == k,
                    onClick = { if (k == ReportPeriod.Kind.CUSTOM) pickingRange = true else vm.updateReportKind(k) },
                    label = { Text(if (k == ReportPeriod.Kind.CUSTOM && vm.reportKind == k) niceRange(period.from, period.to) else k.label) },
                )
            }
        }

        // headline figures
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Kpi(
                "Overall adherence", pct(r.adherence), "${num(r.delivered)} of ${num(r.reqToDate)} needed", Modifier.weight(1f),
                valueColor = pal.fg(r.status), container = if (r.status in JUDGED) pal.bg(r.status) else null,
            )
            Kpi(
                "Short to date", num(r.shortToDate), "pcs · ${r.partsShort} of ${r.partsInPlay} parts short", Modifier.weight(1f),
                valueColor = if (r.shortToDate >= 0.5) pal.bad else Color.Unspecified, container = if (r.shortToDate >= 0.5) pal.badBg else null,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            val onSchedule = r.vendors.count { it.status == Status.ON_TRACK || it.status == Status.AHEAD }
            Kpi("Vendors on schedule", "$onSchedule of ${r.vendors.size}", "${r.vendors.count { it.status == Status.BEHIND }} behind", Modifier.weight(1f))
            Kpi("Need per day", num(r.vendors.sumOf { it.needPerDay }), "to close the period", Modifier.weight(1f))
        }

        SectionCard(title = "Week-on-week adherence") {
            TrendChart(r.weeks, r.trend)
            Muted("Each week: dispatch ÷ that week's plan. Green ≥ 95%, amber 85–95%, red below.", Modifier.padding(top = 4.dp))
        }

        SectionCard(title = "Vendors") {
            if (r.vendors.isEmpty()) Muted("No vendors yet.")
            r.vendors.sortedByDescending { it.status.rank }.forEachIndexed { i, v ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                val dir = r.trend.firstOrNull { it.name == v.vendor.name }?.direction ?: 0
                Column(Modifier.fillMaxWidth().clickable { onOpenVendor(v.vendor.id) }.padding(vertical = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(v.vendor.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            pct(v.adherence) + when (dir) { -1 -> " ▼"; 1 -> " ▲"; else -> "" },
                            color = pal.fg(v.status), fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    ProgressBars(v.reqToDate, v.delivered, maxOf(v.reqToDate, v.delivered), v.status)
                    val worst = v.parts.filter { it.isShort && it.short >= 0.5 }.sortedByDescending { it.short }
                    Muted(
                        when {
                            v.partsInPlay == 0 -> "Nothing to deliver in this period"
                            worst.isEmpty() -> "All parts on schedule"
                            else -> "${worst.size} of ${v.partsInPlay} parts short · " + worst.take(2).joinToString(", ") { "${it.part.name} ${signed(-it.short)}" }
                        },
                        Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        val causes = r.causes
        if (causes.isNotEmpty()) {
            SectionCard(title = "Why parts are short") {
                val total = causes.sumOf { it.short }
                val max = causes.maxOf { it.short }
                causes.take(5).forEach { c ->
                    val muted = c.cause == NO_REASON || c.cause == WITHIN_TOLERANCE
                    Text(
                        c.cause, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
                        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).height(12.dp)) {
                            Box(
                                Modifier.fillMaxWidth((c.short / max).toFloat().coerceIn(0.02f, 1f)).height(12.dp)
                                    .background(if (muted) pal.neutral else MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
                            )
                        }
                        Text(
                            "${pct(c.short / total)} · ${num(c.short)}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Muted("Share of ${num(total)} pcs short, by each part's latest reason.", Modifier.padding(top = 4.dp))
            }
        }

        SectionCard(title = "PowerPoint report") {
            Muted("${period.title} · as of ${niceDate(period.asOf, true)} · ${vm.templateLabel}")
            Button(
                onClick = {
                    building = true
                    scope.launch {
                        try {
                            val bytes = vm.buildReport(period)
                            shareBytes(ctx, "Supplier-Dispatch-Review-${period.fileTag}.pptx", bytes, MIME_PPTX, "Share report")
                        } catch (e: Exception) {
                            toast(e.message ?: "Could not build the report.")
                        } finally {
                            building = false
                        }
                    }
                },
                enabled = !building,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                if (building) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Build report (PowerPoint)")
            }
            OutlinedButton(onClick = { shareText(ctx, shareSummary(data, data.vendors, vm.date), "Share dispatch status") }, modifier = Modifier.fillMaxWidth()) {
                Text("Share today's status as text (WhatsApp)")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    shareFile(ctx, "summary-all-${vm.date}.csv", monthlySummaryCsv(data, data.vendors, vm.date), "text/csv", "Share summary CSV")
                }, modifier = Modifier.weight(1f)) { Text("Summary CSV") }
                OutlinedButton(onClick = {
                    shareFile(ctx, "dispatch-all-${vm.date}.csv", dispatchLogCsv(data, data.vendors, vm.date), "text/csv", "Share dispatch log")
                }, modifier = Modifier.weight(1f)) { Text("Dispatch log CSV") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (pickingRange) {
        DateRangeDialog(vm.date, "Report period: first and last day", onDismiss = { pickingRange = false }) { a, b ->
            pickingRange = false
            vm.updateCustomRange(a, b)
        }
    }
}

private val JUDGED = setOf(Status.ON_TRACK, Status.AHEAD, Status.WATCH, Status.BEHIND)

/** Weekly adherence lines over green / amber / red bands. */
@Composable
private fun TrendChart(weeks: List<TrendWeek>, rows: List<VendorTrend>) {
    val pal = LocalStatusPalette.current
    val dark = MaterialTheme.colorScheme.background.let { it.red + it.green + it.blue < 1.5f }
    val lines = if (dark) LINES_DARK else LINES_LIGHT
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val values = rows.flatMap { r -> r.cells.mapNotNull { it.adherence } }
    val lo = minOf(0.75, (values.minOrNull() ?: 1.0) - 0.05).coerceAtLeast(0.0)
    val hi = maxOf(1.0, (values.maxOrNull() ?: 1.0) + 0.02).coerceAtMost(1.5)
    val description = rows.joinToString(". ") { r -> r.name + ": " + r.cells.joinToString(", ") { pct(it.adherence) } }
    Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = "Weekly adherence. $description" }) {
        val left = 36.dp.toPx()
        val right = size.width - 6.dp.toPx()
        val top = 6.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
        fun band(a: Double, b: Double, c: Color) {
            val y1 = y(minOf(b, hi))
            val y2 = y(maxOf(a, lo))
            if (y2 > y1) drawRect(c, Offset(left, y1), Size(right - left, y2 - y1))
        }
        band(0.95, hi, pal.okBg)
        band(0.85, 0.95, pal.warnBg)
        band(lo, 0.85, pal.badBg)
        val small = TextStyle(fontSize = 10.sp, color = label)
        for (v in listOf(1.0, 0.95, 0.85).filter { it in lo..hi }) {
            drawText(measurer, pct(v), Offset(0f, y(v) - 7.dp.toPx()), style = small)
        }
        val n = weeks.size
        fun x(i: Int) = if (n <= 1) left else left + (right - left) * i / (n - 1)
        weeks.forEachIndexed { i, w ->
            val t = measurer.measure(w.label, small)
            val cx = (x(i) - t.size.width / 2f).coerceIn(left - 10.dp.toPx(), size.width - t.size.width)
            drawText(t, topLeft = Offset(cx, bottom + 4.dp.toPx()))
        }
        rows.forEachIndexed { ri, row ->
            val c = lines[ri % lines.size]
            var path: Path? = null
            var last: Offset? = null
            row.cells.forEachIndexed { i, cell ->
                val a = cell.adherence?.coerceIn(lo, hi)
                if (a == null) {
                    path?.let { drawPath(it, c, style = Stroke(width = 2.5.dp.toPx())) }
                    path = null
                } else {
                    val p = Offset(x(i), y(a))
                    path = (path ?: Path().apply { moveTo(p.x, p.y) }).apply { lineTo(p.x, p.y) }
                    last = p
                }
            }
            path?.let { drawPath(it, c, style = Stroke(width = 2.5.dp.toPx())) }
            last?.let { drawCircle(c, 4.dp.toPx(), it) }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
        rows.forEachIndexed { ri, row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(16.dp).height(3.dp).background(lines[ri % lines.size]))
                Text(" ${row.name} ${pct(row.cells.lastOrNull { it.adherence != null }?.adherence)}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
