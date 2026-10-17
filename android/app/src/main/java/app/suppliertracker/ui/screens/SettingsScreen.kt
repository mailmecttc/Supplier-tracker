@file:OptIn(ExperimentalMaterial3Api::class)

package app.suppliertracker.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.suppliertracker.BuildConfig
import app.suppliertracker.data.shareFile
import app.suppliertracker.model.niceDate
import app.suppliertracker.ui.AppViewModel
import app.suppliertracker.ui.ConfirmDialog
import app.suppliertracker.ui.Muted
import app.suppliertracker.ui.launchPicker
import app.suppliertracker.ui.SectionCard
import app.suppliertracker.ui.theme.LocalStatusPalette
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val TEXT_SIZES = listOf("Small" to 0.9f, "Normal" to 1f, "Large" to 1.15f, "Extra large" to 1.3f)

private fun backupName() = "supplier-tracker-backup-${LocalDate.now()}.json"

/** Settings, opened from Setup: appearance, fingerprint lock, nightly backup, backup password, copy / restore. */
@Composable
fun SettingsScreen(vm: AppViewModel, lockAvailable: Boolean, toast: (String) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pal = LocalStatusPalette.current
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refreshAuto() }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            vm.chooseBackupFolder(uri)
            toast("Night backup on: ${vm.auto.folder}")
        } catch (e: Exception) {
            toast(e.message ?: "Could not use that folder.")
        }
    }
    val importer = rememberImporter(vm, toast)
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.saveBackupTo(uri)
    }
    LaunchedEffect(vm.saveBackupNote) {
        vm.saveBackupNote?.let { toast(it); vm.saveBackupNote = null }
    }
    val working = busy ?: "Saving the backup…".takeIf { vm.savingBackup }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            if (working != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Muted(working, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
                SectionCard(title = "Appearance") {
                    Muted("Theme")
                    val themes = AppViewModel.ThemeMode.entries
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp)) {
                        themes.forEachIndexed { i, m ->
                            SegmentedButton(
                                selected = vm.themeMode == m, onClick = { vm.updateTheme(m) },
                                shape = SegmentedButtonDefaults.itemShape(i, themes.size),
                            ) { Text(m.label) }
                        }
                    }
                    Muted("Colours")
                    val wallpaper = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp)) {
                        SegmentedButton(selected = !vm.phoneColours, onClick = { vm.updatePhoneColours(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("App blue") }
                        SegmentedButton(
                            selected = vm.phoneColours, onClick = { vm.updatePhoneColours(true) }, enabled = wallpaper,
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("Phone wallpaper") }
                    }
                    if (!wallpaper) Muted("Wallpaper colours need Android 12 or newer.", Modifier.padding(bottom = 8.dp))
                    Muted("Text size")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
                        TEXT_SIZES.forEachIndexed { i, (label, x) ->
                            SegmentedButton(
                                selected = vm.textScale == x, onClick = { vm.updateTextScale(x) },
                                shape = SegmentedButtonDefaults.itemShape(i, TEXT_SIZES.size),
                                icon = {},
                            ) { Text(label, maxLines = 1, fontSize = 12.sp) }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Dispatched today", Modifier.weight(1f))
                        Text("1,500", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Muted("Preview. Applies to every screen.", Modifier.padding(top = 4.dp))
                }

                SectionCard(title = "Security") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Fingerprint lock")
                            Muted(
                                if (lockAvailable) "Asks for your fingerprint (or phone PIN) when the app opens."
                                else "Set up a fingerprint or screen lock in your phone's Settings to use this.",
                            )
                        }
                        Switch(checked = vm.lockEnabled && lockAvailable, onCheckedChange = { vm.setLock(it) }, enabled = lockAvailable)
                    }
                }

                val auto = vm.auto
                SectionCard(title = "Auto backup") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Back up every night")
                            Muted("Around 2:30 AM, into the folder you pick. The app does not need to be open.")
                        }
                        Switch(
                            checked = auto.enabled,
                            onCheckedChange = { on -> if (on && !auto.hasFolder) folderPicker.launchPicker(null, toast) else vm.setAutoBackup(on) },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Muted("Folder")
                            Text(auto.folder)
                        }
                        OutlinedButton(onClick = { folderPicker.launchPicker(null, toast) }) { Text(if (auto.hasFolder) "Change" else "Choose") }
                    }
                    Muted(
                        "Keeps the last 14 nights; older ones are deleted. Pick Documents or an SD card. A folder that syncs to the cloud also protects you if the phone is lost.",
                        Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    when {
                        auto.lastError.isNotBlank() -> Text(
                            "Last backup failed: ${auto.lastError}",
                            color = pal.bad, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().background(pal.badBg, RoundedCornerShape(10.dp)).padding(10.dp),
                        )
                        auto.lastAt > 0 -> {
                            val at = Instant.ofEpochMilli(auto.lastAt).atZone(ZoneId.systemDefault())
                            Text(
                                "✓ Last backup: ${niceDate(at.toLocalDate(), true)}, ${"%d:%02d".format(at.hour, at.minute)} · ${auto.lastFile}",
                                color = pal.ok, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().background(pal.okBg, RoundedCornerShape(10.dp)).padding(10.dp),
                            )
                        }
                        else -> Muted("No backup in the folder yet.")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                busy = "Backing up…"
                                try {
                                    toast("Saved ${vm.backUpNow()}")
                                } catch (e: Exception) {
                                    toast(e.message ?: "Backup failed.")
                                } finally {
                                    busy = null
                                }
                            }
                        },
                        enabled = auto.hasFolder && working == null,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("Back up now") }
                }

                PasswordCard(vm, toast)

                SectionCard(title = "Copy off the phone") {
                    Muted(
                        "Last copy off the phone: ${vm.data.lastBackup.ifBlank { "never" }}. Night backups stay on this phone; " +
                            "once a week send one to your Drive or email in case the phone is lost.",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    busy = if (vm.backupLocked) "Locking the backup…" else "Preparing the backup…"
                                    try {
                                        val text = vm.backupText()
                                        vm.markBackedUp()
                                        shareFile(ctx, backupName(), text, "application/json", "Share backup")
                                    } catch (e: Exception) {
                                        toast(e.message ?: "Could not prepare the backup.")
                                    } finally {
                                        busy = null
                                    }
                                }
                            },
                            enabled = working == null,
                            modifier = Modifier.weight(1f),
                        ) { Text("Share backup") }
                        OutlinedButton(onClick = { saver.launchPicker(backupName(), toast) }, enabled = working == null, modifier = Modifier.weight(1f)) { Text("Save to phone") }
                    }
                    OutlinedButton(onClick = { importer.launchPicker(arrayOf("*/*"), toast) }, modifier = Modifier.fillMaxWidth()) { Text("Import / restore from file") }
                    ImportPrompt(vm, toast)
                }
                Muted("Supplier Tracker ${BuildConfig.VERSION_NAME} · data stays on this phone, no internet used.", Modifier.fillMaxWidth().padding(16.dp))
            }
        }
    }
}

