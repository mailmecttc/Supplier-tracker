// Figures for the dashboard and the PowerPoint review of any period (a week, a month, a custom range):
// adherence, time gone vs coverage, parts short / on schedule, week-on-week trend, gap causes, RAG.
package app.suppliertracker.model

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale
import kotlin.math.max

/** The period [from]..[to]; figures count up to [asOf] (the date chosen in the app, or [to] once it has passed). */
data class ReportPeriod(val kind: Kind, val from: LocalDate, val to: LocalDate, val asOf: LocalDate) {
    enum class Kind(val label: String) { WEEK("This week"), MONTH_TO_DATE("Month to date"), LAST_MONTH("Last month"), CUSTOM("Custom range") }

    /** "Week 41 · 5–11 Oct 2026", "October 2026 · month to date", "September 2026", "1–20 Oct 2026". */
    val title: String
        get() = when (kind) {
            Kind.WEEK -> "Week ${from.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} · ${niceRange(from, to)}"
            Kind.MONTH_TO_DATE -> "${longMonth(YearMonth.from(from))} · month to date"
            Kind.LAST_MONTH -> longMonth(YearMonth.from(from))
            Kind.CUSTOM -> niceRange(from, to)
        }

    /** For file names: "W41-2026", "2026-10", "2026-10-01-to-2026-10-20". */
    val fileTag: String
        get() = when (kind) {
            Kind.WEEK -> "W${from.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}-${from.get(IsoFields.WEEK_BASED_YEAR)}"
            Kind.MONTH_TO_DATE, Kind.LAST_MONTH -> YearMonth.from(from).toString()
            Kind.CUSTOM -> "$from-to-$to"
        }

    companion object {
        /** The period of [kind] around the chosen date [d]; a custom range uses [customFrom]..[customTo]. */
        fun of(kind: Kind, d: LocalDate, customFrom: LocalDate = d.withDayOfMonth(1), customTo: LocalDate = d): ReportPeriod = when (kind) {
            Kind.WEEK -> {
                val mon = d.minusDays(d.weekdayIndex().toLong())
                ReportPeriod(kind, mon, mon.plusDays(6), d)
            }
            Kind.MONTH_TO_DATE -> ReportPeriod(kind, d.withDayOfMonth(1), d.withDayOfMonth(d.lengthOfMonth()), d)
            Kind.LAST_MONTH -> {
                val ym = YearMonth.from(d).minusMonths(1)
                ReportPeriod(kind, ym.atDay(1), ym.atEndOfMonth(), ym.atEndOfMonth())
            }
            Kind.CUSTOM -> {
                val a = minOf(customFrom, customTo)
                val b = maxOf(customFrom, customTo)
                ReportPeriod(kind, a, b, minOf(b, maxOf(d, a.minusDays(1))))
            }
        }
    }
}

fun longMonth(ym: YearMonth): String = "${ym.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${ym.year}"

data class PartReport(
    val part: Part,
    /** Pending quantity carried into the first day of the period. */
    val backlogIn: Double,
    /** Plan for the whole period. */
    val plan: Double,
    /** Plan from the first day up to the as-of date. */
    val planToDate: Double,
    val delivered: Double,
    val entered: Boolean,
    val needPerDay: Double,
    /** The latest reason logged for this part (open, or logged during the period). */
    val reason: Issue?,
) {
    val required: Double get() = backlogIn + plan
    val reqToDate: Double get() = backlogIn + planToDate
    val short: Double get() = max(0.0, reqToDate - delivered)
    val gap: Double get() = delivered - reqToDate
    val adherence: Double? get() = if (reqToDate >= 0.5) delivered / reqToDate else null
    val status: Status get() = status(reqToDate, delivered, entered)

    /** Behind or slightly behind, or nothing entered yet against a requirement: goes on the "parts short" slide. */
    val isShort: Boolean
        get() = status == Status.BEHIND || status == Status.WATCH || (status == Status.NOT_ENTERED && reqToDate >= 0.5)

    val onSchedule: Boolean get() = status == Status.ON_TRACK || status == Status.AHEAD
}

