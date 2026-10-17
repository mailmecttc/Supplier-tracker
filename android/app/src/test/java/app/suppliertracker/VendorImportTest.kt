package app.suppliertracker

import app.suppliertracker.importer.Layout
import app.suppliertracker.importer.Sheet
import app.suppliertracker.importer.Xlsx
import app.suppliertracker.importer.applyImport
import app.suppliertracker.importer.chooseSheets
import app.suppliertracker.importer.guessVendor
import app.suppliertracker.importer.parseDate
import app.suppliertracker.importer.parseDateRange
import app.suppliertracker.importer.previewImport
import app.suppliertracker.importer.readSheet
import app.suppliertracker.importer.sheetKey
import app.suppliertracker.model.Codec
import app.suppliertracker.model.Data
import app.suppliertracker.model.MODE_CUM
import app.suppliertracker.model.Part
import app.suppliertracker.model.Vendor
import app.suppliertracker.model.periodPlan
import app.suppliertracker.model.pkey
import app.suppliertracker.model.setPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** Reading suppliers' own Excel reports. All data here is made up. */
class VendorImportTest {
    private fun d(s: String) = LocalDate.parse(s)
    private val oct = YearMonth.of(2026, 10)

    private val vendorA = Vendor(
        id = "a", name = "Supplier A", mode = MODE_CUM,
        parts = listOf(Part("p1", "A 100 200 30 40", "Bracket"), Part("p2", "A 100 200 30 50", "Pipe"), Part("p3", "ME123456", "Spacer")),
    )

    private fun xlsx(vararg sheets: Pair<String, List<List<Any?>>>): ByteArray = TestXlsx.build(*sheets)

    @Test fun `dates, ranges and sheet names as suppliers write them`() {
        assertEquals(d("2026-10-03"), parseDate("03-10-2026", 2026))
        assertEquals(d("2026-10-05"), parseDate("05.10.26", 2026))
        assertEquals(d("2026-10-05"), parseDate("5-Oct-26", 2026))
        assertEquals(d("2026-10-03"), parseDate("2026-10-03", 2026))
        assertNull(parseDate("Plan", 2026))
        assertEquals(d("2026-10-05") to d("2026-10-10"), parseDateRange("05.10.26-\n10.10.26", 2026))
        assertEquals("dispatches", sheetKey("OCT Dispatches"))
        assertEquals("dispatches", sheetKey("Sep Dispatches"))
        assertEquals("week", sheetKey("OCT-26 Week 7"))
        assertEquals(d("2026-10-03"), Xlsx.excelDate(46298.0))
    }

    @Test fun `invoice list - summed by day, till-date vendor gets running totals, a wrong 0 is replaced`() {
        val file = xlsx(
            "Sep Dispatches" to listOf(listOf("Invoice", "Invoice date", "Description", "Quantity"), listOf(1001, d("2026-09-30"), "A 100 200 30 40_BRACKET", 99)),
            "OCT Dispatches" to listOf(
                listOf(null, "Invoice", "Invoice date", "Description", "Part number", "Quantity"),
                listOf(null, 2001, d("2026-10-03"), "A 100 200 30 40_BRACKET", "A 100 200 30 40", 260),
                listOf(null, 2002, d("2026-10-03"), "A 100 200 30 40_BRACKET", "A 100 200 30 40", 260),
                listOf(null, 2003, d("2026-10-03"), "A 100 200 30 50_PIPE", "A 100 200 30 50", 784),
                listOf(null, 2004, d("2026-10-06"), "A 100 200 30 40_BRACKET", "A 100 200 30 40", 130),
                listOf(null, 2005, d("2026-10-06"), "X 999 999 99 99_OTHER", "X 999 999 99 99", 50),
            ),
        )
        val sheets = Xlsx.read(file)
        assertEquals(listOf("Sep Dispatches", "OCT Dispatches"), sheets.map { it.name })
        assertEquals(vendorA.id, guessVendor(sheets, listOf(Vendor("z", "Other"), vendorA))!!.first.id)
        val reads = sheets.map { readSheet(it, vendorA, oct) }
        val (dispatch, _) = chooseSheets(reads)
        assertEquals("OCT Dispatches", dispatch!!.sheet.name)
        assertEquals(Layout.LIST, dispatch.layout)
        assertEquals(520.0, dispatch.dispatch.getValue("p1" to d("2026-10-03")), 0.0)
        assertEquals(mapOf("X 999 999 99 99" to 50.0), dispatch.unmatched)

        // on the phone: a till-date 0 typed on 5 Oct after 260 on the 3rd, and a total typed on the 7th
        val data = Data(vendors = listOf(vendorA), cumulative = mapOf(
            pkey("a", "p1", "2026-10-03") to 260.0, pkey("a", "p1", "2026-10-05") to 0.0, pkey("a", "p1", "2026-10-07") to 900.0,
        )).let { app.suppliertracker.model.recomputeCumulative(it, "a", "p1", oct) }
        val p = previewImport(data, vendorA, dispatch, null, today = d("2026-10-17"), missingAsZero = true, usePlan = false)
        assertEquals(d("2026-10-03"), p.from)
        assertEquals(d("2026-10-06"), p.to)
        assertEquals(listOf("p3"), p.zeroParts) // the spacer has no invoice: 0 on the file's dispatch days
        assertTrue(p.changes.any { it.partId == "p1" && it.date == d("2026-10-05") && it.old == -260.0 && it.new == 0.0 })
        val out = applyImport(data, p)
        fun cum(day: String) = out.cumulative[pkey("a", "p1", day)]
        fun disp(day: String) = out.dispatch[pkey("a", "p1", day)]
        assertEquals(520.0, cum("2026-10-03")!!, 0.0)
        assertEquals(520.0, cum("2026-10-05")!!, 0.0) // the wrong 0 is now the real till-date total
        assertEquals(650.0, cum("2026-10-06")!!, 0.0)
        assertEquals(130.0, disp("2026-10-06")!!, 0.0)
        assertEquals(900.0, cum("2026-10-07")!!, 0.0) // typed after the file's days: kept as typed
        assertEquals(250.0, disp("2026-10-07")!!, 0.0)
        assertEquals(0.0, out.dispatch.getValue(pkey("a", "p3", "2026-10-05")), 0.0)
        assertNull(out.dispatch[pkey("a", "p1", "2026-10-04")]) // a Sunday with no invoice stays empty
    }

