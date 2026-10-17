// Builds a .pptx on top of a template deck: keeps its masters, layouts, theme, logo and footer,
// drops the template's own slides (and anything only they used), and adds new slides.
// Plain JVM (java.util.zip + string XML), so it runs on the phone and in unit tests.
package app.suppliertracker.report

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val REL_SLIDE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide"
private const val REL_LAYOUT = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout"
private const val REL_MASTER = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster"
private const val REL_NOTES = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide"
private const val CT_SLIDE = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"

const val NS = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
    "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""

/** EMU box. */
data class Box(val x: Long, val y: Long, val w: Long, val h: Long) {
    val bottom: Long get() = y + h
}

data class Layout(val part: String, val name: String, val xml: String) {
    /** Placeholders as attribute maps, whatever order the attributes were written in. */
    private val placeholders: List<Map<String, String>> = Regex("<p:ph\\b([^>]*?)/?>").findAll(xml).map { m ->
        Regex("(\\w+)=\"([^\"]*)\"").findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
    }.toList()

    fun phIdx(type: String): String? = placeholders.firstOrNull { it["type"] == type }?.get("idx")

    fun has(type: String): Boolean = placeholders.any { it["type"] == type }
}

private data class Rel(val id: String, val type: String, val target: String, val external: Boolean)

class PptxTemplate(bytes: ByteArray) {
    private val parts: LinkedHashMap<String, ByteArray> = unzip(bytes)

    init {
        require("ppt/presentation.xml" in parts) { "This file is not a PowerPoint (.pptx) deck." }
    }

    private fun text(name: String) = String(parts.getValue(name), Charsets.UTF_8)

    private val presentation = text("ppt/presentation.xml")

    /** Slide width and height in EMU. */
    val slideSize: Pair<Long, Long> = Regex("<p:sldSz cx=\"(\\d+)\" cy=\"(\\d+)\"").find(presentation)
        ?.let { it.groupValues[1].toLong() to it.groupValues[2].toLong() } ?: (12192000L to 6858000L)

    val layouts: List<Layout> = run {
        val masters = rels("ppt/presentation.xml").filter { it.type == REL_MASTER }.map { resolve("ppt/presentation.xml", it.target) }
        masters.flatMap { m -> rels(m).filter { it.type == REL_LAYOUT }.map { resolve(m, it.target) } }
            .filter { it in parts }
            .map { p ->
                val xml = text(p)
                Layout(p, Regex("<p:cSld name=\"([^\"]*)\"").find(xml)?.groupValues?.get(1).orEmpty(), xml)
            }
    }

    /** Area for content on a titled slide: the master's body placeholder, or a sensible default. */
    val contentBox: Box = run {
        val (w, h) = slideSize
        val master = rels("ppt/presentation.xml").firstOrNull { it.type == REL_MASTER }?.let { resolve("ppt/presentation.xml", it.target) }
        val body = master?.let { m ->
            Regex("<p:sp>(?:(?!</p:sp>).)*?<p:ph type=\"body\"(?:(?!</p:sp>).)*?<a:off x=\"(\\d+)\" y=\"(\\d+)\"/><a:ext cx=\"(\\d+)\" cy=\"(\\d+)\"", RegexOption.DOT_MATCHES_ALL)
                .find(text(m))?.groupValues?.drop(1)?.map { it.toLong() }
        }
        if (body != null) Box(body[0], body[1], body[2], body[3]) else Box(w * 43 / 1000, h * 211 / 1000, w * 915 / 1000, h * 700 / 1000)
    }

    /** First layout whose name contains one of [names] (case-insensitive), in that order of preference. */
    fun findLayout(vararg names: String): Layout? =
        names.firstNotNullOfOrNull { n -> layouts.firstOrNull { it.name.contains(n, ignoreCase = true) } }

    private fun relsPath(part: String): String {
        val dir = part.substringBeforeLast('/', "")
        val file = part.substringAfterLast('/')
        return if (dir.isEmpty()) "_rels/$file.rels" else "$dir/_rels/$file.rels"
    }

    private fun rels(part: String): List<Rel> {
        val path = if (part.isEmpty()) "_rels/.rels" else relsPath(part)
        val xml = parts[path]?.toString(Charsets.UTF_8) ?: return emptyList()
        return Regex("<Relationship\\b([^>]*?)/?>").findAll(xml).map { m ->
            fun attr(n: String) = Regex("\\b$n=\"([^\"]*)\"").find(m.groupValues[1])?.groupValues?.get(1).orEmpty()
            Rel(attr("Id"), attr("Type"), attr("Target"), attr("TargetMode") == "External")
        }.toList()
    }

