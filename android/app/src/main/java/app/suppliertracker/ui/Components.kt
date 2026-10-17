@file:OptIn(ExperimentalMaterial3Api::class)

package app.suppliertracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.suppliertracker.model.Status
import app.suppliertracker.model.Vendor
import app.suppliertracker.model.niceDate
import app.suppliertracker.model.parseQty
import app.suppliertracker.model.qtyText
import app.suppliertracker.ui.theme.LocalStatusPalette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Sticky header: vendor dropdown (optional) and the date with ‹ › and Today. */
@Composable
fun Header(vm: AppViewModel, showVendor: Boolean, showDate: Boolean = true) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (showVendor) VendorPicker(vm.data.vendors, vm.vendor) { vm.selectVendor(it) }
            if (showDate) DateRow(vm.date, onChange = { vm.selectDate(it) })
        }
    }
}

@Composable
fun VendorPicker(vendors: List<Vendor>, selected: Vendor?, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth().clickable { open = true },
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Vendor", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        selected?.name ?: "No vendor",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose vendor", tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            vendors.forEach { v ->
                DropdownMenuItem(
                    text = { Text("${v.name}  ·  ${v.activeParts.size} parts") },
                    onClick = {
                        open = false
                        onSelect(v.id)
                    },
                )
            }
        }
    }
}

@Composable
fun DateRow(date: LocalDate, onChange: (LocalDate) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = { onChange(date.minusDays(1)) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
        }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.weight(1f).clickable { picking = true },
        ) {
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text(niceDate(date, true) + " ${date.year}", fontWeight = FontWeight.SemiBold)
            }
        }
        IconButton(onClick = { onChange(date.plusDays(1)) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
        }
        if (date != today) FilledTonalButton(onClick = { onChange(today) }) { Text("Today") }
    }
    if (picking) DateDialog(date, onDismiss = { picking = false }, onPick = { picking = false; onChange(it) })
}

@Composable
fun DateDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state = state) }
}

private fun Long.toDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/** Pick a first and last day (tap one day twice, or OK after the first tap, for a single day). */
@Composable
fun DateRangeDialog(initial: LocalDate, title: String, onDismiss: () -> Unit, onPick: (LocalDate, LocalDate) -> Unit) {
    val ms = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDateRangePickerState(initialDisplayedMonthMillis = ms)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val a = state.selectedStartDateMillis
                if (a == null) onDismiss() else onPick(a.toDate(), (state.selectedEndDateMillis ?: a).toDate())
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier.weight(1f),
            title = { Text(title, Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) },
        )
    }
}

@Composable
fun StatusPill(s: Status, modifier: Modifier = Modifier) {
    val pal = LocalStatusPalette.current
    Text(
        s.label,
        color = pal.fg(s),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier.background(pal.bg(s), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

@Composable
fun Badge(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

/** Small labelled number box, used in rows of three. */
@Composable
fun RowScope.Stat(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    Column(
        Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        // two lines allowed so large text sizes wrap instead of cutting numbers off
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = valueColor, maxLines = 2)
    }
}

@Composable
fun Kpi(
    label: String, value: String, sub: String, modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    container: androidx.compose.ui.graphics.Color? = null,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = container ?: MaterialTheme.colorScheme.surfaceContainerLowest),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 1)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Two bars: required to date (light grey) and dispatched (green on schedule, amber slightly behind, red behind). */
@Composable
fun ProgressBars(required: Double, actual: Double, max: Double, status: Status) {
    val pal = LocalStatusPalette.current
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val req = pal.neutral.copy(alpha = 0.35f)
    val act = pal.fill(status)
    fun f(x: Double) = if (max <= 0) 0f else (x / max).coerceIn(0.0, 1.0).toFloat()
    Box(Modifier.fillMaxWidth().height(14.dp).background(track, RoundedCornerShape(4.dp))) {
        Box(Modifier.fillMaxWidth(f(required)).height(14.dp).background(req, RoundedCornerShape(4.dp)))
        Box(Modifier.fillMaxWidth(f(actual)).padding(vertical = 3.dp).height(8.dp).background(act, RoundedCornerShape(3.dp)))
    }
}

/**
 * Number box that saves when you leave it or press Next/Done (not on every key),
 * so a weekly plan's first saved value is the real original plan.
 */
@Composable
fun CommitNumberField(
    value: Double?,
    onCommit: (Double?) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    last: Boolean = false,
) {
    var text by remember(value) { mutableStateOf(qtyText(value)) }
    // Next / Done commit and then the focus change commits again before the new value arrives: send each text once
    var sent by remember(value) { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val commit by rememberUpdatedState {
        val parsed = parseQty(text)
        if ((text.isBlank() || parsed != null) && parsed != value && text != sent) {
            sent = text
            onCommit(parsed)
        }
    }
    DisposableEffect(Unit) { onDispose { commit() } }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = modifier.onFocusChanged { if (!it.isFocused) commit() },
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End) } },
        singleLine = true,
        textStyle = TextStyle(textAlign = TextAlign.End, fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = if (last) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(
            onNext = { commit(); focus.moveFocus(FocusDirection.Down) },
            onDone = { commit(); focus.clearFocus() },
        ),
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun Gap(width: Int = 8) = Spacer(Modifier.width(width.dp))

/** Opens a system picker (file, folder, save as); a phone with the Files app turned off has none. */
fun <I> androidx.activity.result.ActivityResultLauncher<I>.launchPicker(input: I, toast: (String) -> Unit) {
    try {
        launch(input)
    } catch (e: android.content.ActivityNotFoundException) {
        toast("No file picker on this phone. Turn on the Files app in the phone's settings and try again.")
    }
}

/**
 * One of a part's Day / Week / Month bars: dispatched against plan, green on schedule, amber slightly behind,
 * red behind (the same colours as the Entry box), grey when there is nothing to judge.
 */
@Composable
fun AdherenceBar(label: String, plan: Double, actual: Double, status: Status, entered: Boolean = true) {
    val pal = LocalStatusPalette.current
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val share = when {
        plan >= 0.5 -> (actual / plan).coerceIn(0.0, 1.0)
        actual > 0 -> 1.0
        else -> 0.0
    }.toFloat()
    val pct = app.suppliertracker.model.adherence(plan, actual)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        // grows with the text size, so "Month" is never cut to "Mont"
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 52.dp).padding(end = 4.dp))
        Box(Modifier.weight(1f).height(12.dp).background(track, RoundedCornerShape(6.dp))) {
            if (share > 0f) Box(Modifier.fillMaxWidth(share).height(12.dp).background(pal.fill(status), RoundedCornerShape(6.dp)))
        }
        Text(
            // nothing typed reads "– / plan", so it is never mistaken for a typed 0
            if (!entered && plan < 0.5) "–" else "${if (entered) app.suppliertracker.model.num(actual) else "–"} / ${app.suppliertracker.model.num(plan)}",
            style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false, textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 8.dp).widthIn(min = 88.dp),
        )
        Text(
            if (entered) app.suppliertracker.model.pct(pct) else "–",
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = pal.fg(status),
            maxLines = 1, softWrap = false, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 44.dp),
        )
    }
}
