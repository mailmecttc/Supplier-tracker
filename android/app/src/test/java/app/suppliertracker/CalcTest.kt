package app.suppliertracker

import app.suppliertracker.model.Codec
import app.suppliertracker.model.Data
import app.suppliertracker.model.MODE_CUM
import app.suppliertracker.model.Part
import app.suppliertracker.model.Replan
import app.suppliertracker.model.Status
import app.suppliertracker.model.Vendor
import app.suppliertracker.model.dailyPlan
import app.suppliertracker.model.dates
import app.suppliertracker.model.indian
import app.suppliertracker.model.monthPlan
import app.suppliertracker.model.monthRecord
import app.suppliertracker.model.openingBacklog
import app.suppliertracker.model.partMetrics
import app.suppliertracker.model.pastMonths
import app.suppliertracker.model.periodPlan
import app.suppliertracker.model.periodsOfMonth
import app.suppliertracker.model.sumPlan
import app.suppliertracker.model.pkey
import app.suppliertracker.model.recomputeCumulative
import app.suppliertracker.model.replan
import app.suppliertracker.model.setPlan
import app.suppliertracker.model.shareSummary
import app.suppliertracker.model.signed
import app.suppliertracker.model.status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

private val MON_SAT = listOf(1, 1, 1, 1, 1, 1, 0)
private fun d(s: String) = LocalDate.parse(s)
private val OCT = YearMonth.of(2026, 10)

private fun setup(days: List<Int> = MON_SAT, mode: String = "daily"): Pair<Data, Vendor> {
    val v = Vendor(id = "v1", name = "Vendor", mode = mode, dispatchDays = days, parts = listOf(Part("p1", "X-1", "Part", critical = true)))
    return Data(vendors = listOf(v)) to v
}

private fun Data.dispatched(day: String, qty: Double) = copy(dispatch = dispatch + (pkey("v1", "p1", day) to qty))

/** Spec section 8 acceptance tests, same cases as the web app's tests/calc.test.ts. */
class CalcTest {
    @Test fun `1 periods - October 2026 is W40 to W44 cut at month boundaries`() {
        val ps = periodsOfMonth(OCT).map { Triple(it.label, it.start.toString(), it.end.toString()) }
        assertEquals(
            listOf(
                Triple("W40", "2026-10-01", "2026-10-04"), Triple("W41", "2026-10-05", "2026-10-11"),
                Triple("W42", "2026-10-12", "2026-10-18"), Triple("W43", "2026-10-19", "2026-10-25"),
                Triple("W44", "2026-10-26", "2026-10-31"),
            ),
            ps,
        )
    }

    @Test fun `2 daily plan spread - 1800 over Mon-Sat is 300 a day, Sunday 0`() {
        val (d0, v) = setup()
        val data = setPlan(d0, "v1", "p1", d("2026-10-05"), 1800.0)
        for (x in dates(d("2026-10-05"), d("2026-10-10"))) assertEquals(300.0, dailyPlan(data, v, "p1", x), 0.0)
        assertEquals(0.0, dailyPlan(data, v, "p1", d("2026-10-11")), 0.0)
    }

    @Test fun `3 weekly dispatcher - plan lands on Saturday, not Behind mid-week`() {
        val (d0, v) = setup(listOf(0, 0, 0, 0, 0, 1, 0))
        var data = setPlan(d0, "v1", "p1", d("2026-10-05"), 1800.0)
        for (x in dates(d("2026-10-05"), d("2026-10-09"))) {
            assertEquals(0.0, dailyPlan(data, v, "p1", x), 0.0)
            val m = partMetrics(data, v, "p1", x)
            assertNotEquals(Status.BEHIND, m.weekStatus)
            assertNotEquals(Status.BEHIND, m.monthStatus)
        }
        assertEquals(1800.0, dailyPlan(data, v, "p1", d("2026-10-10")), 0.0)
        data = data.dispatched("2026-10-10", 0.0)
        assertEquals(Status.BEHIND, partMetrics(data, v, "p1", d("2026-10-10")).weekStatus)
    }