    private fun resolve(from: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val stack = ArrayDeque(from.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() })
        for (seg in target.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> stack.removeLastOrNull()
                else -> stack.addLast(seg)
            }
        }
        return stack.joinToString("/")
    }

    /**
     * Writes the finished deck: template slides removed, [slides] added in order.
     * Each slide is (layout, slide XML).
     */
    fun build(slides: List<Pair<Layout, String>>): ByteArray {
        // keep every part still reachable from the package root once the old slides are cut loose
        val keep = LinkedHashSet<String>()
        fun walk(part: String) {
            for (r in rels(part)) {
                if (r.external || r.type == REL_SLIDE || r.type == REL_NOTES) continue
                val t = resolve(part, r.target)
                if (t in parts && keep.add(t)) walk(t)
            }
        }
        walk("")
        val out = LinkedHashMap<String, ByteArray>()
        for ((name, bytes) in parts) {
            val isRels = name.endsWith(".rels")
            val owner = if (isRels) name.replace("_rels/", "").removeSuffix(".rels").trimEnd('/') else name
            if (name == "[Content_Types].xml" || name == "_rels/.rels" || name in keep || (isRels && owner in keep)) out[name] = bytes
        }

        // presentation.xml: new slide list; sections and custom shows would point at removed slides
        val newRels = slides.indices.map { "rIdRpt${it + 1}" }
        var pres = presentation
            .replace(Regex("<p:custShowLst>.*?</p:custShowLst>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<p:ext uri=\"\\{521415D9-36F7-43E2-AB2F-B90AF26B5E84\\}\">.*?</p:ext>", RegexOption.DOT_MATCHES_ALL), "")
        val ids = "<p:sldIdLst>" + newRels.mapIndexed { i, r -> "<p:sldId id=\"${256 + i}\" r:id=\"$r\"/>" }.joinToString("") + "</p:sldIdLst>"
        // replace the slide list where it stands: some writers rely on the elements around it staying in place
        val old = Regex("<p:sldIdLst>.*?</p:sldIdLst>|<p:sldIdLst/>", RegexOption.DOT_MATCHES_ALL).find(pres)
        pres = if (old != null) pres.replaceRange(old.range, ids) else pres.replaceFirst("<p:sldSz", "$ids<p:sldSz")
        out["ppt/presentation.xml"] = pres.toByteArray(Charsets.UTF_8)

        val presRelsPath = "ppt/_rels/presentation.xml.rels"
        var presRels = text(presRelsPath).replace(Regex("<Relationship\\b[^>]*Type=\"${Regex.escape(REL_SLIDE)}\"[^>]*/>"), "")
        presRels = presRels.replace(
            "</Relationships>",
            newRels.mapIndexed { i, r -> "<Relationship Id=\"$r\" Type=\"$REL_SLIDE\" Target=\"slides/slide${i + 1}.xml\"/>" }.joinToString("") + "</Relationships>",
        )
        out[presRelsPath] = presRels.toByteArray(Charsets.UTF_8)

        slides.forEachIndexed { i, (layout, xml) ->
            out["ppt/slides/slide${i + 1}.xml"] = xml.toByteArray(Charsets.UTF_8)
            val target = "../" + layout.part.removePrefix("ppt/")
            out["ppt/slides/_rels/slide${i + 1}.xml.rels"] = (
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"$REL_LAYOUT\" Target=\"$target\"/></Relationships>"
                ).toByteArray(Charsets.UTF_8)
        }

        // content types: drop overrides for removed parts, declare the new slides
        var ct = text("[Content_Types].xml")
        ct = Regex("<Override\\b[^>]*PartName=\"/([^\"]+)\"[^>]*/>").replace(ct) { m -> if (m.groupValues[1] in out) m.value else "" }
        ct = ct.replace(
            "</Types>",
            slides.indices.joinToString("") { "<Override PartName=\"/ppt/slides/slide${it + 1}.xml\" ContentType=\"$CT_SLIDE\"/>" } + "</Types>",
        )
        out["[Content_Types].xml"] = ct.toByteArray(Charsets.UTF_8)

        return zip(out)
    }

    private companion object {
        fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
            val map = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (!e.isDirectory) map[e.name] = zin.readBytes()
                }
            }
            return map
        }

        fun zip(parts: Map<String, ByteArray>): ByteArray {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { z ->
                // [Content_Types].xml first, as Office writes it
                for (name in listOf("[Content_Types].xml") + parts.keys.filter { it != "[Content_Types].xml" }) {
                    z.putNextEntry(ZipEntry(name))
                    z.write(parts.getValue(name))
                    z.closeEntry()
                }
            }
            return bos.toByteArray()
        }
    }
}

