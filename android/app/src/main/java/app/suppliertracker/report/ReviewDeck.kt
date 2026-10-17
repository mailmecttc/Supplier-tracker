// The review deck for management, laid out on the template's own title / content / final layouts.
package app.suppliertracker.report

import app.suppliertracker.model.NO_REASON
import app.suppliertracker.model.PartReport
import app.suppliertracker.model.PeriodReport
import app.suppliertracker.model.Status
import app.suppliertracker.model.VendorReport
import app.suppliertracker.model.WITHIN_TOLERANCE
import app.suppliertracker.model.niceDate
import app.suppliertracker.model.num
import app.suppliertracker.model.overdue
import app.suppliertracker.model.pct
import app.suppliertracker.model.rag
import app.suppliertracker.model.signed
import app.suppliertracker.report.SlideXml.Cell
import kotlin.math.roundToLong

private const val IN = 914400L
private fun inch(x: Double) = (x * IN).roundToLong()

private const val HEAD = "accent1" // the template's main brand colour
private const val HIGHLIGHT = "accent2" // the template's second (highlight) colour
private const val MUTED = "595959"
private const val TOTAL_FILL = "F2F2F2"
private const val BAND_FILL = "D9E2EC"
private const val SHORT_RED = "C00000"
private const val GOOD_GREEN = "00843D"

/** Solid RAG colour (RAG cells, bars). */
private fun ragFill(s: Status) = when (s.rag) {
    "G" -> "00A050"
    "A" -> "FFC000"
    "R" -> "FF0000"
    else -> "D9D9D9"
}

/** Light RAG tint for a whole table row. */
private fun ragTint(s: Status): String? = when (s.rag) {
    "G" -> "E2F0D9"
    "A" -> "FFF2CC"
    "R" -> "FBE5D6"
    else -> null
}

/** Stronger tint for the trend heat map. */
private fun heat(s: Status): String = when (s.rag) {
    "G" -> "C6E7CC"
    "A" -> "FFE699"
    "R" -> "F4B6B6"
    else -> "EDEDED"
}

/** Text colour for a figure judged by RAG. */
private fun ragText(s: Status): String = when (s.rag) {
    "G" -> GOOD_GREEN
    "A" -> "9C6500"
    "R" -> SHORT_RED
    else -> MUTED
}

private fun ragCell(s: Status, size: Int = 10) =
    Cell(s.rag, bold = true, align = "ctr", fill = ragFill(s), color = if (s.rag == "G" || s.rag == "R") "FFFFFF" else "262626", size = size)

private fun head(text: String, align: String = "r", size: Int = 10) = Cell(text, bold = true, align = align, fill = HEAD, color = "FFFFFF", size = size)

private fun numCell(x: Double?, size: Int = 10, bold: Boolean = false, fill: String? = null, color: String? = null) =
    Cell(num(x), bold = bold, align = "r", fill = fill, size = size, color = color)

private fun clip(s: String, n: Int) = if (s.length <= n) s else s.take(n - 1).trimEnd() + "…"

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

/** Vendors with their parts in display order, cut into pages of at most [perPage] table rows (vendor bands included). */
private fun <T> pagesByVendor(groups: List<Pair<VendorReport, List<T>>>, perPage: Int): List<List<Pair<VendorReport?, T?>>> {
    // a row is (vendor, null) for a band or (null, item) for a part; a band is repeated when a vendor continues
    val pages = mutableListOf<MutableList<Pair<VendorReport?, T?>>>()
    var page = mutableListOf<Pair<VendorReport?, T?>>()
    for ((v, items) in groups) {
        if (page.size >= perPage - 1) { pages += page; page = mutableListOf() }
        page += v to null
        for (item in items) {
            if (page.size >= perPage) {
                pages += page
                page = mutableListOf(v to null)
            }
            page += null to item
        }
    }
    if (page.isNotEmpty()) pages += page
    return pages
}

