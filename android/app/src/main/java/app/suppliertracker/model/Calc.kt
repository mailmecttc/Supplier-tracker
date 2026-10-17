// Pure calculations, ported from the web app's calc.ts (spec section 4). No Android, no storage.
package app.suppliertracker.model

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.IsoFields
import kotlin.math.max

/** 0 = Monday … 6 = Sunday */
fun LocalDate.weekdayIndex(): Int = dayOfWeek.value - 1

/** Inclusive dates from a to b (empty when a > b). */
fun dates(a: LocalDate, b: LocalDate): Sequence<LocalDate> =
    generateSequence(a) { it.plusDays(1) }.takeWhile { it <= b }

// ---------- periods ----------

/** ISO week (Mon–Sun) cut at month boundaries. */
data class Period(val start: LocalDate, val end: LocalDate) {
    val week: Int get() = start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    val label: String get() = "W$week"
}

fun periodOf(d: LocalDate): Period {
    val mon = d.minusDays(d.weekdayIndex().toLong())
    val sun = mon.plusDays(6)
    val ms = d.withDayOfMonth(1)
    val me = d.withDayOfMonth(d.lengthOfMonth())
    return Period(if (mon < ms) ms else mon, if (sun > me) me else sun)
}

fun periodsOfMonth(ym: YearMonth): List<Period> {
    val out = mutableListOf<Period>()
    var d = ym.atDay(1)
    val me = ym.atEndOfMonth()
    while (d <= me) {
        val p = periodOf(d)
        out += p
        d = p.end.plusDays(1)
    }
    return out
}

// ---------- working days ----------

/** A dispatch day of this vendor that is neither a holiday for all vendors nor one of its own days off. */
fun isWork(v: Vendor, d: LocalDate, holidays: Collection<String>): Boolean =
    v.dispatchDays.getOrElse(d.weekdayIndex()) { 0 } != 0 && d.toString().let { it !in holidays && it !in v.holidays }

fun workDays(v: Vendor, a: LocalDate, b: LocalDate, holidays: Collection<String>): Int =
    if (a > b) 0 else dates(a, b).count { isWork(v, it, holidays) }

// ---------- plan ----------

fun periodPlan(data: Data, vendorId: String, partId: String, periodStart: LocalDate): Double =
    data.plan[pkey(vendorId, partId, periodStart.toString())] ?: 0.0

fun dailyPlan(data: Data, v: Vendor, partId: String, d: LocalDate): Double {
    val p = periodOf(d)
    val total = periodPlan(data, v.id, partId, p.start)
    if (total == 0.0) return 0.0
    val n = workDays(v, p.start, p.end, data.holidays)
    if (n == 0) return if (d == p.end) total else 0.0
    return if (isWork(v, d, data.holidays)) total / n else 0.0
}

fun sumPlan(data: Data, v: Vendor, partId: String, a: LocalDate, b: LocalDate): Double =
    dates(a, b).sumOf { dailyPlan(data, v, partId, it) }

fun monthPlan(data: Data, vendorId: String, partId: String, ym: YearMonth): Double =
    periodsOfMonth(ym).sumOf { periodPlan(data, vendorId, partId, it.start) }

/** Saves a period plan; the first save is kept as the original plan. */
fun setPlan(data: Data, vendorId: String, partId: String, periodStart: LocalDate, value: Double?): Data {
    val k = pkey(vendorId, partId, periodStart.toString())
    if (value == null || value.isNaN()) return data.copy(plan = data.plan - k)
    val orig = if (k in data.planOrig) data.planOrig else data.planOrig + (k to value)
    return data.copy(plan = data.plan + (k to value), planOrig = orig)
}

/** A month total turned into weekly plans: the weeks changed, and what weeks already ended kept. */
data class MonthSplit(val weeks: Map<LocalDate, Double>, val kept: Double, val keptWeeks: Set<LocalDate>)

/** Whole pieces in proportion to [weights] by running-total rounding: they add up to [pieces], every running
 *  total is within half a piece of the exact share (so the daily rate stays level), a weight of 0 gets 0. */
fun levelShares(pieces: Long, weights: List<Long>): LongArray {
    val out = LongArray(weights.size)
    if (weights.isEmpty()) return out
    val total = weights.sum()
    if (total == 0L) {
        out[out.lastIndex] = pieces
        return out
    }
    var cum = 0L
    var prev = 0L
    for (i in weights.indices) {
        cum += weights[i]
        val upTo = (2 * pieces * cum + total) / (2 * total) // pieces × cum / total, rounded half up
        out[i] = upTo - prev
        prev = upTo
    }
    return out
}

