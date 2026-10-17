package app.suppliertracker.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.suppliertracker.data.Store
import app.suppliertracker.model.Data
import app.suppliertracker.model.Issue
import app.suppliertracker.model.Part
import app.suppliertracker.model.Period
import app.suppliertracker.model.Vendor
import app.suppliertracker.model.parseQty
import app.suppliertracker.model.periodOf
import app.suppliertracker.model.pkey
import app.suppliertracker.model.prevCumulative
import app.suppliertracker.model.qtyText
import app.suppliertracker.model.recomputeCumulative
import app.suppliertracker.data.AutoBackup
import app.suppliertracker.data.BackupCrypto
import app.suppliertracker.data.BackupSettings
import app.suppliertracker.data.CrashLog
import app.suppliertracker.model.Codec
import app.suppliertracker.model.ReportPeriod
import app.suppliertracker.model.periodReport
import app.suppliertracker.report.PptxTemplate
import app.suppliertracker.report.ReviewDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val COMPANY_TEMPLATE = "company-template.pptx"

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val backupSettings = BackupSettings(app)

    var data by mutableStateOf(Data())
        private set
    var loaded by mutableStateOf(false)
        private set

    /** Set when the saved file could not be read; saving stays off so nothing overwrites it. */
    var loadError by mutableStateOf<String?>(null)
        private set
    var vendorId by mutableStateOf("")
        private set
    var date: LocalDate by mutableStateOf(LocalDate.now())
        private set

    /** True when a company template (.pptx) has been imported for the weekly report. */
    var customTemplate by mutableStateOf(store.hasReportTemplate())
        private set

    /** The company template built into this copy of the app (private builds only), if any. */
    val companyTemplate: Boolean = runCatching { app.assets.open(COMPANY_TEMPLATE).close() }.isSuccess

    /** Which template the report uses: imported in Setup, else the company one built in, else the plain one. */
    val templateLabel: String
        get() = when {
            customTemplate -> "your imported template"
            companyTemplate -> "your company template"
            else -> "built-in plain template"
        }

    /** Department shown in the report footer. */
    var department by mutableStateOf(prefs.getString("department", "SCM") ?: "SCM")
        private set

    /** Ask for fingerprint / screen lock when the app opens. */
    var lockEnabled by mutableStateOf(prefs.getBoolean("lock", true))
        private set

    // ---------- appearance (Settings) ----------

    enum class ThemeMode(val label: String) { PHONE("Phone"), LIGHT("Light"), DARK("Dark") }

    var themeMode by mutableStateOf(runCatching { ThemeMode.valueOf(prefs.getString("theme", "PHONE")!!) }.getOrDefault(ThemeMode.PHONE))
        private set

    /** Colours from the phone's wallpaper (Android 12+) instead of the app's blue. */
    var phoneColours by mutableStateOf(prefs.getBoolean("phoneColours", false))
        private set

    /** Multiplies the phone's own font size: 0.9 small, 1 normal, 1.15 large, 1.3 extra large. */
    var textScale by mutableStateOf(prefs.getFloat("textScale", 1f))
        private set

    /** Plan tab: type one total per month (split into weeks), or week by week. */
    var planByMonth by mutableStateOf(prefs.getBoolean("planByMonth", true))
        private set

    fun updatePlanByMonth(on: Boolean) {
        planByMonth = on
        prefs.edit().putBoolean("planByMonth", on).apply()
    }

    fun updateTheme(m: ThemeMode) {
        themeMode = m
        prefs.edit().putString("theme", m.name).apply()
    }

    fun updatePhoneColours(on: Boolean) {
        phoneColours = on
        prefs.edit().putBoolean("phoneColours", on).apply()
    }

    fun updateTextScale(x: Float) {
        textScale = x
        prefs.edit().putFloat("textScale", x).apply()
    }

    /** The last crash, kept on the phone until it is sent or dismissed. */
    var lastCrash by mutableStateOf(CrashLog.read(app))
        private set

    fun dismissCrash() {
        CrashLog.clear(getApplication())
        lastCrash = null
    }

    /** Raw text typed in Entry boxes, so "1,2" is not reformatted mid-typing. Key: vendor|part|date. */
    val drafts = mutableStateMapOf<String, String>()

    val vendor: Vendor? get() = data.vendors.firstOrNull { it.id == vendorId } ?: data.vendors.firstOrNull()

    init {
        // keep the nightly backup scheduled (WorkManager keeps it across restarts; this repairs it after an update)
        if (backupSettings.autoEnabled) runCatching { AutoBackup.schedule(app, restart = false) }
        viewModelScope.launch {
            try {
                data = withContext(Dispatchers.IO) { store.load() }
            } catch (e: Exception) {
                loadError = e.message ?: "The saved data could not be read."
            }
            val saved = prefs.getString("vendorId", null)
            vendorId = saved?.takeIf { id -> data.vendors.any { it.id == id } } ?: data.vendors.firstOrNull()?.id.orEmpty()
            loaded = true
        }
    }

    // ---------- persistence ----------

    private var saveJob: Job? = null

    private fun update(f: (Data) -> Data) {
        data = f(data)
        if (loadError != null) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            val snapshot = data
            withContext(Dispatchers.IO) { store.save(snapshot) }
        }
    }

    /** Called when the app goes to the background, so nothing typed is lost. */
    fun saveNow() {
        if (!loaded || loadError != null) return
        saveJob?.cancel()
        store.save(data)
    }

    // ---------- selection ----------

    fun selectVendor(id: String) {
        vendorId = id
        drafts.clear()
        prefs.edit().putString("vendorId", id).apply()
    }

    fun selectDate(d: LocalDate) {
        date = d
        drafts.clear()
    }

    // ---------- daily entry ----------

    private fun entryKey(v: Vendor, partId: String) = pkey(v.id, partId, date.toString())

    fun entryText(v: Vendor, partId: String): String {
        val k = entryKey(v, partId)
        drafts[k]?.let { return it }
        return qtyText(if (v.cumulative) data.cumulative[k] else data.dispatch[k])
    }

    /** Saves as you type. Blank clears the entry; text that is not a number is kept on screen only. */
    fun setEntry(v: Vendor, partId: String, text: String) {
        val k = entryKey(v, partId)
        drafts[k] = text
        val value = parseQty(text)
        if (text.isNotBlank() && value == null) return
        val ym = YearMonth.from(date)
        update { d ->
            if (v.cumulative) {
                val cum = if (value == null) d.cumulative - k else d.cumulative + (k to value)
                recomputeCumulative(d.copy(cumulative = cum), v.id, partId, ym)
            } else {
                d.copy(dispatch = if (value == null) d.dispatch - k else d.dispatch + (k to value))
            }
        }
    }

    /** Daily quantity implied by the entry box (cumulative mode subtracts the last till-date total). */
    fun entryDaily(v: Vendor, partId: String): Double? {
        val value = parseQty(entryText(v, partId)) ?: return null
        return if (v.cumulative) value - prevCumulative(data, v.id, partId, date) else value
    }

    fun enteredCount(v: Vendor): Int = v.activeParts.count { entryText(v, it.id).isNotBlank() }

    // ---------- plan ----------

    fun setPlan(v: Vendor, partId: String, periodStart: LocalDate, value: Double?) =
        update { app.suppliertracker.model.setPlan(it, v.id, partId, periodStart, value) }

    /** Copies last week's plan into this week for every part that had one. Returns how many were copied. */
    fun copyPreviousWeek(v: Vendor, period: Period): Int {
        val prev = periodOf(period.start.minusDays(1))
        var n = 0
        update { d ->
            var out = d
            for (p in v.activeParts) {
                val x = d.plan[pkey(v.id, p.id, prev.start.toString())] ?: continue
                out = app.suppliertracker.model.setPlan(out, v.id, p.id, period.start, x)
                n++
            }
            out
        }
        return n
    }

    /**
     * A whole month's plan for one part, split into its weeks by dispatch days. Weeks already under way at the date
     * at the top keep their plan (see splitMonthPlan); blank clears the other weeks. Returns a note for the user, or null.
     */
    fun setMonthPlan(v: Vendor, partId: String, ym: YearMonth, total: Double?): String? {
        if (total != null && (total < 0 || !total.isFinite())) return "A plan can't be below 0."
        val split = app.suppliertracker.model.splitMonthPlan(data, v, partId, ym, total ?: 0.0, date)
        val month = app.suppliertracker.model.niceMonth(ym)
        if (split.weeks.isEmpty()) return "Every week of $month has ended, so its plan stays. To change one week, use Week by week."
        val next = split.weeks.entries.fold(data) { acc, (start, q) ->
            when {
                total == null -> app.suppliertracker.model.setPlan(acc, v.id, partId, start, null)
                // a week with no dispatch day and no plan yet stays empty rather than showing a plan of 0
                q == 0.0 && pkey(v.id, partId, start.toString()) !in acc.plan &&
                    app.suppliertracker.model.workDays(v, start, periodOf(start).end, acc.holidays) == 0 -> acc
                else -> app.suppliertracker.model.setPlan(acc, v.id, partId, start, q)
            }
        }
        if (next != data) update { next }
        return when {
            total == null && split.keptWeeks.isNotEmpty() -> "Cleared the weeks to come. Weeks already under way keep their plan."
            total != null && split.kept > total ->
                "Weeks already under way have ${app.suppliertracker.model.num(split.kept)}, more than ${app.suppliertracker.model.num(total)}. " +
                    "The weeks to come are set to 0."
            else -> null
        }
    }

    fun setBacklog(v: Vendor, partId: String, ym: YearMonth, value: Double?) = update { d ->
        val k = pkey(v.id, partId, ym.toString())
        d.copy(openingBacklog = if (value == null) d.openingBacklog - k else d.openingBacklog + (k to value))
    }

    // ---------- setup ----------

    fun updateVendor(v: Vendor) = update { d -> d.copy(vendors = d.vendors.map { if (it.id == v.id) v else it }) }

    fun addVendor(name: String) {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifEmpty { "vendor" }
        var id = base
        while (data.vendors.any { it.id == id }) id = "$base-${UUID.randomUUID().toString().take(3)}"
        update { it.copy(vendors = it.vendors + Vendor(id = id, name = name.trim())) }
        selectVendor(id)
    }

    fun deleteVendor(id: String) {
        val prefix = "$id|"
        fun Map<String, Double>.drop() = filterKeys { !it.startsWith(prefix) }
        update { d ->
            d.copy(
                vendors = d.vendors.filter { it.id != id },
                plan = d.plan.drop(), planOrig = d.planOrig.drop(), dispatch = d.dispatch.drop(),
                cumulative = d.cumulative.drop(), openingBacklog = d.openingBacklog.drop(),
                commitment = d.commitment.drop(), closedPlan = d.closedPlan.drop(), closedActual = d.closedActual.drop(),
                issues = d.issues.filter { it.vendorId != id },
                notes = d.notes.filter { it.vendorId != id },
            )
        }
        if (vendorId == id) selectVendor(data.vendors.firstOrNull()?.id.orEmpty())
    }

    fun addPart(v: Vendor): Part {
        val p = Part(id = "p" + UUID.randomUUID().toString().take(8), critical = false)
        updateVendor(v.copy(parts = v.parts + p))
        return p
    }

    fun updatePart(v: Vendor, part: Part) = updateVendor(v.copy(parts = v.parts.map { if (it.id == part.id) part else it }))

    /** Removes the part from the lists. Its plans and dispatch stay in the data (and in backups). */
    fun removePart(v: Vendor, partId: String) = updateVendor(v.copy(parts = v.parts.filter { it.id != partId }))

    fun addHoliday(d: LocalDate) = update { if (d.toString() in it.holidays) it else it.copy(holidays = (it.holidays + d.toString()).sorted()) }

    fun removeHoliday(d: String) = update { it.copy(holidays = it.holidays - d) }

    /** Adds this supplier's own days off, from [from] to [to] inclusive (a shutdown or a single day). */
    fun addVendorHolidays(v: Vendor, from: LocalDate, to: LocalDate) {
        val days = app.suppliertracker.model.dates(minOf(from, to), maxOf(from, to)).map { it.toString() }.toList()
        updateVendor(v.copy(holidays = (v.holidays + days).distinct().sorted()))
    }

    fun removeVendorHolidays(v: Vendor, from: LocalDate, to: LocalDate) {
        val days = app.suppliertracker.model.dates(from, to).map { it.toString() }.toSet()
        updateVendor(v.copy(holidays = v.holidays.filter { it !in days }))
    }

    // ---------- reasons ----------

    fun addReason(v: Vendor, partId: String, cause: String, action: String, owner: String, due: LocalDate?) = update {
        it.copy(
            issues = it.issues + Issue(
                id = "i" + UUID.randomUUID().toString().take(8), vendorId = v.id, partId = partId, cause = cause,
                action = action.trim(), owner = owner.trim(), due = due?.toString().orEmpty(), logged = date.toString(),
            ),
        )
    }

    fun closeReason(id: String) = update { d -> d.copy(issues = d.issues.map { if (it.id == id) it.copy(status = "closed") else it }) }

    /** Changes a reason; the day it was added stays. */
    fun editReason(id: String, cause: String, action: String, owner: String, due: LocalDate?) = update { d ->
        d.copy(issues = d.issues.map { if (it.id == id) it.copy(cause = cause, action = action.trim(), owner = owner.trim(), due = due?.toString().orEmpty()) else it })
    }

    // ---------- dated notes ----------

    /** A note about the vendor (blank [partId]) or one of its parts, for [day]. Blank text is not saved. */
    fun addNote(v: Vendor, partId: String, day: LocalDate, text: String) {
        if (text.isBlank()) return
        update {
            it.copy(notes = it.notes + app.suppliertracker.model.Note(
                id = "n" + UUID.randomUUID().toString().take(8), vendorId = v.id, partId = partId, date = day.toString(), text = text.trim(),
            ))
        }
    }

    /** Blank text deletes the note. */
    fun editNote(id: String, text: String) = update { d ->
        if (text.isBlank()) d.copy(notes = d.notes.filterNot { it.id == id })
        else d.copy(notes = d.notes.map { if (it.id == id) it.copy(text = text.trim(), edited = LocalDate.now().toString()) else it })
    }

    fun deleteNote(id: String) = update { d -> d.copy(notes = d.notes.filterNot { it.id == id }) }

    // ---------- weekly report ----------

    fun importReportTemplate(bytes: ByteArray) {
        PptxTemplate(bytes) // throws with a readable message if this is not a .pptx
        store.saveReportTemplate(bytes)
        customTemplate = true
    }

    fun useBuiltInTemplate() {
        store.saveReportTemplate(null)
        customTemplate = false
    }

    fun updateDepartment(text: String) {
        department = text
        prefs.edit().putString("department", text.trim()).apply()
    }

    // ---------- report period (Dashboard) ----------

    var reportKind by mutableStateOf(runCatching { ReportPeriod.Kind.valueOf(prefs.getString("reportKind", "MONTH_TO_DATE")!!) }.getOrDefault(ReportPeriod.Kind.MONTH_TO_DATE))
        private set
    var customFrom by mutableStateOf<LocalDate?>(null)
        private set
    var customTo by mutableStateOf<LocalDate?>(null)
        private set

    /** The period shown on the Dashboard and used for the PowerPoint, around the date chosen at the top. */
    val period: ReportPeriod
        get() = ReportPeriod.of(reportKind, date, customFrom ?: date.withDayOfMonth(1), customTo ?: date)

    fun updateReportKind(k: ReportPeriod.Kind) {
        reportKind = k
        prefs.edit().putString("reportKind", k.name).apply()
    }

    fun updateCustomRange(from: LocalDate, to: LocalDate) {
        customFrom = minOf(from, to)
        customTo = maxOf(from, to)
        updateReportKind(ReportPeriod.Kind.CUSTOM)
    }

    /** Builds the PowerPoint for [period], on the imported template or the built-in one. */
    suspend fun buildReport(p: ReportPeriod = period): ByteArray {
        val snapshot = data
        val dept = department.trim().ifBlank { "SCM" }
        return withContext(Dispatchers.Default) {
            val assets = getApplication<Application>().assets
            val template = store.reportTemplate()
                ?: (if (companyTemplate) assets.open(COMPANY_TEMPLATE).use { it.readBytes() } else null)
                ?: assets.open("report-template.pptx").use { it.readBytes() }
            ReviewDeck.build(template, periodReport(snapshot, p), dept)
        }
    }

    // ---------- security ----------

    fun setLock(on: Boolean) {
        lockEnabled = on
        prefs.edit().putBoolean("lock", on).apply()
    }

    // ---------- backup ----------

    /** Replaces everything on the phone with an imported data file. */
    fun replaceAll(newData: Data) {
        loadError = null
        drafts.clear()
        update { newData }
        selectVendor(newData.vendors.firstOrNull()?.id.orEmpty())
    }

    fun markBackedUp() = update { it.copy(lastBackup = LocalDate.now().toString()) }

    /** True when backups are locked with a password. */
    var backupLocked by mutableStateOf(backupSettings.password != null)
        private set

    fun updateBackupPassword(password: String?) {
        backupSettings.password = password?.takeIf { it.isNotEmpty() }
        backupLocked = backupSettings.password != null
    }

    /** The backup file's text: plain JSON, or locked with the backup password. Slow when locked (key stretching). */
    suspend fun backupText(): String {
        val plain = Codec.encode(data)
        val pw = backupSettings.password ?: return plain
        return withContext(Dispatchers.Default) { BackupCrypto.encrypt(plain, pw) }
    }

    /** True while "Save to phone" writes its file. */
    var savingBackup by mutableStateOf(false)
        private set

    /** What happened to the last "Save to phone", for a message on Settings; cleared once shown. */
    var saveBackupNote by mutableStateOf<String?>(null)

    /**
     * Writes a backup into the file picked with "Save to phone". It runs here rather than on the screen, so a
     * fingerprint relock on the way back from the picker (which closes Settings for a moment) cannot cut it short.
     */
    fun saveBackupTo(uri: android.net.Uri) {
        savingBackup = true
        viewModelScope.launch {
            try {
                val text = backupText()
                withContext(Dispatchers.IO) { app.suppliertracker.data.writeText(getApplication(), uri, text) }
                markBackedUp()
                saveBackupNote = "Backup saved"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                saveBackupNote = e.message ?: "Could not save the backup."
            } finally {
                savingBackup = false
            }
        }
    }

    // ---------- auto backup ----------

    data class AutoState(val enabled: Boolean, val folder: String, val hasFolder: Boolean, val lastAt: Long, val lastFile: String, val lastError: String)

    var auto by mutableStateOf(readAuto())
        private set

    private fun readAuto() = AutoState(
        backupSettings.autoEnabled, AutoBackup.folderName(getApplication(), backupSettings.folder), backupSettings.folder != null,
        backupSettings.lastAt, backupSettings.lastFile, backupSettings.lastError,
    )

    /** Re-reads what the night backup last did (it runs in the background). */
    fun refreshAuto() {
        auto = readAuto()
    }

    fun chooseBackupFolder(uri: android.net.Uri) {
        AutoBackup.keepAccess(getApplication(), uri)
        backupSettings.folder = uri
        setAutoBackup(true)
    }

    fun setAutoBackup(on: Boolean) {
        backupSettings.autoEnabled = on && backupSettings.folder != null
        runCatching {
            if (backupSettings.autoEnabled) AutoBackup.schedule(getApplication(), restart = true) else AutoBackup.cancel(getApplication())
        }
        refreshAuto()
    }

    /** "Back up now": saves first so the backup has everything typed so far. Returns the file name. */
    suspend fun backUpNow(): String {
        saveNow()
        return try {
            withContext(Dispatchers.IO) { AutoBackup.run(getApplication()) }
        } finally {
            refreshAuto()
        }
    }

    // ---------- import / restore ----------

    sealed interface PendingImport {
        /** A locked backup waiting for its password. */
        data class Locked(val text: String, val error: String? = null) : PendingImport

        /** A readable file waiting for "Replace everything?" */
        data class Ready(val data: Data) : PendingImport
    }

    var pendingImport by mutableStateOf<PendingImport?>(null)
        private set

    /** Starts an import. Returns a message when the data was loaded straight away (first launch). */
    fun beginImport(text: String): String? {
        if (BackupCrypto.isEncrypted(text)) {
            pendingImport = PendingImport.Locked(text)
            return null
        }
        return accept(Codec.decode(text))
    }

    private fun accept(d: Data): String? {
        if (data.vendors.isEmpty()) {
            pendingImport = null
            replaceAll(d)
            return "Imported ${d.vendors.size} vendors, ${d.vendors.sumOf { it.parts.size }} parts"
        }
        pendingImport = PendingImport.Ready(d)
        return null
    }

    suspend fun unlockImport(password: String): String? {
        val locked = pendingImport as? PendingImport.Locked ?: return null
        return try {
            val text = withContext(Dispatchers.Default) { BackupCrypto.decrypt(locked.text, password) }
            accept(Codec.decode(text))
        } catch (e: IllegalArgumentException) {
            pendingImport = locked.copy(error = e.message)
            null
        }
    }

    fun confirmImport() {
        (pendingImport as? PendingImport.Ready)?.let { replaceAll(it.data) }
        pendingImport = null
    }

    fun cancelImport() {
        pendingImport = null
    }

    // ---------- a vendor's own Excel report ----------

    /** A vendor file being imported: what was chosen so far. Sheets are kept by name. */
    data class VendorFile(
        val fileName: String,
        val sheets: List<app.suppliertracker.importer.Sheet>,
        val vendorId: String,
        val dispatchSheet: String?,
        val planSheet: String?,
        val cols: app.suppliertracker.importer.Columns = app.suppliertracker.importer.Columns(),
        val usePlan: Boolean = true,
        val missingAsZero: Boolean = true,
        val replaceEntered: Boolean = true,
    )

    var vendorFile by mutableStateOf<VendorFile?>(null)
        private set

    /** The last import, until anything else changes: the data before it, the data after it and what it did. */
    data class LastImport(val before: Data, val after: Data, val summary: String)

    var lastImport by mutableStateOf<LastImport?>(null)
        private set

    /** Reads a shared or picked Excel / CSV file and finds its vendor, sheets and columns. Returns an error, or null. */
    fun openVendorFile(bytes: ByteArray, fileName: String): String? {
        if (bytes.size >= 4 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte()) {
            return "This is the old Excel format (.xls). Open it in Excel and save it as .xlsx, or ask the vendor to send .xlsx."
        }
        val sheets = try {
            if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) app.suppliertracker.importer.Xlsx.read(bytes)
            else listOf(app.suppliertracker.importer.Xlsx.readCsv(String(bytes, Charsets.UTF_8), fileName.substringBeforeLast('.')))
        } catch (e: IllegalArgumentException) {
            return e.message
        }
        if (sheets.isEmpty()) return "No sheets found in this file."
        if (data.vendors.isEmpty()) return "Add your vendors first (Setup), then import their files."
        val guess = app.suppliertracker.importer.guessVendor(sheets, data.vendors)
        val v = guess?.first ?: vendor ?: data.vendors.first()
        lastImport = null
        vendorFile = chooseFor(VendorFile(fileName, sheets, v.id, null, null), v)
        return if (guess == null) "None of your vendors' part numbers are in this file. Check the vendor and the sheet." else null
    }

    /** Picks the sheets for [v]: the ones remembered from its last import, else the best ones found. */
    private fun chooseFor(f: VendorFile, v: Vendor): VendorFile {
        val format = data.importFormats[v.id]
        val cols = format?.let { app.suppliertracker.importer.Columns(it.partCol, it.dateCol, it.qtyCol) } ?: app.suppliertracker.importer.Columns()
        val reads = f.sheets.map { app.suppliertracker.importer.readSheet(it, v, YearMonth.from(date)) }
        val (d, p) = app.suppliertracker.importer.chooseSheets(reads, format?.dispatchSheet.orEmpty(), format?.planSheet.orEmpty())
        return f.copy(
            vendorId = v.id, dispatchSheet = d?.sheet?.name, planSheet = p?.sheet?.name, cols = cols,
            usePlan = format?.usePlan ?: true,
            missingAsZero = format?.missingAsZero ?: (d?.layout == app.suppliertracker.importer.Layout.LIST),
        )
    }

    /** A message about a shared file for the screen to show (cleared once shown). */
    var importMessage by mutableStateOf<String?>(null)

    /** A file shared to the app (WhatsApp, email…): read once the data has loaded. */
    fun openSharedFile(read: suspend () -> Pair<ByteArray, String>) {
        viewModelScope.launch {
            val (bytes, name) = try {
                withContext(Dispatchers.IO) { read() }
            } catch (e: Exception) {
                importMessage = e.message ?: "Could not open that file."
                return@launch
            }
            androidx.compose.runtime.snapshotFlow { loaded }.first { it }
            openVendorFile(bytes, name)?.let { importMessage = it }
        }
    }

    fun vendorFileVendor(id: String) {
        val f = vendorFile ?: return
        val v = data.vendors.firstOrNull { it.id == id } ?: return
        vendorFile = chooseFor(f.copy(cols = app.suppliertracker.importer.Columns()), v)
    }

    fun vendorFileChange(change: (VendorFile) -> VendorFile) {
        vendorFile = vendorFile?.let(change)
    }

    /** What each sheet holds for the chosen vendor (the column choices apply to the dispatch sheet). */
    fun vendorFileReads(f: VendorFile): List<app.suppliertracker.importer.SheetRead> {
        val v = data.vendors.firstOrNull { it.id == f.vendorId } ?: return emptyList()
        return f.sheets.map { s ->
            app.suppliertracker.importer.readSheet(s, v, YearMonth.from(date), if (s.name == f.dispatchSheet) f.cols else app.suppliertracker.importer.Columns())
        }
    }

    fun vendorFilePreview(f: VendorFile): app.suppliertracker.importer.ImportPreview? {
        val v = data.vendors.firstOrNull { it.id == f.vendorId } ?: return null
        val reads = vendorFileReads(f)
        val p = app.suppliertracker.importer.previewImport(
            data, v, reads.firstOrNull { it.sheet.name == f.dispatchSheet }, reads.firstOrNull { it.sheet.name == f.planSheet },
            today = LocalDate.now(), missingAsZero = f.missingAsZero, usePlan = f.usePlan,
        )
        if (f.replaceEntered) return p
        // keep what was typed: only days with nothing entered yet
        val typed = p.changes.map { it.partId to it.date }.toSet()
        return p.copy(targets = p.targets.filterKeys { it !in typed }, changes = emptyList())
    }

    /** Saves the import, remembers the vendor's format, and keeps the data from before for Undo. */
    fun saveVendorFile(): String? {
        val f = vendorFile ?: return null
        val v = data.vendors.firstOrNull { it.id == f.vendorId } ?: return null
        val p = vendorFilePreview(f) ?: return null
        val before = data
        val key = { s: String -> app.suppliertracker.importer.sheetKey(s) }
        val format = app.suppliertracker.model.ImportFormat(
            dispatchSheet = f.dispatchSheet?.let(key).orEmpty(), planSheet = f.planSheet?.let(key).orEmpty(),
            partCol = f.cols.part, dateCol = f.cols.date, qtyCol = f.cols.qty, usePlan = f.usePlan, missingAsZero = f.missingAsZero,
        )
        drafts.clear()
        update { d -> app.suppliertracker.importer.applyImport(d, p).copy(importFormats = d.importFormats + (v.id to format)) }
        val summary = buildString {
            fun plural(n: Int, one: String) = "$n $one" + if (n == 1) "" else "s"
            if (p.days > 0) append("${v.name}: ${plural(p.days, "day")} of dispatch saved" + (p.from?.let { " (${app.suppliertracker.model.niceDate(it)} – ${app.suppliertracker.model.niceDate(p.to!!)})" } ?: ""))
            if (p.planChanges.isNotEmpty()) append((if (isNotEmpty()) ", " else "${v.name}: ") + "${plural(p.planChanges.size, "week plan")} updated")
            if (isEmpty()) append("Nothing new in this file for ${v.name}")
        }
        lastImport = LastImport(before, data, summary)
        selectVendor(v.id)
        vendorFile = null
        return summary
    }

    fun cancelVendorFile() {
        vendorFile = null
    }

    /** Undo is offered only while nothing else has changed since the import. */
    val canUndoImport: Boolean get() = lastImport?.after === data

    fun undoImport(): Boolean {
        val l = lastImport ?: return false
        if (l.after !== data) return false
        drafts.clear()
        update { l.before }
        lastImport = null
        return true
    }

    fun dismissImportResult() {
        lastImport = null
    }

    fun backupDue(): Boolean {
        if (data.vendors.isEmpty()) return false
        val last = data.lastBackup.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return true
        return ChronoUnit.DAYS.between(last, LocalDate.now()) >= 7
    }
}