    @Test fun `4 holiday - no plan on 2 Oct, plan spread over remaining working days`() {
        val (d0, v) = setup()
        val data = setPlan(d0.copy(holidays = listOf("2026-10-02")), "v1", "p1", d("2026-10-01"), 900.0)
        assertEquals(0.0, dailyPlan(data, v, "p1", d("2026-10-02")), 0.0)
        assertEquals(450.0, dailyPlan(data, v, "p1", d("2026-10-01")), 0.0)
        assertEquals(450.0, dailyPlan(data, v, "p1", d("2026-10-03")), 0.0)
    }

    @Test fun `4b supplier days off - only that supplier, plan moves to its other days`() {
        val (d0, v0) = setup()
        val v = v0.copy(holidays = listOf("2026-10-06", "2026-10-07"))
        val other = v0.copy(id = "v2")
        val data = setPlan(d0.copy(vendors = listOf(v, other)), "v1", "p1", d("2026-10-05"), 1200.0)
        // week of 5 Oct: Mon-Sat minus Tue and Wed off = 4 days of 300
        assertEquals(0.0, dailyPlan(data, v, "p1", d("2026-10-06")), 0.0)
        assertEquals(300.0, dailyPlan(data, v, "p1", d("2026-10-05")), 0.0)
        assertTrue(app.suppliertracker.model.isWork(other, d("2026-10-06"), data.holidays))
        assertEquals(listOf(d("2026-10-06") to d("2026-10-07")), app.suppliertracker.model.dayRuns(v.holidays + "2026-10-06"))
        assertEquals("6–7 Oct 2026", app.suppliertracker.model.niceRange(d("2026-10-06"), d("2026-10-07")))
        assertEquals("28 Oct – 2 Nov 2026", app.suppliertracker.model.niceRange(d("2026-10-28"), d("2026-11-02")))
    }

    @Test fun `4c month plan - split by dispatch days, holidays and days off left out`() {
        val (d0, v) = setup()
        val data = d0.copy(holidays = listOf("2026-10-02"))
        // Oct 2026: W40 1-4 has Thu + Sat (Fri is a holiday) = 2 days, then 4 weeks of 6 days = 26 days, 100 a day
        val s = app.suppliertracker.model.splitMonthPlan(data, v, "p1", OCT, 2600.0, null)
        assertEquals(listOf(200.0, 600.0, 600.0, 600.0, 600.0), periodsOfMonth(OCT).map { s.weeks.getValue(it.start) })
        // the supplier's own days off move its plan to its other weeks
        val off = v.copy(holidays = (5..10).map { "2026-10-%02d".format(it) })
        val s2 = app.suppliertracker.model.splitMonthPlan(data, off, "p1", OCT, 2000.0, null)
        assertEquals(0.0, s2.weeks.getValue(d("2026-10-05")), 0.0)
        assertEquals(2000.0, s2.weeks.values.sum(), 0.0)
    }

    @Test fun `4d month plan - ended weeks keep their plan, the rest is spread in whole pieces`() {
        val (d0, v) = setup()
        var data = setPlan(d0, "v1", "p1", d("2026-10-01"), 300.0)
        data = setPlan(data, "v1", "p1", d("2026-10-05"), 900.0)
        val s = app.suppliertracker.model.splitMonthPlan(data, v, "p1", OCT, 2600.0, d("2026-10-12"))
        assertEquals(1200.0, s.kept, 0.0)
        assertEquals(setOf(d("2026-10-01"), d("2026-10-05")), s.keptWeeks)
        // 1,400 left over three 6-day weeks in whole pieces, kept level: 467, 466, 467
        assertEquals(listOf(467.0, 466.0, 467.0), listOf("2026-10-12", "2026-10-19", "2026-10-26").map { s.weeks.getValue(d(it)) })
    }

    @Test fun `4e month plan - Saturday-only supplier, empty edge weeks get nothing`() {
        val (d0, v0) = setup(days = listOf(0, 0, 0, 0, 0, 1, 0))
        val nov = YearMonth.of(2026, 11) // 1 Nov is a Sunday and 30 Nov a Monday: no Saturday in those weeks
        val s = app.suppliertracker.model.splitMonthPlan(d0, v0, "p1", nov, 1000.0, null)
        assertEquals(listOf(0.0, 250.0, 250.0, 250.0, 250.0, 0.0), periodsOfMonth(nov).map { s.weeks.getValue(it.start) })
        // no dispatch day at all: the last week takes the whole month
        val none = v0.copy(dispatchDays = listOf(0, 0, 0, 0, 0, 0, 0))
        val s2 = app.suppliertracker.model.splitMonthPlan(d0, none, "p1", nov, 1000.0, null)
        assertEquals(1000.0, s2.weeks.getValue(periodsOfMonth(nov).last().start), 0.0)
    }

