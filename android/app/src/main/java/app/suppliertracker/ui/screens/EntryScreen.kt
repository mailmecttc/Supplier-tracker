package app.suppliertracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.suppliertracker.model.Status
import app.suppliertracker.model.WEEKDAYS
import app.suppliertracker.model.isWork
import app.suppliertracker.model.niceDate
import app.suppliertracker.model.num
import app.suppliertracker.model.entryCheck
import app.suppliertracker.model.peakCumulative
import app.suppliertracker.model.tillDateDrops
import app.suppliertracker.model.parseQty
import app.suppliertracker.model.partMetrics
import app.suppliertracker.model.prevCumulative
import app.suppliertracker.ui.AppViewModel
import app.suppliertracker.ui.Badge
import app.suppliertracker.ui.Muted
import app.suppliertracker.ui.SectionCard
import app.suppliertracker.ui.Stat
import app.suppliertracker.ui.theme.LocalStatusPalette

@Composable
fun EntryScreen(vm: AppViewModel, onBackup: () -> Unit, onSetup: () -> Unit, onImportFile: () -> Unit = {}) {
    val v = vm.vendor ?: return
    val data = vm.data
    val date = vm.date
    val parts = v.activeParts
    val focus = LocalFocusManager.current
    val pal = LocalStatusPalette.current

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (vm.backupDue()) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (data.lastBackup.isBlank()) "Your data lives only on this phone. Save a backup." else "A week since a backup was copied off this phone.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = onBackup) { Text("Back up") }
            }
            Spacer(Modifier.height(6.dp))
        }

        if (parts.isEmpty()) {
            SectionCard {
                Text("${v.name} has no parts yet.")
                Button(onClick = onSetup, modifier = Modifier.padding(top = 8.dp)) { Text("Add parts in Setup") }
            }
            return@Column
        }

        // the vendor's own report instead of typing
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp)) {
            Muted("${v.name} sent an Excel report?", Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = onImportFile) { Text("Import file") }
        }

        val note = when {
            date.toString() in data.holidays -> "${niceDate(date)} is a holiday. No plan today."
            date.toString() in v.holidays -> "${niceDate(date)} is a day off for ${v.name}. No plan today."
            !isWork(v, date, data.holidays) -> "Not a dispatch day for ${v.name} (dispatches ${v.dispatchDays.mapIndexedNotNull { i, on -> if (on != 0) WEEKDAYS[i] else null }.joinToString(", ")})."
            else -> null
        }
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp)).padding(10.dp))
            Spacer(Modifier.height(6.dp))
        }
        Muted(
            if (v.cumulative) "${v.name} reports till-date totals: type the month-to-date total for each part."
            else "Type today's dispatch. Blank = not entered, 0 = nothing dispatched. Saves as you type.",
            Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )

        parts.forEachIndexed { i, p ->
            val m = partMetrics(data, v, p.id, date)
            val daily = vm.entryDaily(v, p.id)
            SectionCard {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                        if (p.partNo.isNotBlank() && p.desc.isNotBlank()) Muted(p.partNo)
                    }
                    if (p.critical) Badge("Critical")
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Stat("Today plan", if (m.todayPlan < 0.5) "–" else num(m.todayPlan))
                    Stat("Week act/plan", "${num(m.wtdActual)} / ${num(m.weekPlan)}")
                    Stat("Month act/req", "${num(m.mtdActual)} / ${num(m.mtdRequirement)}")
                }
                Spacer(Modifier.height(8.dp))
                // today's figure against today's plan (or, with no plan today, the shortfall to date):
                // the box turns green / amber / red once a number is in
                val check = entryCheck(m, daily)
                // a till-date total never goes down: a lower one is a typing slip (often 0 for "nothing today")
                val last = if (v.cumulative) peakCumulative(data, v.id, p.id, date) else null
                val typed = parseQty(vm.entryText(v, p.id))
                val dropped = last != null && typed != null && typed < last.second - 0.5
                val gapText = if (dropped) "Lower than the last till-date total" else check.text
                val gapStatus = if (dropped) Status.BEHIND else check.status
                val judged = gapStatus in setOf(Status.ON_TRACK, Status.WATCH, Status.BEHIND)
                val boxColors = if (!judged) OutlinedTextFieldDefaults.colors() else OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = pal.fill(gapStatus), unfocusedBorderColor = pal.fill(gapStatus),
                    focusedTextColor = pal.fg(gapStatus), unfocusedTextColor = pal.fg(gapStatus),
                    focusedContainerColor = pal.bg(gapStatus), unfocusedContainerColor = pal.bg(gapStatus),
                    cursorColor = pal.fg(gapStatus),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (v.cumulative) "Till-date total" else "Dispatched today", modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = vm.entryText(v, p.id),
                        onValueChange = { vm.setEntry(v, p.id, it) },
                        modifier = Modifier.width(150.dp),
                        singleLine = true,
                        placeholder = { Text("–", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End) },
                        textStyle = TextStyle(textAlign = TextAlign.End, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                        colors = boxColors,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = if (i == parts.lastIndex) ImeAction.Done else ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }, onDone = { focus.clearFocus() }),
                    )
                }
                if (v.cumulative) {
                    Text(
                        when {
                            daily == null -> "Last till-date: ${num(prevCumulative(data, v.id, p.id, date))}" + (last?.let { " (${niceDate(it.first)})" } ?: "")
                            dropped -> "= ${num(daily)} today: a till-date total never goes down"
                            else -> "= ${num(daily)} dispatched today"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (dropped) pal.bad else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (dropped) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    // an earlier day's total below the one before it (often a 0 typed for "nothing today")
                    tillDateDrops(data, v.id, p.id, date).lastOrNull()?.let { drop ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp).background(pal.badBg, RoundedCornerShape(10.dp)).padding(start = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${niceDate(drop.date, true)}: till-date ${num(drop.total)} is below ${num(drop.peak)} (${niceDate(drop.peakDate)}), so " +
                                    "that day counts as ${num(drop.total - drop.peak)}.",
                                style = MaterialTheme.typography.bodySmall, color = pal.bad, modifier = Modifier.weight(1f),
                            )
                            androidx.compose.material3.TextButton(onClick = { vm.selectDate(drop.date) }) { Text("Fix ${niceDate(drop.date)}") }
                        }
                    }
                    if (dropped && last != null) {
                        Muted(
                            "The total so far this month reached ${num(last.second)} on ${niceDate(last.first)}. If nothing was dispatched " +
                                "today, the till-date total stays ${num(last.second)}.",
                            Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                        Row {
                            Spacer(Modifier.weight(1f))
                            androidx.compose.material3.TextButton(
                                onClick = { vm.setEntry(v, p.id, app.suppliertracker.model.qtyText(last.second)) },
                            ) { Text("Nothing today: use ${num(last.second)}") }
                        }
                    }
                }
                Text(
                    gapText,
                    color = pal.fg(gapStatus),
                    fontWeight = if (gapStatus in setOf(Status.ON_TRACK, Status.WATCH, Status.BEHIND)) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        val n = vm.enteredCount(v)
        Muted("$n of ${parts.size} parts entered for ${niceDate(date, true)}. Saved on this phone.", Modifier.padding(8.dp))
        Spacer(Modifier.height(24.dp))
    }
}
