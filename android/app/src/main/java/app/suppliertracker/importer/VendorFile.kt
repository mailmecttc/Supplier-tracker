// Finds a vendor's dispatch and plan figures in its own Excel report, whatever the layout. Pure: no Android, no storage.
package app.suppliertracker.importer

import app.suppliertracker.model.Data
import app.suppliertracker.model.Period
import app.suppliertracker.model.Vendor
import app.suppliertracker.model.dates
import app.suppliertracker.model.isWork
import app.suppliertracker.model.periodOf
import app.suppliertracker.model.periodsOfMonth
import java.time.LocalDate
import java.time.YearMonth

/** The three report layouts seen from suppliers. */
enum class Layout(val label: String) {
    LIST("One row per dispatch"),
    DATE_COLUMNS("A column per date"),
    WEEK_COLUMNS("A column per week"),
}

/** Letters and digits only, upper case: "Z 123 456 78 90" and "Z1234567890" are the same part number. */
fun normKey(s: String): String = s.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

/** Finds which of a vendor's parts a cell names: by part number (also at the start of "Z 123 … 90_BRACKET"), else by name. */
class PartMatcher(v: Vendor) {
    // long enough to be safe: 6 characters, or 4 with a letter (a bare "1080" could be a quantity)
    private val numbers = v.parts.mapNotNull { p -> normKey(p.partNo).takeIf { it.length >= 6 || it.length >= 4 && it.any(Char::isLetter) }?.let { it to p.id } }
        .sortedByDescending { it.first.length }
    private val names = v.parts.mapNotNull { p -> normKey(p.desc).takeIf { it.length >= 4 }?.let { it to p.id } }.toMap()

    fun match(text: String): String? {
        val t = normKey(text)
        if (t.length < 4) return null
        numbers.firstOrNull { (k, _) -> t == k || t.startsWith(k) }?.let { return it.second }
        numbers.firstOrNull { (k, _) -> k.length >= 7 && t.contains(k) }?.let { return it.second }
        return names[t]
    }

    /** The cell is exactly one of the part numbers (not a number with a description after it). */
    fun isExact(text: String): Boolean = normKey(text).let { t -> numbers.any { it.first == t } }

    /** True when a cell looks like a part number this vendor does not have (letters and digits, long enough). */
    fun looksLikePartNo(text: String): Boolean {
        val t = normKey(text)
        return t.length >= 6 && t.any { it.isDigit() } && t.count { it.isDigit() } >= 4
    }
}

// ---------- reading dates and labels ----------

private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

/** A cell as a date: an Excel date, or text such as 03-10-2026, 05.10.26, 3/10/2026, 2026-10-03, 5-Oct-26. Day first. */
fun asDate(v: Any?, yearHint: Int): LocalDate? = when (v) {
    is LocalDate -> v
    is Double -> null // a plain number is a quantity, not a date (Excel dates arrive as LocalDate)
    is String -> parseDate(v.trim(), yearHint)
    else -> null
}

private fun year(y: String): Int = y.toInt().let { if (y.length <= 2) 2000 + it else it }