    @Test fun `4f month plan - past weeks without a plan share it, fractions are kept`() {
        val (d0, v) = setup()
        val data = d0.copy(holidays = listOf("2026-10-02"))
        // nothing planned yet: on 12 Oct the ended weeks W40 and W41 still get their share
        val s = app.suppliertracker.model.splitMonthPlan(data, v, "p1", OCT, 2600.0, d("2026-10-12"))
        assertEquals(listOf(200.0, 600.0, 600.0, 600.0, 600.0), periodsOfMonth(OCT).map { s.weeks.getValue(it.start) })
        assertTrue(s.keptWeeks.isEmpty())
        // 10.5 kg over 2, 6, 6, 6, 6 days: whole pieces kept level, the 0.5 goes to the last week
        val f = app.suppliertracker.model.splitMonthPlan(data, v, "p1", OCT, 10.5, null)
        assertEquals(listOf(1.0, 2.0, 2.0, 3.0, 2.5), periodsOfMonth(OCT).map { f.weeks.getValue(it.start) })
        // a month that has ended with every week planned is left as it is
        val planned = periodsOfMonth(OCT).fold(data) { acc, p -> setPlan(acc, "v1", "p1", p.start, 100.0) }
        assertTrue(app.suppliertracker.model.splitMonthPlan(planned, v, "p1", OCT, 9999.0, d("2026-11-02")).weeks.isEmpty())
        assertEquals(500.0, app.suppliertracker.model.monthPlanOrNull(planned, "v1", "p1", OCT)!!, 0.0)
        assertEquals(null, app.suppliertracker.model.monthPlanOrNull(data, "v1", "p1", OCT))
    }

    @Test fun `4g month plan - a change never moves the plan of days already gone`() {
        val (d0, v) = setup()
        fun apply(d: Data, total: Double, asOf: LocalDate?) =
            app.suppliertracker.model.splitMonthPlan(d, v, "p1", OCT, total, asOf).weeks.entries.fold(d) { acc, (start, q) -> setPlan(acc, "v1", "p1", start, q) }
        // Oct 2026, Mon–Sat: 3 + 6 + 6 + 6 + 6 dispatch days, 2,700 is 100 a day
        val first = apply(d0, 2700.0, d("2026-10-01"))
        assertEquals(listOf(300.0, 600.0, 600.0, 600.0, 600.0), periodsOfMonth(OCT).map { periodPlan(first, "v1", "p1", it.start) })
        // raised to 3,300 on Thursday 15 Oct: W42 is under way (Mon–Wed gone), so the change starts on Monday 19
        val raised = apply(first, 3300.0, d("2026-10-15"))
        assertEquals(listOf(300.0, 600.0, 600.0, 900.0, 900.0), periodsOfMonth(OCT).map { periodPlan(raised, "v1", "p1", it.start) })
        assertEquals(1500.0, sumPlan(raised, v, "p1", d("2026-10-01"), d("2026-10-18")), 1e-9)
        // changed on Monday 12 Oct: W42 has no day gone yet and takes its share
        val monday = apply(first, 3300.0, d("2026-10-12"))
        assertEquals(listOf(300.0, 600.0, 800.0, 800.0, 800.0), periodsOfMonth(OCT).map { periodPlan(monday, "v1", "p1", it.start) })
        // on Wednesday 28 Oct every week is under way: the current week takes the change
        val late = apply(first, 3000.0, d("2026-10-28"))
        assertEquals(900.0, periodPlan(late, "v1", "p1", d("2026-10-26")), 0.0)
        // small quantities stay level: 3 pieces over the month are not all due early
        assertEquals(listOf(0L, 1L, 1L, 0L, 1L), app.suppliertracker.model.levelShares(3, listOf(3, 6, 6, 6, 6)).toList())
    }

