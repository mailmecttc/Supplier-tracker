package app.suppliertracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.suppliertracker.model.Vendor
import app.suppliertracker.model.niceDate
import app.suppliertracker.ui.AppViewModel
import app.suppliertracker.ui.ConfirmDialog
import app.suppliertracker.ui.Muted
import java.time.LocalDate

/**
 * Dated notes, newest day first. [partId] null: every note of the vendor (part notes carry the part's name);
 * otherwise only that part's. "+ Add note" files the note under [day] (the date at the top, or the day tapped).
 */
@Composable
fun NotesSection(vm: AppViewModel, v: Vendor, partId: String?, day: LocalDate, newestFirst: Int = 8) {
    // newest day first; within a day the newest added first (notes are kept in the order they were added)
    val notes = vm.data.notes
        .filter { it.vendorId == v.id && (partId == null || it.partId == partId) }
        .asReversed().sortedByDescending { it.date }
    // not keyed on the day: a note being typed stays when another day is picked, and goes under that day
    var adding by rememberSaveable(v.id, partId) { mutableStateOf(false) }
    var editing by rememberSaveable(v.id, partId) { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var shown by rememberSaveable(v.id, partId) { mutableIntStateOf(newestFirst) }

    if (adding) {
        NoteEditor(
            title = "Note for ${niceDate(day, true)}",
            initial = "",
            onCancel = { adding = false },
            onSave = { vm.addNote(v, partId.orEmpty(), day, it); adding = false },
        )
    } else {
        TextButton(onClick = { adding = true }, contentPadding = PaddingValues(0.dp)) { Text("+ Add note for ${niceDate(day, true)}") }
    }
    if (notes.isEmpty()) {
        Muted("No notes yet. Notes are saved under their date, so you can see what was said on which day.")
        return
    }
    var lastDate: String? = null
    notes.take(shown).forEach { n ->
        if (n.date != lastDate) {
            if (lastDate != null) HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text(
                runCatching { niceDate(LocalDate.parse(n.date), true) + " " + n.date.take(4) }.getOrDefault(n.date),
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
            )
            lastDate = n.date
        }
        if (editing == n.id) {
            NoteEditor(
                title = "Edit note",
                initial = n.text,
                onCancel = { editing = null },
                onSave = { vm.editNote(n.id, it); editing = null },
            )
        } else {
            val partName = if (partId == null && n.partId.isNotBlank()) v.parts.firstOrNull { it.id == n.partId }?.name ?: "Removed part" else null
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Column(Modifier.weight(1f).padding(top = 6.dp)) {
                    Text((partName?.let { "$it: " } ?: "") + n.text, style = MaterialTheme.typography.bodyMedium)
                    if (n.edited.isNotBlank() && n.edited != n.date) {
                        Muted("edited ${runCatching { niceDate(LocalDate.parse(n.edited)) }.getOrDefault(n.edited)}")
                    }
                }
                TextButton(onClick = { editing = n.id }) { Text("Edit") }
                TextButton(onClick = { deleting = n.id }) { Text("Delete") }
            }
        }
    }
    if (notes.size > shown) {
        TextButton(onClick = { shown += 20 }) { Text("Show older notes (${notes.size - shown})") }
    }
    deleting?.let { id ->
        ConfirmDialog(
            title = "Delete this note?",
            text = notes.firstOrNull { it.id == id }?.text.orEmpty(),
            confirm = "Delete",
            onConfirm = { vm.deleteNote(id); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun NoteEditor(title: String, initial: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Muted(title)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(text, { text = it }, placeholder = { Text("What happened, what was agreed") }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save note") }
        }
    }
}
