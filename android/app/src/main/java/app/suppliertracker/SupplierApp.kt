package app.suppliertracker

import android.app.Application
import app.suppliertracker.data.CrashLog

class SupplierApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