object ReviewDeck {
    /** Builds the review on [template] (any .pptx with title / "Slide with Title" style layouts). */
    fun build(template: ByteArray, r: PeriodReport, department: String = "SCM"): ByteArray {
        val t = PptxTemplate(template)
        val titleLayout = t.findLayout("Title Slide", "Title") ?: t.layouts.first()
        val contentLayout = t.findLayout("Slide with Title", "Title Only", "Title only", "Content Slide", "Title and Content") ?: t.layouts.last()
        val finalLayout = t.findLayout("Final", "Closing", "End Slide")
        val cb = t.contentBox
        val asOf = r.period.asOf
        val footer = "Supplier Dispatch Review | $department | ${niceDate(asOf)} ${asOf.year}"
        val slides = mutableListOf<Pair<Layout, String>>()

        fun content(title: String, body: SlideXml.() -> Unit) {
            val s = SlideXml()
            s.placeholder("title", null, listOf(Para(listOf(Run(title, null, null)), inherit = true)), "Title")
            val ftr = contentLayout.phIdx("ftr")
            if (ftr != null) {
                s.placeholder("ftr", ftr, listOf(Para(listOf(Run(footer, null, null)), inherit = true)), "Footer")
            } else {
                s.shape(Box(cb.x, cb.bottom + inch(0.15), cb.w, inch(0.3)), listOf(Para(footer, 9, color = MUTED)), "Footer")
            }
            contentLayout.phIdx("sldNum")?.let { s.slideNumber(it, slides.size + 1) }
            s.body()
            slides += contentLayout to s.xml()
        }

        fun SlideXml.message(text: String, color: String = HIGHLIGHT, sub: String? = null) {
            val paras = mutableListOf(Para(text, 16, bold = true, color = color))
            if (sub != null) paras += Para(sub, 11, color = MUTED)
            shape(Box(cb.x, cb.y, cb.w, inch(if (sub != null) 0.7 else 0.45)), paras, "Key message", inset = 0)
        }

        fun SlideXml.notes(y: Long, lines: List<String>) {
            if (y >= cb.bottom - inch(0.2)) return
            shape(Box(cb.x, y, cb.w, minOf(inch(0.9), cb.bottom - y)), lines.map { Para(it, 10, color = MUTED) }, "Notes", inset = 0)
        }

        val periodLine = "${r.period.title} · status as of ${niceDate(asOf, true)} ${asOf.year}"

        // 1. title
        SlideXml().apply {
            placeholder("title", null, listOf(Para(listOf(Run("Supplier Dispatch Review", null, null)), inherit = true)), "Title")
            val idx = titleLayout.phIdx("body")
            when {
                idx != null -> placeholder("body", idx, listOf(Para(listOf(Run(periodLine, null, null)), inherit = true)), "Subtitle")
                titleLayout.has("subTitle") -> placeholder("subTitle", titleLayout.phIdx("subTitle") ?: "1", listOf(Para(listOf(Run(periodLine, null, null)), inherit = true)), "Subtitle")
                else -> shape(Box(cb.x, cb.y + cb.h / 2, cb.w, inch(0.6)), listOf(Para(periodLine, 18)), "Subtitle")
            }
            slides += titleLayout to xml()
        }

        // 2. summary of all vendors, each row coloured by its RAG
        content("Supply status – all vendors") {
            val onSchedule = r.vendors.count { it.status == Status.ON_TRACK || it.status == Status.AHEAD }
            val msg = "$onSchedule of ${r.vendors.size} vendors on schedule · ${plural(r.partsShort, "part")} short · short ${num(r.shortToDate)} pcs to date" +
                if (r.notEntered > 0) " · no dispatch entered for ${plural(r.notEntered, "part")}" else ""
            message(msg, sub = periodLine)
            val cols = listOf(1.9, 0.5, 0.85, 0.95, 1.05, 1.1, 1.0, 1.0, 0.9, 0.95, 0.9, 0.95).map(::inch)
            val rows = mutableListOf(
                listOf(
                    head("Vendor", "l", 11), head("RAG", "ctr", 11), head("Parts short", size = 11), head("Backlog in", size = 11),
                    head("Period req.", size = 11), head("Req. to date", size = 11), head("Delivered", size = 11), head("Adherence", size = 11),
                    head("Days gone", size = 11), head("Coverage", size = 11), head("Short", size = 11), head("Need/day", size = 11),
                ),
            )
            for (v in r.vendors) {
                val tint = ragTint(v.status)
                rows += listOf(
                    Cell(v.vendor.name, bold = true, size = 12, fill = tint), ragCell(v.status, 12),
                    Cell("${v.partsShort} of ${v.partsInPlay}", align = "r", size = 12, fill = tint, bold = v.partsShort > 0, color = if (v.partsShort > 0) SHORT_RED else null),
                    numCell(v.backlogIn, 12, fill = tint), numCell(v.required, 12, fill = tint), numCell(v.reqToDate, 12, fill = tint),
                    numCell(v.delivered, 12, fill = tint), Cell(pct(v.adherence), align = "r", size = 12, bold = true, fill = tint),
                    Cell(pct(v.timeGone), align = "r", size = 12, fill = tint), Cell(pct(v.coverage), align = "r", size = 12, fill = tint),
                    numCell(v.short, 12, bold = v.short >= 0.5, fill = tint, color = if (v.short >= 0.5) SHORT_RED else null), numCell(v.needPerDay, 12, fill = tint),
                )
            }
            val required = r.vendors.sumOf { it.required }
            rows += listOf(
                Cell("Total", bold = true, size = 12, fill = TOTAL_FILL), Cell("", fill = TOTAL_FILL),
                Cell("${r.partsShort} of ${r.partsInPlay}", bold = true, align = "r", size = 12, fill = TOTAL_FILL),
                numCell(r.vendors.sumOf { it.backlogIn }, 12, true, TOTAL_FILL), numCell(required, 12, true, TOTAL_FILL),
                numCell(r.reqToDate, 12, true, TOTAL_FILL), numCell(r.delivered, 12, true, TOTAL_FILL),
                Cell(pct(r.adherence), bold = true, align = "r", size = 12, fill = TOTAL_FILL), Cell("", fill = TOTAL_FILL),
                Cell(pct(if (required >= 0.5) r.delivered / required else null), bold = true, align = "r", size = 12, fill = TOTAL_FILL),
                numCell(r.shortToDate, 12, true, TOTAL_FILL), numCell(r.vendors.sumOf { it.needPerDay }, 12, true, TOTAL_FILL),
            )
            val top = cb.y + inch(0.85)
            val rowH = minOf(inch(0.5), (cb.bottom - top - inch(0.85)) / rows.size)
            table(cb.x, top, cols, rowH, rows, "Vendor summary")
            notes(
                top + rowH * rows.size + inch(0.15),
                listOf(
                    "Row colour = vendor RAG. Parts short = parts red or amber, so a big part on schedule cannot hide a short one.",
                    "Period req. = backlog carried in + plan for the period. Adherence = delivered ÷ required to date. Days gone = share of the period's dispatch days passed. Coverage = delivered ÷ period req.",
                    "RAG on adherence to date: G ≥ 95% · A 85–95% · R < 85% · – no plan or no dispatch entered yet.",
                ),
            )
        }

        // 3. time gone vs delivered, bars in RAG colours
        content("Time gone vs deliveries") {
            shape(
                Box(cb.x, cb.y, cb.w, inch(0.45)),
                listOf(Para(listOf(Run("Bar: share of the period's requirement delivered, in RAG colour.  ", 13), Run("Line: share of dispatch days gone.", 13, true, HIGHLIGHT)))),
                "Legend", inset = 0,
            )
            val rowsTop = cb.y + inch(0.75)
            val rowH = minOf(inch(1.0), (cb.bottom - rowsTop) / maxOf(1, r.vendors.size))
            val labelW = inch(2.7)
            val trackX = cb.x + labelW
            val trackW = cb.w - labelW - inch(2.3)
            r.vendors.forEachIndexed { i, v ->
                val y = rowsTop + rowH * i
                shape(
                    Box(cb.x, y, labelW - inch(0.1), rowH),
                    listOf(Para(v.vendor.name, 14, bold = true), Para("${num(v.delivered)} of ${num(v.required)} delivered", 10, color = MUTED)),
                    "Vendor ${i + 1}", inset = 0,
                )
                val barY = y + inch(0.12)
                val barH = inch(0.34)
                val cov = v.coverage
                val gone = v.timeGone
                if (cov == null || gone == null) {
                    // a shutdown week can still have backlog due: say so rather than "nothing to deliver"
                    val text = if (v.required >= 0.5) "${num(v.required)} due · no dispatch day in this period" else "Nothing to deliver in this period"
                    shape(Box(trackX, barY, trackW, barH), listOf(Para(text, 11, color = MUTED)), "No plan ${i + 1}", inset = 0, anchor = "ctr")
                    return@forEachIndexed
                }
                shape(Box(trackX, barY, trackW, barH), emptyList(), "Track ${i + 1}", fillColor = "E7E6E6")
                val w = (trackW * cov.coerceIn(0.0, 1.0)).toLong()
                val barColor = if (v.status.rag == "–") HEAD else ragFill(v.status)
                if (w > 0) shape(Box(trackX, barY, w, barH), emptyList(), "Delivered ${i + 1}", fillColor = barColor)
                val mx = trackX + (trackW * gone.coerceIn(0.0, 1.0)).toLong()
                line(mx, barY - inch(0.08), mx, barY + barH + inch(0.08), HIGHLIGHT, 28575, "Days gone ${i + 1}")
                shape(
                    Box(trackX, barY + barH + inch(0.06), trackW, inch(0.28)),
                    listOf(Para(listOf(Run("Delivered ${pct(cov)}", 10, true, ragText(v.status)), Run("   ·   ${v.daysPassed} of ${v.daysTotal} days gone (${pct(gone)})", 10, false, HIGHLIGHT)))),
                    "Values ${i + 1}", inset = 0,
                )
                val gap = ((cov - gone) * 100).roundToLong()
                shape(
                    Box(trackX + trackW + inch(0.2), barY, inch(2.1), barH),
                    listOf(
                        Para(
                            when {
                                gap == 0L -> "On time"
                                gap > 0 -> "Ahead of time by $gap pts"
                                else -> "Behind time by ${-gap} pts"
                            },
                            12, bold = true, color = if (gap >= 0) GOOD_GREEN else SHORT_RED,
                        ),
                    ),
                    "Verdict ${i + 1}", inset = 0, anchor = "ctr",
                )
            }
        }

        // 4. week-on-week trend as a heat map
        content("Week-on-week adherence") {
            // the vendor with the biggest fall from an earlier week to the latest one
            val slipping = r.trend.mapNotNull { t -> t.worstSlide?.let { t to it } }
                .maxByOrNull { (t, s) -> t.cells[s.first].adherence!! - t.cells[s.second].adherence!! }
            val lastRed = r.trend.filter { it.cells.lastOrNull()?.status == Status.BEHIND }.minByOrNull { it.cells.last().adherence ?: 1.0 }
            val msg = when {
                slipping != null -> {
                    val (t, s) = slipping
                    "${t.name} has slipped from ${pct(t.cells[s.first].adherence)} in ${r.weeks[s.first].label} to ${pct(t.cells[s.second].adherence)} in ${r.weeks[s.second].label}"
                }
                lastRed != null -> "${lastRed.name} is below 85% this week (${pct(lastRed.cells.last().adherence)})"
                else -> "No vendor has slipped in the last ${r.weeks.size} weeks"
            }
            message(msg, color = if (slipping != null || lastRed != null) HIGHLIGHT else GOOD_GREEN)
            val weekW = 1.2
            val cols = (listOf(2.4) + r.weeks.map { weekW } + listOf(1.5)).map(::inch)
            val rows = mutableListOf(
                listOf(head("Vendor", "l", 11)) + r.weeks.map { head(it.label, "ctr", 11) } + listOf(head("Direction", "ctr", 11)),
                listOf(head("", "l", 8)) + r.weeks.map { w ->
                    head(if (w.partial) "to ${niceDate(w.toDate)}" else "${niceDate(w.start)}–${niceDate(w.end)}", "ctr", 8)
                } + listOf(head("", "ctr", 8)),
            )
            fun trendRow(t: app.suppliertracker.model.VendorTrend, total: Boolean): List<Cell> {
                val dir = t.direction
                return listOf(Cell(t.name, bold = true, size = 12, fill = if (total) "D9D9D9" else TOTAL_FILL)) +
                    t.cells.map { c ->
                        val text = when {
                            c.plan < 0.5 && !c.entered -> "nothing due"
                            !c.entered -> "not entered"
                            else -> pct(c.adherence ?: (if (c.actual > 0) 1.0 else null))
                        }
                        Cell(text, align = "ctr", size = if (c.adherence == null) 9 else 12, bold = total, fill = heat(c.status), color = if (c.adherence == null) MUTED else "262626")
                    } +
                    Cell(
                        when (dir) { -1 -> "▼ falling"; 1 -> "▲ rising"; else -> "→ steady" },
                        align = "ctr", size = 11, bold = true, color = when (dir) { -1 -> SHORT_RED; 1 -> GOOD_GREEN; else -> MUTED },
                    )
            }
            r.trend.forEach { rows += trendRow(it, false) }
            rows += trendRow(r.trendTotal, true)
            val top = cb.y + inch(0.6)
            val rowH = minOf(inch(0.5), (cb.bottom - top - inch(0.8)) / rows.size)
            table(cb.x, top, cols, rowH, rows, "Trend")
            notes(
                top + rowH * rows.size + inch(0.15),
                listOf(
                    "Each cell = that week's dispatch ÷ that week's plan (backlog not included, so one bad week does not colour the weeks after it). Direction = this week against the three before it.",
                    "Green ≥ 95% · amber 85–95% · red < 85%. The last ${r.weeks.size} weeks up to ${niceDate(asOf)}; the current week counts up to that day.",
                ),
            )
        }

        // 5. parts short, all vendors, grouped by vendor (worst vendor first, worst part first)
        val shortGroups = r.vendors
            .map { v -> v to v.parts.filter { it.isShort }.sortedByDescending { it.short } }
            .filter { it.second.isNotEmpty() }
            .sortedWith(compareByDescending<Pair<VendorReport, List<PartReport>>> { it.first.status.rank }.thenByDescending { it.first.short })
        val shortParts = shortGroups.flatMap { it.second }
        val shortCols = listOf(1.35, 1.95, 0.5, 0.9, 0.95, 1.0, 0.95, 0.7, 0.85, 0.85, 2.15).map(::inch)
        val shortPages = pagesByVendor(shortGroups, 15).ifEmpty { listOf(emptyList()) }
        shortPages.forEachIndexed { pi, page ->
            val suffix = if (shortPages.size > 1) " (${pi + 1}/${shortPages.size})" else ""
            content("Parts short – all vendors (${shortParts.size})$suffix") {
                if (shortParts.isEmpty()) {
                    message("No part is short in this period", color = GOOD_GREEN)
                    return@content
                }
                val vendorsShort = shortGroups.size
                message(
                    "${plural(shortParts.size, "part")} at ${plural(vendorsShort, "vendor")} · short ${num(shortParts.sumOf { it.short })} pcs to date · " +
                        "need ${num(shortParts.sumOf { it.needPerDay })} a day to recover",
                )
                val rows = mutableListOf(
                    listOf(
                        head("Part no.", "l"), head("Description", "l"), head("RAG", "ctr"), head("Backlog in"), head("Period plan"),
                        head("Req. to date"), head("Delivered"), head("Adh."), head("Short"), head("Need/day"), head("Reason", "l"),
                    ),
                )
                var prev: VendorReport? = null
                for ((v, p) in page) {
                    if (v != null) {
                        val cont = if (prev == v) " (cont.)" else ""
                        rows += listOf(
                            Cell(
                                "${v.vendor.name}$cont · ${v.status.label.lowercase()} · ${v.partsShort} of ${v.partsInPlay} parts short · ${pct(v.adherence)} to date",
                                bold = true, fill = BAND_FILL, span = 11,
                            ),
                        )
                        prev = v
                        continue
                    }
                    p ?: continue
                    val tint = ragTint(p.status)
                    val reason = p.reason?.cause?.ifBlank { null }
                    val reasonText = reason ?: if (p.status == Status.NOT_ENTERED) "No dispatch entered" else NO_REASON
                    rows += listOf(
                        Cell(p.part.partNo.ifBlank { "–" }, fill = tint), Cell(clip(p.part.desc.ifBlank { p.part.name }, 28), fill = tint), ragCell(p.status),
                        numCell(p.backlogIn, fill = tint), numCell(p.plan, fill = tint), numCell(p.reqToDate, fill = tint), numCell(p.delivered, fill = tint),
                        Cell(pct(p.adherence), align = "r", bold = true, fill = tint), numCell(p.short, bold = true, fill = tint, color = SHORT_RED),
                        numCell(p.needPerDay, fill = tint), Cell(clip(reasonText, 34), size = 9, fill = tint, color = if (reason == null) MUTED else null),
                    )
                }
                if (pi == shortPages.lastIndex) {
                    val req = shortParts.sumOf { it.reqToDate }
                    val del = shortParts.sumOf { it.delivered }
                    rows += listOf(
                        Cell("Total short", bold = true, fill = TOTAL_FILL), Cell("", fill = TOTAL_FILL), Cell("", fill = TOTAL_FILL),
                        numCell(shortParts.sumOf { it.backlogIn }, bold = true, fill = TOTAL_FILL), numCell(shortParts.sumOf { it.plan }, bold = true, fill = TOTAL_FILL),
                        numCell(req, bold = true, fill = TOTAL_FILL), numCell(del, bold = true, fill = TOTAL_FILL),
                        Cell(pct(if (req >= 0.5) del / req else null), bold = true, align = "r", fill = TOTAL_FILL),
                        numCell(shortParts.sumOf { it.short }, bold = true, fill = TOTAL_FILL), numCell(shortParts.sumOf { it.needPerDay }, bold = true, fill = TOTAL_FILL),
                        Cell("", fill = TOTAL_FILL),
                    )
                }
                table(cb.x, cb.y + inch(0.6), shortCols, inch(0.28), rows, "Parts short")
            }
        }

        // 6. parts on schedule, all vendors, grouped by vendor
        val okGroups = r.vendors
            .map { v -> v to v.parts.filter { it.onSchedule }.sortedBy { it.part.partNo } }
            .filter { it.second.isNotEmpty() }
        val okParts = okGroups.flatMap { it.second }
        val idle = r.vendors.sumOf { v -> v.parts.count { !it.isShort && !it.onSchedule } }
        val okCols = listOf(1.35, 2.2, 0.5, 0.95, 1.05, 1.1, 1.05, 0.8, 0.9, 0.95).map(::inch)
        val okPages = pagesByVendor(okGroups, 15).ifEmpty { listOf(emptyList()) }
        okPages.forEachIndexed { pi, page ->
            val suffix = if (okPages.size > 1) " (${pi + 1}/${okPages.size})" else ""
            content("Parts on schedule – all vendors (${okParts.size})$suffix") {
                if (okParts.isEmpty()) {
                    message("No part is on schedule yet in this period", color = SHORT_RED)
                    return@content
                }
                val req = okParts.sumOf { it.reqToDate }
                val del = okParts.sumOf { it.delivered }
                message(
                    "${okParts.size} of ${plural(r.partsInPlay, "part")} on schedule · delivered ${num(del)} against ${num(req)} required" +
                        (if (req >= 0.5) " (${pct(del / req)})" else ""),
                    color = GOOD_GREEN,
                )
                val rows = mutableListOf(
                    listOf(
                        head("Part no.", "l"), head("Description", "l"), head("RAG", "ctr"), head("Backlog in"), head("Period plan"),
                        head("Req. to date"), head("Delivered"), head("Adh."), head("Gap"), head("Need/day"),
                    ),
                )
                var prev: VendorReport? = null
                for ((v, p) in page) {
                    if (v != null) {
                        val n = okGroups.first { it.first == v }.second.size
                        val cont = if (prev == v) " (cont.)" else ""
                        rows += listOf(
                            Cell(
                                "${v.vendor.name}$cont · " + if (v.partsShort == 0) "all parts on schedule" else "$n of ${v.partsInPlay} parts on schedule",
                                bold = true, fill = BAND_FILL, span = 10,
                            ),
                        )
                        prev = v
                        continue
                    }
                    p ?: continue
                    val tint = ragTint(p.status)
                    rows += listOf(
                        Cell(p.part.partNo.ifBlank { "–" }, fill = tint), Cell(clip(p.part.desc.ifBlank { p.part.name }, 32), fill = tint), ragCell(p.status),
                        numCell(p.backlogIn, fill = tint), numCell(p.plan, fill = tint), numCell(p.reqToDate, fill = tint), numCell(p.delivered, fill = tint),
                        Cell(pct(p.adherence), align = "r", bold = true, fill = tint),
                        Cell(if (kotlin.math.abs(p.gap) < 0.5) "0" else signed(p.gap), align = "r", fill = tint, color = if (p.gap >= 0.5) GOOD_GREEN else null, bold = p.gap >= 0.5),
                        numCell(p.needPerDay, fill = tint),
                    )
                }
                val tableTop = cb.y + inch(0.6)
                table(cb.x, tableTop, okCols, inch(0.28), rows, "Parts on schedule")
                if (pi == okPages.lastIndex) {
                    notes(
                        tableTop + inch(0.28) * rows.size + inch(0.15),
                        listOf("On schedule = 95% or more of what was needed by ${niceDate(asOf)}. A small gap can still be on schedule.") +
                            if (idle > 0) listOf("${plural(idle, "part")} had no plan and no dispatch in this period and are left out.") else emptyList(),
                    )
                }
            }
        }

        // 7. backlog details across vendors
        val backlog = r.vendors.flatMap { v -> v.parts.map { v to it } }
            .filter { (_, p) -> p.backlogIn >= 0.5 || p.short >= 0.5 }
            .sortedWith(compareByDescending<Pair<VendorReport, PartReport>> { it.second.short }.thenByDescending { it.second.backlogIn })
        val backlogPages = backlog.chunked(13).ifEmpty { listOf(emptyList()) }
        backlogPages.forEachIndexed { pi, page ->
            val suffix = if (backlogPages.size > 1) " (${pi + 1}/${backlogPages.size})" else ""
            content("Backlog details$suffix") {
                val carried = r.vendors.sumOf { it.backlogIn }
                message("Backlog carried into the period ${num(carried)} pcs · short to date ${num(r.shortToDate)} pcs across ${plural(backlog.size, "part")}")
                if (page.isEmpty()) {
                    shape(Box(cb.x, cb.y + inch(0.7), cb.w, inch(0.5)), listOf(Para("No backlog and no shortfall.", 14, color = MUTED)), "Empty", inset = 0)
                    return@content
                }
                val cols = listOf(2.0, 1.35, 2.1, 1.0, 1.05, 1.1, 0.9, 0.5, 2.1).map(::inch)
                val rows = mutableListOf(
                    listOf(
                        head("Vendor", "l"), head("Part no.", "l"), head("Description", "l"), head("Backlog in"), head("Short to date"),
                        head("Period balance"), head("Need/day"), head("RAG", "ctr"), head("Reason", "l"),
                    ),
                )
                for ((v, p) in page) {
                    val tint = ragTint(p.status)
                    val reason = p.reason?.cause?.ifBlank { null }
                    rows += listOf(
                        Cell(v.vendor.name, bold = true, fill = tint), Cell(p.part.partNo.ifBlank { "–" }, fill = tint), Cell(clip(p.part.desc.ifBlank { p.part.name }, 32), fill = tint),
                        numCell(p.backlogIn, fill = tint), numCell(p.short, fill = tint, color = if (p.short >= 0.5) SHORT_RED else null),
                        numCell(kotlin.math.max(0.0, p.required - p.delivered), fill = tint), numCell(p.needPerDay, fill = tint), ragCell(p.status),
                        Cell(clip(reason ?: if (p.onSchedule) "" else NO_REASON, 34), size = 9, fill = tint, color = if (reason == null) MUTED else null),
                    )
                }
                table(cb.x, cb.y + inch(0.6), cols, inch(0.3), rows, "Backlog")
            }
        }

        // 8. gap causes, biggest first (Pareto)
        content("Why parts are short – gap causes") {
            val causes = r.causes
            val total = causes.sumOf { it.short }
            val real = causes.filter { it.cause != WITHIN_TOLERANCE && it.cause != NO_REASON }
            val first = causes.firstOrNull()
            val msg = when {
                first == null -> "No shortfall in this period"
                first.cause == NO_REASON -> "Most of the shortfall has no reason yet: ${pct(first.short / total)} of ${num(total)} pcs"
                first.cumulative >= 0.6 -> "${first.cause} alone explains ${pct(first.cumulative)} of the ${num(total)} pcs short"
                real.size >= 2 && causes.getOrNull(1)?.let { it.cause == real[1].cause } == true ->
                    "Two causes explain ${pct(causes[1].cumulative)} of the ${num(total)} pcs short: ${real[0].cause}, ${real[1].cause}"
                else -> "Biggest cause: ${first.cause} (${pct(first.short / total)} of ${num(total)} pcs short)"
            }
            message(msg, color = if (first == null) GOOD_GREEN else HIGHLIGHT)
            if (causes.isEmpty()) return@content
            val shown = if (causes.size <= 8) causes else causes.take(7) + app.suppliertracker.model.CauseRow(
                "Other causes", causes.drop(7).sumOf { it.short }, causes.drop(7).sumOf { it.parts }, 1.0,
            )
            val labelW = inch(3.1)
            val numW = inch(1.0)
            val partsW = inch(0.8)
            val cumW = inch(1.1)
            val gap = inch(0.15)
            val barX = cb.x + labelW + gap
            val barW = cb.w - labelW - numW - partsW - cumW - gap * 4
            val headY = cb.y + inch(0.6)
            val headH = inch(0.32)
            listOf(
                Triple(cb.x, labelW, "Gap cause") to "l", Triple(barX, barW, "Pieces short") to "l",
                Triple(barX + barW + gap, numW, "Pcs") to "r", Triple(barX + barW + gap * 2 + numW, partsW, "Parts") to "r",
                Triple(barX + barW + gap * 3 + numW + partsW, cumW, "Cumulative") to "r",
            ).forEach { (c, align) ->
                shape(Box(c.first, headY, c.second, headH), listOf(Para(c.third, 11, bold = true, color = "FFFFFF", align = align)), "Head ${c.third}", fillColor = HEAD, anchor = "ctr", inset = inch(0.06))
            }
            val rowsTop = headY + headH + inch(0.15)
            val rowH = minOf(inch(0.5), (cb.bottom - rowsTop - inch(0.75)) / shown.size)
            val max = shown.maxOf { it.short }
            shown.forEachIndexed { i, c ->
                val y = rowsTop + rowH * i
                val muted = c.cause == NO_REASON || c.cause == WITHIN_TOLERANCE
                shape(Box(cb.x, y, labelW, rowH), listOf(Para(c.cause, 13, bold = true, color = if (muted) MUTED else null)), "Cause ${i + 1}", anchor = "ctr", inset = inch(0.06))
                val bh = (rowH * 0.62).toLong()
                val by = y + (rowH - bh) / 2
                shape(Box(barX, by, barW, bh), emptyList(), "Track ${i + 1}", fillColor = "EDEDED")
                val w = (barW * (c.short / max)).toLong()
                val color = when (c.cause) {
                    NO_REASON -> "A6A6A6"
                    WITHIN_TOLERANCE -> "A9D18E"
                    else -> HEAD
                }
                if (w > 0) shape(Box(barX, by, w, bh), emptyList(), "Bar ${i + 1}", fillColor = color)
                shape(Box(barX + barW + gap, y, numW, rowH), listOf(Para(num(c.short), 13, bold = true, align = "r")), "Pcs ${i + 1}", anchor = "ctr", inset = inch(0.06))
                shape(Box(barX + barW + gap * 2 + numW, y, partsW, rowH), listOf(Para("${c.parts}", 13, align = "r")), "Parts ${i + 1}", anchor = "ctr", inset = inch(0.06))
                shape(Box(barX + barW + gap * 3 + numW + partsW, y, cumW, rowH), listOf(Para(pct(c.cumulative), 13, bold = true, align = "r")), "Cumulative ${i + 1}", anchor = "ctr", inset = inch(0.06))
            }
            val noReason = r.vendors.sumOf { v -> v.parts.count { !it.onSchedule && it.short >= 0.5 && it.reason == null } }
            notes(
                rowsTop + rowH * shown.size + inch(0.2),
                listOf("Each part's shortfall to date counts under its latest reason. Biggest cause first; Cumulative = share of all pieces short so far.") +
                    (if (noReason > 0) listOf("${plural(noReason, "part")} behind ${if (noReason == 1) "has" else "have"} no reason yet – log them in the app: Status → part → + Reason.") else emptyList()),
            )
        }

        // 9. reasons and actions: logged reasons first (overdue on top), then parts behind with none
        data class ReasonRow(val vendor: String, val part: String, val cause: String, val action: String, val owner: String, val due: String, val late: Boolean, val missing: Boolean)
        val logged = r.openIssues.sortedWith(compareBy({ !it.overdue(asOf) }, { it.due.ifBlank { "9999" } })).map { i ->
            val v = r.vendors.firstOrNull { it.vendor.id == i.vendorId }
            val due = i.due.takeIf { it.isNotBlank() }?.let { runCatching { niceDate(java.time.LocalDate.parse(it)) }.getOrDefault(it) }.orEmpty()
            ReasonRow(v?.vendor?.name.orEmpty(), v?.parts?.firstOrNull { it.part.id == i.partId }?.part?.name ?: "All parts", i.cause, i.action, i.owner, due, i.overdue(asOf), false)
        }
        val missing = r.vendors.flatMap { v ->
            v.parts.filter { (it.status == Status.BEHIND || it.status == Status.WATCH) && it.reason == null }
                .map { ReasonRow(v.vendor.name, it.part.name, NO_REASON, "", "", "", false, true) }
        }
        val reasonRows = logged + missing
        val reasonPages = reasonRows.chunked(10).ifEmpty { listOf(emptyList()) }
        reasonPages.forEachIndexed { pi, page ->
            val suffix = if (reasonPages.size > 1) " (${pi + 1}/${reasonPages.size})" else ""
            content("Reasons and actions$suffix") {
                val msg = when {
                    missing.isNotEmpty() -> "${plural(missing.size, "part")} behind schedule without a reason yet"
                    r.notEntered > 0 -> "No dispatch entered yet for ${plural(r.notEntered, "part")} with a requirement to date"
                    else -> "Every part behind schedule has a reason and an action"
                }
                message(msg)
                if (page.isEmpty()) {
                    shape(Box(cb.x, cb.y + inch(0.7), cb.w, inch(0.5)), listOf(Para("No open reasons. Log them in the app: Status → part → + Reason.", 14, color = MUTED)), "Empty", inset = 0)
                    return@content
                }
                val cols = listOf(1.9, 2.0, 2.7, 2.7, 1.7, 1.1).map(::inch)
                val rows = mutableListOf(listOf(head("Vendor", "l"), head("Part", "l"), head("Gap cause", "l"), head("Action", "l"), head("Owner", "l"), head("Due", "l")))
                for (x in page) {
                    rows += listOf(
                        Cell(x.vendor, bold = true), Cell(clip(x.part, 30)),
                        Cell(clip(x.cause, 40), color = if (x.missing) MUTED else null),
                        Cell(clip(x.action, 44)), Cell(clip(x.owner, 22)),
                        Cell(x.due + if (x.late) " (overdue)" else "", color = if (x.late) SHORT_RED else null, bold = x.late),
                    )
                }
                table(cb.x, cb.y + inch(0.6), cols, inch(0.36), rows, "Reasons")
            }
        }

        // 10. closing slide, if the template has one
        if (finalLayout != null) slides += finalLayout to SlideXml().xml()

        return t.build(slides)
    }
}
