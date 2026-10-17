package app.suppliertracker

import android.app.Application
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.hardware.fingerprint.FingerprintManager
import android.os.Bundle
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import app.suppliertracker.model.Data
import app.suppliertracker.model.Part
import app.suppliertracker.model.Vendor
import app.suppliertracker.ui.AppViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/** The fingerprint lock: found by the v1.4 review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class LockTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Suppress("UNCHECKED_CAST")
    private fun unlocked(a: MainActivity): MutableState<Boolean> =
        MainActivity::class.java.getDeclaredField("unlocked\$delegate").apply { isAccessible = true }.get(a) as MutableState<Boolean>

    private fun needsLock(a: MainActivity) = MainActivity::class.java.getDeclaredMethod("needsLock").apply { isAccessible = true }.invoke(a) as Boolean

    private fun fingerprintEnrolled(a: android.app.Activity) =
        Shadow.extract<ShadowBiometricManager>(a.getSystemService(android.hardware.biometrics.BiometricManager::class.java)).setCanAuthenticate(true)

    /** "Save to phone", then a relock on the way back from the save dialog: the backup is still written. */
    @Test
    fun saveToPhoneSurvivesTheRelock() {
        fun vm() = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        rule.waitUntil(5_000) { vm().loaded }
        if (rule.onAllNodesWithText("Supplier Tracker is locked").fetchSemanticsNodes().isNotEmpty()) {
            rule.runOnUiThread { vm().setLock(false) }
            rule.waitForIdle()
        }
        rule.runOnUiThread {
            vm().replaceAll(Data(vendors = listOf(Vendor(id = "v1", name = "Vendor A", parts = listOf(Part("p1", "X-100", "Bracket"))))))
            vm().updateBackupPassword("secret1") // the slow, locked backup
        }
        rule.onNodeWithText("Setup").performClick()
        rule.onNodeWithText("Open settings").performScrollTo().performClick()
        rule.onNodeWithText("Save to phone").performScrollTo().performClick()
        rule.waitForIdle()
        val a = rule.activity
        val shadow = Shadows.shadowOf(a)
        val request = shadow.nextStartedActivityForResult!!
        fingerprintEnrolled(a)
        val out = File(a.cacheDir, "backup.json").apply { writeText("") }
        rule.runOnUiThread {
            vm().setLock(true)
            unlocked(a).value = false // what onStart does after more than a minute in the save dialog
            shadow.receiveResult(request.intent, android.app.Activity.RESULT_OK, android.content.Intent().setData(android.net.Uri.fromFile(out)))
        }
        rule.waitForIdle()
        rule.onNodeWithText("Supplier Tracker is locked").assertExists()
        repeat(40) {
            if (out.length() > 0 && vm().data.lastBackup.isNotBlank()) return@repeat
            Thread.sleep(250)
            rule.waitForIdle()
        }
        assertTrue("backup written (${out.length()} bytes)", out.length() > 0)
        assertTrue(vm().data.lastBackup.isNotBlank())
        rule.runOnUiThread {
            unlocked(a).value = true
            vm().setLock(false)
            vm().updateBackupPassword(null)
        }
    }
}

/** Lock tests that build their own activities (no Compose rule). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockLifecycleTest {
    @Suppress("UNCHECKED_CAST")
    private fun unlocked(a: MainActivity): MutableState<Boolean> =
        MainActivity::class.java.getDeclaredField("unlocked\$delegate").apply { isAccessible = true }.get(a) as MutableState<Boolean>

    private fun needsLock(a: MainActivity) = MainActivity::class.java.getDeclaredMethod("needsLock").apply { isAccessible = true }.invoke(a) as Boolean

    private fun fingerprintEnrolled(a: android.app.Activity) =
        Shadow.extract<ShadowBiometricManager>(a.getSystemService(android.hardware.biometrics.BiometricManager::class.java)).setCanAuthenticate(true)

    /** Android 8 shows its own AppCompat fingerprint dialog: with a non-AppCompat theme every launch crashed. */
    @Test
    @Config(sdk = [27])
    fun fingerprintPromptOnAndroid8() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_FINGERPRINT, true)
        Shadows.shadowOf(app.getSystemService(FingerprintManager::class.java)).apply {
            setIsHardwareDetected(true)
            setDefaultFingerprints(1)
        }
        Shadows.shadowOf(app.getSystemService(KeyguardManager::class.java)).apply {
            setIsKeyguardSecure(true)
            setIsDeviceSecure(true)
        }
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        repeat(5) { ShadowLooper.idleMainLooper() }
        assertTrue("the fingerprint prompt is showing", c.get().supportFragmentManager.fragments.isNotEmpty())
        c.pause().stop().destroy()
    }

    /** Android closed the app in the background: coming back after 10 minutes must ask for the fingerprint again. */
    @Test
    fun locksAgainAfterTheAppWasClosedInTheBackground() {
        // other tests in this run may have switched the lock off (Robolectric keeps the settings between tests)
        ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("lock", true).commit()
        val c1 = Robolectric.buildActivity(MainActivity::class.java).setup()
        fingerprintEnrolled(c1.get())
        unlocked(c1.get()).value = true
        val saved = Bundle()
        c1.pause().stop().saveInstanceState(saved)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        val c2 = Robolectric.buildActivity(MainActivity::class.java).setup(saved)
        ShadowLooper.idleMainLooper()
        assertTrue(needsLock(c2.get()))
        assertFalse("supplier data shown without the fingerprint", unlocked(c2.get()).value)
    }
}