    @Test fun `4h month plan - a week under way without a plan never takes a change`() {
        val (d0, v) = setup()
        val feb = YearMonth.of(2026, 2) // 1 Feb is a Sunday: W5 has no dispatch day and no plan
        val data = listOf("2026-02-02", "2026-02-09", "2026-02-16", "2026-02-23").fold(d0) { acc, w -> setPlan(acc, "v1", "p1", d(w), 600.0) }
        // raised from 2,400 to 3,000 on Wednesday 25 Feb: every week is under way, so the current week takes it
        val s = app.suppliertracker.model.splitMonthPlan(data, v, "p1", feb, 3000.0, d("2026-02-25"))
        assertEquals(mapOf(d("2026-02-23") to 1200.0), s.weeks)
        // W40 never planned, the rest 600 a week: on 15 Oct the change goes to W43 and W44 only
        val oct = listOf("2026-10-05", "2026-10-12", "2026-10-19", "2026-10-26").fold(d0) { acc, w -> setPlan(acc, "v1", "p1", d(w), 600.0) }
        val o = app.suppliertracker.model.splitMonthPlan(oct, v, "p1", OCT, 3000.0, d("2026-10-15"))
        assertEquals(mapOf(d("2026-10-19") to 900.0, d("2026-10-26") to 900.0), o.weeks)
        // a month that has ended is never rewritten, whichever weekday it starts on
        val nov = YearMonth.of(2026, 11)
        val n = periodsOfMonth(nov).drop(1).fold(d0) { acc, p -> setPlan(acc, "v1", "p1", p.start, 500.0) }
        assertTrue(app.suppliertracker.model.splitMonthPlan(n, v, "p1", nov, 2800.0, d("2026-12-03")).weeks.isEmpty())
    }

    @Test fun `4i entry box - 0 with no plan today is red when the part is short to date`() {
        val (d0, v) = setup()
        // backlog 784 carried into October, nothing planned in W41 (5-11 Oct)
        var data = d0.copy(openingBacklog = mapOf(pkey("v1", "p1", "2026-10") to 784.0))
        data = setPlan(data, "v1", "p1", d("2026-10-01"), 300.0)
        data = data.copy(dispatch = data.dispatch + (pkey("v1", "p1", "2026-10-05") to 0.0))
        val m = partMetrics(data, v, "p1", d("2026-10-05"))
        assertEquals(0.0, m.todayPlan, 0.0)
        val c = app.suppliertracker.model.entryCheck(m, 0.0)
        assertEquals(Status.BEHIND, c.status)
        assertTrue(c.text, c.text.contains("short 1,084 to date"))
        // the backlog cleared on a no-plan day is on schedule, not "extra"; only what goes beyond it is extra
        val cleared = data.copy(dispatch = data.dispatch + (pkey("v1", "p1", "2026-10-05") to 1084.0))
        val c2 = app.suppliertracker.model.entryCheck(partMetrics(cleared, v, "p1", d("2026-10-05")), 1084.0)
        assertEquals(Status.ON_TRACK, c2.status)
        assertEquals("No plan today · on schedule to date", c2.text)
        val over = data.copy(dispatch = data.dispatch + (pkey("v1", "p1", "2026-10-05") to 1300.0))
        assertEquals("No plan today · on schedule to date · +216 extra", app.suppliertracker.model.entryCheck(partMetrics(over, v, "p1", d("2026-10-05")), 1300.0).text)
        // nothing short: no plan today stays neutral, extra is shown as extra
        val clear = partMetrics(d0, v, "p1", d("2026-10-05"))
        assertEquals(Status.NO_PLAN, app.suppliertracker.model.entryCheck(clear, 0.0).status)
        assertEquals("No plan today · +5 extra", app.suppliertracker.model.entryCheck(clear, 5.0).text)
        // with a plan today: today's figure against it, as before
        val planned = partMetrics(setPlan(d0, "v1", "p1", d("2026-10-05"), 600.0), v, "p1", d("2026-10-05"))
        assertEquals(Status.BEHIND, app.suppliertracker.model.entryCheck(planned, 0.0).status)
        assertEquals(Status.ON_TRACK, app.suppliertracker.model.entryCheck(planned, 100.0).status)
        assertEquals(Status.NOT_ENTERED, app.suppliertracker.model.entryCheck(planned, null).status)
    }

