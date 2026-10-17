package app.suppliertracker

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds small made-up Excel files for the import tests. */
object TestXlsx {
    /** A minimal .xlsx: each sheet a list of rows; a cell is a String, a Number, or a LocalDate (stored as a dated number). */
    fun build(vararg sheets: Pair<String, List<List<Any?>>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("[Content_Types].xml", "<Types/>")
            put("xl/workbook.xml", "<workbook><sheets>" + sheets.indices.joinToString("") { i ->
                "<sheet name=\"${sheets[i].first}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>"
            } + "</sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", "<Relationships>" + sheets.indices.joinToString("") { i ->
                "<Relationship Id=\"rId${i + 1}\" Target=\"worksheets/sheet${i + 1}.xml\"/>"
            } + "</Relationships>")
            put("xl/styles.xml", "<styleSheet><cellXfs count=\"2\"><xf numFmtId=\"0\"/><xf numFmtId=\"14\"/></cellXfs></styleSheet>")
            sheets.forEachIndexed { i, (_, rows) ->
                val body = rows.mapIndexed { r, row ->
                    "<row r=\"${r + 1}\">" + row.mapIndexedNotNull { c, v ->
                        val ref = app.suppliertracker.importer.columnName(c) + (r + 1)
                        when (v) {
                            null -> null
                            is Number -> "<c r=\"$ref\"><v>$v</v></c>"
                            is LocalDate -> "<c r=\"$ref\" s=\"1\"><v>${java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), v)}</v></c>"
                            else -> "<c r=\"$ref\" t=\"inlineStr\"><is><t>${v.toString().replace("&", "&amp;")}</t></is></c>"
                        }
                    }.joinToString("") + "</row>"
                }.joinToString("")
                put("xl/worksheets/sheet${i + 1}.xml", "<worksheet><sheetData>$body</sheetData></worksheet>")
            }
        }
        return bos.toByteArray()
    }
}
