// Reads the cells of an Excel (.xlsx) or CSV file. Plain Kotlin, no Android: the file is a zip of XML parts.
package app.suppliertracker.importer

import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

/** One sheet: cell values by (row, column), both from 0. A value is a String, a Double or a LocalDate. */
class Sheet(val name: String, private val cells: Map<Long, Any>) {
    val rows: Int = (cells.keys.maxOfOrNull { (it shr 20).toInt() } ?: -1) + 1
    val cols: Int = (cells.keys.maxOfOrNull { (it and 0xFFFFF).toInt() } ?: -1) + 1

    operator fun get(row: Int, col: Int): Any? = cells[key(row, col)]

    fun text(row: Int, col: Int): String = when (val v = this[row, col]) {
        null -> ""
        is Double -> if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    fun number(row: Int, col: Int): Double? = when (val v = this[row, col]) {
        is Double -> v
        is String -> v.trim().replace(",", "").toDoubleOrNull()
        else -> null
    }

    companion object {
        fun key(row: Int, col: Int): Long = (row.toLong() shl 20) or col.toLong()
    }
}

/** Column letters for a column index from 0: 0 → A, 26 → AA. */
fun columnName(col: Int): String {
    var n = col + 1
    val sb = StringBuilder()
    while (n > 0) {
        val r = (n - 1) % 26
        sb.append('A' + r)
        n = (n - 1) / 26
    }
    return sb.reverse().toString()
}

object Xlsx {
    /** Every sheet of an .xlsx file, in workbook order (hidden sheets included). Throws with a readable message. */
    fun read(bytes: ByteArray): List<Sheet> {
        val parts = unzip(bytes)
        val workbook = parts["xl/workbook.xml"]?.let { String(it, Charsets.UTF_8) }
            ?: throw IllegalArgumentException("This is not an Excel (.xlsx) file. Old .xls files: open in Excel and Save as .xlsx.")
        val rels = parts["xl/_rels/workbook.xml.rels"]?.let { String(it, Charsets.UTF_8) }.orEmpty()
        val targets = Regex("<Relationship\\b([^>]*)/?>").findAll(rels).associate { m ->
            attr(m.groupValues[1], "Id") to attr(m.groupValues[1], "Target")
        }
        val shared = parts["xl/sharedStrings.xml"]?.let { sharedStrings(String(it, Charsets.UTF_8)) } ?: emptyList()
        val dateStyles = parts["xl/styles.xml"]?.let { dateStyles(String(it, Charsets.UTF_8)) } ?: emptySet()
        return Regex("<sheet\\b([^>]*)/?>").findAll(workbook).mapNotNull { m ->
            val a = m.groupValues[1]
            val name = unescape(attr(a, "name"))
            val target = targets[attr(a, "r:id")] ?: return@mapNotNull null
            val path = if (target.startsWith("/")) target.drop(1) else "xl/" + target.removePrefix("./")
            val xml = parts[path]?.let { String(it, Charsets.UTF_8) } ?: return@mapNotNull null
            Sheet(name, cells(xml, shared, dateStyles))
        }.toList()
    }

    /** A CSV or semicolon / tab separated text file as one sheet. */
    fun readCsv(text: String, name: String = "Sheet1"): Sheet {
        val lines = text.removePrefix("\uFEFF").lines().filter { it.isNotBlank() }
        val sep = listOf(',', ';', '\t').maxByOrNull { c -> lines.take(5).sumOf { l -> l.count { it == c } } } ?: ','
        val cells = HashMap<Long, Any>()
        lines.forEachIndexed { r, line ->
            splitCsv(line, sep).forEachIndexed { c, raw ->
                val s = raw.trim()
                if (s.isNotEmpty()) cells[Sheet.key(r, c)] = s.replace(",", "").toDoubleOrNull() ?: s
            }
        }
        return Sheet(name, cells)
    }

