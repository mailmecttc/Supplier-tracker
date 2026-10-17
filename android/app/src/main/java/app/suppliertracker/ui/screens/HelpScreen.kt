package app.suppliertracker.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.suppliertracker.BuildConfig
import app.suppliertracker.data.shareText
import app.suppliertracker.model.Data
import app.suppliertracker.ui.AppViewModel
import app.suppliertracker.ui.Muted
import app.suppliertracker.ui.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val GUIDE_ASSET = "user-guide.pdf"

/** Copies the bundled guide to the share folder so viewers and other apps can open it. */
private fun guideFile(context: Context): File {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val f = File(dir, "Supplier-Tracker-User-Guide.pdf")
    context.assets.open(GUIDE_ASSET).use { input -> f.outputStream().use { input.copyTo(it) } }
    return f
}

private fun guideUri(context: Context) =
    FileProvider.getUriForFile(context, context.packageName + ".files", guideFile(context))

private fun openGuideExternally(context: Context, toast: (String) -> Unit) {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(guideUri(context), "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(view)
    } catch (e: ActivityNotFoundException) {
        toast("No PDF app found. Use \"Read user guide\" here, or Share to save it to Drive.")
    }
}

private fun shareGuide(context: Context) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, guideUri(context))
        putExtra(Intent.EXTRA_SUBJECT, "Supplier Tracker – user guide")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share user guide"))
}

/** Problem report text: app, phone and data counts only (no supplier names or quantities). */
fun problemReport(data: Data, date: java.time.LocalDate, lastScreen: String, auto: AppViewModel.AutoState? = null): String = buildString {
    appendLine("Supplier Tracker – problem report")
    appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    appendLine("Date selected in the app: $date · screen: $lastScreen")
    appendLine(
        "Data: ${data.vendors.size} vendors, ${data.vendors.sumOf { it.parts.size }} parts, " +
            "${data.dispatch.size} dispatch entries, ${data.plan.size} weekly plans, last backup ${data.lastBackup.ifBlank { "never" }}",
    )
    if (auto != null) appendLine("Night backup: ${if (auto.enabled) "on" else "off"}${auto.lastError.takeIf { it.isNotBlank() }?.let { ", last error: $it" } ?: ""}")
    appendLine()
    appendLine("What happened:")
    appendLine()
    appendLine("What I expected:")
    appendLine()
    appendLine("What I tapped just before:")
    appendLine()
    append("(Attach a screenshot if you can.)")
}

private val QUICK_FIXES = listOf(
    "Numbers look wrong" to "Check the date at the top, the vendor, and Setup → How this vendor reports (daily quantity or till-date total).",
    "\"No plan\" on a part" to "This week has no plan: Plan tab → type the part's month total (or the week, under Week by week). Also check the supplier's days off in Setup.",
    "\"Not entered\"" to "Nothing typed yet for this week or month. If the supplier sent nothing, type 0.",
    "Typed 0 but the box is not red" to "With no plan today the box turns red only if the part is short to date. Check the week's plan on the Plan tab.",
    "\"Lower than the last till-date total\"" to "Till-date suppliers: type the month total so far, not today's quantity. Nothing today? Tap Nothing today: use … under the box.",
    "Vendor's Excel file: nothing found" to "Pick the right vendor at the top of the import; its part numbers in Setup must match the file's. Pictures and old .xls files can't be read: ask for the .xlsx file.",
    "Typed on the wrong day" to "Go to that day with ‹ ›, clear the box, then type it on the right day.",
    "Report button fails" to "Setup → Report template → Import template again (a .pptx file), or tap Use built-in.",
    "Red banner \"Could not read the saved data\"" to "Do not uninstall. Setup → Open settings → Import / restore from file → pick your latest backup.",
    "Night backup failed" to "Setup → Open settings → Auto backup → Change the folder, then Back up now.",
    "Forgot the backup password" to "It cannot be recovered. Use a backup made before the password was set, or set a new password and make a new backup.",
    "App closes or freezes" to "Open it again – what you typed is saved. Tap Send crash report in the red box at the top (it has no supplier data). If it freezes: App info → Force stop.",
    "Changing phone / reinstalling" to "Back up first (Settings → Share backup). On the new install: Import data file → pick that backup (and type its password).",
)

/** Help & support card, shown at the top of Setup. */
@Composable
fun HelpCard(vm: AppViewModel, toast: (String) -> Unit, onReadGuide: () -> Unit) {
    val ctx = LocalContext.current
    var fixesOpen by remember { mutableStateOf(false) }
    SectionCard(title = "Help & support") {
        Muted("How to fill in the details, what each number means, and what to do if something goes wrong.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Button(onClick = onReadGuide, modifier = Modifier.weight(1f)) { Text("Read user guide") }
            OutlinedButton(onClick = { shareGuide(ctx) }, modifier = Modifier.weight(1f)) { Text("Share guide") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openGuideExternally(ctx, toast) }, modifier = Modifier.weight(1f)) { Text("Open in PDF app") }
            OutlinedButton(
                onClick = { shareText(ctx, problemReport(vm.data, vm.date, "Setup", vm.auto), "Report a problem") },
                modifier = Modifier.weight(1f),
            ) { Text("Report a problem") }
        }
        TextButton(onClick = { fixesOpen = !fixesOpen }) { Text(if (fixesOpen) "Hide quick fixes" else "Quick fixes ▾") }
        if (fixesOpen) {
            QUICK_FIXES.forEach { (problem, fix) ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(problem, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Muted(fix)
                }
            }
        }
    }
}

/** The user guide, rendered page by page inside the app (no PDF app needed). */
@Composable
fun GuideScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val renderer = remember {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(guideFile(ctx), ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
    }
    DisposableEffect(renderer) { onDispose { renderer?.close() } }
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("User guide", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = onClose) { Text("Close") }
            }
            if (renderer == null) {
                Muted("The guide could not be opened here. Use \"Open in PDF app\" or \"Share guide\" in Setup.", Modifier.padding(16.dp))
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(renderer.pageCount) { i -> GuidePage(renderer, i, widthPx) }
            }
        }
    }
}

@Composable
private fun GuidePage(renderer: PdfRenderer, index: Int, widthPx: Int) {
    val ratio = remember(index) { synchronized(renderer) { renderer.openPage(index).use { it.height.toFloat() / it.width } } }
    val bitmap by produceState<ImageBitmap?>(null, index, widthPx) {
        value = withContext(Dispatchers.IO) {
            synchronized(renderer) {
                renderer.openPage(index).use { page ->
                    val b = Bitmap.createBitmap(widthPx, (widthPx * ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b.asImageBitmap()
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp).aspectRatio(1f / ratio).background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(2.dp))) {
        bitmap?.let { Image(it, contentDescription = "Guide page ${index + 1}", modifier = Modifier.fillMaxSize()) }
    }
}
