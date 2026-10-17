// CSV exports (spec section 7) and the plain-text summary shared to WhatsApp.
package app.suppliertracker.model

import java.time.LocalDate
import kotlin.math.roundToLong

private fun cell(v: Any?): String {
    val s = when (v) {
        null -> ""
        is Double -> if (v == Math.floor(v)) v.roundToLong().toString() else ((v * 100).roundToLong() / 100.0).toString()
        else -> v.toString()
    }
    return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

/** UTF-8 BOM so Excel opens it cleanly. */
fun toCsv(rows: List<List<Any?>>): String =
    "﻿" + rows.joinToString("\r\n") { r -> r.joinToString(",") { cell(it) } } + "\r\n"

/** Date, Vendor, Part No, Description, Daily plan, Dispatched, Gap — month to date. */
fun dispatchLogCsv(data: Data, vendors: List<Vendor>, asOf: LocalDate): String {
    val rows = mutableListOf<List<Any?>>(listOf("Date", "Vendor", "Part No", "Description", "Daily plan", "Dispatched", "Gap"))
    for (d in dates(asOf.withDayOfMonth(1), asOf)) {
        for (v in vendors) {
            for (p in v.activeParts) {
                val plan = dailyPlan(data, v, p.id, d)
                val act = actual(data, v.id, p.id, d)
                if (plan == 0.0 && act == null) continue
                rows += listOf(d.toString(), v.name, p.partNo, p.desc, plan, act, act?.let { it - plan })
            }
        }
    }
    return toCsv(rows)
}

fun monthlySummaryCsv(data: Data, vendors: List<Vendor>, asOf: LocalDate): String {
    val rows = mutableListOf<List<Any?>>(
        listOf("As of", asOf.toString()),
        emptyList(),
        listOf(
            "Vendor", "Part No", "Description", "Opening backlog", "Month plan", "Required to date", "Dispatched MTD",
            "Gap", "Month balance", "Need/day", "Week status", "Month status",
        ),
    )
    for (v in vendors) {
        for ((p, m) in vendorSummary(data, v, asOf).parts) {
            rows += listOf(
                v.name, p.partNo, p.desc, m.openingBacklog, m.monthPlan, m.mtdRequirement, m.mtdActual,
                m.mtdGap, m.monthBalance, m.monthReqPerDay, m.weekStatus.label, m.monthStatus.label,
            )
        }
    }
    return toCsv(rows)
}

private fun partLabel(p: Part) = if (p.partNo.isNotBlank() && p.desc.isNotBlank()) "${p.desc} (${p.partNo})" else p.name

/** Short status report to paste into WhatsApp / email: one block per vendor, worst parts first. */
fun shareSummary(data: Data, vendors: List<Vendor>, d: LocalDate): String {
    val period = periodOf(d)
    val sb = StringBuilder()
    sb.append("*Supplier dispatch – ${niceDate(d, true)} (${period.label})*\n")
    for (v in vendors) {
        val s = vendorSummary(data, v, d)
        sb.append("\n*${v.name}* – ${s.status.label}\n")
        if (s.mtdRequirement < 0.5 && s.mtdActual == 0.0) {
            sb.append("No plan to date.\n")
            continue
        }
        sb.append("MTD ${pct(s.adherence)} (${num(s.mtdActual)} of ${num(s.mtdRequirement)}) · ")
        sb.append("week ${num(s.wtdActual)} of ${num(s.wtdPlan)} to date\n")
        if (s.todayEntered) sb.append("Today ${num(s.todayActual)} vs plan ${num(s.todayPlan)}\n")
        val short = s.parts.filter { it.m.mtdGap < -0.5 }.sortedBy { it.m.mtdGap }
        if (short.isEmpty()) {
            sb.append("All parts on plan.\n")
        } else {
            sb.append("Short to date ${num(s.shortToDate)}, need ${num(s.needPerDay)}/day:\n")
            for ((p, m) in short.take(5)) {
                sb.append("• ${partLabel(p)}: short ${num(-m.mtdGap)}, need ${num(m.monthReqPerDay)}/day\n")
            }
            if (short.size > 5) sb.append("• +${short.size - 5} more parts\n")
        }
    }
    return sb.toString().trimEnd()
}