/**
 * Splits a month's plan for one part into the month's weeks (ISO weeks cut at the month edges), in proportion to
 * the vendor's dispatch days in each week (holidays and the vendor's own days off left out), in whole pieces with
 * nothing lost to rounding (a fraction, for kg or metres, goes to the last week with dispatch days).
 *
 * Once the month has a plan, a week under way before [asOf] – ended, or one of its dispatch days gone – keeps what
 * it has (nothing, if it had none), so the plan of a day already gone never changes and a supplier is never short
 * for days it worked to the old plan; the rest of [total] goes to the weeks to come ([weeks] holds only those).
 * The first entry for a month is spread over the whole month. When every week is under way
 * the current week takes the change. A week with no dispatch day gets 0, unless no remaining week has one: then
 * the last week takes it all (its plan then falls on its last day, as for any week without dispatch days).
 */
fun splitMonthPlan(data: Data, v: Vendor, partId: String, ym: YearMonth, total: Double, asOf: LocalDate?): MonthSplit {
    val periods = periodsOfMonth(ym)
    fun planned(p: Period) = pkey(v.id, partId, p.start.toString()) in data.plan
    fun underWay(p: Period) = asOf != null && p.start < asOf && (p.end < asOf || workDays(v, p.start, asOf.minusDays(1), data.holidays) > 0)
    // once the month has a plan, a week under way without one (say a Sunday-only first week) stays at nothing
    val anyPlanned = periods.any { planned(it) }
    var kept = periods.filter { underWay(it) && (planned(it) || anyPlanned) }
    if (kept.size == periods.size && asOf != null && asOf <= ym.atEndOfMonth()) kept = kept.filter { it.end < asOf }
    val open = periods - kept.toSet()
    val keptSum = kept.sumOf { periodPlan(data, v.id, partId, it.start) }
    if (open.isEmpty()) return MonthSplit(emptyMap(), keptSum, kept.map { it.start }.toSet())
    val rest = max(0.0, total - keptSum)
    val pieces = kotlin.math.floor(rest).toLong()
    val weights = open.map { workDays(v, it.start, it.end, data.holidays).toLong() }
    val shares = levelShares(pieces, weights)
    val fractionTo = weights.indexOfLast { it > 0 }.takeIf { it >= 0 } ?: open.lastIndex
    return MonthSplit(
        open.mapIndexed { i, p -> p.start to shares[i] + if (i == fractionTo) rest - pieces else 0.0 }.toMap(),
        keptSum, kept.map { it.start }.toSet(),
    )
}

/** The month's plan for one part, or null when no week of the month has one. */
fun monthPlanOrNull(data: Data, vendorId: String, partId: String, ym: YearMonth): Double? =
    if (periodsOfMonth(ym).any { pkey(vendorId, partId, it.start.toString()) in data.plan }) {
        Math.round(monthPlan(data, vendorId, partId, ym) * 1e6) / 1e6 // 12.1 + 12.2 + … shows 48.8, not 48.79999999999999
    } else {
        null
    }

data class Replan(val original: Double, val diff: Double)

/** Original plan and the re-plan difference (current − original), or null if never re-planned. */
fun replan(data: Data, vendorId: String, partId: String, periodStart: LocalDate): Replan? {
    val k = pkey(vendorId, partId, periodStart.toString())
    val orig = data.planOrig[k] ?: return null
    val cur = data.plan[k] ?: return null
    return if (orig == cur) null else Replan(orig, cur - orig)
}

// ---------- dispatch ----------

fun actual(data: Data, vendorId: String, partId: String, d: LocalDate): Double? =
    data.dispatch[pkey(vendorId, partId, d.toString())]

data class ActualSum(val total: Double, val entered: Boolean)

fun sumActual(data: Data, vendorId: String, partId: String, a: LocalDate, b: LocalDate): ActualSum {
    var total = 0.0
    var entered = false
    for (d in dates(a, b)) {
        actual(data, vendorId, partId, d)?.let {
            total += it
            entered = true
        }
    }
    return ActualSum(total, entered)
}

/** Cumulative mode: rebuild every daily dispatch value of one part for one month from the till-date totals. */
fun recomputeCumulative(data: Data, vendorId: String, partId: String, ym: YearMonth): Data {
    val dispatch = data.dispatch.toMutableMap()
    var last = 0.0
    for (d in dates(ym.atDay(1), ym.atEndOfMonth())) {
        val k = pkey(vendorId, partId, d.toString())
        val c = data.cumulative[k]
        if (c == null) {
            dispatch.remove(k)
        } else {
            dispatch[k] = c - last
            last = c
        }
    }
    return data.copy(dispatch = dispatch)
}

/** Last cumulative total entered before d in the same month (0 if none). */
fun prevCumulative(data: Data, vendorId: String, partId: String, d: LocalDate): Double {
    val ms = d.withDayOfMonth(1)
    var x = d.minusDays(1)
    while (x >= ms) {
        data.cumulative[pkey(vendorId, partId, x.toString())]?.let { return it }
        x = x.minusDays(1)
    }
    return 0.0
}

