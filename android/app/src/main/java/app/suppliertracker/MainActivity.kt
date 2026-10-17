package app.suppliertracker

import android.os.Bundle
import android.os.SystemClock
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.suppliertracker.ui.AppRoot
import app.suppliertracker.ui.AppViewModel
import app.suppliertracker.ui.LockScreen
import app.suppliertracker.ui.theme.SupplierTheme

class MainActivity : FragmentActivity() {
    private val vm: AppViewModel by viewModels()

    private var unlocked by mutableStateOf(false)
    private var prompting = false
    private var stoppedAt = 0L

    /** Fingerprint, face or the phone's PIN / pattern. */
    private val auth = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    private fun lockAvailable() = BiometricManager.from(this).canAuthenticate(auth) == BiometricManager.BIOMETRIC_SUCCESS

    private fun needsLock() = vm.lockEnabled && lockAvailable()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        unlocked = savedInstanceState?.getBoolean(UNLOCKED) == true || !needsLock()
        // when Android closed the app in the background, the time it left still counts for the lock
        stoppedAt = savedInstanceState?.getLong(STOPPED_AT) ?: 0L
        if (savedInstanceState == null) openShared(intent)
        setContent {
            val dark = when (vm.themeMode) {
                AppViewModel.ThemeMode.LIGHT -> false
                AppViewModel.ThemeMode.DARK -> true
                AppViewModel.ThemeMode.PHONE -> isSystemInDarkTheme()
            }
            // status bar icons follow the app's theme, not only the phone's
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            SupplierTheme(dark = dark, phoneColours = vm.phoneColours) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * vm.textScale)) {
                    // keeps the open tab / Settings while locked, so unlocking (say after a long trip to the folder
                    // picker) comes back to the same screen
                    val screens = rememberSaveableStateHolder()
                    if (needsLock() && !unlocked) LockScreen(onUnlock = ::authenticate)
                    else screens.SaveableStateProvider("app") { AppRoot(vm, lockAvailable()) }
                }
            }
        }
    }

    // a file shared to the app while it was already open
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openShared(intent)
    }

    /** A vendor's Excel or CSV report shared to the app (WhatsApp, Gmail…) or opened with it. */
    private fun openShared(intent: android.content.Intent?) {
        val uri: android.net.Uri = when (intent?.action) {
            android.content.Intent.ACTION_SEND -> androidx.core.content.IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
            android.content.Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return
        vm.openSharedFile { app.suppliertracker.data.readBytes(this, uri) to app.suppliertracker.data.displayName(this, uri) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(UNLOCKED, unlocked)
        outState.putLong(STOPPED_AT, if (stoppedAt != 0L) stoppedAt else SystemClock.elapsedRealtime())
    }

    override fun onStart() {
        super.onStart()
        // lock again after a minute in the background (a quick trip to WhatsApp to share a report does not count)
        // (a negative gap means the phone restarted in between)
        val away = SystemClock.elapsedRealtime() - stoppedAt
        if (stoppedAt != 0L && (away > GRACE_MS || away < 0)) unlocked = false
        stoppedAt = 0L // back in front: an old time must not count again (Android 8 saves state before onStop)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshAuto() // the night backup may have run while the app was closed
        if (!needsLock()) unlocked = true
        else if (!unlocked && !prompting) authenticate()
    }

    // never lose typed entries when the app is sent to the background or closed
    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
        vm.saveNow()
    }

    private fun authenticate() {
        if (prompting) return
        prompting = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false
                unlocked = true
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                prompting = false
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Supplier Tracker")
                .setSubtitle("Use your fingerprint")
                .setAllowedAuthenticators(auth)
                .build(),
        )
    }

    private companion object {
        const val UNLOCKED = "unlocked"
        const val STOPPED_AT = "stoppedAt"
        const val GRACE_MS = 60_000L
    }
}