    @Test fun `4j till-date - a total below an earlier one is caught, even past a wrong 0`() {
        val (d0, _) = setup(mode = MODE_CUM)
        val data = d0.copy(cumulative = mapOf(pkey("v1", "p1", "2026-10-03") to 784.0, pkey("v1", "p1", "2026-09-30") to 9000.0))
        assertEquals(d("2026-10-03") to 784.0, app.suppliertracker.model.peakCumulative(data, "v1", "p1", d("2026-10-05")))
        // only this month counts: on the 1st there is no earlier total
        assertNull(app.suppliertracker.model.peakCumulative(data, "v1", "p1", d("2026-10-01")))
        // 324 on Fri, a slip of 0 on Sat, the real 350 on Mon: Monday is checked against 324, Saturday is flagged
        val slip = d0.copy(cumulative = mapOf(
            pkey("v1", "p1", "2026-10-09") to 324.0, pkey("v1", "p1", "2026-10-10") to 0.0, pkey("v1", "p1", "2026-10-12") to 350.0,
        ))
        assertEquals(d("2026-10-09") to 324.0, app.suppliertracker.model.peakCumulative(slip, "v1", "p1", d("2026-10-12")))
        assertEquals(
            listOf(app.suppliertracker.model.TillDateDrop(d("2026-10-10"), 0.0, d("2026-10-09"), 324.0)),
            app.suppliertracker.model.tillDateDrops(slip, "v1", "p1", d("2026-10-12")),
        )
        assertTrue(app.suppliertracker.model.tillDateDrops(data, "v1", "p1", d("2026-10-31")).isEmpty())
    }

    @Test fun `4m percentages round down so they agree with the colour`() {
        assertEquals("94%", app.suppliertracker.model.pct(284.0 / 300))
        assertEquals(Status.WATCH, status(300.0, 284.0, true))
        assertEquals("84%", app.suppliertracker.model.pct(254.0 / 300))
        assertEquals("95%", app.suppliertracker.model.pct(285.0 / 300))
        assertEquals(Status.ON_TRACK, status(300.0, 285.0, true))
        assertEquals("100%", app.suppliertracker.model.pct(1.0))
    }

    @Test fun `4k part history - calendar days, weeks to date and recent months`() {
        val (d0, v0) = setup()
        val v = v0.copy(parts = listOf(Part("p1", "X-1", "Part", critical = true), Part("p2", "X-2", "Gear")))
        var data = d0.copy(vendors = listOf(v), holidays = listOf("2026-10-02"))
        data = setPlan(data, "v1", "p1", d("2026-10-05"), 600.0) // 100 a day Mon-Sat
        data = data.copy(dispatch = mapOf(pkey("v1", "p1", "2026-10-05") to 100.0, pkey("v1", "p1", "2026-10-06") to 50.0))
        val days = app.suppliertracker.model.monthDays(data, v, "p1", OCT)
        assertEquals(31, days.size)
        assertEquals(Status.ON_TRACK, days[4].status) // 5 Oct
        assertEquals(Status.BEHIND, days[5].status) // 6 Oct: 50 of 100
        assertTrue(days[1].holiday && !days[1].work) // 2 Oct
        assertEquals(Status.NOT_ENTERED, days[6].status) // 7 Oct: planned, nothing typed
        // weeks as of Wed 7 Oct: W41 has 300 due so far and 150 dispatched; W42 has not started
        val weeks = app.suppliertracker.model.monthWeeks(data, v, "p1", OCT, d("2026-10-07"))
        val w41 = weeks.first { it.period.start == d("2026-10-05") }
        assertEquals(300.0, w41.planToDate, 1e-9)
        assertEquals(150.0, w41.actual, 0.0)
        assertEquals(Status.BEHIND, w41.status)
        assertEquals(Status.NO_PLAN, weeks.first { it.period.start == d("2026-10-12") }.status)
        // months: September from the supplier's closing report, October to date with the backlog
        data = data.copy(closedPlan = mapOf(pkey("v1", "p1", "2026-09") to 2000.0), closedActual = mapOf(pkey("v1", "p1", "2026-09") to 1900.0))
        val months = app.suppliertracker.model.recentMonths(data, v, "p1", OCT, d("2026-10-07"))
        assertEquals(6, months.size)
        assertEquals(YearMonth.of(2026, 9), months[4].ym)
        assertEquals(Status.ON_TRACK, months[4].status) // 95%
        assertTrue(months[5].current)
        assertEquals(300.0, months[5].required!!, 1e-9)
        // vendor adherence: the critical part is behind, Gear has no plan
        val a = app.suppliertracker.model.vendorAdherence(app.suppliertracker.model.vendorSummary(data, v, d("2026-10-07")))
        assertEquals(1, a.critical)
        assertEquals(1, a.criticalBad)
        assertEquals(0.5, a.week!!, 1e-9)
        assertEquals(Status.NOT_ENTERED, a.dayStatus)
    }

