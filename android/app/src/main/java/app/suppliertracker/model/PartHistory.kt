// A part's history for the Status tab and the part page: daily, weekly and monthly plan against dispatch.
package app.suppliertracker.model

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max

/** Dispatched ÷ plan, or null when there is nothing planned. */
fun adherence(plan: Double, actual: Double): Double? = if (plan >= 0.5) actual / plan else null

/** One day on the part's calendar. [actual] is null when nothing was typed for the day. */
data class DayCell(val date: LocalDate, val plan: Double, val actual: Double?, val work: Boolean, val holiday: Boolean) {
    val status: Status get() = status(plan, actual ?: 0.0, actual != null)
}

fun monthDays(data: Data, v: Vendor, partId: String, ym: YearMonth): List<DayCell> =
    dates(ym.atDay(1), ym.atEndOfMonth()).map { d ->
        DayCell(
            date = d,
            plan = dailyPlan(data, v, partId, d),
            actual = actual(data, v.id, partId, d),
            work = isWork(v, d, data.holidays),
            holiday = d.toString() in data.holidays || d.toString() in v.holidays,
        )
    }.toList()

/** A week of the month (cut at the month edges): its plan, the plan up to [asOf] and what was dispatched up to then. */
data class WeekRow(val period: Period, val plan: Double, val planToDate: Double, val actual: Double, val entered: Boolean, val started: Boolean) {
    val status: Status get() = if (!started) Status.NO_PLAN else status(planToDate, actual, entered)
    val adherence: Double? get() = adherence(planToDate, actual)
}

fun monthWeeks(data: Data, v: Vendor, partId: String, ym: YearMonth, asOf: LocalDate): List<WeekRow> =
    periodsOfMonth(ym).map { p ->
        val started = p.start <= asOf
        val upTo = if (asOf < p.end) asOf else p.end
        val act = if (started) sumActual(data, v.id, partId, p.start, upTo) else ActualSum(0.0, false)
        WeekRow(
            period = p,
            plan = periodPlan(data, v.id, partId, p.start),
            planToDate = if (started) sumPlan(data, v, partId, p.start, upTo) else 0.0,
            actual = act.total,
            entered = act.entered,
            started = started,
        )
    }

/**
 * A month for the part page. For the month of [asOf] the figures run to that day and include the opening backlog
 * ([required] = backlog + plan to date); earlier months are plan against dispatch for the whole month.
 */
data class MonthRow(val ym: YearMonth, val plan: Double?, val required: Double?, val actual: Double?, val current: Boolean, val future: Boolean) {
    val status: Status get() = when {
        future -> Status.NO_PLAN
        required == null -> if ((actual ?: 0.0) > 0) Status.AHEAD else Status.NO_PLAN
        else -> status(required, actual ?: 0.0, actual != null)
    }
    val adherence: Double? get() = if (required != null && actual != null) adherence(required, actual) else null
}

/** [count] months ending with [last], oldest first. */
fun recentMonths(data: Data, v: Vendor, partId: String, last: YearMonth, asOf: LocalDate, count: Int = 6): List<MonthRow> {
    val now = YearMonth.from(asOf)
    return (count - 1 downTo 0).map { last.minusMonths(it.toLong()) }.map { ym ->
        when {
            ym == now -> {
                val m = partMetrics(data, v, partId, asOf)
                val entered = sumActual(data, v.id, partId, ym.atDay(1), asOf).entered
                MonthRow(ym, m.monthPlan, m.mtdRequirement.takeIf { it >= 0.5 }, if (entered) m.mtdActual else null, current = true, future = false)
            }
            ym > now -> MonthRow(ym, monthRecord(data, v, partId, ym).plan, null, null, current = false, future = true)
            else -> {
                val r = monthRecord(data, v, partId, ym)
                MonthRow(ym, r.plan, r.plan, r.actual, current = false, future = false)
            }
        }
    }
}

/** The vendor's adherence today, this week to date and this month to date, and how its critical parts stand. */
data class VendorAdherence(
    val day: Double?, val dayStatus: Status,
    val week: Double?, val weekStatus: Status,
    val month: Double?, val monthStatus: Status,
    /** Critical parts; those behind or slightly behind, the worse of the two; those with nothing typed yet. */
    val critical: Int, val criticalBad: Int, val criticalWorst: Status?, val criticalNotEntered: Int,
)