// ---------- slide XML ----------

fun esc(s: String): String = buildString(s.length) {
    for (c in s) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> if (c.code < 0x20 && c != '\t') append(' ') else append(c)
        }
    }
}

/** A colour: theme slot ("accent1", "tx1", "bg1") or RRGGBB. */
fun color(c: String): String =
    if (c.length == 6 && c.all { it.isDigit() || it in 'A'..'F' }) "<a:srgbClr val=\"$c\"/>" else "<a:schemeClr val=\"$c\"/>"

fun fill(c: String?): String = if (c == null) "<a:noFill/>" else "<a:solidFill>${color(c)}</a:solidFill>"

/** A text run. Null size / bold / colour inherit from the placeholder or theme. */
data class Run(val text: String, val size: Int? = 14, val bold: Boolean? = false, val color: String? = null)

/** One paragraph: runs, alignment ("l", "ctr", "r"); [inherit] keeps the layout's own paragraph settings. */
data class Para(val runs: List<Run>, val align: String = "l", val spaceAfter: Int = 0, val inherit: Boolean = false) {
    constructor(text: String, size: Int? = 14, bold: Boolean? = false, color: String? = null, align: String = "l") :
        this(listOf(Run(text, size, bold, color)), align)
}

private fun rPr(tag: String, size: Int?, bold: Boolean?, color: String?): String {
    val attrs = "lang=\"en-US\"" + (size?.let { " sz=\"${it * 100}\"" } ?: "") + (bold?.let { " b=\"${if (it) 1 else 0}\"" } ?: "") + " dirty=\"0\""
    return if (color == null) "<a:$tag $attrs/>" else "<a:$tag $attrs>${fill(color)}</a:$tag>"
}

fun paraXml(p: Para): String {
    val ppr = if (p.inherit) "" else "<a:pPr algn=\"${p.align}\" marL=\"0\" indent=\"0\">" +
        (if (p.spaceAfter > 0) "<a:spcAft><a:spcPts val=\"${p.spaceAfter * 100}\"/></a:spcAft>" else "") +
        "<a:buNone/></a:pPr>"
    val runs = p.runs.filter { it.text.isNotEmpty() }.joinToString("") { r ->
        "<a:r>${rPr("rPr", r.size, r.bold, r.color)}<a:t>${esc(r.text)}</a:t></a:r>"
    }
    val first = p.runs.firstOrNull()
    return "<a:p>$ppr$runs${rPr("endParaRPr", first?.size, null, null)}</a:p>"
}

class SlideXml {
    private val sb = StringBuilder()
    private var nextId = 2

    private fun id() = nextId++

