package app.suppliertracker.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

const val MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

/** Writes text to the app's cache and opens the share sheet (WhatsApp, Drive, Gmail…). */
fun shareFile(context: Context, name: String, text: String, mime: String, title: String) =
    shareBytes(context, name, text.toByteArray(Charsets.UTF_8), mime, title)

fun shareBytes(context: Context, name: String, bytes: ByteArray, mime: String, title: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val f = File(dir, name)
    f.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, title))
}

fun shareText(context: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, title))
}

fun readText(context: Context, uri: Uri): String =
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        ?: error("Could not open the file.")

fun readBytes(context: Context, uri: Uri): ByteArray =
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not open the file.")

fun writeBytes(context: Context, uri: Uri, bytes: ByteArray) {
    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Could not write the file.")
}

fun writeText(context: Context, uri: Uri, text: String) {
    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
        ?: error("Could not write the file.")
}

fun openMaps(context: Context, address: String) {
    val i = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(address)))
    try {
        context.startActivity(i)
    } catch (e: android.content.ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(address))))
    }
}

/** The file name a picker or a sharing app gives, or "file". */
fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
