@file:OptIn(ExperimentalMaterial3Api::class)

package app.suppliertracker.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.suppliertracker.model.DayCell
import app.suppliertracker.model.Part
import app.suppliertracker.model.Status
import app.suppliertracker.model.Vendor
import app.suppliertracker.model.monthDays
import app.suppliertracker.model.monthWeeks
import app.suppliertracker.model.niceDate
import app.suppliertracker.model.niceMonth
import app.suppliertracker.model.num
import app.suppliertracker.model.partMetrics
import app.suppliertracker.model.pkey
import app.suppliertracker.model.recentMonths
import app.suppliertracker.model.signed
import app.suppliertracker.model.status
import app.suppliertracker.ui.AdherenceBar
import app.suppliertracker.ui.AppViewModel
import app.suppliertracker.ui.Badge
import app.suppliertracker.ui.Muted
import app.suppliertracker.ui.SectionCard
import app.suppliertracker.ui.StatusPill
import app.suppliertracker.ui.theme.LocalStatusPalette
import java.time.LocalDate
import java.time.YearMonth

private enum class PartView(val label: String) { DAILY("Calendar"), WEEKLY("Weekly"), MONTHLY("Monthly") }

/** One part of one vendor: the month as a calendar (dispatch against plan each day), its weeks and recent months. */
@Composable
fun PartScreen(vm: AppViewModel, v: Vendor, part: Part, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val data = vm.data
    val asOf = vm.date
    var view by rememberSaveable(part.id) { mutableStateOf(PartView.DAILY) }
    var month by rememberSaveable(part.id) { mutableStateOf(YearMonth.from(asOf).toString()) }
    var picked by rememberSaveable(part.id) { mutableStateOf(asOf.toString()) }
    val ym = YearMonth.parse(month)
    val m = partMetrics(data, v, part.id, asOf)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Column(Modifier.weight(1f)) {
                    Text(part.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted(listOf(part.partNo.takeIf { part.desc.isNotBlank() }, v.name).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "))
                }
                if (part.critical) Badge("Critical")
                Spacer(Modifier.size(12.dp))
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("As of ${niceDate(asOf, true)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        StatusPill(m.monthStatus)
                    }
                    Spacer(Modifier.height(4.dp))
                    val today = m.todayActual
                    AdherenceBar("Day", m.todayPlan, today ?: 0.0, status(m.todayPlan, today ?: 0.0, today != null), entered = today != null || m.todayPlan < 0.5)
                    AdherenceBar("Week", m.wtdPlan, m.wtdActual, m.weekStatus, entered = m.weekStatus != Status.NOT_ENTERED)
                    AdherenceBar("Month", m.mtdRequirement, m.mtdActual, m.monthStatus, entered = m.monthStatus != Status.NOT_ENTERED)
                    Muted("Day: today's plan. Week: plan to date. Month: backlog in + plan to date.", Modifier.padding(top = 2.dp))
                }

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    PartView.entries.forEachIndexed { i, pv ->
                        SegmentedButton(selected = view == pv, onClick = { view = pv }, shape = SegmentedButtonDefaults.itemShape(i, PartView.entries.size)) { Text(pv.label) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = ym.minusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
                    Text(
                        if (view == PartView.MONTHLY) "6 months to ${niceMonth(ym)}" else niceMonth(ym),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { month = ym.plusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month") }
                }

                when (view) {
                    PartView.DAILY -> {
                        val day = LocalDate.parse(picked).takeIf { YearMonth.from(it) == ym }
                        Calendar(vm, v, part, ym, asOf, day) { picked = it.toString() }
                        Legend()
                        if (day != null) DayDetail(vm, v, part, day, asOf)
                        else Muted("Tap a day to see its plan, dispatch and notes.", Modifier.padding(8.dp))
                    }
                    PartView.WEEKLY -> Weekly(vm, v, part, ym, asOf)
                    PartView.MONTHLY -> Monthly(vm, v, part, ym, asOf)
                }

                SectionCard(title = "Notes on ${part.name}") {
                    val day = LocalDate.parse(picked)
                    NotesSection(vm, v, partId = part.id, day = day)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Mon–Sun grid of the month: each day shows what was dispatched over its plan, coloured like the Entry box. */
@Composable
private fun Calendar(vm: AppViewModel, v: Vendor, part: Part, ym: YearMonth, asOf: LocalDate, picked: LocalDate?, onPick: (LocalDate) -> Unit) {
    val pal = LocalStatusPalette.current
    val cs = MaterialTheme.colorScheme
    val days = monthDays(vm.data, v, part.id, ym)
    val noted = vm.data.notes.filter { it.vendorId == v.id && it.partId == part.id }.map { it.date }.toSet()
    val lead = ym.atDay(1).dayOfWeek.value - 1
    val density = LocalDensity.current
    val cells: List<DayCell?> = List(lead) { null } + days + List((7 - (lead + days.size) % 7) % 7) { null }
    SectionCard {
        CompositionLocalProvider(LocalDensity provides Density(density.density, minOf(density.fontScale, 1.1f))) {
        Row {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(4.dp))
        cells.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(vertical = 1.5.dp)) {
                week.forEach { c ->
                    Box(Modifier.weight(1f).aspectRatio(0.78f)) {
                        if (c != null) {
                            val future = c.date > asOf
                            val st = if (future) Status.NO_PLAN else c.status
                            val judged = st in setOf(Status.ON_TRACK, Status.WATCH, Status.BEHIND, Status.AHEAD)
                            val bg = when {
                                judged -> pal.bg(st)
                                !c.work -> Color.Transparent
                                else -> cs.surfaceContainerHigh
                            }
                            val ring = when {
                                c.date == picked -> cs.primary
                                judged -> pal.fill(st).copy(alpha = 0.55f)
                                else -> Color.Transparent
                            }
                            Column(
                                Modifier.fillMaxSize()
                                    .background(bg, RoundedCornerShape(8.dp))
                                    .border(if (c.date == picked) 2.dp else 1.dp, ring, RoundedCornerShape(8.dp))
                                    .clickable { onPick(c.date) }
                                    .semantics { contentDescription = "${niceDate(c.date)}: dispatched ${num(c.actual)}, plan ${num(c.plan)}" }
                                    .padding(horizontal = 3.dp, vertical = 2.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${c.date.dayOfMonth}", style = tight(11), fontWeight = if (c.date == asOf) FontWeight.Bold else FontWeight.Normal,
                                        color = if (c.work) cs.onSurface else cs.onSurfaceVariant, modifier = Modifier.weight(1f),
                                    )
                                    if (c.date.toString() in noted) Box(Modifier.size(5.dp).background(cs.primary, CircleShape))
                                }
                                Spacer(Modifier.weight(1f))
                                when {
                                    c.holiday && c.actual == null -> Text("off", style = tight(9), color = cs.onSurfaceVariant)
                                    c.actual != null -> Text(short(c.actual), style = tight(12), fontWeight = FontWeight.Bold, color = if (judged) pal.fg(st) else cs.onSurface, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                    else -> Text(if (c.plan >= 0.5 && !future) "–" else "", style = tight(12), color = cs.onSurfaceVariant)
                                }
                                if (c.plan >= 0.5) Text("/${short(c.plan)}", style = tight(9), color = cs.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

/** A calendar figure that fits a narrow cell: 9,850 stays, 12,500 becomes 12.5k (the day's full figures are below). */
private fun short(x: Double): String = when {
    kotlin.math.abs(x) < 10_000 -> num(x)
    kotlin.math.abs(x) < 100_000 -> "%.1fk".format(java.util.Locale.ENGLISH, x / 1000).replace(".0k", "k")
    else -> "${Math.round(x / 1000)}k"
}

/** Small text with a line height to match, so three lines fit in a calendar cell. */
private fun tight(size: Int) = TextStyle(fontSize = size.sp, lineHeight = (size + 2).sp)

@Composable
private fun Legend() {
    val pal = LocalStatusPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
        listOf(Status.ON_TRACK to "on schedule", Status.WATCH to "slightly behind", Status.BEHIND to "behind").forEach { (st, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(pal.fill(st), CircleShape))
                Text(" $label", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    Muted("Big number = dispatched, small = plan. – = not entered.", Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
}

/** The tapped day: plan, dispatch (and the till-date total for till-date suppliers), gap and status. */
@Composable
private fun DayDetail(vm: AppViewModel, v: Vendor, part: Part, day: LocalDate, asOf: LocalDate) {
    val c = monthDays(vm.data, v, part.id, YearMonth.from(day)).first { it.date == day }
    val upcoming = day > asOf && c.actual == null
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(niceDate(day, true) + " " + day.year, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (!upcoming && (c.actual != null || c.plan >= 0.5)) StatusPill(c.status)
        }
        Spacer(Modifier.height(4.dp))
        Muted(
            listOfNotNull(
                "Plan ${if (c.plan >= 0.5) num(c.plan) else "none"}",
                when {
                    upcoming -> "still to come"
                    c.actual == null -> "dispatched: not entered"
                    else -> "dispatched ${num(c.actual)}"
                },
                c.actual?.takeIf { c.plan >= 0.5 }?.let { "gap ${signed(it - c.plan)}" },
                if (v.cumulative) vm.data.cumulative[pkey(v.id, part.id, day.toString())]?.let { "till-date total ${num(it)}" } else null,
                when {
                    c.holiday -> "holiday / day off"
                    !c.work -> "not a dispatch day"
                    else -> null
                },
            ).joinToString(" · "),
        )
        val reasons = vm.data.issues.filter { it.vendorId == v.id && it.partId == part.id && it.logged == day.toString() }
        reasons.forEach { Muted("Reason added: ${it.cause}" + (it.action.takeIf { a -> a.isNotBlank() }?.let { a -> " – $a" } ?: "")) }
        vm.data.notes.filter { it.vendorId == v.id && it.partId == part.id && it.date == day.toString() }.forEach {
            Text("Note: ${it.text}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** The month's weeks: dispatched against the plan up to the date at the top (all of it once the week is over). */
@Composable
private fun Weekly(vm: AppViewModel, v: Vendor, part: Part, ym: YearMonth, asOf: LocalDate) {
    val weeks = monthWeeks(vm.data, v, part.id, ym, asOf)
    SectionCard(title = "Weekly plan vs dispatch · ${niceMonth(ym)}") {
        weeks.forEachIndexed { i, w ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (w.started) AdherenceBar(w.period.label, w.planToDate, w.actual, w.status, entered = w.entered || w.planToDate < 0.5)
            else AdherenceBar(w.period.label, w.plan, 0.0, Status.NO_PLAN, entered = false)
            Muted(
                "${w.period.start.dayOfMonth}–${niceDate(w.period.end)} · week plan ${num(w.plan)}" + when {
                    !w.started -> " · not started"
                    w.period.end > asOf -> " · ${num(w.planToDate)} due so far"
                    else -> ""
                },
            )
        }
        val plan = weeks.sumOf { it.planToDate }
        val act = weeks.sumOf { it.actual }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        AdherenceBar("Total", plan, act, status(plan, act, weeks.any { it.entered }), entered = weeks.any { it.entered } || plan < 0.5)
        Muted("Weeks are cut at the month end. Without the backlog carried in: that is on the Month bar.")
    }
}

/** Six months ending with the month shown: the month of the date at the top counts to that day, backlog included. */
@Composable
private fun Monthly(vm: AppViewModel, v: Vendor, part: Part, last: YearMonth, asOf: LocalDate) {
    val months = recentMonths(vm.data, v, part.id, last, asOf).filter { it.current || it.plan != null || it.actual != null }
    SectionCard(title = "Monthly plan vs dispatch") {
        if (months.isEmpty()) Muted("No plan or dispatch in the 6 months to ${niceMonth(last)}.")
        months.reversed().forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val label = r.ym.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
            if (r.future) AdherenceBar(label, r.plan ?: 0.0, 0.0, Status.NO_PLAN, entered = false)
            else AdherenceBar(label, r.required ?: 0.0, r.actual ?: 0.0, r.status, entered = r.actual != null || (r.required ?: 0.0) < 0.5)
            Muted(
                niceMonth(r.ym) + " · " + when {
                    r.future -> "plan ${num(r.plan)} · not started"
                    r.current -> "month plan ${num(r.plan)} · ${num(r.required)} due to ${niceDate(asOf)} (backlog included)"
                    r.plan == null && r.actual == null -> "no plan or dispatch"
                    r.plan == null -> "dispatched ${num(r.actual)} · plan not available"
                    r.actual == null -> "plan ${num(r.plan)} · dispatch not available"
                    else -> "plan ${num(r.plan)} · short ${num(maxOf(0.0, r.plan - r.actual))}"
                },
            )
        }
    }
}