// ---------- backlog ----------

fun openingBacklog(data: Data, v: Vendor, partId: String, ym: YearMonth, depth: Int = 0): Double =
    data.openingBacklog[pkey(v.id, partId, ym.toString())] ?: autoBacklog(data, v, partId, ym, depth)

/** Carried-forward value, ignoring any manual entry for ym itself. */
fun autoBacklog(data: Data, v: Vendor, partId: String, ym: YearMonth, depth: Int = 0): Double {
    if (depth >= 12) return 0.0
    val pm = ym.minusMonths(1)
    val prevOpening = openingBacklog(data, v, partId, pm, depth + 1)
    val prevPlan = monthPlan(data, v.id, partId, pm)
    val prevActual = sumActual(data, v.id, partId, pm.atDay(1), pm.atEndOfMonth()).total
    return max(0.0, prevOpening + prevPlan - prevActual)
}

/**
 * Pending quantity carried into day [d]: the month's opening backlog plus that month's plan before [d],
 * less what was dispatched before [d] (never below zero). On the 1st it is the opening backlog itself.
 */
fun backlogAt(data: Data, v: Vendor, partId: String, d: LocalDate): Double {
    val ym = YearMonth.from(d)
    val ob = openingBacklog(data, v, partId, ym)
    if (d.dayOfMonth == 1) return ob
    val ms = ym.atDay(1)
    val prev = d.minusDays(1)
    return max(0.0, ob + sumPlan(data, v, partId, ms, prev) - sumActual(data, v.id, partId, ms, prev).total)
}

// ---------- status ----------

/** rank: higher = worse, for sorting */
enum class Status(val label: String, val rank: Int) {
    BEHIND("Behind", 5),
    WATCH("Slightly behind", 4),
    NOT_ENTERED("Not entered", 3),
    ON_TRACK("On schedule", 2),
    AHEAD("Ahead", 1),
    NO_PLAN("No plan", 0),
}

fun status(plan: Double, act: Double, entered: Boolean): Status {
    if (plan < 0.5) return if (act > 0) Status.AHEAD else Status.NO_PLAN
    if (!entered) return Status.NOT_ENTERED
    val r = act / plan
    return when {
        r >= 0.95 -> Status.ON_TRACK
        r >= 0.85 -> Status.WATCH
        else -> Status.BEHIND
    }
}

// ---------- per-part metrics ----------

data class PartMetrics(
    val todayPlan: Double,
    val todayActual: Double?,
    val weekPlan: Double,
    val wtdPlan: Double,
    val wtdActual: Double,
    val weekBalance: Double,
    val weekReqPerDay: Double,
    val openingBacklog: Double,
    val monthPlan: Double,
    val mtdPlan: Double,
    val mtdRequirement: Double,
    val mtdActual: Double,
    val mtdGap: Double,
    val monthBalance: Double,
    val monthReqPerDay: Double,
    val weekStatus: Status,
    val monthStatus: Status,
) {
    val behind: Boolean get() = weekStatus == Status.BEHIND || monthStatus == Status.BEHIND
}

/** Need per day: balance spread over working days left after d (all of it if none are left). */
private fun perDay(balance: Double, daysLeft: Int): Double =
    if (balance <= 0) 0.0 else if (daysLeft > 0) balance / daysLeft else balance

fun partMetrics(data: Data, v: Vendor, partId: String, d: LocalDate): PartMetrics {
    val p = periodOf(d)
    val ym = YearMonth.from(d)
    val ms = ym.atDay(1)
    val me = ym.atEndOfMonth()
    val hol = data.holidays

    val todayPlan = dailyPlan(data, v, partId, d)
    val todayActual = actual(data, v.id, partId, d)
    val weekPlan = periodPlan(data, v.id, partId, p.start)
    val wtdPlan = sumPlan(data, v, partId, p.start, d)
    val wtd = sumActual(data, v.id, partId, p.start, d)
    val weekBalance = max(0.0, weekPlan - wtd.total)
    val weekReqPerDay = perDay(weekBalance, workDays(v, d.plusDays(1), p.end, hol))

    val ob = openingBacklog(data, v, partId, ym)
    val mPlan = monthPlan(data, v.id, partId, ym)
    val mtdPlan = sumPlan(data, v, partId, ms, d)
    val mtdRequirement = ob + mtdPlan
    val mtd = sumActual(data, v.id, partId, ms, d)
    val monthBalance = max(0.0, ob + mPlan - mtd.total)
    val monthReqPerDay = perDay(monthBalance, workDays(v, d.plusDays(1), me, hol))

    return PartMetrics(
        todayPlan = todayPlan,
        todayActual = todayActual,
        weekPlan = weekPlan,
        wtdPlan = wtdPlan,
        wtdActual = wtd.total,
        weekBalance = weekBalance,
        weekReqPerDay = weekReqPerDay,
        openingBacklog = ob,
        monthPlan = mPlan,
        mtdPlan = mtdPlan,
        mtdRequirement = mtdRequirement,
        mtdActual = mtd.total,
        mtdGap = mtd.total - mtdRequirement,
        monthBalance = monthBalance,
        monthReqPerDay = monthReqPerDay,
        weekStatus = status(wtdPlan, wtd.total, wtd.entered),
        monthStatus = status(mtdRequirement, mtd.total, mtd.entered),
    )
}