fun parseDate(s: String, yearHint: Int): LocalDate? {
    Regex("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})$").find(s)?.let { m ->
        return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
    }
    Regex("^(\\d{1,2})[-/.](\\d{1,2})(?:[-/.](\\d{2,4}))?$").find(s)?.let { m ->
        val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.let(::year) ?: yearHint
        return runCatching { LocalDate.of(y, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
    }
    Regex("^(\\d{1,2})(?:st|nd|rd|th)?[-\\s/.']*([A-Za-z]{3,9})[-\\s/.',]*(\\d{2,4})?$").find(s)?.let { m ->
        val mi = MONTHS.indexOf(m.groupValues[2].take(3).lowercase())
        if (mi < 0) return null
        val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.let(::year) ?: yearHint
        return runCatching { LocalDate.of(y, mi + 1, m.groupValues[1].toInt()) }.getOrNull()
    }
    return null
}

private val DATE_TOKEN = Regex("\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}")

/** "05.10.26–10.10.26" (any dash or a line break between): the first and last day of a week. */
fun parseDateRange(s: String, yearHint: Int): Pair<LocalDate, LocalDate>? {
    val ds = DATE_TOKEN.findAll(s).mapNotNull { parseDate(it.value, yearHint) }.toList()
    if (ds.size < 2) return null
    val (a, b) = ds[0] to ds[1]
    return if (b >= a && b <= a.plusDays(10)) a to b else null
}

/** A plain number that is really an Excel date (a date column saved without a date format), near [around]. */
fun serialDate(x: Double?, around: YearMonth): LocalDate? {
    if (x == null || x != Math.floor(x)) return null
    val d = Xlsx.excelDate(x)
    return d.takeIf { it >= around.atDay(1).minusDays(400) && it <= around.atEndOfMonth().plusDays(400) }
}

/** A month named in text such as "OCT-26", "Oct'26", "Oct Plan", "for the month of Oct-2026". */
fun monthIn(text: String, yearHint: Int): YearMonth? {
    val m = Regex("(?i)\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\b[\\s'’\\-_.]*(\\d{2,4})?").find(text) ?: return null
    val mi = MONTHS.indexOf(m.groupValues[1].lowercase())
    val y = m.groupValues[2].takeIf { it.length == 2 || it.length == 4 }?.let(::year) ?: yearHint
    return YearMonth.of(y, mi + 1)
}

private val WEEK_NO = Regex("(?i)\\b(?:w|wk|week)\\s*-?\\s*(\\d{1,2})(?!\\d)")

fun isActualWord(s: String): Boolean {
    val t = s.lowercase()
    return "actual" in t || "spatch" in t || "sapatch" in t || "despatch" in t || "supplied" in t || "deliver" in t || t.trim().startsWith("dis")
}

fun isPlanWord(s: String): Boolean {
    val t = s.lowercase()
    return "plan" in t || "target" in t || "schedule" in t || "firm" in t
}

/** A sheet's name without months, weeks and numbers: "OCT Dispatches" and "Sep Dispatches" are both "dispatches". */
fun sheetKey(name: String): String =
    Regex("[a-z]+").findAll(name.lowercase()).map { it.value }
        .filter { w -> w.take(3) !in MONTHS || w.length > 9 }.joinToString(" ")

// ---------- what a sheet holds ----------

/** Everything found in one sheet for one vendor. Keys are (partId, day) or (partId, week start). */
data class SheetRead(
    val sheet: Sheet,
    val layout: Layout?,
    val partCol: Int,
    val dateCol: Int = -1,
    val qtyCol: Int = -1,
    /** The row with the column titles (-1 if none), for naming columns on the screen. */
    val headerRow: Int = -1,
    val month: YearMonth? = null,
    val dispatch: Map<Pair<String, LocalDate>, Double> = emptyMap(),
    val weekActual: Map<Pair<String, LocalDate>, Double> = emptyMap(),
    val dayPlan: Map<Pair<String, LocalDate>, Double> = emptyMap(),
    /** The dates the day-wise plan covers (a week's plan is taken only when all its days are there). */
    val planDates: Set<LocalDate> = emptySet(),
    val weekPlan: Map<Pair<String, LocalDate>, Double> = emptyMap(),
    val monthPlan: Map<String, Double> = emptyMap(),
    /** Parts with a row in the sheet (a figure or a blank in their dispatch row). */
    val partsSeen: Set<String> = emptySet(),
    /** Figures in rows whose part number is not one of the vendor's: text → quantity. */
    val unmatched: Map<String, Double> = emptyMap(),
    val rowsRead: Int = 0,
    /** What was found where, for the screen: "Date: D (Invoice date) · Part no: F · Quantity: G". */
    val found: String = "",
) {
    val hasDispatch: Boolean get() = dispatch.isNotEmpty() || weekActual.isNotEmpty()
    val hasPlan: Boolean get() = dayPlan.isNotEmpty() || weekPlan.isNotEmpty() || monthPlan.isNotEmpty()
    val lastDay: LocalDate? get() = (dispatch.keys.map { it.second } + weekActual.keys.map { it.second }).maxOrNull()
    val firstDay: LocalDate? get() = (dispatch.keys.map { it.second } + weekActual.keys.map { it.second }).minOrNull()
}

/** A column for the screen: "D · Invoice date", or "D · 03-10-2026" from the first figure row when there is no title. */
fun columnTitle(r: SheetRead, col: Int): String {
    val t = if (r.headerRow >= 0) r.sheet.text(r.headerRow, col).trim().replace(Regex("\\s+"), " ") else ""
    return columnName(col) + " · " + t.ifEmpty { "(no title)" }.take(28)
}

/** Column choices the user made on the format screen ( -1 = find it). */
data class Columns(val part: Int = -1, val date: Int = -1, val qty: Int = -1)

/** A column's header text over several rows, without title rows (one text across most of the sheet). */
private fun header(sheet: Sheet, col: Int, rows: List<Int>): String =
    rows.map { sheet.text(it, col).trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" ")

private fun isTitleRow(sheet: Sheet, r: Int): Boolean {
    val texts = (0 until sheet.cols).map { sheet.text(r, it).trim() }.filter { it.isNotEmpty() }
    if (texts.size < 3) return false
    val top = texts.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return false
    return top.value >= texts.size * 0.6 && top.value >= 3
}

private fun colLabel(sheet: Sheet, col: Int, headerRow: Int): String {
    val h = if (headerRow >= 0) sheet.text(headerRow, col).trim().replace(Regex("\\s+"), " ") else ""
    return columnName(col) + if (h.isNotEmpty()) " ($h)" else ""
}

/** Reads one sheet for [v]: finds the part column, the layout and the figures. [month] is used when the sheet names none. */
fun readSheet(sheet: Sheet, v: Vendor, month: YearMonth, cols: Columns = Columns()): SheetRead {
    val matcher = PartMatcher(v)
    // the part column: the one naming most of the vendor's parts
    val partCol = if (cols.part >= 0) cols.part else (0 until sheet.cols).maxWithOrNull(compareBy<Int>(
        { c -> (0 until sheet.rows).count { r -> matcher.match(sheet.text(r, c)) != null } },
        { c -> (0 until sheet.rows).count { r -> matcher.isExact(sheet.text(r, c)) } },
    )) ?: return SheetRead(sheet, null, -1)
    val partRows = (0 until sheet.rows).filter { r -> matcher.match(sheet.text(r, partCol)) != null }
    if (partRows.isEmpty()) return SheetRead(sheet, null, -1, found = "None of ${v.name}'s part numbers are in this sheet.")
    val first = partRows.first()
    val yearHint = month.year

    // ---- one row per dispatch: a column of dates and a column of quantities in the part rows ----
    fun share(c: Int, test: (Any?) -> Boolean) = partRows.count { test(sheet[it, c]) }.toDouble() / partRows.size
    fun dateAt(r: Int, c: Int, serial: Boolean) = asDate(sheet[r, c], yearHint) ?: if (serial) serialDate(sheet[r, c] as? Double, month) else null
    val textDateCol = (0 until sheet.cols).filter { it != partCol }
        .map { c -> c to share(c) { asDate(it, yearHint) != null } }.filter { it.second >= 0.6 }.maxByOrNull { it.second }?.first
    // a date column saved as plain numbers: whole numbers that fall within a year or so of [month]
    val serialCol = if (textDateCol != null) null else (0 until sheet.cols).filter { it != partCol }
        .map { c -> c to share(c) { serialDate(it as? Double, month) != null } }.filter { it.second >= 0.8 }.maxByOrNull { it.second }?.first
    val dateCol = if (cols.date >= 0) cols.date else textDateCol ?: serialCol ?: -1
    val serialDates = cols.date >= 0 || (textDateCol == null && serialCol != null)
    val headerRow = (first - 1 downTo maxOf(0, first - 6)).firstOrNull { r ->
        (0 until sheet.cols).count { c -> sheet[r, c] is String && sheet.number(r, c) == null } >= 2
    } ?: -1
    if (dateCol >= 0) {
        val numeric = (0 until sheet.cols).filter { it != partCol && it != dateCol && share(it) { x -> x is Double } >= 0.6 }
        val qtyCol = if (cols.qty >= 0) cols.qty else numeric.firstOrNull { c ->
            val h = if (headerRow >= 0) sheet.text(headerRow, c).lowercase() else ""
            "qty" in h || "quant" in h || isActualWord(h) || "nos" in h
        } ?: numeric.filter { c ->
            val h = if (headerRow >= 0) sheet.text(headerRow, c).lowercase() else ""
            "inv" !in h && " no" !in " $h" && "code" !in h && partRows.mapNotNull { sheet.number(it, c) }.sorted().let { it.isNotEmpty() && it[it.size / 2] < 1_000_000 }
        }.lastOrNull() ?: -1
        if (qtyCol >= 0) {
            val dispatch = HashMap<Pair<String, LocalDate>, Double>()
            val unmatched = HashMap<String, Double>()
            val seen = HashSet<String>()
            var n = 0
            for (r in 0 until sheet.rows) {
                val d = dateAt(r, dateCol, serialDates) ?: continue
                val q = sheet.number(r, qtyCol) ?: continue
                val text = sheet.text(r, partCol)
                val p = matcher.match(text)
                if (p == null) {
                    if (matcher.looksLikePartNo(text)) unmatched.merge(text.trim(), q, Double::plus)
                    continue
                }
                dispatch.merge(p to d, q, Double::plus)
                seen += p
                n++
            }
            return SheetRead(
                sheet, Layout.LIST, partCol, dateCol, qtyCol, headerRow, month = dispatch.keys.maxOfOrNull { it.second }?.let(YearMonth::from),
                dispatch = dispatch, partsSeen = seen, unmatched = unmatched, rowsRead = n,
                found = "Date: ${colLabel(sheet, dateCol, headerRow)} · Part no: ${colLabel(sheet, partCol, headerRow)} · Quantity: ${colLabel(sheet, qtyCol, headerRow)}",
            )
        }
    }

    // the header block above the first part row (title rows left out), and the sheet's own month if it names one
    val top = maxOf(0, first - 8)
    val headRows = (top until first).filterNot { isTitleRow(sheet, it) }
    val titleText = (0 until minOf(first, 12)).flatMap { r -> (0 until minOf(sheet.cols, 30)).map { c -> sheet.text(r, c) } }.joinToString(" ")
    val sheetMonth = monthIn(sheet.name, yearHint) ?: monthIn(titleText, yearHint)

    // ---- a column per date: a header row of dates ----
    val dateHeader = (first - 1 downTo top).map { r ->
        r to (0 until sheet.cols).filter { it != partCol }.mapNotNull { c -> asDate(sheet[r, c], sheetMonth?.year ?: yearHint)?.let { c to it } }
    }.filter { it.second.size >= 3 }.maxByOrNull { it.second.size }
    if (dateHeader != null) {
        val dateCols = dateHeader.second.toMap()
        val firstDateCol = dateCols.keys.min()
        // a label column saying which rows are plan and which are dispatch
        val labelCol = (0 until firstDateCol).filter { it != partCol }.maxByOrNull { c ->
            (first until sheet.rows).count { r -> sheet.text(r, c).let { t -> t.isNotBlank() && (isPlanWord(t) || isActualWord(t)) && sheet.number(r, c) == null } }
        }?.takeIf { c -> (first until sheet.rows).count { r -> sheet.text(r, c).let { t -> isPlanWord(t) || isActualWord(t) } } >= 2 }
        val sheetIsPlan = isPlanWord(titleText) && !isActualWord(titleText.replace(Regex("(?i)dispatch\\s+plan"), ""))
        val dispatch = HashMap<Pair<String, LocalDate>, Double>()
        val dayPlan = HashMap<Pair<String, LocalDate>, Double>()
        val unmatched = HashMap<String, Double>()
        val seen = HashSet<String>()
        var current: String? = null
        var n = 0
        for (r in first until sheet.rows) {
            val text = sheet.text(r, partCol)
            val matched = matcher.match(text)
            val leftText = (0 until firstDateCol).filter { it != labelCol && it != partCol }.any { sheet.text(r, it).isNotBlank() && matcher.match(sheet.text(r, it)) == null }
            current = when {
                matched != null -> matched
                text.isBlank() && !leftText && labelCol != null -> current // second row of the same part (Plan / Dispatch)
                else -> null
            }
            val label = labelCol?.let { sheet.text(r, it) }.orEmpty()
            val isPlan = if (labelCol != null) isPlanWord(label) && !isActualWord(label) else sheetIsPlan
            val isActual = if (labelCol != null) isActualWord(label) else !sheetIsPlan
            val p = current
            if (p == null) {
                if (matcher.looksLikePartNo(text)) dateCols.keys.mapNotNull { sheet.number(r, it) }.sum().takeIf { it > 0 }?.let { unmatched.merge(text.trim(), it, Double::plus) }
                continue
            }
            if (!isPlan && !isActual) continue
            if (isActual) seen += p
            for ((c, d) in dateCols) {
                val q = sheet.number(r, c) ?: continue
                if (isActual) dispatch.merge(p to d, q, Double::plus) else dayPlan.merge(p to d, q, Double::plus)
            }
            n++
        }
        return SheetRead(
            sheet, Layout.DATE_COLUMNS, partCol, month = sheetMonth ?: YearMonth.from(dateCols.values.min()),
            dispatch = dispatch, dayPlan = dayPlan, planDates = dateCols.values.toSet(), partsSeen = seen, unmatched = unmatched, rowsRead = n,
            found = "Dates: row ${dateHeader.first + 1}, ${dateCols.values.min().let { "${it.dayOfMonth}/${it.monthValue}" }} to " +
                "${dateCols.values.max().let { "${it.dayOfMonth}/${it.monthValue}" }} · Part no: ${columnName(partCol)}" +
                (labelCol?.let { " · Plan / dispatch rows: ${columnName(it)}" } ?: ""),
        )
    }

    // ---- a column per week: W1 / Week41 / 05.10.26–10.10.26, with Plan and Actual ----
    val ym = sheetMonth ?: month
    data class WeekCol(val col: Int, val period: Period?, val actual: Boolean, val monthPlan: Boolean)
    val weekCols = (0 until sheet.cols).filter { it != partCol }.mapNotNull { c ->
        val h = header(sheet, c, headRows)
        if (h.isBlank()) return@mapNotNull null
        val range = parseDateRange(h, ym.year)
        val period = range?.let { periodOf(it.first) }
            ?: WEEK_NO.find(h)?.groupValues?.get(1)?.toInt()?.takeIf { it >= 6 }?.let { n -> periodsOfMonth(ym).firstOrNull { it.week == n } }
        // plan or actual from the header cell nearest the figures ("Plan" / "Actual" / "Week41 Dispatched qty"),
        // so a title above ("Dispatch Plan for the Month…") does not decide it
        val role = headRows.sortedDescending().map { sheet.text(it, c).trim() }.filter { it.isNotEmpty() }
            .firstOrNull { isPlanWord(it) || isActualWord(it) }.orEmpty()
        val actual = isActualWord(role) && !isPlanWord(role)
        when {
            period != null && actual -> WeekCol(c, period, actual = true, monthPlan = false)
            period != null && Regex("(?i)cum|total|balance|\\bbal\\b|stock|\\bfg\\b|wip|yet").containsMatchIn(h) && !isPlanWord(role) -> null
            period != null -> WeekCol(c, period, actual = false, monthPlan = false)
            isPlanWord(role) && !actual && (monthIn(role, ym.year) != null || "month" in role.lowercase() || "firm" in role.lowercase()) &&
                !Regex("(?i)from|to date|machin|balance").containsMatchIn(role) -> WeekCol(c, null, actual = false, monthPlan = true)
            else -> null
        }
    }
    if (weekCols.count { it.period != null } >= 2) {
        val weekActual = HashMap<Pair<String, LocalDate>, Double>()
        val weekPlan = HashMap<Pair<String, LocalDate>, Double>()
        val monthPlan = HashMap<String, Double>()
        val unmatched = HashMap<String, Double>()
        val seen = HashSet<String>()
        var n = 0
        for (r in first until sheet.rows) {
            val text = sheet.text(r, partCol)
            val p = matcher.match(text)
            if (p == null) {
                if (matcher.looksLikePartNo(text)) weekCols.filter { it.actual }.mapNotNull { sheet.number(r, it.col) }.sum().takeIf { it > 0 }
                    ?.let { unmatched.merge(text.trim(), it, Double::plus) }
                continue
            }
            n++
            for (w in weekCols) {
                val q = sheet.number(r, w.col) ?: continue
                when {
                    w.monthPlan -> monthPlan[p] = q
                    w.actual -> { weekActual.merge(p to w.period!!.start, q, Double::plus); seen += p }
                    else -> weekPlan.merge(p to w.period!!.start, q, Double::plus)
                }
            }
        }
        val labels = weekCols.filter { it.period != null }.map { it.period!! }.distinct().sortedBy { it.start }
        return SheetRead(
            sheet, Layout.WEEK_COLUMNS, partCol, month = labels.firstOrNull()?.start?.let(YearMonth::from) ?: ym,
            weekActual = weekActual, weekPlan = weekPlan, monthPlan = monthPlan, partsSeen = seen, unmatched = unmatched, rowsRead = n,
            found = "Weeks: ${labels.joinToString { it.label }} · Part no: ${columnName(partCol)}" +
                (if (weekCols.any { it.actual }) " · plan and actual" else " · plan only"),
        )
    }
    return SheetRead(sheet, null, partCol, found = "Part numbers found in column ${columnName(partCol)}, but no dates or weeks next to them.")
}

// ---------- the whole file ----------

/** The vendor whose part numbers appear most in the file, with how many of its parts were found. */
fun guessVendor(sheets: List<Sheet>, vendors: List<Vendor>): Pair<Vendor, Int>? =
    vendors.map { v ->
        val m = PartMatcher(v)
        v to sheets.flatMap { s -> (0 until s.rows).flatMap { r -> (0 until minOf(s.cols, 40)).mapNotNull { c -> m.match(s.text(r, c)) } } }.toSet().size
    }.filter { it.second > 0 }.maxByOrNull { it.second }

/** The best sheet for dispatch (daily before weekly, then the latest dates) and for plan (the latest that has one). */
fun chooseSheets(reads: List<SheetRead>, rememberedDispatch: String = "", rememberedPlan: String = ""): Pair<SheetRead?, SheetRead?> {
    fun latest(xs: List<SheetRead>) = xs.maxWithOrNull(compareBy<SheetRead>({ it.lastDay ?: LocalDate.MIN }, { reads.indexOf(it) }))
    val withDispatch = reads.filter { it.hasDispatch }
    val dispatch = withDispatch.filter { rememberedDispatch.isNotEmpty() && sheetKey(it.sheet.name) == rememberedDispatch }.let(::latest)
        ?: latest(withDispatch.filter { it.layout != Layout.WEEK_COLUMNS }) ?: latest(withDispatch)
    val withPlan = reads.filter { it.hasPlan }
    fun latestPlan(xs: List<SheetRead>) = xs.maxWithOrNull(compareBy<SheetRead>({ it.month ?: YearMonth.of(1900, 1) }, { reads.indexOf(it) }))
    val plan = withPlan.filter { rememberedPlan.isNotEmpty() && sheetKey(it.sheet.name) == rememberedPlan }.let(::latestPlan)
        ?: dispatch?.takeIf { it.hasPlan } ?: latestPlan(withPlan.filter { it.layout == Layout.WEEK_COLUMNS || it.layout == Layout.DATE_COLUMNS }) ?: latestPlan(withPlan)
    return dispatch to plan
}

// ---------- what an import changes ----------

data class DispatchChange(val partId: String, val date: LocalDate, val old: Double?, val new: Double?)
data class PlanChange(val partId: String, val periodStart: LocalDate, val old: Double?, val new: Double)

/** What saving would do: the day-by-day figures and the plans, with what they replace. */
data class ImportPreview(
    val vendor: Vendor,
    /** New daily figures by (partId, day); null removes a day's figure (a week's total goes on one day). */
    val targets: Map<Pair<String, LocalDate>, Double?>,
    val plans: Map<Pair<String, LocalDate>, Double>,
    val changes: List<DispatchChange>,
    val planChanges: List<PlanChange>,
    val unmatched: Map<String, Double>,
    /** Parts with no row in the file that are counted as 0 on the file's days. */
    val zeroParts: List<String>,
    val from: LocalDate?,
    val to: LocalDate?,
    val notes: List<String>,
) {
    val days: Int get() = targets.filterValues { it != null }.keys.map { it.second }.distinct().size
    val total: Double get() = targets.values.sumOf { it ?: 0.0 }
    val partsWithFigures: Int get() = targets.filterValues { it != null && it > 0 }.keys.map { it.first }.distinct().size
    val empty: Boolean get() = targets.isEmpty() && plans.isEmpty()
}

/**
 * What saving [dispatch] and [plan] would change for [v]. Days run from the file's first to its last day with a figure,
 * never after [today]. On those days a dispatch day with nothing in the file counts as 0 for a part the file covers
 * (and for every active part when [missingAsZero]); a week's total goes on that week's last dispatch day so far.
 */
fun previewImport(
    data: Data, v: Vendor, dispatch: SheetRead?, plan: SheetRead?, today: LocalDate,
    missingAsZero: Boolean, usePlan: Boolean,
): ImportPreview {
    val targets = LinkedHashMap<Pair<String, LocalDate>, Double?>()
    val notes = mutableListOf<String>()
    val active = v.activeParts.map { it.id }.toSet()
    var from: LocalDate? = null
    var to: LocalDate? = null
    val zeroParts = mutableListOf<String>()
    if (dispatch != null) {
        val daily = dispatch.dispatch.filterKeys { it.second <= today }
        if (daily.isNotEmpty()) {
            from = daily.keys.minOf { it.second }
            to = daily.keys.maxOf { it.second }
            val covered = dispatch.partsSeen + daily.keys.map { it.first }
            val parts = (if (missingAsZero) active + covered else covered).filter { it in active || daily.keys.any { k -> k.first == it } }
            zeroParts += (active - covered).filter { missingAsZero }
            for (p in parts) for (d in dates(from, to)) {
                val q = daily[p to d]
                when {
                    q != null -> targets[p to d] = q
                    isWork(v, d, data.holidays) -> targets[p to d] = 0.0
                }
            }
            if (dispatch.dispatch.keys.any { it.second > today }) notes += "Figures dated after ${today} are left out."
        }
        // a week's total: on its last dispatch day so far, the other days of that week cleared
        for ((key, q) in dispatch.weekActual) {
            val (p, start) = key
            val period = periodOf(start)
            val days = dates(period.start, minOf(period.end, today)).toList()
            val day = days.lastOrNull { isWork(v, it, data.holidays) } ?: days.lastOrNull() ?: continue
            for (d in days) targets[p to d] = if (d == day) q else null
            from = minOf(from ?: period.start, period.start)
            to = maxOf(to ?: day, day)
        }
        if (dispatch.weekActual.isNotEmpty()) notes += "${v.name} reports by week: each week's figure is saved on its last dispatch day so far."
    }

    val plans = LinkedHashMap<Pair<String, LocalDate>, Double>()
    if (usePlan && plan != null) {
        plans += plan.weekPlan
        // a day-wise plan counts for a week only when the file has every day of that week
        plan.dayPlan.keys.map { periodOf(it.second) }.distinct().forEach { per ->
            if (dates(per.start, per.end).all { it in plan.planDates }) {
                plan.dayPlan.keys.map { it.first }.distinct().forEach { p ->
                    plans.putIfAbsent(p to per.start, dates(per.start, per.end).sumOf { plan.dayPlan[p to it] ?: 0.0 })
                }
            }
        }
        // a month total only for parts with no week plan in the file: split like the Plan tab's month box
        val ym = plan.month
        if (ym != null) {
            for ((p, total) in plan.monthPlan) {
                if (plans.keys.any { it.first == p && YearMonth.from(it.second) == ym }) continue
                val s = app.suppliertracker.model.splitMonthPlan(data, v, p, ym, total, today)
                s.weeks.forEach { (start, q) -> plans[p to start] = q }
            }
        }
    }
    // only what really changes
    val planChanges = plans.mapNotNull { (k, q) ->
        val key = app.suppliertracker.model.pkey(v.id, k.first, k.second.toString())
        val old = data.plan[key]
        if (old != null && kotlin.math.abs(old - q) < 0.5) null else PlanChange(k.first, k.second, old, q)
    }
    val changes = targets.mapNotNull { (k, q) ->
        val old = data.dispatch[app.suppliertracker.model.pkey(v.id, k.first, k.second.toString())]
        if (old == null || (q != null && kotlin.math.abs(old - q) < 0.5)) null else DispatchChange(k.first, k.second, old, q)
    }
    return ImportPreview(
        vendor = v, targets = targets, plans = planChanges.associate { (it.partId to it.periodStart) to it.new },
        changes = changes, planChanges = planChanges,
        unmatched = (dispatch?.unmatched.orEmpty() + plan?.unmatched.orEmpty()), zeroParts = zeroParts, from = from, to = to, notes = notes,
    )
}

/**
 * Saves a preview into the data. Daily-quantity vendors get the day's figure; till-date vendors get the running
 * total on each imported day (the totals typed on other days stay as typed). Plans go through setPlan, so the first
 * plan of a week stays its original.
 */
fun applyImport(data: Data, p: ImportPreview): Data {
    val v = p.vendor
    var d = data
    if (!v.cumulative) {
        val dispatch = d.dispatch.toMutableMap()
        for ((k, q) in p.targets) {
            val key = app.suppliertracker.model.pkey(v.id, k.first, k.second.toString())
            if (q == null) dispatch.remove(key) else dispatch[key] = q
        }
        d = d.copy(dispatch = dispatch)
    } else {
        // per part and month: the final daily series, then the till-date total on each imported day
        val byPartMonth = p.targets.keys.groupBy { it.first to YearMonth.from(it.second) }
        val cumulative = d.cumulative.toMutableMap()
        for ((pm, keys) in byPartMonth) {
            val (partId, ym) = pm
            val touched = keys.map { it.second }.toSet()
            var running = 0.0
            for (day in dates(ym.atDay(1), ym.atEndOfMonth())) {
                val key = app.suppliertracker.model.pkey(v.id, partId, day.toString())
                val q = if (day in touched) p.targets[partId to day] else d.dispatch[key]
                if (day in touched) {
                    if (q == null) cumulative.remove(key) else cumulative[key] = running + q
                }
                if (q != null) running += q
            }
        }
        d = d.copy(cumulative = cumulative)
        for ((partId, ym) in byPartMonth.keys) d = app.suppliertracker.model.recomputeCumulative(d, v.id, partId, ym)
    }
    for ((k, q) in p.plans) d = app.suppliertracker.model.setPlan(d, v.id, k.first, k.second, q)
    return d
}
