package app.suppliertracker.data

import android.content.Context
import app.suppliertracker.model.Codec
import app.suppliertracker.model.Data
import java.io.File

/**
 * Keeps the whole data document in one JSON file in the app's private storage.
 * Nothing leaves the phone unless you share a backup or CSV yourself.
 */
class Store(private val context: Context) {
    private val file = File(context.filesDir, "data.json")
    private val lock = Any()

    /** Saved data; empty on first launch (vendors come from an imported data file). */
    fun load(): Data = if (file.exists()) Codec.decode(file.readText()) else Data()

    private val template = File(context.filesDir, "report-template.pptx")

    /** The company template imported for the weekly report, if any. */
    fun reportTemplate(): ByteArray? = if (template.exists()) template.readBytes() else null

    fun hasReportTemplate(): Boolean = template.exists()

    fun saveReportTemplate(bytes: ByteArray?) {
        if (bytes == null) template.delete() else template.writeBytes(bytes)
    }

    /** Writes a temp file and renames it over the old one, so a crash mid-write never loses data. */
    fun save(data: Data) {
        val text = Codec.encode(data)
        synchronized(lock) {
            val tmp = File(file.parentFile, "data.json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}
