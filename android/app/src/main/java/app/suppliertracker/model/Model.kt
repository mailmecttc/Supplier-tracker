package app.suppliertracker.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

const val MODE_DAILY = "daily"
const val MODE_CUM = "cum"

@Serializable
data class Part(
    val id: String,
    val partNo: String = "",
    val desc: String = "",
    val critical: Boolean = false,
    val active: Boolean = true,
) {
    val name: String get() = desc.ifBlank { partNo.ifBlank { "Unnamed part" } }
}

@Serializable
data class Vendor(
    val id: String,
    val name: String,
    val contact: String = "",
    val address: String = "",
    val notes: String = "",
    /** "daily" = quantity per day, "cum" = till-date total for the month */
    val mode: String = MODE_DAILY,
    /** Mon..Sun, 1 = dispatch day */
    val dispatchDays: List<Int> = listOf(1, 1, 1, 1, 1, 1, 0),
    val parts: List<Part> = emptyList(),
    val sample: Boolean = false,
    /** This supplier's own days off (YYYY-MM-DD), on top of the holidays for all vendors. */
    val holidays: List<String> = emptyList(),
) {
    val cumulative: Boolean get() = mode == MODE_CUM
    val activeParts: List<Part> get() = parts.filter { it.active }
}

@Serializable
data class Issue(
    val id: String,
    val vendorId: String,
    val partId: String = "",
    val cause: String = "",
    val action: String = "",
    val owner: String = "",
    val due: String = "",
    val logged: String = "",
    val status: String = "open",
)

/** A dated note about a vendor, or one of its parts ([partId] set). [date] is the day it is about (YYYY-MM-DD). */
@Serializable
data class Note(
    val id: String,
    val vendorId: String,
    val partId: String = "",
    val date: String,
    val text: String,
    /** Last change (YYYY-MM-DD), blank if never edited. */
    val edited: String = "",
)

/**
 * How a vendor's own Excel report is read, remembered after the first import. Sheets are kept by name without months
 * and numbers ("dispatches"), so next month's "NOV Dispatches" is found too. A column of -1 is found automatically.
 */
@Serializable
data class ImportFormat(
    val dispatchSheet: String = "",
    val planSheet: String = "",
    val partCol: Int = -1,
    val dateCol: Int = -1,
    val qtyCol: Int = -1,
    val usePlan: Boolean = true,
    val missingAsZero: Boolean = true,
)

/**
 * The whole data document, stored as one JSON file on the phone.
 * Same format (version 1) as the web app's backup files, so either can restore the other.
 * Map keys: "vendorId|partId|YYYY-MM-DD" (plan uses the period start date), backlog uses "YYYY-MM".
 */
@Serializable
data class Data(
    val version: Int = 1,
    val vendors: List<Vendor> = emptyList(),
    val plan: Map<String, Double> = emptyMap(),
    val planOrig: Map<String, Double> = emptyMap(),
    val dispatch: Map<String, Double> = emptyMap(),
    val cumulative: Map<String, Double> = emptyMap(),
    val openingBacklog: Map<String, Double> = emptyMap(),
    val commitment: Map<String, Double> = emptyMap(),
    val issues: List<Issue> = emptyList(),
    val holidays: List<String> = emptyList(),
    val lastBackup: String = "",
    /**
     * Month totals taken from supplier reports, for months before daily tracking started
     * (e.g. a supplier's September closing). Key "vendorId|partId|YYYY-MM".
     */
    val closedPlan: Map<String, Double> = emptyMap(),
    val closedActual: Map<String, Double> = emptyMap(),
    /** Dated notes (added in 1.5; older files have none). */
    val notes: List<Note> = emptyList(),
    /** Each vendor's Excel report format, by vendor id (added in 1.6). */
    val importFormats: Map<String, ImportFormat> = emptyMap(),
)

fun pkey(vendorId: String, partId: String, suffix: String) = "$vendorId|$partId|$suffix"

/** Reasons offered when a part is behind plan (same list as the web app). */
val GAP_CAUSES = listOf(
    "Material shortage (tier-2)",
    "Shared machine loaded with other part",
    "Machine breakdown",
    "Fixture / tooling",
    "Quality hold / rejection",
    "Manpower",
    "Plan change (FTCI/MFTBC)",
    "Logistics / dispatch",
    "Other",
)

object Codec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
    }

    fun encode(data: Data): String = json.encodeToString(Data.serializer(), data)

    /** Parses and checks a data or backup file. Throws IllegalArgumentException with a readable message. */
    fun decode(text: String): Data {
        val data = try {
            json.decodeFromString(Data.serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("This file is not a Supplier Tracker backup.", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("This file is not a Supplier Tracker backup.", e)
        }
        require(data.version == 1) { "Unknown backup version." }
        for (v in data.vendors) {
            require(v.id.isNotBlank() && v.dispatchDays.size == 7) { "A vendor in the backup is malformed." }
        }
        return data
    }
}