fun vendorAdherence(s: VendorSummary): VendorAdherence {
    val critical = s.parts.filter { it.part.critical }
    return VendorAdherence(
        day = adherence(s.todayPlan, s.todayActual),
        dayStatus = status(s.todayPlan, s.todayActual, s.todayEntered),
        week = adherence(s.wtdPlan, s.wtdActual),
        weekStatus = s.weekStatus,
        month = s.adherence,
        monthStatus = s.status,
        critical = critical.size,
        criticalBad = critical.count { it.m.monthStatus == Status.BEHIND || it.m.monthStatus == Status.WATCH },
        criticalWorst = critical.map { it.m.monthStatus }.filter { it == Status.BEHIND || it == Status.WATCH }.maxByOrNull { it.rank },
        criticalNotEntered = critical.count { it.m.monthStatus == Status.NOT_ENTERED },
    )
}

/** Behind, slightly behind or not entered: the parts the "Bad" filter shows. */
val Status.needsAction: Boolean get() = this == Status.BEHIND || this == Status.WATCH || this == Status.NOT_ENTERED

/** On schedule or ahead: the parts the "Good" filter shows. */
val Status.good: Boolean get() = this == Status.ON_TRACK || this == Status.AHEAD

/** What the Entry box says about the figure typed for the day. */
data class EntryCheck(val text: String, val status: Status)

/**
 * Today's dispatch against today's plan. With no plan today a part can still be short – a backlog, or a week
 * behind – so then the month to date decides: typing 0 for a part short 784 shows red, not "No plan today".
 */
fun entryCheck(m: PartMetrics, daily: Double?): EntryCheck {
    if (daily == null) return EntryCheck("Not entered", Status.NOT_ENTERED)
    if (m.todayPlan < 0.5) {
        val short = m.mtdRequirement - m.mtdActual
        if (short >= 0.5) {
            val st = status(m.mtdRequirement, m.mtdActual, true)
            return EntryCheck("No plan today · ${st.label.lowercase()} · short ${num(short)} to date", st)
        }
        // what was still due before today's figure: today's dispatch cleared it, and only the rest is extra
        val dueBefore = (m.mtdRequirement - (m.mtdActual - daily)).coerceIn(0.0, max(0.0, m.mtdRequirement))
        if (dueBefore >= 0.5) {
            val extra = daily - dueBefore
            return EntryCheck("No plan today · on schedule to date" + if (extra >= 0.5) " · +${num(extra)} extra" else "", Status.ON_TRACK)
        }
        return EntryCheck(if (daily > 0) "No plan today · +${num(daily)} extra" else "No plan today", Status.NO_PLAN)
    }
    val st = status(m.todayPlan, daily, true)
    return EntryCheck("${st.label} · gap ${signed(daily - m.todayPlan)}", st)
}

/**
 * The highest till-date total typed before [d] in the same month, with its date (the latest day, on a tie).
 * A till-date total never goes down, so anything typed below it is a slip – even when a wrong 0 sits in between.
 */
fun peakCumulative(data: Data, vendorId: String, partId: String, d: LocalDate): Pair<LocalDate, Double>? {
    var best: Pair<LocalDate, Double>? = null
    for (x in dates(d.withDayOfMonth(1), d.minusDays(1))) {
        val c = data.cumulative[pkey(vendorId, partId, x.toString())] ?: continue
        if (best == null || c >= best.second) best = x to c
    }
    return best
}

/** A till-date total lower than an earlier one in the same month: the day, its total, and the earlier highest. */
data class TillDateDrop(val date: LocalDate, val total: Double, val peakDate: LocalDate, val peak: Double)

/** Every day before [d] this month whose till-date total is below an earlier one (oldest first). */
fun tillDateDrops(data: Data, vendorId: String, partId: String, d: LocalDate): List<TillDateDrop> {
    val out = mutableListOf<TillDateDrop>()
    var peak: Pair<LocalDate, Double>? = null
    for (x in dates(d.withDayOfMonth(1), d.minusDays(1))) {
        val c = data.cumulative[pkey(vendorId, partId, x.toString())] ?: continue
        val p = peak
        if (p != null && c < p.second - 0.5) out += TillDateDrop(x, c, p.first, p.second)
        if (p == null || c >= p.second) peak = x to c
    }
    return out
}

/** Dispatch still needed this month, never below 0. */
fun monthShort(m: PartMetrics): Double = max(0.0, m.mtdRequirement - m.mtdActual)