    private fun splitCsv(line: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == sep && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val map = HashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (!e.isDirectory && (e.name.startsWith("xl/") && e.name.endsWith(".xml") || e.name.endsWith(".rels"))) {
                        map[e.name] = zin.readBytes()
                    }
                }
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("This file could not be read as Excel (.xlsx).", e)
        }
        return map
    }

    private fun attr(attrs: String, name: String): String =
        Regex("(?:^|\\s)" + Regex.escape(name) + "\\s*=\\s*\"([^\"]*)\"").find(attrs)?.groupValues?.get(1).orEmpty()

    private fun sharedStrings(xml: String): List<String> =
        Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { si ->
            // rich text has several <t> runs; phonetic hints (<rPh>) are not part of the text
            val body = si.groupValues[1].replace(Regex("<rPh\\b.*?</rPh>", RegexOption.DOT_MATCHES_ALL), "")
            Regex("<t\\b[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(body).joinToString("") { unescape(it.groupValues[1]) }
        }.toList()

    /** Indexes of cell styles that show a number as a date. */
    private fun dateStyles(xml: String): Set<Int> {
        val custom = Regex("<numFmt\\b([^>]*)/?>").findAll(xml).associate { m ->
            attr(m.groupValues[1], "numFmtId").toIntOrNull() to unescape(attr(m.groupValues[1], "formatCode"))
        }
        fun isDate(id: Int): Boolean {
            // built-in formats: 14–17 and 22 are dates (22 with a time), 27–36 and 50–58 East Asian dates
            if (id in 14..17 || id == 22 || id in 27..36 || id in 50..58) return true
            val code = custom[id] ?: return false
            // drop quoted text, [colour]/[locale] parts and escapes, then look for day / month / year codes
            val bare = code.replace(Regex("\"[^\"]*\""), "").replace(Regex("\\[[^]]*]"), "").replace(Regex("\\\\."), "")
            return bare.any { it == 'd' || it == 'D' || it == 'y' || it == 'Y' } || (bare.contains('m', true) && !bare.contains('h', true) && !bare.contains('s', true))
        }
        val xfs = Regex("<cellXfs\\b[^>]*>(.*?)</cellXfs>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1) ?: return emptySet()
        return Regex("<xf\\b([^>]*)").findAll(xfs).mapIndexedNotNull { i, m ->
            val id = attr(m.groupValues[1], "numFmtId").toIntOrNull() ?: 0
            if (isDate(id)) i else null
        }.toSet()
    }

    private val CELL = Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
    private val VALUE = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
    private val INLINE = Regex("<t\\b[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)

    private fun cells(xml: String, shared: List<String>, dateStyles: Set<Int>): Map<Long, Any> {
        val out = HashMap<Long, Any>()
        for (m in CELL.findAll(xml)) {
            val a = m.groupValues[1]
            val body = m.groupValues[2]
            val ref = attr(a, "r")
            val (row, col) = parseRef(ref) ?: continue
            val type = attr(a, "t")
            val raw = VALUE.find(body)?.groupValues?.get(1)
            val value: Any = when (type) {
                "s" -> shared.getOrNull(raw?.trim()?.toIntOrNull() ?: -1) ?: continue
                "inlineStr" -> INLINE.findAll(body).joinToString("") { unescape(it.groupValues[1]) }
                "str" -> unescape(raw ?: continue)
                "b" -> if (raw?.trim() == "1") "TRUE" else "FALSE"
                "e" -> continue // #DIV/0! and other errors: no value
                else -> {
                    val d = raw?.trim()?.toDoubleOrNull() ?: continue
                    val style = attr(a, "s").toIntOrNull() ?: 0
                    if (style in dateStyles && d in 1.0..2_958_465.0) excelDate(d) else d
                }
            }
            if (value is String && value.isBlank()) continue
            out[Sheet.key(row, col)] = value
        }
        // a merged area shows its top-left value in every cell, as it looks on screen
        for (mm in Regex("<mergeCell\\b[^>]*ref=\"([A-Z]+\\d+):([A-Z]+\\d+)\"").findAll(xml)) {
            val (r1, c1) = parseRef(mm.groupValues[1]) ?: continue
            val (r2, c2) = parseRef(mm.groupValues[2]) ?: continue
            val v = out[Sheet.key(r1, c1)] ?: continue
            if ((r2 - r1 + 1).toLong() * (c2 - c1 + 1) > 5000) continue
            for (r in r1..r2) for (c in c1..c2) out.putIfAbsent(Sheet.key(r, c), v)
        }
        return out
    }

    /** "C12" → (11, 2). */
    fun parseRef(ref: String): Pair<Int, Int>? {
        val m = Regex("^([A-Z]+)(\\d+)$").find(ref.uppercase()) ?: return null
        var col = 0
        for (ch in m.groupValues[1]) col = col * 26 + (ch - 'A' + 1)
        return (m.groupValues[2].toInt() - 1) to (col - 1)
    }

    /** Excel's day number (1900 system) as a date. */
    fun excelDate(serial: Double): LocalDate = LocalDate.of(1899, 12, 30).plusDays(Math.floor(serial).toLong())

    fun unescape(s: String): String {
        if ('&' !in s && "_x" !in s) return s
        var t = s.replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        // Excel's own escape for control characters, e.g. _x000D_
        t = t.replace(Regex("_x([0-9A-Fa-f]{4})_")) { it.groupValues[1].toInt(16).toChar().toString() }
        return t
    }
}