data class VendorReport(
    val vendor: Vendor,
    val parts: List<PartReport>,
    /** Dispatch days of this vendor passed in the period, up to and including the as-of date. */
    val daysPassed: Int,
    val daysTotal: Int,
) {
    val backlogIn: Double get() = parts.sumOf { it.backlogIn }
    val plan: Double get() = parts.sumOf { it.plan }
    val required: Double get() = backlogIn + plan
    val reqToDate: Double get() = parts.sumOf { it.reqToDate }
    val delivered: Double get() = parts.sumOf { it.delivered }
    val short: Double get() = parts.sumOf { it.short }
    val needPerDay: Double get() = parts.sumOf { it.needPerDay }
    val adherence: Double? get() = if (reqToDate >= 0.5) delivered / reqToDate else null

    /** Share of the period's requirement already delivered. */
    val coverage: Double? get() = if (required >= 0.5) delivered / required else null

    /** Share of the period's dispatch days gone. */
    val timeGone: Double? get() = if (daysTotal > 0) daysPassed.toDouble() / daysTotal else null
    val status: Status get() = status(reqToDate, delivered, parts.any { it.entered })
    val partsShort: Int get() = parts.count { it.isShort }

    /** Parts with something to deliver or delivered in the period (the "of N" in "2 of 4 parts short"). */
    val partsInPlay: Int get() = parts.count { it.required >= 0.5 || it.delivered > 0 }
}

// ---------- week-on-week trend ----------

/** One ISO week (Mon–Sun) of the trend, counted up to [toDate] for the current week. */
data class TrendWeek(val start: LocalDate, val end: LocalDate, val toDate: LocalDate) {
    val label: String get() = "W${start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
    val partial: Boolean get() = toDate < end
}

/** A week's dispatch against that week's plan (backlog not included, so one bad week does not colour the next). */
data class TrendCell(val plan: Double, val actual: Double, val entered: Boolean) {
    val adherence: Double? get() = if (plan >= 0.5 && entered) actual / plan else null
    val status: Status get() = status(plan, actual, entered)
}

data class VendorTrend(val name: String, val cells: List<TrendCell>) {
    /** Weeks in a row, up to the latest, in which adherence fell. */
    val fallingWeeks: Int
        get() {
            var n = 0
            for (i in cells.lastIndex downTo 1) {
                val a = cells[i].adherence ?: break
                val b = cells[i - 1].adherence ?: break
                if (a < b - 0.005) n++ else break
            }
            return n
        }

    /** -1 falling, 0 steady, +1 rising: the latest week against the average of up to three weeks before it (3 points either way). */
    val direction: Int
        get() {
            val known = cells.mapNotNull { it.adherence }
            if (known.size < 2) return 0
            val d = known.last() - known.dropLast(1).takeLast(3).average()
            return if (d < -0.03) -1 else if (d > 0.03) 1 else 0
        }

    /** The biggest fall (5 points or more) from an earlier week to the latest week with figures: (earlier, latest) indexes. */
    val worstSlide: Pair<Int, Int>?
        get() {
            val idx = cells.indices.filter { cells[it].adherence != null }
            if (idx.size < 2) return null
            val last = idx.last()
            val peak = idx.dropLast(1).maxBy { cells[it].adherence!! }
            return if (cells[peak].adherence!! - cells[last].adherence!! >= 0.05) peak to last else null
        }
}

fun trendWeeks(asOf: LocalDate, n: Int = 6): List<TrendWeek> {
    val mon = asOf.minusDays(asOf.weekdayIndex().toLong())
    return (n - 1 downTo 0).map { i ->
        val s = mon.minusWeeks(i.toLong())
        val e = s.plusDays(6)
        TrendWeek(s, e, minOf(e, asOf))
    }
}

fun vendorTrend(data: Data, v: Vendor, weeks: List<TrendWeek>): VendorTrend =
    VendorTrend(v.name, weeks.map { w ->
        var plan = 0.0
        var actual = 0.0
        var entered = false
        for (p in v.activeParts) {
            plan += sumPlan(data, v, p.id, w.start, w.toDate)
            val a = sumActual(data, v.id, p.id, w.start, w.toDate)
            actual += a.total
            entered = entered || a.entered
        }
        TrendCell(plan, actual, entered)
    })

fun totalTrend(rows: List<VendorTrend>): VendorTrend =
    VendorTrend("All vendors", rows.firstOrNull()?.cells?.indices?.map { i ->
        TrendCell(rows.sumOf { it.cells[i].plan }, rows.sumOf { it.cells[i].actual }, rows.any { it.cells[i].entered })
    } ?: emptyList())

// ---------- gap causes ----------

const val NO_REASON = "Reason not given"
const val WITHIN_TOLERANCE = "On schedule (small gaps)"

data class CauseRow(val cause: String, val short: Double, val parts: Int, val cumulative: Double)

