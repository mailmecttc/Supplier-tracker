package app.suppliertracker.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.suppliertracker.data.displayName
import app.suppliertracker.data.readBytes
import app.suppliertracker.importer.Columns
import app.suppliertracker.importer.Layout
import app.suppliertracker.importer.SheetRead
import app.suppliertracker.importer.columnTitle
import app.suppliertracker.model.niceDate
import app.suppliertracker.model.num
import app.suppliertracker.ui.AppViewModel
import app.suppliertracker.ui.Kpi
import app.suppliertracker.ui.Muted
import app.suppliertracker.ui.SectionCard
import app.suppliertracker.ui.launchPicker
import app.suppliertracker.ui.theme.LocalStatusPalette

/** "Import vendor file": picks an Excel or CSV file and opens the import screen. */
@Composable
fun rememberVendorFilePicker(vm: AppViewModel, toast: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.openSharedFile { readBytes(ctx, uri) to displayName(ctx, uri) }
    }
    return { launcher.launchPicker(SPREADSHEET_TYPES, toast) }
}

val SPREADSHEET_TYPES = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel",
    "text/csv", "text/comma-separated-values", "application/octet-stream",
)

/** The import of a vendor's own report: vendor, sheets, then a check of every figure before saving. */
@Composable
fun VendorImportScreen(vm: AppViewModel, toast: (String) -> Unit, onOpenStatus: () -> Unit) {
    val f = vm.vendorFile
    if (f == null) {
        vm.lastImport?.let { ImportDone(vm, it, toast, onOpenStatus) }
        return
    }
    BackHandler { vm.cancelVendorFile() }
    val data = vm.data
    val v = data.vendors.firstOrNull { it.id == f.vendorId } ?: return
    val reads = remember(f, data) { vm.vendorFileReads(f) }
    val preview = remember(f, data) { vm.vendorFilePreview(f) }
    val dispatch = reads.firstOrNull { it.sheet.name == f.dispatchSheet }
    val plan = reads.firstOrNull { it.sheet.name == f.planSheet }
    val pal = LocalStatusPalette.current
    // two parts can share a name (two "Pipe"s): then the part number tells them apart
    val partName = { id: String ->
        val part = v.parts.firstOrNull { it.id == id }
        when {
            part == null -> id
            v.parts.count { it.name == part.name } > 1 && part.partNo.isNotBlank() -> "${part.name} (${part.partNo})"
            else -> part.name
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                IconButton(onClick = vm::cancelVendorFile) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Import vendor file", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Muted(f.fileName, Modifier.padding(end = 12.dp))
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                // ---- whose file ----
                SectionCard(title = "Vendor") {
                    Picker(v.name, data.vendors.map { it.id to it.name }) { vm.vendorFileVendor(it) }
                    val found = reads.flatMap { r -> r.partsSeen + r.dispatch.keys.map { it.first } + r.weekPlan.keys.map { it.first } + r.monthPlan.keys + r.dayPlan.keys.map { it.first } }.toSet()
                    Text(
                        if (found.isEmpty()) "None of ${v.name}'s part numbers are in this file." else "${found.size} of its ${v.activeParts.size} parts are in this file",
                        color = if (found.isEmpty()) pal.bad else pal.ok, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                // ---- where the figures are ----
                SectionCard(title = "Dispatch from") {
                    val options = reads.filter { it.hasDispatch || it.sheet.name == f.dispatchSheet }
                    Picker(f.dispatchSheet ?: "Don't take dispatch from this file", options.map { it.sheet.name to sheetLine(it) } + ("" to "Don't take dispatch from this file")) { name ->
                        vm.vendorFileChange { it.copy(dispatchSheet = name.ifEmpty { null }, cols = Columns()) }
                    }
                    if (dispatch != null) {
                        Muted(dispatch.found, Modifier.padding(top = 6.dp))
                        if (dispatch.layout == Layout.LIST) ColumnChoices(dispatch, f.cols) { c -> vm.vendorFileChange { it.copy(cols = c) } }
                        if (dispatch.layout == Layout.LIST) {
                            CheckLine(f.missingAsZero, "A part with no row on a day counts as 0 dispatched (a full invoice list)") { on ->
                                vm.vendorFileChange { it.copy(missingAsZero = on) }
                            }
                        }
                    }
                }
                SectionCard(title = "Plan from") {
                    val options = reads.filter { it.hasPlan || it.sheet.name == f.planSheet }
                    Picker(f.planSheet ?: "Don't take the plan from this file", options.map { it.sheet.name to sheetLine(it) } + ("" to "Don't take the plan from this file")) { name ->
                        vm.vendorFileChange { it.copy(planSheet = name.ifEmpty { null }) }
                    }
                    if (plan != null) Muted(plan.found, Modifier.padding(top = 6.dp))
                }

                // ---- the check ----
                if (preview == null || preview.empty) {
                    SectionCard { Muted("Nothing to import from these sheets for ${v.name}. Pick another sheet or vendor.") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        Kpi("Days", "${preview.days}", preview.from?.let { "${niceDate(it)} – ${niceDate(preview.to!!)}" } ?: "–", Modifier.weight(1f))
                        Kpi("Parts", "${preview.partsWithFigures}", "with dispatch", Modifier.weight(1f))
                        Kpi("Dispatched", num(preview.total), "pcs", Modifier.weight(1f))
                    }
                    preview.notes.forEach { Muted(it, Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) }
                    if (preview.changes.isNotEmpty() || !f.replaceEntered) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp).background(pal.warnBg, RoundedCornerShape(16.dp)).padding(14.dp),
                        ) {
                            Text(
                                when {
                                    !f.replaceEntered -> "Days already entered are kept as they are"
                                    preview.changes.size == 1 -> "1 figure differs from what is in the app"
                                    else -> "${preview.changes.size} figures differ from what is in the app"
                                },
                                fontWeight = FontWeight.Bold, color = pal.warn,
                            )
                            preview.changes.take(6).forEach { c ->
                                Text(
                                    "${niceDate(c.date, true)} · ${partName(c.partId)}: app ${num(c.old)}, file ${c.new?.let(::num) ?: "nothing"}",
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            if (preview.changes.size > 6) Muted("+ ${preview.changes.size - 6} more")
                            CheckLine(f.replaceEntered, "Use the file's figures") { on -> vm.vendorFileChange { it.copy(replaceEntered = on) } }
                        }
                    }
                    if (preview.unmatched.isNotEmpty()) {
                        SectionCard(title = "Not ${v.name}'s part numbers") {
                            preview.unmatched.entries.take(6).forEach { (t, q) -> Muted("$t · ${num(q)} pcs") }
                            Muted("Left out. If one is theirs, add it in Setup → Parts with this part number, then import again.", Modifier.padding(top = 4.dp))
                        }
                    }
                    if (preview.zeroParts.isNotEmpty()) {
                        Muted("No row in the file: ${preview.zeroParts.joinToString { partName(it) }} – counted as 0 on the file's dispatch days.", Modifier.padding(4.dp))
                    }
                    if (plan != null) {
                        SectionCard(title = "Plan from the file") {
                            if (preview.planChanges.isEmpty() && f.usePlan) Muted("Same as the plan in the app – nothing to change.")
                            preview.planChanges.take(8).forEach { c ->
                                Text(
                                    "${partName(c.partId)} · ${app.suppliertracker.model.periodOf(c.periodStart).label}: ${num(c.new)}" + (c.old?.let { " (app ${num(it)})" } ?: " (new)"),
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            if (preview.planChanges.size > 8) Muted("+ ${preview.planChanges.size - 8} more")
                            CheckLine(f.usePlan, "Use the file's plan") { on -> vm.vendorFileChange { it.copy(usePlan = on) } }
                            Muted("A changed week shows as re-planned; its first plan stays the original.")
                        }
                    }
                    DaysList(preview, partName)
                }
                Spacer(Modifier.height(16.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = vm::cancelVendorFile, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { vm.saveVendorFile() },
                    enabled = preview != null && !preview.empty,
                    modifier = Modifier.weight(2f),
                ) { Text(if (preview == null || preview.days == 0) "Save" else if (preview.days == 1) "Save 1 day" else "Save ${preview.days} days") }
            }
        }
    }
}

private fun sheetLine(r: SheetRead): String = r.sheet.name + " · " + (r.layout?.label ?: "nothing found") +
    (r.lastDay?.let { " · to ${niceDate(it)}" } ?: "")

/** The figures day by day; tap a day for its parts. */
@Composable
private fun DaysList(p: app.suppliertracker.importer.ImportPreview, partName: (String) -> String) {
    val byDay = p.targets.entries.filter { it.value != null }.groupBy({ it.key.second }, { it.key.first to it.value!! }).toSortedMap()
    if (byDay.isEmpty()) return
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    SectionCard(title = "Dispatch by day") {
        byDay.entries.forEachIndexed { i, (day, rows) ->
            if (i > 0) HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable { open = if (open == day.toString()) null else day.toString() }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(niceDate(day, true), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Muted("${rows.count { it.second > 0 }} parts · ${num(rows.sumOf { it.second })} pcs")
            }
            if (open == day.toString()) {
                rows.sortedByDescending { it.second }.forEach { (part, q) ->
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 4.dp)) {
                        Text(partName(part), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(num(q), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Which column holds the part number, the date and the quantity, for a one-row-per-dispatch sheet. */
@Composable
private fun ColumnChoices(r: SheetRead, chosen: Columns, onChange: (Columns) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Text(if (open) "Hide columns" else "Wrong column? Change it")
    }
    if (!open) return
    val all = (0 until r.sheet.cols).map { it.toString() to columnTitle(r, it) }
    Muted("Part number")
    Picker(columnTitle(r, r.partCol), all) { onChange(chosen.copy(part = it.toInt())) }
    Muted("Date", Modifier.padding(top = 6.dp))
    Picker(if (r.dateCol >= 0) columnTitle(r, r.dateCol) else "–", all) { onChange(chosen.copy(date = it.toInt())) }
    Muted("Quantity dispatched", Modifier.padding(top = 6.dp))
    Picker(if (r.qtyCol >= 0) columnTitle(r, r.qtyCol) else "–", all) { onChange(chosen.copy(qty = it.toInt())) }
    if (chosen != Columns()) TextButton(onClick = { onChange(Columns()) }) { Text("Find the columns automatically") }
}

/** A drop-down: the current text, and options as (key, text). */
@Composable
private fun Picker(current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                .clickable { expanded = true }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("▾", color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (k, t) -> DropdownMenuItem(text = { Text(t) }, onClick = { expanded = false; onPick(k) }) }
        }
    }
}

@Composable
private fun CheckLine(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** After saving: what was saved, Undo while nothing else has changed, and the way to Status. */
@Composable
private fun ImportDone(vm: AppViewModel, done: AppViewModel.LastImport, toast: (String) -> Unit, onOpenStatus: () -> Unit) {
    BackHandler { vm.dismissImportResult() }
    val pal = LocalStatusPalette.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))
            Box(Modifier.size(88.dp).background(pal.okBg, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = pal.ok, modifier = Modifier.size(44.dp))
            }
            Text("Saved", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
            Text(done.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            SectionCard(Modifier.padding(top = 16.dp), title = "Next time") {
                Muted("Share the vendor's file to Supplier Tracker (or Entry → Import vendor file) → check → save. Its format is remembered.")
            }
            if (vm.canUndoImport) {
                SectionCard(title = "Changed your mind?") {
                    Muted("Undo puts everything back as it was before this import.")
                    OutlinedButton(onClick = { if (vm.undoImport()) toast("Import undone") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text("Undo this import")
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { vm.dismissImportResult(); onOpenStatus() }, modifier = Modifier.fillMaxWidth()) { Text("Open Status") }
            TextButton(onClick = vm::dismissImportResult) { Text("Close") }
        }
    }
}