    @Test fun `4l notes survive a backup round trip and old files without notes still open`() {
        val (d0, _) = setup()
        val data = d0.copy(notes = listOf(app.suppliertracker.model.Note("n1", "v1", "p1", "2026-10-05", "Supplier promised 500 by Wednesday")))
        assertEquals(data, Codec.decode(Codec.encode(data)))
        // a 1.4 file has no top-level "notes" list (and no "importFormats", added in 1.6)
        val old = Codec.encode(d0).replace(",\"notes\":[]", "").replace(",\"importFormats\":{}", "")
        assertTrue(!old.contains("\"notes\":[") && !old.contains("importFormats"))
        assertTrue(Codec.decode(old).notes.isEmpty())
    }

    @Test fun `5 cumulative mode - 5450 then 5610 is 160, editing first to 5500 gives 110`() {
        val (d0, _) = setup(mode = MODE_CUM)
        val k7 = pkey("v1", "p1", "2026-10-07")
        val k8 = pkey("v1", "p1", "2026-10-08")
        var data = recomputeCumulative(d0.copy(cumulative = mapOf(k7 to 5450.0, k8 to 5610.0)), "v1", "p1", OCT)
        assertEquals(160.0, data.dispatch[k8]!!, 0.0)
        assertEquals(5450.0, data.dispatch[k7]!!, 0.0)
        data = recomputeCumulative(data.copy(cumulative = data.cumulative + (k7 to 5500.0)), "v1", "p1", OCT)
        assertEquals(110.0, data.dispatch[k8]!!, 0.0)
    }

    @Test fun `6 backlog - 1150 plus month plan 7470 is 8620`() {
        val (d0, v) = setup()
        var data = d0.copy(openingBacklog = mapOf(pkey("v1", "p1", "2026-10") to 1150.0))
        listOf(870.0, 1800.0, 1800.0, 1800.0, 1200.0).zip(periodsOfMonth(OCT)).forEach { (q, p) -> data = setPlan(data, "v1", "p1", p.start, q) }
        assertEquals(7470.0, monthPlan(data, "v1", "p1", OCT), 0.0)
        val m = partMetrics(data, v, "p1", d("2026-10-01"))
        assertEquals(8620.0, m.openingBacklog + m.monthPlan, 0.0)
        assertEquals(8620.0, m.monthBalance, 0.0)
        assertEquals(290.0, dailyPlan(data, v, "p1", d("2026-10-01")), 0.0)
        assertEquals(1150.0 + 290.0, m.mtdRequirement, 1e-9)
    }

    @Test fun `7 zero plan - entering 0 on a no-plan day is neutral`() {
        val (d0, v) = setup()
        val m = partMetrics(d0.dispatched("2026-10-07", 0.0), v, "p1", d("2026-10-07"))
        assertEquals(0.0, m.todayPlan, 0.0)
        assertEquals(Status.NO_PLAN, m.weekStatus)
        assertEquals(Status.NO_PLAN, m.monthStatus)
        assertTrue(!m.behind)
    }

    @Test fun `8 re-plan - 1800 then 1500 shows original 1800, re-planned -300`() {
        val (d0, _) = setup()
        var data = setPlan(d0, "v1", "p1", d("2026-10-05"), 1800.0)
        assertNull(replan(data, "v1", "p1", d("2026-10-05")))
        data = setPlan(data, "v1", "p1", d("2026-10-05"), 1500.0)
        assertEquals(Replan(1800.0, -300.0), replan(data, "v1", "p1", d("2026-10-05")))
        assertEquals("−300", signed(-300.0))
    }

