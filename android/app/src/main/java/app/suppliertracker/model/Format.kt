package app.suppliertracker.model

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Indian digit grouping: 123456 -> 1,23,456 */
fun indian(n: Long): String {
    val s = abs(n).toString()
    val body = if (s.length <= 3) {
        s
    } else {
        var rest = s.dropLast(3)
        val groups = ArrayDeque<String>()
        while (rest.length > 2) {
            groups.addFirst(rest.takeLast(2))
            rest = rest.dropLast(2)
        }
        if (rest.isNotEmpty()) groups.addFirst(rest)
        groups.joinToString(",") + "," + s.takeLast(3)
    }
    return if (n < 0) "−$body" else body
}

/** Rounded quantity with Indian grouping; "–" when missing. */
fun num(x: Double?): String = if (x == null || x.isNaN()) "–" else indian(x.roundToLong())

/** Signed, for gaps: +120 / −300 */
fun signed(x: Double): String {
    val r = x.roundToLong()
    return when {
        r == 0L -> "0"
        r > 0 -> "+" + indian(r)
        else -> indian(r)
    }
}

/** Whole percent, rounded down so the figure agrees with the colour: 94.8% is amber and reads 94%, not 95%. */
fun pct(r: Double?): String = if (r == null) "–" else "${kotlin.math.floor(r * 100 + 1e-9).toLong()}%"

/** Parses a typed quantity; accepts Indian/Western grouping. Blank -> null. */
fun parseQty(text: String): Double? {
    val t = text.replace(",", "").replace(" ", "").trim()
    if (t.isEmpty()) return null
    return t.toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** Value for an input box: 300.0 -> "300", 12.5 -> "12.5", null -> "" */
fun qtyText(x: Double?): String = when {
    x == null -> ""
    x == Math.floor(x) && abs(x) < 1e15 -> x.toLong().toString()
    else -> x.toString()
}

private fun mon(d: LocalDate) = d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
private fun wd(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

/** "9 Oct" or "Fri 9 Oct" */
fun niceDate(d: LocalDate, withDay: Boolean = false): String =
    (if (withDay) "${wd(d)} " else "") + "${d.dayOfMonth} ${mon(d)}"

fun niceMonth(ym: YearMonth): String = "${ym.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${ym.year}"

val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** "2 Oct 2026", "3–9 Nov 2026", "28 Oct – 2 Nov 2026" or "29 Dec 2026 – 2 Jan 2027". */
fun niceRange(a: LocalDate, b: LocalDate): String = when {
    a == b -> "${niceDate(a)} ${a.year}"
    a.year != b.year -> "${niceDate(a)} ${a.year} – ${niceDate(b)} ${b.year}"
    a.month == b.month -> "${a.dayOfMonth}–${niceDate(b)} ${b.year}"
    else -> "${niceDate(a)} – ${niceDate(b)} ${b.year}"
}

/** Groups dates (YYYY-MM-DD) into runs of consecutive days, oldest first. */
fun dayRuns(days: Collection<String>): List<Pair<LocalDate, LocalDate>> {
    val sorted = days.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.distinct().sorted()
    val out = mutableListOf<Pair<LocalDate, LocalDate>>()
    for (d in sorted) {
        val last = out.lastOrNull()
        if (last != null && last.second.plusDays(1) == d) out[out.lastIndex] = last.first to d else out += d to d
    }
    return out
}
