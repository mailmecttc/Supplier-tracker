package app.suppliertracker

import app.suppliertracker.model.Codec
import app.suppliertracker.model.Data
import app.suppliertracker.model.Issue
import app.suppliertracker.model.Part
import app.suppliertracker.model.Vendor
import app.suppliertracker.model.dailyPlan
import app.suppliertracker.model.dates
import app.suppliertracker.model.periodsOfMonth
import app.suppliertracker.model.pkey
import app.suppliertracker.model.NO_REASON
import app.suppliertracker.model.ReportPeriod
import app.suppliertracker.model.Status
import app.suppliertracker.model.gapCauses
import app.suppliertracker.model.periodReport
import app.suppliertracker.model.sumActual
import app.suppliertracker.model.sumPlan
import app.suppliertracker.model.vendorSummary
import app.suppliertracker.report.ReviewDeck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ReportTest {
    private val date = LocalDate.of(2026, 10, 9)

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes()
            }
        }
        return out
    }

    private fun sample(): Data {
        val v1 = Vendor("v1", "Vendor A", parts = listOf(Part("p1", "X-100", "Bracket", true), Part("p2", "X-200", "Gear")))
        val v2 = Vendor("v2", "Vendor B", dispatchDays = listOf(0, 0, 0, 0, 0, 1, 0), parts = listOf(Part("p1", "Y-1", "Cover")))
        var data = Data(vendors = listOf(v1, v2), holidays = listOf("2026-10-02"))
        val plan = mutableMapOf<String, Double>()
        for (p in periodsOfMonth(YearMonth.of(2026, 10))) {
            plan[pkey("v1", "p1", p.start.toString())] = 1200.0
            plan[pkey("v1", "p2", p.start.toString())] = 600.0
            plan[pkey("v2", "p1", p.start.toString())] = 500.0
        }
        data = data.copy(plan = plan, planOrig = plan, openingBacklog = mapOf(pkey("v1", "p1", "2026-10") to 400.0))
        val dispatch = mutableMapOf<String, Double>()
        for (d in dates(date.withDayOfMonth(1), date)) {
            dispatch[pkey("v1", "p1", d.toString())] = dailyPlan(data, v1, "p1", d) * 0.7
            dispatch[pkey("v1", "p2", d.toString())] = dailyPlan(data, v1, "p2", d)
        }
        return data.copy(
            dispatch = dispatch,
            issues = listOf(Issue("i1", "v1", "p1", "Material shortage (tier-2)", "Expedite castings", "Buyer", "2026-10-07", "2026-10-05")),
        )
    }

    private fun mtd(data: Data = sample()) = periodReport(data, ReportPeriod.of(ReportPeriod.Kind.MONTH_TO_DATE, date))

    @Test
    fun `deck on the built-in template has every slide registered and well-formed`() {
        val template = File("src/main/assets/report-template.pptx").readBytes()
        val deck = unzip(ReviewDeck.build(template, mtd()))
        val slides = deck.keys.filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }
        // title, summary, time gone, trend, parts short, parts on schedule, backlog, gap causes, reasons, final
        assertEquals(10, slides.size)
        val pres = String(deck.getValue("ppt/presentation.xml"))
        assertEquals(10, Regex("<p:sldId ").findAll(pres).count())
        val ct = String(deck.getValue("[Content_Types].xml"))
        val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        for (s in slides) {
            assertTrue("$s registered", ct.contains("PartName=\"/$s\""))
            assertTrue("$s has rels", deck.containsKey(s.replace("slides/", "slides/_rels/") + ".rels"))
            val doc = builder.parse(ByteArrayInputStream(deck.getValue(s)))
            // every table row has one cell per grid column, merged cells included
            val tables = doc.getElementsByTagNameNS("*", "tbl")
            for (t in 0 until tables.length) {
                val tbl = tables.item(t) as org.w3c.dom.Element
                val cols = tbl.getElementsByTagNameNS("*", "gridCol").length
                val rows = tbl.getElementsByTagNameNS("*", "tr")
                for (r in 0 until rows.length) {
                    val cells = (rows.item(r) as org.w3c.dom.Element).getElementsByTagNameNS("*", "tc").length
                    assertEquals("$s table $t row $r", cols, cells)
                }
            }
        }
        val all = slides.joinToString("") { String(deck.getValue(it)) }
        for (title in listOf("Supply status – all vendors", "Week-on-week adherence", "Parts short – all vendors (2)", "Parts on schedule – all vendors (1)", "Why parts are short – gap causes")) {
            assertTrue(title, all.contains(title))
        }
        assertTrue("vendor band merged across the table", all.contains("gridSpan=\"11\""))
        assertTrue(all.contains("Expedite castings"))
        assertTrue(all.contains("Material shortage (tier-2)"))
        assertFalse("no 'for boss' anywhere", all.contains("boss"))
        assertFalse("template's own slide text removed", all.contains("Weekly report<"))
    }

    @Test
    fun `month to date matches the vendor figures in the app`() {
        val data = sample()
        val r = mtd(data)
        val a = r.vendors.first { it.vendor.id == "v1" }
        val s = vendorSummary(data, data.vendors[0], date)
        assertEquals(s.mtdRequirement, a.reqToDate, 0.01)
        assertEquals(s.mtdActual, a.delivered, 0.01)
        assertEquals(400.0, a.backlogIn, 0.01)
        val bracket = a.parts.first { it.part.id == "p1" }
        val gear = a.parts.first { it.part.id == "p2" }
        assertEquals(Status.BEHIND, bracket.status)
        assertTrue(bracket.isShort && !bracket.onSchedule)
        assertTrue(gear.onSchedule && !gear.isShort)
        assertEquals("Material shortage (tier-2)", bracket.reason?.cause)
        // Vendor B's Saturday part has a requirement but nothing entered: listed with the short parts
        val cover = r.vendors.first { it.vendor.id == "v2" }.parts.single()
        assertEquals(Status.NOT_ENTERED, cover.status)
        assertTrue(cover.isShort)
        assertEquals(2, r.partsShort)
        assertEquals(1, r.notEntered)
    }

    @Test
    fun `a week carries the earlier shortfall in as backlog`() {
        val data = sample()
        val p = ReportPeriod.of(ReportPeriod.Kind.WEEK, date)
        assertEquals(LocalDate.of(2026, 10, 5), p.from)
        assertEquals(LocalDate.of(2026, 10, 11), p.to)
        assertEquals("Week 41 · 5–11 Oct 2026", p.title)
        val bracket = periodReport(data, p).vendors[0].parts[0]
        val v = data.vendors[0]
        val expected = 400 + sumPlan(data, v, "p1", date.withDayOfMonth(1), p.from.minusDays(1)) -
            sumActual(data, "v1", "p1", date.withDayOfMonth(1), p.from.minusDays(1)).total
        assertEquals(expected, bracket.backlogIn, 0.01)
        assertTrue(bracket.backlogIn > 400)
    }

    @Test
    fun `last month and custom ranges`() {
        val last = ReportPeriod.of(ReportPeriod.Kind.LAST_MONTH, date)
        assertEquals(LocalDate.of(2026, 9, 1), last.from)
        assertEquals(LocalDate.of(2026, 9, 30), last.asOf)
        assertEquals("September 2026", last.title)
        val custom = ReportPeriod.of(ReportPeriod.Kind.CUSTOM, date, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 1))
        assertEquals(LocalDate.of(2026, 10, 1), custom.from)
        assertEquals(date, custom.asOf)
        assertEquals("1–20 Oct 2026", custom.title)
        // a range that has not started: nothing due yet, everything still to come
        val future = ReportPeriod.of(ReportPeriod.Kind.CUSTOM, date, LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 23))
        val r = periodReport(sample(), future)
        assertTrue(r.vendors[0].parts.all { it.planToDate == 0.0 && it.delivered == 0.0 })
        assertTrue(r.vendors[0].plan > 0)
    }

    @Test
    fun `trend spots a vendor slipping week after week`() {
        var data = sample()
        val v1 = data.vendors[0]
        val dispatch = data.dispatch.toMutableMap()
        // weeks of 7 Sep .. 4 Oct at 100%, 95%, 88%, 80% of plan
        val weekly = listOf(1.0, 0.95, 0.88, 0.8)
        val plan = data.plan.toMutableMap()
        for (p in periodsOfMonth(YearMonth.of(2026, 9))) {
            plan[pkey("v1", "p1", p.start.toString())] = 1200.0
            plan[pkey("v1", "p2", p.start.toString())] = 600.0
        }
        data = data.copy(plan = plan)
        weekly.forEachIndexed { i, f ->
            val mon = LocalDate.of(2026, 9, 7).plusWeeks(i.toLong())
            for (d in dates(mon, mon.plusDays(6))) for (pid in listOf("p1", "p2")) {
                val q = dailyPlan(data, v1, pid, d)
                if (q > 0) dispatch[pkey("v1", pid, d.toString())] = q * f
            }
        }
        data = data.copy(dispatch = dispatch)
        val r = periodReport(data, ReportPeriod.of(ReportPeriod.Kind.MONTH_TO_DATE, LocalDate.of(2026, 10, 4)))
        val a = r.trend.first { it.name == "Vendor A" }
        assertEquals(6, r.weeks.size)
        assertEquals("W40", r.weeks.last().label)
        assertEquals(0.8, a.cells.last().adherence!!, 0.01)
        assertEquals(3, a.fallingWeeks)
        assertEquals(-1, a.direction)
    }

    @Test
    fun `gap causes are biggest first and add up to all pieces short`() {
        val r = mtd()
        val causes = gapCauses(r.vendors)
        assertEquals(r.shortToDate, causes.sumOf { it.short }, 0.01)
        assertEquals(1.0, causes.last().cumulative, 0.0001)
        assertEquals("Material shortage (tier-2)", causes.first().cause)
        // without the reason the same pieces count as "Reason not given"
        val bare = periodReport(sample().copy(issues = emptyList()), ReportPeriod.of(ReportPeriod.Kind.MONTH_TO_DATE, date))
        assertEquals(NO_REASON, gapCauses(bare.vendors).first().cause)
    }

    /** Local only: decks on a real template with a real data file, for visual checks. */
    @Test
    fun localDecks() {
        val tpl = System.getProperty("report.template").orEmpty()
        val dataPath = System.getProperty("report.data").orEmpty()
        assumeTrue(tpl.isNotBlank() && dataPath.isNotBlank())
        val out = File(System.getProperty("report.out").orEmpty().ifBlank { "build/report" }).apply { mkdirs() }
        val data = Codec.decode(File(dataPath).readText())
        val month = periodReport(data, ReportPeriod.of(ReportPeriod.Kind.MONTH_TO_DATE, date))
        File(out, "weekly-report.pptx").writeBytes(ReviewDeck.build(File(tpl).readBytes(), month))
        File(out, "weekly-report-builtin.pptx").writeBytes(ReviewDeck.build(File("src/main/assets/report-template.pptx").readBytes(), month))
        File(out, "week-report-builtin.pptx").writeBytes(
            ReviewDeck.build(File("src/main/assets/report-template.pptx").readBytes(), periodReport(data, ReportPeriod.of(ReportPeriod.Kind.WEEK, date))),
        )
        // the same data with simulated dispatch, to check RAG colours and long tables (never shared)
        val sim = simulate(data)
        File(out, "weekly-report-simulated.pptx").writeBytes(ReviewDeck.build(File(tpl).readBytes(), periodReport(sim, ReportPeriod.of(ReportPeriod.Kind.MONTH_TO_DATE, date))))
    }

    private fun simulate(data: Data): Data {
        val dispatch = data.dispatch.toMutableMap()
        val issues = data.issues.toMutableList()
        val factors = listOf(1.02, 0.9, 0.7, 1.0, 0.5, 0.96)
        for (v in data.vendors) {
            v.activeParts.forEachIndexed { i, p ->
                val f = factors[(i + v.name.length) % factors.size]
                for (d in dates(date.withDayOfMonth(1), date.minusDays(1))) {
                    val q = dailyPlan(data, v, p.id, d)
                    if (q > 0) dispatch[pkey(v.id, p.id, d.toString())] = Math.round(q * f).toDouble()
                }
                if (f < 0.85 && i % 2 == 0) issues += Issue("s$i${v.id}", v.id, p.id, "Shared machine loaded with other part", "Load machine 3 on 2nd shift from Monday", "Vendor PPC", "2026-10-12", "2026-10-08")
            }
        }
        return data.copy(dispatch = dispatch, issues = issues)
    }
}
