package app.suppliertracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import app.suppliertracker.model.Issue
import app.suppliertracker.model.PartRow
import app.suppliertracker.model.good
import app.suppliertracker.model.needsAction
import app.suppliertracker.model.vendorAdherence
import app.suppliertracker.ui.AdherenceBar
import app.suppliertracker.ui.Badge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.suppliertracker.data.openMaps
import app.suppliertracker.data.shareFile
import app.suppliertracker.data.shareText
import app.suppliertracker.model.Data
import app.suppliertracker.model.GAP_CAUSES
import app.suppliertracker.model.Part
import app.suppliertracker.model.Status
import app.suppliertracker.model.Vendor
import app.suppliertracker.model.dispatchLogCsv
import app.suppliertracker.model.monthRecord
import app.suppliertracker.model.monthlySummaryCsv
import app.suppliertracker.model.niceDate
import app.suppliertracker.model.niceMonth
import app.suppliertracker.model.num
import app.suppliertracker.model.overdue
import app.suppliertracker.model.pastMonths
import app.suppliertracker.model.pct
import app.suppliertracker.model.periodOf
import app.suppliertracker.model.shareSummary
import app.suppliertracker.model.status
import app.suppliertracker.model.vendorSummary
import app.suppliertracker.ui.AppViewModel
import app.suppliertracker.ui.DateDialog
import app.suppliertracker.ui.Kpi
import app.suppliertracker.ui.Muted
import app.suppliertracker.ui.SectionCard
import app.suppliertracker.ui.StatusPill
import app.suppliertracker.ui.theme.LocalStatusPalette
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun StatusScreen(vm: AppViewModel, onOpenPart: (String) -> Unit = {}) {
    val v = vm.vendor ?: return
    val data = vm.data
    val d = vm.date
    val s = vendorSummary(data, v, d)
    val a = vendorAdherence(s)
    val p = periodOf(d)
    val pal = LocalStatusPalette.current
    val ctx = LocalContext.current
    var reasonFor by remember(v.id) { mutableStateOf<String?>(null) }
    var editingReason by remember(v.id) { mutableStateOf<String?>(null) }
    var filter by rememberSaveable(v.id) { mutableStateOf(PartFilter.ALL) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(v.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            StatusPill(s.status)
        }
        Muted("As of ${niceDate(d, true)} · ${p.label} (${niceDate(p.start)}–${niceDate(p.end)})")
        Spacer(Modifier.height(8.dp))

        @Composable
        fun AdhKpi(label: String, r: Double?, st: Status, sub: String, modifier: Modifier) {
            val judged = st in setOf(Status.ON_TRACK, Status.WATCH, Status.BEHIND, Status.AHEAD)
            Kpi(label, if (st == Status.NOT_ENTERED) "–" else pct(r), sub, modifier,
                valueColor = if (judged) pal.fg(st) else androidx.compose.ui.graphics.Color.Unspecified,
                container = if (judged) pal.bg(st) else null)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AdhKpi("Daily adherence", a.day, a.dayStatus,
                when {
                    s.todayPlan < 0.5 -> "no plan today"
                    !s.todayEntered -> "today not entered"
                    else -> "${num(s.todayActual)} of ${num(s.todayPlan)} today"
                }, Modifier.weight(1f))
            AdhKpi("Weekly adherence", a.week, a.weekStatus,
                if (s.wtdPlan < 0.5) "no plan this week" else "${num(s.wtdActual)} of ${num(s.wtdPlan)} · ${p.label}", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AdhKpi("Monthly adherence", a.month, a.monthStatus,
                if (s.mtdRequirement < 0.5) "no plan this month" else "${num(s.mtdActual)} of ${num(s.mtdRequirement)} needed", Modifier.weight(1f))
            // coloured by the worst critical part (amber stays amber); parts with nothing typed yet are not "behind"
            val critSt = when {
                a.critical == 0 -> Status.NO_PLAN
                a.criticalWorst != null -> a.criticalWorst
                a.criticalNotEntered > 0 -> Status.NOT_ENTERED
                else -> Status.ON_TRACK
            }
            Kpi(
                "Critical parts",
                when {
                    a.critical == 0 || critSt == Status.NOT_ENTERED -> "–"
                    a.criticalBad == 0 -> "All OK"
                    else -> "${a.criticalBad} of ${a.critical}"
                },
                when {
                    a.critical == 0 -> "none marked in Setup"
                    critSt == Status.NOT_ENTERED -> "${a.criticalNotEntered} not entered yet"
                    a.criticalBad == 0 -> "${a.critical} on schedule"
                    else -> if (critSt == Status.BEHIND) "behind schedule" else "slightly behind"
                },
                Modifier.weight(1f).clickable(enabled = a.critical > 0) { filter = PartFilter.CRITICAL },
                valueColor = if (critSt in setOf(Status.NO_PLAN, Status.NOT_ENTERED)) androidx.compose.ui.graphics.Color.Unspecified else pal.fg(critSt),
                container = if (critSt in setOf(Status.NO_PLAN, Status.NOT_ENTERED)) null else pal.bg(critSt),
            )
        }

        // ---- Parts details: a header band of its own, then one card per part ----
        val rows = s.parts.sortedWith(compareBy<PartRow> { it.m.mtdGap }.thenByDescending { it.m.monthStatus.rank })
        val shownRows = rows.filter { filter.keep(it) }
        Column(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text("Parts details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "Worst first by month to date. Tap a part for its calendar, weeks and months.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PartFilter.entries.forEach { f ->
                    val n = rows.count { f.keep(it) }
                    if (f == PartFilter.CRITICAL && n == 0) return@forEach
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text("${f.label} $n") },
                        leadingIcon = f.dot?.let { st -> { Box(Modifier.size(10.dp).background(pal.fill(st), CircleShape)) } },
                        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    )
                }
            }
        }
        if (rows.isEmpty()) SectionCard { Muted("No parts yet.") }
        else if (shownRows.isEmpty()) SectionCard { Muted(filter.empty) }

        shownRows.forEach { (part, m) ->
            val open = data.issues.filter { it.status == "open" && it.vendorId == v.id && it.partId == part.id }
            SectionCard {
                Column(Modifier.fillMaxWidth().clickable { onOpenPart(part.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(part.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (part.partNo.isNotBlank() && part.desc.isNotBlank()) Muted(part.partNo)
                        }
                        if (part.critical) Badge("Critical")
                        Spacer(Modifier.width(6.dp))
                        StatusPill(m.monthStatus)
                    }
                    Spacer(Modifier.height(6.dp))
                    val today = m.todayActual
                    AdherenceBar("Day", m.todayPlan, today ?: 0.0, status(m.todayPlan, today ?: 0.0, today != null), entered = today != null || m.todayPlan < 0.5)
                    AdherenceBar("Week", m.wtdPlan, m.wtdActual, m.weekStatus, entered = m.weekStatus != Status.NOT_ENTERED)
                    AdherenceBar("Month", m.mtdRequirement, m.mtdActual, m.monthStatus, entered = m.monthStatus != Status.NOT_ENTERED)
                    val extra = listOfNotNull(
                        m.openingBacklog.takeIf { it >= 0.5 }?.let { "backlog in ${num(it)}" },
                        m.monthBalance.takeIf { it >= 0.5 }?.let { "${num(it)} still to send this month" },
                        m.monthReqPerDay.takeIf { it >= 0.5 }?.let { "need ${num(it)}/day" },
                    )
                    if (extra.isNotEmpty()) Muted(extra.joinToString(" · ").replaceFirstChar { it.uppercase() }, Modifier.padding(top = 2.dp))
                }
                open.forEach { i ->
                    if (editingReason == i.id) {
                        ReasonForm(
                            initial = i,
                            onCancel = { editingReason = null },
                        ) { cause, action, owner, due ->
                            vm.editReason(i.id, cause, action, owner, due)
                            editingReason = null
                        }
                        return@forEach
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Reason: ${i.cause}",
                                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                                color = if (i.overdue(d)) pal.bad else MaterialTheme.colorScheme.onSurface,
                            )
                            val detail = listOf(i.action, i.owner, i.due.takeIf { it.isNotBlank() }?.let { "due " + niceDate(LocalDate.parse(it)) + if (i.overdue(d)) " (overdue)" else "" })
                                .filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
                            if (detail.isNotBlank()) Muted(detail)
                            i.logged.takeIf { it.isNotBlank() }?.let { Muted("Added ${runCatching { niceDate(LocalDate.parse(it), true) }.getOrDefault(it)}") }
                        }
                        TextButton(onClick = { editingReason = i.id }) { Text("Edit") }
                        TextButton(onClick = { vm.closeReason(i.id) }) { Text("Close") }
                    }
                }
                if (reasonFor == part.id) {
                    ReasonForm(onCancel = { reasonFor = null }) { cause, action, owner, due ->
                        vm.addReason(v, part.id, cause, action, owner, due)
                        reasonFor = null
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { reasonFor = part.id }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            Text(if (open.isEmpty()) "+ Reason" else "+ Another reason")
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onOpenPart(part.id) }) { Text("Calendar ›") }
                    }
                }
            }
        }

        SectionCard(title = "Notes") {
            if (v.notes.isNotBlank()) GeneralNote(vm, v)
            NotesSection(vm, v, partId = null, day = d)
        }
        PastMonths(data, v, YearMonth.from(d))
        VendorDetails(v)

        SectionCard(title = "Share") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { shareText(ctx, shareSummary(data, listOf(v), d), "Share ${v.name} status") }, modifier = Modifier.weight(1f)) {
                    Text("Status text")
                }
                OutlinedButton(onClick = {
                    shareFile(ctx, "summary-${v.id}-$d.csv", monthlySummaryCsv(data, listOf(v), d), "text/csv", "Share summary CSV")
                }, modifier = Modifier.weight(1f)) { Text("Summary CSV") }
            }
            OutlinedButton(onClick = {
                shareFile(ctx, "dispatch-${v.id}-$d.csv", dispatchLogCsv(data, listOf(v), d), "text/csv", "Share dispatch log")
            }, modifier = Modifier.fillMaxWidth()) { Text("Dispatch log CSV (this month)") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The filter chips over Parts details: by the month-to-date status the list is sorted on. */
private enum class PartFilter(val label: String, val dot: Status?, val empty: String) {
    ALL("All", null, "No parts."),
    BAD("Bad", Status.BEHIND, "No part is behind. Well done."),
    GOOD("Good", Status.ON_TRACK, "No part is on schedule yet."),
    CRITICAL("Critical", null, "No critical parts.");

    fun keep(r: PartRow): Boolean = when (this) {
        ALL -> true
        BAD -> r.m.monthStatus.needsAction
        GOOD -> r.m.monthStatus.good
        CRITICAL -> r.part.critical
    }
}

/** The vendor's free-text notes from Setup, editable here too. */
@Composable
private fun GeneralNote(vm: AppViewModel, v: Vendor) {
    var editing by rememberSaveable(v.id) { mutableStateOf(false) }
    var text by rememberSaveable(v.id, editing) { mutableStateOf(v.notes) }
    Muted("General")
    if (editing) {
        OutlinedTextField(text, { text = it }, minLines = 2, maxLines = 8, modifier = Modifier.fillMaxWidth())
        Row {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { editing = false }) { Text("Cancel") }
            TextButton(onClick = { vm.updateVendor(v.copy(notes = text.trim())); editing = false }) { Text("Save") }
        }
    } else {
        Row(verticalAlignment = Alignment.Top) {
            Text(v.notes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(top = 6.dp))
            TextButton(onClick = { editing = true }) { Text("Edit") }
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
}

/** Closed months: plan vs dispatched per month, from daily tracking or the supplier's closing report. */
@Composable
private fun PastMonths(data: Data, v: Vendor, current: YearMonth) {
    val months = pastMonths(data, v, current)
    if (months.isEmpty()) return
    var open by remember(v.id) { mutableStateOf<YearMonth?>(null) }
    SectionCard(title = "Past months") {
        months.forEachIndexed { i, ym ->
            val recs = v.activeParts.map { it to monthRecord(data, v, it.id, ym) }
            val plan = recs.mapNotNull { it.second.plan }.takeIf { it.isNotEmpty() }?.sum()
            val act = recs.mapNotNull { it.second.actual }.takeIf { it.isNotEmpty() }?.sum()
            val short = recs.mapNotNull { it.second.short }.takeIf { it.isNotEmpty() }?.sum()
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(Modifier.fillMaxWidth().clickable { open = if (open == ym) null else ym }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(niceMonth(ym), fontWeight = FontWeight.SemiBold)
                    Muted(
                        when {
                            plan != null && act != null -> "${num(act)} of ${num(plan)} dispatched · short ${num(short)}"
                            act != null -> "${num(act)} dispatched · plan not available"
                            else -> "plan ${num(plan)} · dispatch not available"
                        },
                    )
                }
                if (plan != null && act != null) StatusPill(status(plan, act, true))
                TextButton(onClick = { open = if (open == ym) null else ym }) { Text(if (open == ym) "Hide" else "Parts") }
            }
            if (open == ym) {
                recs.forEach { (part, r) ->
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(part.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (part.partNo.isNotBlank() && part.desc.isNotBlank()) Muted(part.partNo)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${num(r.actual)} / ${num(r.plan)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Muted(if (r.short != null) "${pct(r.adherence)} · short ${num(r.short)}" else "–")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VendorDetails(v: Vendor) {
    if (v.contact.isBlank() && v.address.isBlank()) return
    val ctx = LocalContext.current
    SectionCard(title = "Vendor details") {
        if (v.contact.isNotBlank()) {
            Muted("Contact")
            Text(v.contact)
            Spacer(Modifier.height(8.dp))
        }
        if (v.address.isNotBlank()) {
            Muted("Address")
            Text(v.address)
            TextButton(onClick = { openMaps(ctx, v.address) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("Open in Maps")
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** Why a part is behind, shown under the part: cause from the standard list, the action, who owns it and by when. */
@Composable
private fun ReasonForm(initial: Issue? = null, onCancel: () -> Unit, onSave: (String, String, String, LocalDate?) -> Unit) {
    var cause by remember { mutableStateOf(initial?.cause?.takeIf { it.isNotBlank() } ?: GAP_CAUSES.first()) }
    var causeOpen by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf(initial?.action.orEmpty()) }
    var owner by remember { mutableStateOf(initial?.owner.orEmpty()) }
    var due by remember { mutableStateOf(initial?.due?.let { runCatching { LocalDate.parse(it) }.getOrNull() }) }
    var picking by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Muted("Gap cause")
        Box {
            OutlinedButton(onClick = { causeOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(cause, modifier = Modifier.weight(1f))
                Text("▾")
            }
            DropdownMenu(expanded = causeOpen, onDismissRequest = { causeOpen = false }) {
                GAP_CAUSES.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { cause = c; causeOpen = false }) }
            }
        }
        OutlinedTextField(action, { action = it }, label = { Text("Action") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(owner, { owner = it }, label = { Text("Owner") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) { Text(due?.let { "Due ${niceDate(it, true)}" } ?: "+ Due date") }
            TextButton(onClick = onCancel) { Text("Cancel") }
            androidx.compose.material3.Button(onClick = { onSave(cause, action, owner, due) }) { Text(if (initial == null) "Save reason" else "Save") }
        }
    }
    if (picking) DateDialog(due ?: LocalDate.now().plusDays(3), onDismiss = { picking = false }, onPick = { due = it; picking = false })
}
