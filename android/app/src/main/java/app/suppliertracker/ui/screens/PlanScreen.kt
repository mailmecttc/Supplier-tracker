@file:OptIn(ExperimentalMaterial3Api::class)

package app.suppliertracker.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.suppliertracker.model.autoBacklog
import app.suppliertracker.model.monthPlan
import app.suppliertracker.model.monthPlanOrNull
import app.suppliertracker.model.openingBacklog
import app.suppliertracker.model.splitMonthPlan
import app.suppliertracker.model.niceDate
import app.suppliertracker.model.niceMonth
import app.suppliertracker.model.num
import app.suppliertracker.model.periodOf
import app.suppliertracker.model.periodsOfMonth
import app.suppliertracker.model.pkey
import app.suppliertracker.model.replan
import app.suppliertracker.model.signed
import app.suppliertracker.ui.AppViewModel
import app.suppliertracker.ui.CommitNumberField
import app.suppliertracker.ui.Muted
import app.suppliertracker.ui.SectionCard
import java.time.YearMonth

@Composable
fun PlanScreen(vm: AppViewModel, toast: (String) -> Unit) {
    val v = vm.vendor ?: return
    val data = vm.data
    var ym by remember { mutableStateOf(YearMonth.from(vm.date)) }
    val periods = periodsOfMonth(ym)
    var sel by remember(ym) {
        mutableStateOf(periods.firstOrNull { !vm.date.isBefore(it.start) && !vm.date.isAfter(it.end) } ?: periods.first())
    }
    val parts = v.activeParts

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { ym = ym.minusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
            Text(niceMonth(ym), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { ym = ym.plusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month") }
        }
        // how the plan is typed: one total for the month (split into weeks), or week by week
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            SegmentedButton(selected = vm.planByMonth, onClick = { vm.updatePlanByMonth(true) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Whole month") }
            SegmentedButton(selected = !vm.planByMonth, onClick = { vm.updatePlanByMonth(false) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Week by week") }
        }

        if (vm.planByMonth) {
            val month = ym
            val started = periods.any { it.start < vm.date }
            SectionCard(title = "Month plan · ${niceMonth(month)}") {
                Muted(
                    "Type each part's plan for the whole month. It is split into the weeks by ${v.name}'s dispatch days " +
                        "(holidays and days off left out)." +
                        if (started) " A change goes to the weeks to come: weeks already under way (✓) keep their plan, so past days never change." else "",
                )
                if (parts.isEmpty()) Muted("No parts yet.")
                parts.forEachIndexed { i, p ->
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    val weeks = periods.map { it to data.plan[pkey(v.id, p.id, it.start.toString())] }
                    val total = monthPlanOrNull(data, v.id, p.id, month)
                    val kept = splitMonthPlan(data, v, p.id, month, 0.0, vm.date).keptWeeks
                    val original = weeks.sumOf { (per, q) -> if (q == null) 0.0 else data.planOrig[pkey(v.id, p.id, per.start.toString())] ?: q }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (p.partNo.isNotBlank() && p.desc.isNotBlank()) Muted(p.partNo)
                            if (total != null && kotlin.math.abs(original - total) >= 0.5) {
                                Text(
                                    "Original ${num(original)}, re-planned ${signed(total - original)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        // a note means the typed total was not taken as typed: show the saved one again
                        var shown by remember(v.id, p.id, month) { mutableIntStateOf(0) }
                        key(v.id, p.id, month, shown) {
                            CommitNumberField(
                                value = total,
                                onCommit = { x -> vm.setMonthPlan(v, p.id, month, x)?.let { toast(it); shown++ } },
                                modifier = Modifier.width(140.dp),
                                placeholder = "–",
                                last = i == parts.lastIndex,
                            )
                        }
                    }
                    if (total != null) {
                        // non-breaking spaces keep each week and its quantity together when the line wraps
                        Muted(
                            weeks.joinToString("  ·  ") { (per, q) -> "${per.label}\u00A0${num(q ?: 0.0)}" + if (per.start in kept) "\u00A0✓" else "" },
                            Modifier.padding(top = 2.dp),
                        )
                        // the carried backlog comes on top of the plan; it is not typed into it
                        val backlog = openingBacklog(data, v, p.id, month)
                        if (backlog >= 0.5) Muted("Backlog ${num(backlog)} + plan ${num(total)} = ${num(backlog + total)} to deliver in ${niceMonth(month)}")
                    }
                }
                Muted("✓ = week under way, its plan stays. To change one week only, switch to Week by week.", Modifier.padding(top = 8.dp))
            }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                periods.forEach { p ->
                    FilterChip(
                        selected = p == sel,
                        onClick = { sel = p },
                        label = { Text("${p.label}  ${p.start.dayOfMonth}–${p.end.dayOfMonth}") },
                    )
                }
            }

            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${sel.label} weekly plan", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Muted("${niceDate(sel.start, true)} – ${niceDate(sel.end, true)}")
                    }
                    TextButton(onClick = {
                        val n = vm.copyPreviousWeek(v, sel)
                        toast(if (n > 0) "Copied $n part plans from ${periodOf(sel.start.minusDays(1)).label}" else "No plan in the previous week")
                    }) { Text("Copy last week") }
                }
                if (parts.isEmpty()) Muted("No parts yet.")
                parts.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    // fixed per box: a value still being typed is saved to this week even if another week is tapped
                    val start = sel.start
                    val k = pkey(v.id, p.id, start.toString())
                    val rp = replan(data, v.id, p.id, start)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (p.partNo.isNotBlank() && p.desc.isNotBlank()) Muted(p.partNo)
                            Muted("Month ${num(monthPlan(data, v.id, p.id, ym))}")
                            if (rp != null) {
                                Text(
                                    "Original ${num(rp.original)}, re-planned ${signed(rp.diff)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        key(k) {
                            CommitNumberField(
                                value = data.plan[k],
                                onCommit = { vm.setPlan(v, p.id, start, it) },
                                modifier = Modifier.width(140.dp),
                                placeholder = "–",
                                last = i == parts.lastIndex,
                            )
                        }
                    }
                }
            }

        }

        SectionCard(title = "Opening backlog · ${niceMonth(ym)}") {
            Muted("Pending carried in from last month. Leave blank to carry it forward automatically.")
            Spacer(Modifier.height(4.dp))
            parts.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                val month = ym
                val k = pkey(v.id, p.id, month.toString())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p.partNo.isNotBlank() && p.desc.isNotBlank()) Muted(p.partNo)
                    }
                    key(k) {
                        CommitNumberField(
                            value = data.openingBacklog[k],
                            onCommit = { vm.setBacklog(v, p.id, month, it) },
                            modifier = Modifier.width(140.dp),
                            placeholder = "auto ${num(autoBacklog(data, v, p.id, ym))}",
                            last = i == parts.lastIndex,
                        )
                    }
                }
            }
        }

        SectionCard(title = "Weeks at a glance · ${niceMonth(ym)}") {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Column {
                    Row {
                        Text("Part", Modifier.width(150.dp), style = MaterialTheme.typography.labelMedium)
                        periods.forEach { Text(it.label, Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium) }
                        Text("Total", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    parts.forEach { p ->
                        Spacer(Modifier.height(6.dp))
                        Row {
                            Text(p.name, Modifier.width(150.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            periods.forEach { per ->
                                Text(num(data.plan[pkey(v.id, p.id, per.start.toString())]), Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
                            }
                            Text(num(monthPlan(data, v.id, p.id, ym)), Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
