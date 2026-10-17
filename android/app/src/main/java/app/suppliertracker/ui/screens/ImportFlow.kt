package app.suppliertracker.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.suppliertracker.data.readText
import app.suppliertracker.ui.AppViewModel
import app.suppliertracker.ui.ConfirmDialog
import app.suppliertracker.ui.Muted
import app.suppliertracker.ui.SectionCard
import kotlinx.coroutines.launch

/** "Import data file" / "Import / restore from file": opens the file picker and starts the import. */
@Composable
fun rememberImporter(vm: AppViewModel, toast: (String) -> Unit) = LocalContext.current.let { ctx ->
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            vm.beginImport(readText(ctx, uri))?.let(toast)
        } catch (e: Exception) {
            toast(e.message ?: "Could not read that file.")
        }
    }
}

/** Asks for the password of a locked backup, then "Replace everything?" when data is already on the phone. */
@Composable
fun ImportPrompt(vm: AppViewModel, toast: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    when (val p = vm.pendingImport) {
        is AppViewModel.PendingImport.Locked -> {
            var password by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            SectionCard(title = "This backup is locked") {
                Muted("Type the backup password that was set when it was made.")
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("Backup password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = p.error != null,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                p.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    TextButton(onClick = { vm.cancelImport() }) { Text("Cancel") }
                    Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                vm.unlockImport(password)?.let(toast)
                                busy = false
                            }
                        },
                        enabled = password.isNotEmpty() && !busy,
                    ) { Text(if (busy) "Opening…" else "Open backup") }
                }
            }
        }
        is AppViewModel.PendingImport.Ready -> ConfirmDialog(
            title = "Replace data?",
            text = "Replace everything on this phone with this file (${p.data.vendors.size} vendors, ${p.data.vendors.sumOf { it.parts.size }} parts)? Back up first if unsure.",
            confirm = "Replace",
            onConfirm = {
                vm.confirmImport()
                toast("Data imported")
            },
            onDismiss = { vm.cancelImport() },
        )
        null -> Unit
    }
}