    /** Placeholder filled from the layout (title, body idx, footer, slide number). */
    fun placeholder(type: String, idx: String?, paras: List<Para>, name: String = type) {
        val ph = if (idx != null) "<p:ph type=\"$type\" sz=\"quarter\" idx=\"$idx\"/>" else "<p:ph type=\"$type\"/>"
        sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"${id()}\" name=\"${esc(name)}\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr>$ph</p:nvPr></p:nvSpPr>")
        sb.append("<p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/>")
        sb.append(paras.joinToString("") { paraXml(it) })
        sb.append("</p:txBody></p:sp>")
    }

    fun slideNumber(idx: String, n: Int) {
        sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"${id()}\" name=\"Slide Number\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"sldNum\" sz=\"quarter\" idx=\"$idx\"/></p:nvPr></p:nvSpPr>")
        sb.append("<p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:fld id=\"{B6F15528-21DE-4FAA-801E-634DDDAF4B2B}\" type=\"slidenum\"><a:rPr lang=\"en-US\"/><a:t>$n</a:t></a:fld><a:endParaRPr lang=\"en-US\"/></a:p></p:txBody></p:sp>")
    }

    /** Text box or filled shape. [geom]: rect, roundRect, ellipse. */
    fun shape(
        box: Box, paras: List<Para>, name: String, fillColor: String? = null, line: String? = null, geom: String = "rect",
        anchor: String = "t", inset: Long = 45720, textBox: Boolean = fillColor == null,
    ) {
        sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"${id()}\" name=\"${esc(name)}\"/><p:cNvSpPr${if (textBox) " txBox=\"1\"" else ""}/><p:nvPr/></p:nvSpPr>")
        sb.append("<p:spPr><a:xfrm><a:off x=\"${box.x}\" y=\"${box.y}\"/><a:ext cx=\"${box.w}\" cy=\"${box.h}\"/></a:xfrm>")
        sb.append("<a:prstGeom prst=\"$geom\"><a:avLst/></a:prstGeom>${fill(fillColor)}")
        sb.append(if (line == null) "<a:ln><a:noFill/></a:ln>" else "<a:ln w=\"12700\">${fill(line)}</a:ln>")
        sb.append("</p:spPr><p:txBody><a:bodyPr wrap=\"square\" lIns=\"$inset\" tIns=\"$inset\" rIns=\"$inset\" bIns=\"$inset\" anchor=\"$anchor\" rtlCol=\"0\"><a:noAutofit/></a:bodyPr><a:lstStyle/>")
        sb.append(if (paras.isEmpty()) "<a:p><a:endParaRPr lang=\"en-US\" sz=\"1000\"/></a:p>" else paras.joinToString("") { paraXml(it) })
        sb.append("</p:txBody></p:sp>")
    }

    /** Straight line (connector) from (x1,y1) to (x2,y2). */
    fun line(x1: Long, y1: Long, x2: Long, y2: Long, lineColor: String, width: Int = 19050, name: String = "Line") {
        val x = minOf(x1, x2)
        val y = minOf(y1, y2)
        sb.append("<p:cxnSp><p:nvCxnSpPr><p:cNvPr id=\"${id()}\" name=\"${esc(name)}\"/><p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr>")
        sb.append("<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"${maxOf(x1, x2) - x}\" cy=\"${maxOf(y1, y2) - y}\"/></a:xfrm>")
        sb.append("<a:prstGeom prst=\"line\"><a:avLst/></a:prstGeom><a:ln w=\"$width\">${fill(lineColor)}</a:ln></p:spPr></p:cxnSp>")
    }

    /** One table cell; [span] > 1 merges it with the cells to its right (a vendor band across the table). */
    data class Cell(
        val text: String, val bold: Boolean = false, val align: String = "l", val fill: String? = null,
        val color: String? = null, val size: Int = 10, val span: Int = 1,
    )

    /** Table with explicit column widths (EMU), one row height, thin grey row lines. */
    fun table(x: Long, y: Long, cols: List<Long>, rowH: Long, rows: List<List<Cell>>, name: String = "Table") {
        val line = "<a:solidFill><a:srgbClr val=\"BFBFBF\"/></a:solidFill>"
        sb.append("<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"${id()}\" name=\"${esc(name)}\"/><p:cNvGraphicFramePr><a:graphicFrameLocks noGrp=\"1\"/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr>")
        sb.append("<p:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"${cols.sum()}\" cy=\"${rowH * rows.size}\"/></p:xfrm>")
        sb.append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\"><a:tbl><a:tblPr firstRow=\"1\"/><a:tblGrid>")
        cols.forEach { sb.append("<a:gridCol w=\"$it\"/>") }
        sb.append("</a:tblGrid>")
        for (row in rows) {
            sb.append("<a:tr h=\"$rowH\">")
            for (c in row) {
                val tcPr = "<a:tcPr marL=\"64008\" marR=\"64008\" marT=\"27432\" marB=\"27432\" anchor=\"ctr\">" +
                    "<a:lnL w=\"0\"><a:noFill/></a:lnL><a:lnR w=\"0\"><a:noFill/></a:lnR>" +
                    "<a:lnT w=\"6350\">$line</a:lnT><a:lnB w=\"6350\">$line</a:lnB>${fill(c.fill)}</a:tcPr>"
                sb.append(if (c.span > 1) "<a:tc gridSpan=\"${c.span}\">" else "<a:tc>")
                sb.append("<a:txBody><a:bodyPr/><a:lstStyle/>")
                sb.append(paraXml(Para(listOf(Run(c.text, c.size, c.bold, c.color)), c.align)))
                sb.append("</a:txBody>$tcPr</a:tc>")
                repeat(c.span - 1) {
                    sb.append("<a:tc hMerge=\"1\"><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"en-US\"/></a:p></a:txBody>$tcPr</a:tc>")
                }
            }
            sb.append("</a:tr>")
        }
        sb.append("</a:tbl></a:graphicData></a:graphic></p:graphicFrame>")
    }

    fun xml(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><p:sld $NS><p:cSld><p:spTree>" +
            "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" +
            sb + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
}
