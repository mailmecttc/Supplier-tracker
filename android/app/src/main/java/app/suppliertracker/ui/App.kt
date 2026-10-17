package app.suppliertracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.suppliertracker.ui.screens.EntryScreen
import app.suppliertracker.ui.screens.GuideScreen
import app.suppliertracker.ui.screens.PlanScreen
import app.suppliertracker.ui.screens.SetupScreen
import app.suppliertracker.ui.screens.PartScreen
import app.suppliertracker.ui.screens.StatusScreen
import app.suppliertracker.ui.screens.DashboardScreen
import app.suppliertracker.ui.screens.SettingsScreen
import kotlinx.coroutines.launch

/** Bottom tabs, in screen order: the Dashboard sits in the middle as the big button. */
enum class Tab(val label: String, val icon: ImageVector) {
    ENTRY("Entry", Icons.Filled.Edit),
    STATUS("Status", Icons.Filled.CheckCircle),
    ALL("Dashboard", Icons.Filled.Home),
    PLAN("Plan", Icons.Filled.DateRange),
    SETUP("Setup", Icons.Filled.Settings),
}

@Composable
fun AppRoot(vm: AppViewModel, lockAvailable: Boolean = false) {
    if (!vm.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val toast: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    // a message about a file shared to the app (it arrives before any screen asked for it)
    androidx.compose.runtime.LaunchedEffect(vm.importMessage) {
        vm.importMessage?.let { snackbar.showSnackbar(it); vm.importMessage = null }
    }
    // the app opens on the Dashboard
    var tabName by rememberSaveable { mutableStateOf(Tab.ALL.name) }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    // keeps each tab's own state (the Status filter and scroll, say) while a part page or Settings covers it
    val tabStates = rememberSaveableStateHolder()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    if (showGuide) {
        GuideScreen(onClose = { showGuide = false })
        return
    }
    if (showSettings) {
        Box(Modifier.fillMaxSize().imePadding()) {
            SettingsScreen(vm, lockAvailable, toast, onClose = { showSettings = false })
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        return
    }
    // a vendor's Excel file being imported (shared to the app or picked), and the result after saving
    if (vm.vendorFile != null || vm.lastImport != null) {
        Box(Modifier.fillMaxSize().imePadding()) {
            app.suppliertracker.ui.screens.VendorImportScreen(vm, toast, onOpenStatus = { tabName = Tab.STATUS.name })
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        return
    }
    // a part opened from Status: its calendar, weeks and months
    var openPart by rememberSaveable { mutableStateOf<String?>(null) }
    val part = openPart?.let { id -> vm.vendor?.parts?.firstOrNull { it.id == id } }
    if (part != null) {
        Box(Modifier.fillMaxSize().imePadding()) {
            PartScreen(vm, vm.vendor!!, part, onClose = { openPart = null })
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        return
    }
    val pickVendorFile = app.suppliertracker.ui.screens.rememberVendorFilePicker(vm, toast)
    val noVendors = vm.data.vendors.isEmpty()
    val tab = if (noVendors) Tab.SETUP else Tab.valueOf(tabName)
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar, Modifier.imePadding()) },
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                vm.loadError?.let {
                    Text(
                        "Could not read the saved data: $it. Changes are not being saved. Restore a backup in Setup.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
                vm.lastCrash?.let { crash -> CrashBanner(crash, onDismiss = vm::dismissCrash) }
                when (tab) {
                    Tab.ENTRY, Tab.STATUS, Tab.PLAN -> Header(vm, showVendor = true)
                    Tab.ALL -> Header(vm, showVendor = false)
                    Tab.SETUP -> if (!noVendors) Header(vm, showVendor = true, showDate = false)
                }
            }
        },
        bottomBar = {
            if (!imeOpen) {
                // tab labels keep their normal size at large text sizes, so "Dashboard" stays on one line
                val d = LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(d.density, minOf(d.fontScale, 1f))) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        if (t == Tab.ALL) {
                            CentreTab(t, selected = t == tab, enabled = !noVendors, onClick = { tabName = t.name })
                        } else {
                            NavigationBarItem(
                                selected = t == tab,
                                onClick = { tabName = t.name },
                                enabled = !noVendors || t == Tab.SETUP,
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label, maxLines = 1, softWrap = false) },
                            )
                        }
                    }
                }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            tabStates.SaveableStateProvider(tab.name) {
            when (tab) {
                Tab.ENTRY -> EntryScreen(vm, onBackup = { showSettings = true }, onSetup = { tabName = Tab.SETUP.name }, onImportFile = pickVendorFile)
                Tab.STATUS -> StatusScreen(vm, onOpenPart = { openPart = it })
                Tab.PLAN -> PlanScreen(vm, toast)
                Tab.ALL -> DashboardScreen(vm, toast, onOpenVendor = { vm.selectVendor(it); tabName = Tab.STATUS.name })
                Tab.SETUP -> SetupScreen(
                    vm, toast, onReadGuide = { showGuide = true }, onOpenSettings = { showSettings = true },
                    onFirstVendor = { tabName = Tab.SETUP.name },
                )
            }
            }
        }
    }
}

/** After a crash: offer to send the report (error and phone model only) to whoever looks after the app. */
@Composable
private fun CrashBanner(crash: String, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.errorContainer, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp),
    ) {
        Text(
            "The app closed unexpectedly last time. Please send the crash report so it can be fixed – it has no supplier data.",
            color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall,
        )
        androidx.compose.foundation.layout.Row {
            // the report stays until Done, so backing out of the share sheet does not lose it
            androidx.compose.material3.TextButton(onClick = {
                app.suppliertracker.data.shareText(ctx, crash + "\n\nWhat I was doing:\n", "Send crash report")
            }) { Text("Send crash report") }
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Done") }
        }
    }
}

/** The Dashboard tab: a bigger round button in the middle of the bar, filled when selected. */
@Composable
private fun RowScope.CentreTab(t: Tab, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val off = cs.onSurface.copy(alpha = 0.38f) // Material's disabled look, as on the other tabs
    Column(
        // the bar only sets a minimum height, so a fixed height here (not fillMaxHeight) keeps it at the bottom
        Modifier.weight(1f).height(80.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(54.dp)
                .shadow(if (!enabled) 0.dp else if (selected) 6.dp else 2.dp, CircleShape)
                .background(if (!enabled) cs.onSurface.copy(alpha = 0.12f) else if (selected) cs.primary else cs.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(t.icon, contentDescription = null, tint = if (!enabled) off else if (selected) cs.onPrimary else cs.onPrimaryContainer, modifier = Modifier.size(28.dp))
        }
        Text(
            t.label, maxLines = 1, softWrap = false, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium, color = if (!enabled) off else if (selected) cs.primary else cs.onSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Shown until the fingerprint (or phone PIN) is confirmed. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.padding(bottom = 16.dp).size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Supplier Tracker is locked", style = MaterialTheme.typography.titleLarge)
            Text("Your supplier data stays on this phone.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 24.dp))
            androidx.compose.material3.Button(onClick = onUnlock) { Text("Unlock with fingerprint") }
        }
    }
}