/** Set, change or turn off the password that locks every backup. */
@Composable
private fun PasswordCard(vm: AppViewModel, toast: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    SectionCard(title = "Backup password") {
        Text(if (vm.backupLocked) "On · every backup is locked (AES-256)." else "Off · anyone who gets a backup file can read it.")
        Muted("If you forget it, nobody can open the locked backups – not even you. Write it down somewhere safe.", Modifier.padding(top = 2.dp))
        if (editing) {
            OutlinedTextField(
                pw, { pw = it }, label = { Text("New password (6 or more characters)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            OutlinedTextField(
                again, { again = it }, label = { Text("Type it again") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = again.isNotEmpty() && again != pw,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                TextButton(onClick = { editing = false; pw = ""; again = "" }) { Text("Cancel") }
                Button(
                    onClick = {
                        vm.updateBackupPassword(pw)
                        editing = false
                        pw = ""
                        again = ""
                        toast("Backups will be locked with this password")
                    },
                    enabled = pw.length >= 6 && pw == again,
                ) { Text("Save password") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (vm.backupLocked) {
                    OutlinedButton(onClick = { editing = true }, modifier = Modifier.weight(1f)) { Text("Change") }
                    OutlinedButton(onClick = { confirmOff = true }, modifier = Modifier.weight(1f)) { Text("Turn off") }
                } else {
                    Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { Text("Set a password") }
                }
            }
        }
    }
    if (confirmOff) {
        ConfirmDialog(
            title = "Turn off the backup password?",
            text = "New backups will not be locked. Backups already locked still need the old password.",
            confirm = "Turn off",
            onConfirm = {
                vm.updateBackupPassword(null)
                confirmOff = false
            },
            onDismiss = { confirmOff = false },
        )
    }
}