    @Test fun `9 auto carry - November opening is Oct opening plus plan minus actual`() {
        val (d0, v) = setup()
        var data = setPlan(d0.copy(openingBacklog = mapOf(pkey("v1", "p1", "2026-10") to 1150.0)), "v1", "p1", d("2026-10-05"), 1800.0)
        data = data.dispatched("2026-10-06", 1000.0)
        assertEquals(1150.0 + 1800.0 - 1000.0, openingBacklog(data, v, "p1", YearMonth.of(2026, 11)), 0.0)
        data = data.dispatched("2026-10-07", 5000.0)
        assertEquals(0.0, openingBacklog(data, v, "p1", YearMonth.of(2026, 11)), 0.0)
        data = data.copy(openingBacklog = data.openingBacklog + (pkey("v1", "p1", "2026-11") to 42.0))
        assertEquals(42.0, openingBacklog(data, v, "p1", YearMonth.of(2026, 11)), 0.0)
    }

    @Test fun `status thresholds`() {
        assertEquals(Status.NO_PLAN, status(0.0, 0.0, true))
        assertEquals(Status.AHEAD, status(0.0, 10.0, true))
        assertEquals(Status.NOT_ENTERED, status(100.0, 0.0, false))
        assertEquals(Status.ON_TRACK, status(100.0, 95.0, true))
        assertEquals(Status.WATCH, status(100.0, 85.0, true))
        assertEquals(Status.BEHIND, status(100.0, 84.9, true))
    }

    @Test fun `need per day spreads the balance over working days left`() {
        val (d0, v) = setup()
        val data = setPlan(d0, "v1", "p1", d("2026-10-05"), 1800.0).dispatched("2026-10-05", 0.0)
        val m = partMetrics(data, v, "p1", d("2026-10-05"))
        assertEquals(1800.0, m.weekBalance, 0.0)
        assertEquals(360.0, m.weekReqPerDay, 0.0) // Tue..Sat = 5 days
    }
}

class FormatAndFileTest {
    @Test fun `indian grouping`() {
        assertEquals("0", indian(0))
        assertEquals("999", indian(999))
        assertEquals("1,000", indian(1000))
        assertEquals("1,23,456", indian(123456))
        assertEquals("12,34,56,789", indian(123456789))
        assertEquals("−2,920", indian(-2920))
    }

    @Test fun `reads a web-app backup with whole numbers and unknown fields`() {
        val text = """{"version":1,"vendors":[{"id":"v1","name":"V","contact":"","mode":"cum","dispatchDays":[1,1,1,1,1,1,0],
            "parts":[{"id":"p1","partNo":"A 1","desc":"D","critical":true,"active":true}],"sample":false,"extra":1}],
            "plan":{"v1|p1|2026-10-05":1800},"planOrig":{},"dispatch":{},"cumulative":{},"openingBacklog":{"v1|p1|2026-10":1150},
            "commitment":{},"issues":[],"holidays":["2026-10-02"],"lastBackup":""}"""
        val data = Codec.decode(text)
        assertEquals("cum", data.vendors[0].mode)
        assertEquals(1800.0, data.plan["v1|p1|2026-10-05"]!!, 0.0)
        assertEquals("", data.vendors[0].address)
        assertEquals(data, Codec.decode(Codec.encode(data)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a file that is not a backup`() {
        Codec.decode("""{"hello": "world", "version": 2}""")
    }

    @Test fun `past months come from supplier closing totals`() {
        val (d0, v) = setup()
        val data = d0.copy(
            closedPlan = mapOf(pkey("v1", "p1", "2026-09") to 6760.0),
            closedActual = mapOf(pkey("v1", "p1", "2026-09") to 5610.0, pkey("v1", "p1", "2026-08") to 4500.0),
        )
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 8)), pastMonths(data, v, OCT))
        val sep = monthRecord(data, v, "p1", YearMonth.of(2026, 9))
        assertEquals(1150.0, sep.short!!, 0.0)
        assertNull(monthRecord(data, v, "p1", YearMonth.of(2026, 8)).plan)
    }

    @Test fun `share summary lists the short parts`() {
        val (d0, v) = setup()
        val data = setPlan(d0, "v1", "p1", d("2026-10-05"), 1800.0).dispatched("2026-10-05", 100.0)
        val text = shareSummary(data, listOf(v), d("2026-10-06"))
        assertTrue(text, text.contains("*Vendor* – Behind"))
        assertTrue(text, text.contains("Part (X-1): short 500"))
    }
}
