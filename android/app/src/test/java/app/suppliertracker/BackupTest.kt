package app.suppliertracker

import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import app.suppliertracker.data.AutoBackup
import app.suppliertracker.data.BackupCrypto
import app.suppliertracker.model.Codec
import app.suppliertracker.model.Data
import app.suppliertracker.model.Vendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val plain = Codec.encode(Data(vendors = listOf(Vendor("v1", "Supplier A"))))

    @Test
    fun `locked backup opens with its password only`() {
        val locked = BackupCrypto.encrypt(plain, "secret1")
        assertTrue(BackupCrypto.isEncrypted(locked))
        assertFalse(BackupCrypto.isEncrypted(plain))
        assertFalse(locked.contains("Supplier A"))
        assertEquals(plain, BackupCrypto.decrypt(locked, "secret1"))
        // a new salt and IV every time
        assertTrue(locked != BackupCrypto.encrypt(plain, "secret1"))
        val wrong = runCatching { BackupCrypto.decrypt(locked, "secret2") }.exceptionOrNull()
        assertTrue(wrong is IllegalArgumentException && wrong.message!!.startsWith("Wrong password"))
        // a changed byte is caught too
        val i = locked.indexOf("\"data\":\"") + 8
        val tampered = locked.substring(0, i) + (if (locked[i] == 'A') 'B' else 'A') + locked.substring(i + 1)
        assertTrue(runCatching { BackupCrypto.decrypt(tampered, "secret1") }.isFailure)
    }

    @Test
    fun `night backup writes a dated file and keeps the newest 14`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = tmp.newFolder("backups")
        val folder = DocumentFile.fromFile(dir)
        val other = java.io.File(dir, "my-own-notes.json").apply { writeText("{}") }
        val start = LocalDate.of(2026, 10, 1)
        for (i in 0 until 16) AutoBackup.write(ctx, folder, plain, null, start.plusDays(i.toLong()))
        // same day again replaces that day's file
        AutoBackup.write(ctx, folder, plain, "secret1", start.plusDays(15))
        val names = dir.list()!!.sorted()
        assertEquals(15, names.size)
        assertTrue(other.exists())
        assertFalse("oldest gone", "supplier-tracker-auto-2026-10-01.json" in names)
        assertTrue("supplier-tracker-auto-2026-10-16.json" in names)
        val latest = java.io.File(dir, "supplier-tracker-auto-2026-10-16.json").readText()
        assertEquals("Supplier A", Codec.decode(BackupCrypto.decrypt(latest, "secret1")).vendors.single().name)
    }
}
