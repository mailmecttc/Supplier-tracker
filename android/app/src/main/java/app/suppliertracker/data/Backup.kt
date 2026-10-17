package app.suppliertracker.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.suppliertracker.model.Codec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.SecureRandom
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected backups: AES-256-GCM, with the key made from the password by PBKDF2-HMAC-SHA256
 * (600,000 rounds, a new random salt and IV for every file). A wrong password or a changed file fails to open.
 * The file stays JSON so it is recognised on import.
 */
object BackupCrypto {
    private const val FORMAT = "supplier-tracker-backup"
    private const val ITERATIONS = 600_000
    private val json = Json { ignoreUnknownKeys = true }

    fun isEncrypted(text: String): Boolean {
        val t = text.trimStart()
        if (!t.startsWith("{") || !t.contains(FORMAT)) return false
        return runCatching {
            val o = json.parseToJsonElement(t).jsonObject
            o["format"]?.jsonPrimitive?.content == FORMAT && o["encrypted"]?.jsonPrimitive?.booleanOrNull == true
        }.getOrDefault(false)
    }

    private fun key(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(plain: String, password: String): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(128, iv))
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return buildJsonObject {
            put("format", FORMAT)
            put("encrypted", true)
            put("cipher", "AES-256-GCM")
            put("kdf", "PBKDF2WithHmacSHA256")
            put("iterations", ITERATIONS)
            put("salt", b64.encodeToString(salt))
            put("iv", b64.encodeToString(iv))
            put("data", b64.encodeToString(data))
        }.toString()
    }

    /** Throws IllegalArgumentException("Wrong password…") when the password does not open the file. */
    fun decrypt(text: String, password: String): String {
        val o: JsonObject = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("This file is not a Supplier Tracker backup.", e)
        }
        require(o["encrypted"]?.jsonPrimitive?.boolean == true) { "This backup is not locked." }
        val b64 = Base64.getDecoder()
        fun field(n: String) = (o[n] as? JsonPrimitive)?.content ?: throw IllegalArgumentException("This locked backup is damaged.")
        val salt = b64.decode(field("salt"))
        val iv = b64.decode(field("iv"))
        val data = b64.decode(field("data"))
        val iterations = o["iterations"]?.jsonPrimitive?.int ?: ITERATIONS
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        return try {
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            throw IllegalArgumentException("Wrong password, or the file was changed.", e)
        }
    }
}

/** Backup settings kept on the phone (outside the data file, so a restore does not change them). */
class BackupSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("backup", Context.MODE_PRIVATE)

    var autoEnabled: Boolean
        get() = prefs.getBoolean("auto", false)
        set(v) = prefs.edit().putBoolean("auto", v).apply()

    var folder: Uri?
        get() = prefs.getString("folder", null)?.let(Uri::parse)
        set(v) = prefs.edit().putString("folder", v?.toString()).apply()

    /** Stored in the app's private storage, next to the data it protects, so the night backup can lock files. */
    var password: String?
        get() = prefs.getString("password", null)
        set(v) = prefs.edit().putString("password", v).apply()

    val lastAt: Long get() = prefs.getLong("lastAt", 0L)
    val lastFile: String get() = prefs.getString("lastFile", "").orEmpty()
    val lastError: String get() = prefs.getString("lastError", "").orEmpty()

    fun recordSuccess(file: String) =
        prefs.edit().putLong("lastAt", System.currentTimeMillis()).putString("lastFile", file).putString("lastError", "").apply()

    fun recordError(message: String) = prefs.edit().putString("lastError", message).apply()
}

/** Nightly backup into a folder the user picked (Documents, an SD card, a synced folder…). */
object AutoBackup {
    const val PREFIX = "supplier-tracker-auto-"
    const val KEEP = 14
    private const val WORK = "auto-backup"

    fun fileName(date: LocalDate) = "$PREFIX$date.json"

    /** Runs every night at about 2:30 AM (Android may move it a little to save battery). */
    fun schedule(context: Context, restart: Boolean) {
        val now = ZonedDateTime.now()
        var next = now.toLocalDate().atTime(2, 30).atZone(now.zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK,
            if (restart) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /** Keeps read/write access to the picked folder across restarts. */
    fun keepAccess(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    fun folderName(context: Context, uri: Uri?): String =
        uri?.let { runCatching { DocumentFile.fromTreeUri(context, it)?.name }.getOrNull() } ?: "Not chosen"

    /** Backs up now into the chosen folder. Returns the file name; throws with a readable message. */
    fun run(context: Context): String {
        val settings = BackupSettings(context)
        val uri = settings.folder ?: error("Choose a backup folder first.")
        val folder = DocumentFile.fromTreeUri(context, uri)
        if (folder == null || !folder.canWrite()) error("The backup folder can't be opened. Choose it again in Settings.")
        return try {
            val name = write(context, folder, Store(context).load().let(Codec::encode), settings.password, LocalDate.now())
            settings.recordSuccess(name)
            name
        } catch (e: Exception) {
            settings.recordError(e.message ?: "Backup failed.")
            throw e
        }
    }

    /** Writes today's backup into [folder] (replacing today's earlier one) and keeps the newest [KEEP]. */
    fun write(context: Context, folder: DocumentFile, plain: String, password: String?, date: LocalDate): String {
        val text = if (password.isNullOrEmpty()) plain else BackupCrypto.encrypt(plain, password)
        val name = fileName(date)
        folder.findFile(name)?.delete()
        val file = folder.createFile("application/json", name) ?: error("Could not create $name in the backup folder.")
        if (file.uri.scheme == "file") {
            File(requireNotNull(file.uri.path)).writeText(text)
        } else {
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: error("Could not write $name.")
        }
        folder.listFiles()
            .filter { it.name?.startsWith(PREFIX) == true && it.name?.endsWith(".json") == true }
            .sortedByDescending { it.name }
            .drop(KEEP)
            .forEach { it.delete() }
        return file.name ?: name
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = BackupSettings(applicationContext)
        if (!settings.autoEnabled) return Result.success()
        // nothing to back up before the first import
        if (!File(applicationContext.filesDir, "data.json").exists()) return Result.success()
        return try {
            AutoBackup.run(applicationContext)
            Result.success()
        } catch (e: Exception) {
            // a missing folder will not fix itself; anything else gets one retry
            if (runAttemptCount < 1 && settings.folder != null) Result.retry() else Result.failure()
        }
    }
}