    @Test fun `day-wise table - Plan and Dispatch rows, a week of plan only when the file has all its days`() {
        val b = Vendor("b", "Supplier B", parts = listOf(Part("q1", "Q7000111222", "Lid"), Part("q2", "Q7000333444", "Ring")))
        val header = listOf<Any?>("Part Name", "Part No.", "plan vs Dispatch") + (1..13).map { LocalDate.of(2026, 10, it) } + listOf("TOTAL", "Balance")
        fun row(name: String?, no: String?, label: String, vararg q: Pair<Int, Int>): List<Any?> =
            listOf<Any?>(name, no, label) + (1..13).map { day -> q.firstOrNull { it.first == day }?.second } + listOf(q.sumOf { it.second }, null)
        val sheet = Xlsx.read(xlsx("Oct" to listOf(
            listOf("Daily plan and dispatch, Oct-2026"),
            header,
            row("LID", "Q7000111222", "Plan", 3 to 400, 7 to 400),
            row(null, null, "Disapatch", 5 to 400),
            row("RING", "Q7000333444", "Plan", 3 to 40, 5 to 35, 9 to 40),
            row(null, null, "Dispatch", 3 to 25, 5 to 35, 9 to 40),
            row("Total Plan", null, "", 3 to 440),
            row("Actual Dispatch", null, "", 3 to 25),
        ))).single()
        val r = readSheet(sheet, b, oct)
        assertEquals(Layout.DATE_COLUMNS, r.layout)
        assertEquals(400.0, r.dispatch.getValue("q1" to d("2026-10-05")), 0.0)
        assertEquals(25.0, r.dispatch.getValue("q2" to d("2026-10-03")), 0.0)
        assertEquals(400.0, r.dayPlan.getValue("q1" to d("2026-10-07")), 0.0)
        val p = previewImport(Data(vendors = listOf(b)), b, r, r, today = d("2026-10-17"), missingAsZero = false, usePlan = true)
        // blanks in a dispatch row are 0 on dispatch days between the first and last figure
        assertEquals(0.0, p.targets.getValue("q1" to d("2026-10-06"))!!, 0.0)
        assertEquals(d("2026-10-09"), p.to)
        // W40 (1-4 Oct) and W41 (5-11 Oct) are complete in the file; W42 (12-18 Oct) is not
        val out = applyImport(Data(vendors = listOf(b)), p)
        assertEquals(400.0, periodPlan(out, "b", "q1", d("2026-10-01")), 0.0)
        assertEquals(400.0, periodPlan(out, "b", "q1", d("2026-10-05")), 0.0)
        assertEquals(75.0, periodPlan(out, "b", "q2", d("2026-10-05")), 0.0)
        assertTrue(pkey("b", "q1", "2026-10-12") !in out.plan)
    }

