@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.suppliertracker.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import app.suppliertracker.BuildConfig
import app.suppliertracker.data.readBytes
import app.suppliertracker.data.shareFile
import app.suppliertracker.model.MODE_CUM
import app.suppliertracker.model.MODE_DAILY
import app.suppliertracker.model.WEEKDAYS
import app.suppliertracker.model.dispatchLogCsv
import app.suppliertracker.model.monthlySummaryCsv
import app.suppliertracker.model.dayRuns
import app.suppliertracker.model.niceDate
import app.suppliertracker.model.niceRange
import app.suppliertracker.ui.AppViewModel
import app.suppliertracker.ui.ConfirmDialog
import app.suppliertracker.ui.DateDialog
import app.suppliertracker.ui.DateRangeDialog
import app.suppliertracker.ui.Muted
import app.suppliertracker.ui.launchPicker
import app.suppliertracker.ui.SectionCard
import java.time.LocalDate

@Composable
fun SetupScreen(vm: AppViewModel, toast: (String) -> Unit, onReadGuide: () -> Unit, onOpenSettings: () -> Unit, onFirstVendor: () -> Unit = {}) {
    val ctx = LocalContext.current
    val data = vm.data
    var confirm by remember { mutableStateOf<String?>(null) }
    var addingHoliday by remember { mutableStateOf(false) }
    var addingVendorDays by remember { mutableStateOf(false) }
    var newVendor by remember { mutableStateOf("") }

    val importer = rememberImporter(vm, toast)
    val templatePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            vm.importReportTemplate(readBytes(ctx, uri))
            toast("Report template saved. Weekly reports now use it.")
        } catch (e: Exception) {
            toast(e.message ?: "Could not read that template.")
        }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        HelpCard(vm, toast, onReadGuide)
        SectionCard(title = "Settings") {
            Muted("Theme and text size, fingerprint lock, night backup and backup password, copy or restore a backup.")
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Open settings") }
        }
        if (vm.data.vendors.isNotEmpty()) {
            val pickVendorFile = rememberVendorFilePicker(vm, toast)
            SectionCard(title = "Vendor's Excel report") {
                Muted("Fills dispatch and plan from a vendor's own .xlsx or .csv file. You can also share the file from WhatsApp or email to Supplier Tracker.")
                OutlinedButton(onClick = pickVendorFile, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Import vendor file") }
            }
        }
        val v = vm.vendor
        if (v == null) {
            SectionCard(title = "Welcome") {
                Text("Load your vendors, part numbers and plans from the data file, or start with an empty vendor.")
                Spacer(Modifier.height(10.dp))
                Button(onClick = { importer.launchPicker(arrayOf("*/*"), toast) }, modifier = Modifier.fillMaxWidth()) { Text("Import data file") }
                ImportPrompt(vm, toast)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newVendor, { newVendor = it }, label = { Text("Vendor name") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (newVendor.isNotBlank()) vm.addVendor(newVendor).also { newVendor = ""; onFirstVendor() } }) { Text("Add") }
                }
                Muted("Everything stays on this phone.", Modifier.padding(top = 8.dp))
            }
        } else {
            SectionCard(title = "Vendor") {
                OutlinedTextField(v.name, { vm.updateVendor(v.copy(name = it)) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(v.contact, { vm.updateVendor(v.copy(contact = it)) }, label = { Text("Contact (name / phone)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(v.address, { vm.updateVendor(v.copy(address = it)) }, label = { Text("Address") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(v.notes, { vm.updateVendor(v.copy(notes = it)) }, label = { Text("Notes") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("How this vendor reports", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(v.mode == MODE_DAILY, { vm.updateVendor(v.copy(mode = MODE_DAILY)) })
                    Text("Daily quantity")
                    RadioButton(v.mode == MODE_CUM, { vm.updateVendor(v.copy(mode = MODE_CUM)) })
                    Text("Till-date total")
                }
                Text("Dispatch days", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WEEKDAYS.forEachIndexed { i, day ->
                        FilterChip(
                            selected = v.dispatchDays[i] != 0,
                            onClick = { vm.updateVendor(v.copy(dispatchDays = v.dispatchDays.mapIndexed { j, x -> if (j == i) 1 - x.coerceIn(0, 1) else x })) },
                            label = { Text(day) },
                        )
                    }
                }
                Muted("Weekly dispatchers: turn on only their dispatch day, so they never look behind mid-week.")
                Spacer(Modifier.height(10.dp))
                Text("Days off for ${v.name}", style = MaterialTheme.typography.labelLarge)
                Muted("This supplier's own shutdowns and holidays. No plan is expected on these days.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    dayRuns(v.holidays).forEach { (a, b) ->
                        InputChip(
                            selected = false,
                            onClick = { vm.removeVendorHolidays(v, a, b) },
                            label = { Text(niceRange(a, b)) },
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove") },
                        )
                    }
                }
                OutlinedButton(onClick = { addingVendorDays = true }) { Text("+ Add days off") }
            }

            SectionCard(title = "Parts · ${v.parts.size}") {
                v.parts.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(p.partNo, { vm.updatePart(v, p.copy(partNo = it)) }, label = { Text("Part no.") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(p.desc, { vm.updatePart(v, p.copy(desc = it)) }, label = { Text("Description") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(p.critical, { vm.updatePart(v, p.copy(critical = it)) })
                        Text("Critical")
                        Checkbox(p.active, { vm.updatePart(v, p.copy(active = it)) })
                        Text("Active", Modifier.weight(1f))
                        TextButton(onClick = { confirm = "part:${p.id}" }) { Text("Remove") }
                    }
                }
                OutlinedButton(onClick = { vm.addPart(v) }, modifier = Modifier.padding(top = 8.dp)) { Text("+ Add part") }
            }

            SectionCard(title = "Vendors") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newVendor, { newVendor = it }, label = { Text("New vendor name") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        if (newVendor.isNotBlank()) {
                            vm.addVendor(newVendor)
                            toast("Added ${newVendor.trim()}")
                            newVendor = ""
                        }
                    }) { Text("Add") }
                }
                TextButton(onClick = { confirm = "vendor" }) { Text("Delete ${v.name}…", color = MaterialTheme.colorScheme.error) }
            }
        }

        SectionCard(title = "Holidays (all vendors)") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                data.holidays.sorted().forEach { h ->
                    InputChip(
                        selected = false,
                        onClick = { vm.removeHoliday(h) },
                        label = { Text(runCatching { niceDate(LocalDate.parse(h), true) + " " + h.take(4) }.getOrDefault(h)) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove") },
                    )
                }
            }
            if (data.holidays.isEmpty()) Muted("None")
            OutlinedButton(onClick = { addingHoliday = true }) { Text("+ Add holiday") }
        }

        if (data.vendors.isNotEmpty()) {
            SectionCard(title = "Report template (PowerPoint)") {
                Text("Using ${vm.templateLabel}.")
                Muted(
                    if (vm.companyTemplate && !vm.customTemplate) "Your company template is built into this app: nothing to import. To use another one, import its .pptx."
                    else "Import your company .pptx template once. Its title, footer, logo and colours are used; its sample slides are left out.",
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { templatePicker.launchPicker(arrayOf("*/*"), toast) }, modifier = Modifier.weight(1f)) { Text("Import template") }
                    if (vm.customTemplate) {
                        OutlinedButton(onClick = { vm.useBuiltInTemplate() }, modifier = Modifier.weight(1f)) {
                            Text(if (vm.companyTemplate) "Use company one" else "Use built-in")
                        }
                    }
                }
                OutlinedTextField(vm.department, { vm.updateDepartment(it) }, label = { Text("Department in the footer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Muted("Build the report on the Dashboard tab, for the period chosen there.")
            }
        }

        if (data.vendors.isNotEmpty()) {
            SectionCard(title = "Exports (all vendors)") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        shareFile(ctx, "summary-all-${vm.date}.csv", monthlySummaryCsv(data, data.vendors, vm.date), "text/csv", "Share summary CSV")
                    }, modifier = Modifier.weight(1f)) { Text("Summary CSV") }
                    OutlinedButton(onClick = {
                        shareFile(ctx, "dispatch-all-${vm.date}.csv", dispatchLogCsv(data, data.vendors, vm.date), "text/csv", "Share dispatch log")
                    }, modifier = Modifier.weight(1f)) { Text("Dispatch log CSV") }
                }
            }
        }
        Muted("Supplier Tracker ${BuildConfig.VERSION_NAME} · data stays on this phone, no internet used.", Modifier.fillMaxWidth().padding(16.dp))
    }

    if (addingHoliday) DateDialog(vm.date, onDismiss = { addingHoliday = false }, onPick = { addingHoliday = false; vm.addHoliday(it) })
    vm.vendor?.let { v ->
        if (addingVendorDays) {
            DateRangeDialog(vm.date, "Days off for ${v.name}: first and last day", onDismiss = { addingVendorDays = false }) { a, b ->
                addingVendorDays = false
                vm.addVendorHolidays(v, a, b)
            }
        }
    }

    val v = vm.vendor
    when {
        confirm == "vendor" && v != null -> ConfirmDialog(
            title = "Delete ${v.name}?",
            text = "This deletes its parts, plans, dispatch and history. It cannot be undone unless you have a backup.",
            confirm = "Delete",
            onConfirm = { vm.deleteVendor(v.id); confirm = null; toast("Deleted ${v.name}") },
            onDismiss = { confirm = null },
        )
        confirm?.startsWith("part:") == true && v != null -> {
            val p = v.parts.firstOrNull { it.id == confirm!!.removePrefix("part:") }
            if (p != null) ConfirmDialog(
                title = "Remove ${p.name}?",
                text = "It disappears from the lists. Its plans and dispatch stay in backups.",
                confirm = "Remove",
                onConfirm = { vm.removePart(v, p.id); confirm = null },
                onDismiss = { confirm = null },
            )
        }
    }
}