/** Pieces short grouped by each part's latest reason, biggest first; parts still on schedule last. */
fun gapCauses(vendors: List<VendorReport>): List<CauseRow> {
    val byCause = LinkedHashMap<String, Pair<Double, Int>>()
    for (p in vendors.flatMap { it.parts }) {
        if (p.short < 0.5) continue
        val cause = when {
            p.onSchedule -> WITHIN_TOLERANCE
            else -> p.reason?.cause?.ifBlank { null } ?: NO_REASON
        }
        val (q, n) = byCause[cause] ?: (0.0 to 0)
        byCause[cause] = (q + p.short) to (n + 1)
    }
    val total = byCause.values.sumOf { it.first }
    val ordered = byCause.entries.sortedWith(compareBy<Map.Entry<String, Pair<Double, Int>>> { it.key == WITHIN_TOLERANCE }.thenByDescending { it.value.first })
    var run = 0.0
    return ordered.map { (cause, v) ->
        run += v.first
        CauseRow(cause, v.first, v.second, if (total > 0) run / total else 0.0)
    }
}

// ---------- the report ----------

data class PeriodReport(
    val period: ReportPeriod,
    val vendors: List<VendorReport>,
    val openIssues: List<Issue>,
    val weeks: List<TrendWeek>,
    val trend: List<VendorTrend>,
    val trendTotal: VendorTrend,
) {
    val date: LocalDate get() = period.asOf
    val partsShort: Int get() = vendors.sumOf { it.partsShort }
    val partsInPlay: Int get() = vendors.sumOf { it.partsInPlay }
    val shortToDate: Double get() = vendors.sumOf { it.short }

    /** Parts with a requirement to date but no dispatch entered yet. */
    val notEntered: Int get() = vendors.sumOf { v -> v.parts.count { it.reqToDate >= 0.5 && it.status == Status.NOT_ENTERED } }
    val causes: List<CauseRow> get() = gapCauses(vendors)
    val reqToDate: Double get() = vendors.sumOf { it.reqToDate }
    val delivered: Double get() = vendors.sumOf { it.delivered }
    val adherence: Double? get() = reqToDate.takeIf { it >= 0.5 }?.let { delivered / it }
    val status: Status get() = status(reqToDate, delivered, vendors.any { v -> v.parts.any { it.entered } })
}

/** Need per day: balance spread over dispatch days left after [asOf] (all of it if none are left). */
private fun perDay(balance: Double, daysLeft: Int): Double =
    if (balance <= 0) 0.0 else if (daysLeft > 0) balance / daysLeft else balance

fun periodReport(data: Data, period: ReportPeriod): PeriodReport {
    val (from, to, asOf) = Triple(period.from, period.to, period.asOf)
    val open = data.issues.filter { it.status == "open" }
    fun inPeriod(i: Issue) = runCatching { LocalDate.parse(i.logged) }.getOrNull()?.let { it in from..asOf } ?: false
    val vendors = data.vendors.map { v ->
        val parts = v.activeParts.map { p ->
            val backlog = backlogAt(data, v, p.id, from)
            val plan = sumPlan(data, v, p.id, from, to)
            val planToDate = sumPlan(data, v, p.id, from, asOf)
            val got = sumActual(data, v.id, p.id, from, asOf)
            val balance = max(0.0, backlog + plan - got.total)
            val reason = data.issues
                .filter { it.vendorId == v.id && it.partId == p.id && (it.status == "open" || inPeriod(it)) }
                .maxByOrNull { it.logged }
            PartReport(
                part = p, backlogIn = backlog, plan = plan, planToDate = planToDate, delivered = got.total, entered = got.entered,
                needPerDay = perDay(balance, workDays(v, asOf.plusDays(1), to, data.holidays)), reason = reason,
            )
        }
        VendorReport(v, parts, workDays(v, from, asOf, data.holidays), workDays(v, from, to, data.holidays))
    }
    val weeks = trendWeeks(asOf)
    val trend = data.vendors.map { vendorTrend(data, it, weeks) }
    return PeriodReport(period, vendors, open, weeks, trend, totalTrend(trend))
}

/** Red / amber / green on the same limits as the app (95% / 85%); "–" when there is nothing to judge. */
val Status.rag: String
    get() = when (this) {
        Status.ON_TRACK, Status.AHEAD -> "G"
        Status.WATCH -> "A"
        Status.BEHIND -> "R"
        Status.NOT_ENTERED, Status.NO_PLAN -> "–"
    }

fun Issue.overdue(today: LocalDate): Boolean =
    status == "open" && due.isNotBlank() && runCatching { LocalDate.parse(due) < today }.getOrDefault(false)