    @Test fun `weekly table - Plan and Actual under each week's dates, the firm month plan, a title above`() {
        val g = Vendor("g", "Supplier G", parts = listOf(Part("r1", "R9000111222", "Axle"), Part("r2", "R9000333444", "Bush")))
        val sheet = Xlsx.read(xlsx("Sheet1" to listOf(
            listOf("Dispatch plan, month of Oct'26", "Dispatch plan, month of Oct'26", "Dispatch plan, month of Oct'26", "W1", "W1", "W2", "W2", "W3", "W3"),
            listOf("Customer", "PART NAME", "Part No.", "05.10.26-\n10.10.26", "05.10.26-\n10.10.26", "12.10.26-\n17.10.26", "12.10.26-\n17.10.26", "19.10.26-\n24.10.26", "19.10.26-\n24.10.26", "Firm Plan Oct-26", "FG"),
            listOf(null, null, null, "Plan", "Actual", "Plan", "Actual", "Plan", "Actual"),
            listOf("Customer X", "AXLE", "R9000111222", 1500, 1400, 1500, null, 1500, null, 6000, 1000),
            listOf("Customer X", "BUSH", "R9000333444", 1100, 2200, 1100, null, 1700, null, 6000, 300),
        ))).single()
        val r = readSheet(sheet, g, oct)
        assertEquals(Layout.WEEK_COLUMNS, r.layout)
        assertEquals(1400.0, r.weekActual.getValue("r1" to d("2026-10-05")), 0.0)
        assertEquals(1500.0, r.weekPlan.getValue("r1" to d("2026-10-12")), 0.0)
        assertEquals(6000.0, r.monthPlan.getValue("r1"), 0.0)
        assertTrue(r.weekActual.keys.none { it.second == d("2026-10-12") }) // W2 actual is blank
        val p = previewImport(Data(vendors = listOf(g)), g, r, r, today = d("2026-10-17"), missingAsZero = false, usePlan = true)
        // the week's total sits on its last dispatch day (Sat 10 Oct); the other days of the week hold nothing
        val out = applyImport(Data(vendors = listOf(g)), p)
        assertEquals(1400.0, out.dispatch.getValue(pkey("g", "r1", "2026-10-10")), 0.0)
        assertNull(out.dispatch[pkey("g", "r1", "2026-10-07")])
        assertEquals(1700.0, periodPlan(out, "g", "r2", d("2026-10-19")), 0.0)
    }

    @Test fun `a plan already on the phone is changed only where the file differs, and stays the original`() {
        val g = Vendor("g", "Supplier G", parts = listOf(Part("r1", "R9000111222", "Axle")))
        val sheet = Xlsx.read(xlsx("S" to listOf(
            listOf("Part No.", "Week41", "Week42", "Week41 Dispatched qty"),
            listOf("R9000111222", 1000, 1200, 900),
        ))).single()
        val r = readSheet(sheet, g, oct)
        var data = Data(vendors = listOf(g))
        data = setPlan(data, "g", "r1", d("2026-10-05"), 1000.0)
        data = setPlan(data, "g", "r1", d("2026-10-12"), 1100.0)
        val p = previewImport(data, g, r, r, today = d("2026-10-17"), missingAsZero = false, usePlan = true)
        assertEquals(1, p.planChanges.size)
        val out = applyImport(data, p)
        assertEquals(1200.0, out.plan.getValue(pkey("g", "r1", "2026-10-12")), 0.0)
        assertEquals(1100.0, out.planOrig.getValue(pkey("g", "r1", "2026-10-12")), 0.0)
    }

    @Test fun `csv files read too`() {
        val s: Sheet = Xlsx.readCsv("Date,Part,Qty\n03-10-2026,A 100 200 30 40,12\n04-10-2026,A 100 200 30 40,\"1,200\"\n")
        val r = readSheet(s, vendorA, oct)
        assertEquals(Layout.LIST, r.layout)
        assertEquals(1200.0, r.dispatch.getValue("p1" to d("2026-10-04")), 0.0)
    }

    /** Local only: a real supplier file (never committed). -Pvendor.file=… -Pvendor.data=… */
    @Test fun localVendorFile() {
        val path = System.getProperty("vendor.file").orEmpty()
        val dataPath = System.getProperty("vendor.data").orEmpty()
        assumeTrue(path.isNotBlank() && dataPath.isNotBlank())
        val data = Codec.decode(File(dataPath).readText())
        val sheets = Xlsx.read(File(path).readBytes())
        val (v, n) = guessVendor(sheets, data.vendors)!!
        println("VENDOR ${v.name} parts found $n")
        val reads = sheets.map { readSheet(it, v, oct) }
        reads.forEach { println("SHEET ${it.sheet.name}: ${it.layout} rows ${it.rowsRead} · ${it.found} · dispatch ${it.dispatch.size} weekActual ${it.weekActual.size} weekPlan ${it.weekPlan.size} monthPlan ${it.monthPlan.size} last ${it.lastDay}") }
        val (dispatch, plan) = chooseSheets(reads)
        println("CHOSEN dispatch=${dispatch?.sheet?.name} plan=${plan?.sheet?.name}")
        val p = previewImport(data, v, dispatch, plan, today = d("2026-10-17"), missingAsZero = true, usePlan = true)
        println("PREVIEW ${p.from}..${p.to} days ${p.days} total ${p.total} changes ${p.changes.size} planChanges ${p.planChanges.size} unmatched ${p.unmatched} zero ${p.zeroParts}")
        val out = applyImport(data, p)
        val monthTotals = v.parts.associate { it.desc to app.suppliertracker.model.sumActual(out, v.id, it.id, oct.atDay(1), d("2026-10-17")).total }
        println("MONTH TOTALS $monthTotals")
        System.getProperty("vendor.out").orEmpty().takeIf { it.isNotBlank() }?.let { File(it).writeText(Codec.encode(out)) }
    }
}
