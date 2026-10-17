package app.suppliertracker

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import app.suppliertracker.model.Codec
import app.suppliertracker.model.Data
import app.suppliertracker.model.Part
import app.suppliertracker.model.Vendor
import app.suppliertracker.model.periodOf
import app.suppliertracker.model.pkey
import app.suppliertracker.ui.AppViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class AppUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun vm() = ViewModelProvider(rule.activity)[AppViewModel::class.java]

    /** With a fingerprint / screen lock on the device the app opens on the lock screen; switch it off for the test. */
    private fun lockScreenThenUnlock() {
        if (rule.onAllNodesWithText("Supplier Tracker is locked").fetchSemanticsNodes().isEmpty()) return
        rule.onNodeWithText("Unlock with fingerprint").assertExists()
        rule.onAllNodesWithText("Import data file").assertCountEquals(0)
        rule.runOnUiThread { vm().setLock(false) }
        rule.waitForIdle()
    }

    private fun sample(): Data {
        val week = periodOf(LocalDate.now()).start.toString()
        val v = Vendor(
            id = "v1", name = "Vendor A", address = "Plot 1, Industrial Area",
            parts = listOf(Part("p1", "X-100", "Bracket", critical = true), Part("p2", "X-200", "Gear")),
        )
        return Data(vendors = listOf(v), plan = mapOf(pkey("v1", "p1", week) to 1800.0, pkey("v1", "p2", week) to 600.0))
    }

    @Test
    fun firstLaunchImportThenDailyEntry() {
        rule.waitUntil(5_000) { vm().loaded }
        lockScreenThenUnlock()
        rule.onNodeWithText("Import data file").assertExists()

        rule.runOnUiThread { vm().replaceAll(sample()) }
        rule.onNodeWithText("Entry").performClick()
        rule.onNodeWithText("Bracket").assertExists()
        rule.onNodeWithText("X-100").assertExists()

        rule.onAllNodes(hasSetTextAction())[0].performTextInput("300")
        rule.runOnIdle {
            assertEquals(300.0, vm().data.dispatch[pkey("v1", "p1", LocalDate.now().toString())]!!, 0.0)
        }

        rule.onNodeWithText("Status").performClick()
        rule.onNodeWithText("Vendor details").assertExists()
        rule.onNodeWithText("Open in Maps").assertExists()

        // log a reason for the first part (worst first: Bracket is short)
        rule.onAllNodesWithText("+ Reason")[0].performScrollTo().performClick()
        rule.onNodeWithText("Material shortage (tier-2)").performScrollTo().performClick()
        rule.onNodeWithText("Machine breakdown").performClick()
        rule.onNodeWithText("Action").performScrollTo().performTextInput("Spare spindle on the way")
        rule.onNodeWithText("Save reason").performScrollTo().performClick()
        rule.runOnIdle {
            val i = vm().data.issues.single()
            assertEquals("Machine breakdown", i.cause)
            assertEquals("Spare spindle on the way", i.action)
            assertEquals("open", i.status)
        }
        rule.onNodeWithText("Reason: Machine breakdown").assertExists()
        rule.onNodeWithText("Spare spindle on the way").assertExists()

        rule.onNodeWithText("Dashboard").performClick()
        rule.onAllNodesWithText("Vendor A")[0].assertExists()
        rule.onNodeWithText("Build report (PowerPoint)").assertExists()
        rule.onAllNodesWithText("boss", substring = true).assertCountEquals(0)

        // the report builds on the phone from the bundled template
        val deck = runBlocking { vm().buildReport() }
        assertTrue(deck.size > 1000 && deck[0] == 'P'.code.toByte() && deck[1] == 'K'.code.toByte())
    }

    @Test
    fun helpOpensTheGuideAndBack() {
        rule.waitUntil(5_000) { vm().loaded }
        lockScreenThenUnlock()
        // with no vendors the app opens on Setup, with Help & support at the top
        // (start empty: Robolectric keeps the saved file from earlier tests in this class)
        rule.runOnUiThread { vm().replaceAll(Data()) }
        rule.onNodeWithText("Read user guide").performClick()
        rule.onNodeWithText("User guide").assertExists()
        rule.onAllNodesWithText("Import data file").assertCountEquals(0)
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithText("Import data file").assertExists()
        // the problem report carries counts only
        val text = app.suppliertracker.ui.screens.problemReport(sample(), LocalDate.of(2026, 10, 10), "Setup")
        assertTrue(text.contains("1 vendors, 2 parts") && !text.contains("Vendor A") && !text.contains("X-100"))
    }

    @Test
    fun settingsThemeTextSizeAndLockedBackup() {
        rule.waitUntil(5_000) { vm().loaded }
        lockScreenThenUnlock()
        rule.runOnUiThread { vm().replaceAll(sample()) }
        rule.onNodeWithText("Setup").performClick()
        rule.onNodeWithText("Open settings").performScrollTo().performClick()
        rule.onNodeWithText("Appearance").assertExists()
        rule.onNodeWithText("Dark").performClick()
        rule.onNodeWithText("Large").performClick()
        rule.runOnIdle {
            assertEquals(app.suppliertracker.ui.AppViewModel.ThemeMode.DARK, vm().themeMode)
            assertEquals(1.15f, vm().textScale, 0.001f)
        }
        rule.onNodeWithText("Set a password").performScrollTo().performClick()
        rule.onNodeWithText("New password (6 or more characters)").performTextInput("secret1")
        rule.onNodeWithText("Type it again").performTextInput("secret1")
        rule.onNodeWithText("Save password").performScrollTo().performClick()
        rule.runOnIdle { assertTrue(vm().backupLocked) }

        // a locked backup opens only with its password, then replaces the data after "Replace"
        val locked = runBlocking { vm().backupText() }
        assertTrue(app.suppliertracker.data.BackupCrypto.isEncrypted(locked))
        assertFalse(locked.contains("Vendor A"))
        rule.runOnUiThread { vm().replaceAll(sample().copy(vendors = sample().vendors.map { it.copy(name = "Changed") })) }
        rule.runOnUiThread { vm().beginImport(locked) }
        runBlocking { vm().unlockImport("wrong-one") }
        rule.runOnIdle { assertTrue((vm().pendingImport as app.suppliertracker.ui.AppViewModel.PendingImport.Locked).error!!.startsWith("Wrong password")) }
        runBlocking { vm().unlockImport("secret1") }
        rule.runOnIdle {
            assertTrue(vm().pendingImport is app.suppliertracker.ui.AppViewModel.PendingImport.Ready)
            vm().confirmImport()
            assertEquals("Vendor A", vm().data.vendors.single().name)
        }
        rule.runOnUiThread {
            vm().updateBackupPassword(null)
            vm().updateTheme(app.suppliertracker.ui.AppViewModel.ThemeMode.PHONE)
            vm().updateTextScale(1f)
        }
    }

    @Test
    fun statusFilterPartPageAndDatedNotes() {
        rule.waitUntil(5_000) { vm().loaded }
        lockScreenThenUnlock()
        val today = LocalDate.now()
        // Gear dispatched in full every day so far this week; Bracket (critical) nothing
        val base = sample()
        val gear = app.suppliertracker.model.dates(periodOf(today).start, today)
            .map { pkey("v1", "p2", it.toString()) to app.suppliertracker.model.dailyPlan(base, base.vendors[0], "p2", it) }.toMap()
        rule.runOnUiThread { vm().replaceAll(base.copy(dispatch = gear)); vm().selectDate(today) }
        rule.onNodeWithText("Status").performClick()
        listOf("Daily adherence", "Weekly adherence", "Monthly adherence", "Critical parts", "Parts details").forEach { rule.onNodeWithText(it).assertExists() }
        listOf("Short to date", "Need per day", "Week to date", "Parts behind").forEach { rule.onAllNodesWithText(it).assertCountEquals(0) }
        // the filter: Good shows Gear only, Bad shows Bracket only
        rule.onNodeWithText("Good 1").performScrollTo().performClick()
        rule.onAllNodesWithText("Bracket").assertCountEquals(0)
        rule.onNodeWithText("Gear").assertExists()
        rule.onNodeWithText("Bad 1").performClick()
        rule.onAllNodesWithText("Gear").assertCountEquals(0)

        // a part opens its own page: calendar, weekly and monthly views
        rule.onAllNodesWithText("Calendar ›")[0].performScrollTo().performClick()
        rule.onNodeWithText("Calendar").assertExists()
        rule.onNodeWithText("Mon").assertExists()
        rule.onNodeWithText("Weekly").performClick()
        rule.onNodeWithText("Weekly plan vs dispatch", substring = true).assertExists()
        rule.onNodeWithText("Monthly").performClick()
        rule.onNodeWithText("Monthly plan vs dispatch").assertExists()
        // a note filed under the day
        rule.onNodeWithText("+ Add note", substring = true).performScrollTo().performClick()
        rule.onNode(hasSetTextAction()).performScrollTo().performTextInput("Supplier promised the balance by Friday")
        rule.onNodeWithText("Save note").performScrollTo().performClick()
        rule.runOnIdle {
            val n = vm().data.notes.single()
            assertEquals("p1", n.partId)
            assertEquals(today.toString(), n.date)
        }
        rule.onNodeWithContentDescription("Back").performClick()
        // back on Status the Bad filter is still on
        rule.onAllNodesWithText("Gear").assertCountEquals(0)
        rule.onNodeWithText("All 2").performScrollTo().performClick()
        // the vendor's notes show it with the part's name; it can be edited
        rule.onNodeWithText("Bracket: Supplier promised the balance by Friday").performScrollTo()
        rule.onAllNodesWithText("Edit").onLast().performClick()
        rule.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("Balance on Friday, confirmed")
        rule.onNodeWithText("Save note").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("Balance on Friday, confirmed", vm().data.notes.single().text) }
        // several points on one day read newest first, in the order they were added
        rule.runOnUiThread { (1..5).forEach { vm().addNote(vm().vendor!!, "", today, "point $it") } }
        rule.waitForIdle()
        val ys = (1..5).map { rule.onNodeWithText("point $it").fetchSemanticsNode().positionInRoot.y }
        assertEquals(ys.sortedDescending(), ys)
    }

    @Test
    fun tillDateTotalLowerThanBeforeIsFlagged() {
        rule.waitUntil(5_000) { vm().loaded }
        lockScreenThenUnlock()
        val today = LocalDate.now()
        assumeTrue(today.dayOfMonth > 2) // needs two earlier days in the same month
        val v = sample().vendors[0].copy(mode = app.suppliertracker.model.MODE_CUM)
        val slipDay = today.minusDays(1)
        // 324 two days ago, then 0 typed yesterday for "nothing today": a till-date slip
        val data = sample().copy(vendors = listOf(v), cumulative = mapOf(
            pkey("v1", "p1", today.minusDays(2).toString()) to 324.0, pkey("v1", "p1", slipDay.toString()) to 0.0,
        ))
        rule.runOnUiThread { vm().replaceAll(data); vm().selectDate(today) }
        rule.onNodeWithText("Entry").performClick()
        // today shows yesterday's slip, with a jump to that day
        rule.onNodeWithText("Fix ${app.suppliertracker.model.niceDate(slipDay)}").performClick()
        rule.runOnIdle { assertEquals(slipDay, vm().date) }
        // on that day the 0 is flagged, and one tap keeps the total at 324
        rule.onNodeWithText("Lower than the last till-date total").assertExists()
        rule.onNodeWithText("Nothing today: use 324").performClick()
        rule.runOnIdle {
            assertEquals(324.0, vm().data.cumulative.getValue(pkey("v1", "p1", slipDay.toString())), 0.0)
            assertEquals(0.0, vm().data.dispatch.getValue(pkey("v1", "p1", slipDay.toString())), 0.0)
        }
        rule.onAllNodesWithText("Lower than the last till-date total").assertCountEquals(0)
        // typing a lower total again is flagged as you type
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("100")
        rule.onNodeWithText("Lower than the last till-date total").assertExists()
    }

    @Test
    fun vendorExcelFileIsCheckedSavedAndCanBeUndone() {
        rule.waitUntil(5_000) { vm().loaded }
        lockScreenThenUnlock()
        val today = LocalDate.now()
        rule.runOnUiThread { vm().replaceAll(sample()); vm().selectDate(today) }
        // the vendor's own report: one row per invoice, made up
        val file = TestXlsx.build("Dispatches" to listOf(
            listOf("Inv No", "Inv Date", "Part No", "Qty"),
            listOf(9001, today, "X-100", 120),
            listOf(9002, today, "X-100", 30),
            listOf(9003, today, "X-200", 50),
        ))
        rule.runOnUiThread { assertEquals(null, vm().openVendorFile(file, "VendorA_report.xlsx")) }
        rule.onNodeWithText("Import vendor file").assertExists()
        rule.onNodeWithText("Vendor A").assertExists()
        rule.onNodeWithText("Dispatch from").assertExists()
        rule.onNodeWithText("Save 1 day").performClick()
        rule.onNodeWithText("Saved").assertExists()
        rule.runOnIdle {
            assertEquals(150.0, vm().data.dispatch.getValue(pkey("v1", "p1", today.toString())), 0.0)
            assertEquals("dispatches", vm().data.importFormats.getValue("v1").dispatchSheet)
        }
        rule.onNodeWithText("Undo this import").performClick()
        rule.runOnIdle {
            assertTrue(pkey("v1", "p1", today.toString()) !in vm().data.dispatch)
            assertTrue(vm().data.importFormats.isEmpty())
        }
        // an old .xls is explained, not crashed on
        rule.runOnUiThread {
            val msg = vm().openVendorFile(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte()), "old.xls")
            assertTrue(msg!!.contains(".xlsx"))
        }
    }

    @Test
    fun monthPlanIsTypedOnceAndSplitIntoWeeks() {
        rule.waitUntil(5_000) { vm().loaded }
        lockScreenThenUnlock()
        rule.runOnUiThread { vm().replaceAll(sample()) }
        // the app opens on the Dashboard
        rule.onNodeWithText("Week-on-week adherence").assertExists()
        rule.onNodeWithText("Plan").performClick()
        rule.onNodeWithText("Month plan", substring = true).assertExists()
        val ym = java.time.YearMonth.from(LocalDate.now())
        val box = rule.onAllNodes(hasSetTextAction())[0] // Bracket's month total
        box.performTextReplacement("7000")
        box.performImeAction()
        rule.runOnIdle {
            val d = vm().data
            assertEquals(7000.0, app.suppliertracker.model.monthPlan(d, "v1", "p1", ym), 0.0)
            // whole pieces in every week of the month
            app.suppliertracker.model.periodsOfMonth(ym).forEach { p ->
                val q = d.plan[pkey("v1", "p1", p.start.toString())] ?: 0.0
                assertEquals(q, Math.floor(q), 0.0)
            }
            // Gear is untouched
            assertEquals(600.0, d.plan.getValue(pkey("v1", "p2", periodOf(LocalDate.now()).start.toString())), 0.0)
        }
        // Next saves once: no "re-planned" right after the first entry (the second save used to re-level the weeks)
        rule.runOnIdle {
            val d = vm().data
            app.suppliertracker.model.periodsOfMonth(ym).forEach { p ->
                val k = pkey("v1", "p1", p.start.toString())
                if (k in d.plan && p.start > periodOf(LocalDate.now()).start) assertEquals(d.plan[k], d.planOrig[k])
            }
        }
        rule.onNodeWithText("Week by week").performClick()
        rule.onNodeWithText("Copy last week").assertExists()
        rule.runOnUiThread { vm().updatePlanByMonth(true) }
    }

    /**
     * v1.3 crashed on "Choose" (backup folder): the old fragment library pulled in by biometric refused the
     * request codes of the system pickers. Every picker in the app must open without throwing.
     */
    @Test
    fun systemPickersOpenWithoutCrashing() {
        rule.waitUntil(5_000) { vm().loaded }
        lockScreenThenUnlock()
        val shadow = org.robolectric.Shadows.shadowOf(rule.activity)
        fun opened(): String? { rule.waitForIdle(); return shadow.nextStartedActivityForResult?.intent?.action }

        rule.runOnUiThread { vm().replaceAll(Data()) }
        rule.onNodeWithText("Import data file").performClick()
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, opened())
        // the first vendor typed on the welcome card: stay on Setup, where its parts are added next
        rule.onNodeWithText("Vendor name").performTextInput("Acme")
        rule.onNodeWithText("Add").performClick()
        rule.onNodeWithText("+ Add part").assertExists()

        rule.runOnUiThread { vm().replaceAll(sample()) }
        rule.onNodeWithText("Setup").performClick()
        rule.onNodeWithText("Open settings").performScrollTo().performClick()
        rule.onNodeWithText("Choose").performScrollTo().performClick()
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE, opened())
        rule.onNode(androidx.compose.ui.test.isToggleable() and androidx.compose.ui.test.hasAnySibling(androidx.compose.ui.test.hasText("Back up every night")))
            .performScrollTo().performClick()
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE, opened())
        rule.onNodeWithText("Save to phone").performScrollTo().performClick()
        assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, opened())
    }

    /** Local only: the import screen with a real vendor file and data (never committed). -Pvendor.file=… -Pvendor.data=… */
    @Test
    fun localImportScreenshots() {
        val file = System.getProperty("vendor.file").orEmpty()
        val dataPath = System.getProperty("vendor.data").orEmpty()
        assumeTrue(file.isNotBlank() && dataPath.isNotBlank())
        val out = File(System.getProperty("screenshot.out").orEmpty().ifBlank { "build/screenshots" }).apply { mkdirs() }
        rule.waitUntil(5_000) { vm().loaded }
        lockScreenThenUnlock()
        rule.runOnUiThread { vm().replaceAll(Codec.decode(File(dataPath).readText())) }
        fun shot(name: String) {
            rule.waitForIdle()
            rule.runOnUiThread {
                val view = rule.activity.window.decorView
                val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bmp))
                File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        rule.runOnUiThread { println("OPEN " + vm().openVendorFile(File(file).readBytes(), File(file).name)) }
        shot("real-import-1")
        rule.onNodeWithText("Plan from").performScrollTo()
        shot("real-import-2")
        rule.onNodeWithText("Dispatch by day").performScrollTo()
        shot("real-import-3")
        rule.onAllNodesWithText("Save", substring = true).onLast().performClick()
        shot("real-import-done")
    }

    /** Local only: renders each screen with a real data file. Skipped unless -Pscreenshot.data is given. */
    @Test
    fun screenshots() {
        val path = System.getProperty("screenshot.data").orEmpty()
        assumeTrue(path.isNotBlank())
        val out = File(System.getProperty("screenshot.out").orEmpty().ifBlank { "build/screenshots" }).apply { mkdirs() }
        val data = Codec.decode(File(path).readText())
        rule.waitUntil(5_000) { vm().loaded }
        lockScreenThenUnlock()
        rule.runOnUiThread { vm().replaceAll(data) }
        // show the last day that has dispatch, so "today" is not counted as missing
        val last = data.dispatch.keys.mapNotNull { runCatching { LocalDate.parse(it.substringAfterLast('|')) }.getOrNull() }.maxOrNull()
        if (last != null && last < LocalDate.now()) rule.runOnUiThread { vm().selectDate(last) }

        fun shot(name: String) {
            rule.waitForIdle()
            rule.runOnUiThread {
                val view = rule.activity.window.decorView
                val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bmp))
                File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        fun tab(label: String) = rule.onNodeWithText(label).performClick()

        for (v in data.vendors) {
            rule.runOnUiThread { vm().selectVendor(v.id) }
            tab("Entry"); shot("${v.id}-1-entry")
            tab("Status"); shot("${v.id}-2-status")
            if (rule.onAllNodesWithText("Past months").fetchSemanticsNodes().isNotEmpty()) {
                rule.onNodeWithText("Share").performScrollTo()
                shot("${v.id}-2b-past-months")
                rule.onAllNodesWithText("Parts").onFirst().performClick()
                rule.onAllNodesWithText(v.activeParts.last().name).onLast().performScrollTo()
                shot("${v.id}-2c-past-month-parts")
            }
            tab("Plan"); shot("${v.id}-3-plan")
        }
        rule.runOnUiThread { vm().selectVendor(data.vendors.first().id) }
        tab("Status")
        rule.onNodeWithText("Daily adherence").performScrollTo()
        shot("status-top")
        rule.onNodeWithText("Parts details").performScrollTo()
        shot("status-parts")
        rule.onNodeWithText("Bad", substring = true).performClick()
        rule.onNodeWithText("Parts details").performScrollTo()
        shot("status-filter-bad")
        rule.onNodeWithText("All", substring = true).performClick()
        rule.onNodeWithText("Past months").performScrollTo()
        shot("status-notes")
        // a part's own page
        rule.onAllNodesWithText("Calendar ›").onFirst().performScrollTo().performClick()
        shot("part-calendar")
        rule.onNodeWithText("Big number = dispatched", substring = true).performScrollTo()
        shot("part-calendar-day")
        rule.onNodeWithText("Weekly").performScrollTo().performClick()
        shot("part-weekly")
        rule.onNodeWithText("Monthly").performClick()
        shot("part-monthly")
        rule.onNodeWithText("+ Add note", substring = true).performScrollTo()
        shot("part-notes")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onAllNodesWithText("+ Reason").onFirst().performScrollTo().performClick()
        rule.onNodeWithText("Save reason").performScrollTo()
        shot("reason-form")
        tab("Dashboard"); shot("dashboard")
        rule.onNodeWithText("Vendors").performScrollTo()
        shot("dashboard-2")
        rule.onNodeWithText("Build report (PowerPoint)").performScrollTo()
        shot("dashboard-3")
        tab("Setup")
        shot("setup-help")
        rule.onNodeWithText("Quick fixes ▾").performClick()
        rule.onNodeWithText("Report a problem").performScrollTo()
        shot("setup-quick-fixes")
        rule.runOnUiThread { vm().selectVendor(data.vendors.last().id) }
        rule.onNodeWithText("+ Add days off").performScrollTo()
        shot("setup-days-off")
        rule.runOnUiThread { vm().selectVendor(data.vendors.first().id) }
        rule.onNodeWithText("Holidays (all vendors)").performScrollTo()
        shot("setup-parts")
        rule.onNodeWithText("Report template (PowerPoint)").performScrollTo()
        shot("setup-report")
        rule.onNodeWithText("Open settings").performScrollTo().performClick()
        shot("settings-top")
        rule.onNodeWithText("Set a password").performScrollTo()
        shot("settings-backup")
        rule.onNodeWithText("Import / restore from file").performScrollTo()
        shot("settings-restore")
        rule.onNodeWithContentDescription("Back").performClick()

        // a vendor's own Excel report (made up): invoices for 5-9 Oct and a weekly plan sheet; undone afterwards
        val a = data.vendors.first()
        fun inv(day: Int, part: String, q: Int) = listOf<Any?>(7000 + day * 10 + part.last().digitToInt(), LocalDate.of(2026, 10, day), part, q)
        val report = TestXlsx.build(
            "OCT Dispatches" to listOf(listOf<Any?>("Inv No", "Inv Date", "Part No", "Qty")) +
                (5..9).flatMap { day -> listOf(inv(day, "A-100", 140), inv(day, "A-200", 100), inv(day, "A-300", if (day == 9) 160 else 135), inv(day, "A-400", 52)) },
            "OCT Week 41" to listOf(
                listOf<Any?>("Part No", "Oct Plan", "Week41", "Week42", "Week43", "Week44"),
                listOf<Any?>("A-100", 6000, 1200, 1200, 1200, 1200),
                listOf<Any?>("A-200", 3200, 600, 600, 800, 600),
            ),
        )
        rule.runOnUiThread { vm().selectVendor(a.id); vm().openVendorFile(report, "SupplierA_oct-wk41.xlsx") }
        shot("import-top")
        rule.onNodeWithText("Plan from the file").performScrollTo()
        shot("import-check")
        rule.onAllNodesWithText("parts ·", substring = true).onLast().performScrollTo().performClick()
        rule.onNodeWithText("Dispatch by day").performScrollTo()
        shot("import-days")
        rule.onAllNodesWithText("Save", substring = true).onLast().performClick()
        shot("import-done")
        rule.onNodeWithText("Undo this import").performClick()
        rule.runOnUiThread { vm().dismissImportResult() }

        // last, so it does not change the other screens: quantities typed on the Entry tab
        rule.runOnUiThread { vm().selectVendor(data.vendors.first().id) }
        tab("Entry")
        val boxes = rule.onAllNodes(hasSetTextAction())
        boxes[0].performTextReplacement("150")
        boxes[1].performTextReplacement("100")
        boxes[2].performTextReplacement("140")
        rule.onAllNodes(hasSetTextAction())[0].performScrollTo()
        shot("entry-typed")
        rule.runOnUiThread {
            vm().updateTheme(app.suppliertracker.ui.AppViewModel.ThemeMode.DARK)
            vm().updateTextScale(1.3f)
        }
        rule.onAllNodes(hasSetTextAction())[0].performScrollTo()
        shot("entry-dark-large")
        tab("Status")
        rule.onNodeWithText("Parts details").performScrollTo()
        shot("status-dark-large")
        rule.onAllNodesWithText("Calendar ›").onFirst().performScrollTo().performClick()
        rule.onNodeWithText("Mon").performScrollTo()
        shot("part-dark-large")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.runOnUiThread {
            vm().updateTheme(app.suppliertracker.ui.AppViewModel.ThemeMode.PHONE)
            vm().updateTextScale(1f)
        }
    }
}