// ---------- vendor roll-up ----------

data class PartRow(val part: Part, val m: PartMetrics)

data class VendorSummary(
    val parts: List<PartRow>,
    val todayPlan: Double,
    val todayActual: Double,
    val todayEntered: Boolean,
    val wtdPlan: Double,
    val wtdActual: Double,
    val weekPlan: Double,
    val mtdRequirement: Double,
    val mtdActual: Double,
    val shortToDate: Double,
    val needPerDay: Double,
    val partsBehind: Int,
    val adherence: Double?,
    val status: Status,
    val weekStatus: Status,
)

fun vendorSummary(data: Data, v: Vendor, d: LocalDate): VendorSummary {
    val parts = v.activeParts.map { PartRow(it, partMetrics(data, v, it.id, d)) }
    fun s(f: (PartMetrics) -> Double) = parts.sumOf { f(it.m) }
    val mtdRequirement = s { it.mtdRequirement }
    val mtdActual = s { it.mtdActual }
    val wtdPlan = s { it.wtdPlan }
    val wtdActual = s { it.wtdActual }
    val empty = setOf(Status.NOT_ENTERED, Status.NO_PLAN)
    val monthEntered = parts.any { it.m.monthStatus !in empty } || parts.any { it.m.mtdActual > 0 }
    val weekEntered = parts.any { it.m.weekStatus !in empty } || parts.any { it.m.wtdActual > 0 }
    return VendorSummary(
        parts = parts,
        todayPlan = s { it.todayPlan },
        todayActual = s { it.todayActual ?: 0.0 },
        todayEntered = parts.any { it.m.todayActual != null },
        wtdPlan = wtdPlan,
        wtdActual = wtdActual,
        weekPlan = s { it.weekPlan },
        mtdRequirement = mtdRequirement,
        mtdActual = mtdActual,
        shortToDate = s { max(0.0, -it.mtdGap) },
        needPerDay = s { it.monthReqPerDay },
        partsBehind = parts.count { it.m.behind },
        adherence = if (mtdRequirement >= 0.5) mtdActual / mtdRequirement else null,
        status = status(mtdRequirement, mtdActual, monthEntered),
        weekStatus = status(wtdPlan, wtdActual, weekEntered),
    )
}

// ---------- past months ----------

/** One part's month: plan and dispatch from daily tracking, or from a supplier's closing report. */
data class MonthRecord(val plan: Double?, val actual: Double?) {
    val short: Double? get() = if (plan != null && actual != null) max(0.0, plan - actual) else null
    val adherence: Double? get() = if (plan != null && actual != null && plan >= 0.5) actual / plan else null
}

fun monthRecord(data: Data, v: Vendor, partId: String, ym: YearMonth): MonthRecord {
    val k = pkey(v.id, partId, ym.toString())
    val tracked = sumActual(data, v.id, partId, ym.atDay(1), ym.atEndOfMonth())
    val planned = periodsOfMonth(ym).any { pkey(v.id, partId, it.start.toString()) in data.plan }
    return MonthRecord(
        plan = if (planned) monthPlan(data, v.id, partId, ym) else data.closedPlan[k],
        actual = if (tracked.entered) tracked.total else data.closedActual[k],
    )
}

/** Months before [before] that have any plan or dispatch for this vendor, newest first. */
fun pastMonths(data: Data, v: Vendor, before: YearMonth): List<YearMonth> {
    val prefix = "${v.id}|"
    fun ym(k: String, len: Int) = runCatching { YearMonth.parse(k.substringAfterLast('|').take(len)) }.getOrNull()
    val months = sequenceOf(data.closedPlan.keys, data.closedActual.keys).flatten()
        .filter { it.startsWith(prefix) }.mapNotNull { ym(it, 7) } +
        sequenceOf(data.plan.keys, data.dispatch.keys).flatten()
            .filter { it.startsWith(prefix) }.mapNotNull { ym(it, 7) }
    return months.filter { it < before }.distinct().sortedDescending().toList()
}
