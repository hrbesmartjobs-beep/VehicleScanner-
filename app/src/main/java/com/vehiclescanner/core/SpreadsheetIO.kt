package com.vehiclescanner.core

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.SAXParserFactory

/** A simple rectangular sheet of strings. Row 0 may or may not be a header. */
data class Table(val name: String, val rows: List<List<String>>) {
    val columnCount: Int get() = rows.maxOfOrNull { it.size } ?: 0
    fun cell(r: Int, c: Int): String = rows.getOrNull(r)?.getOrNull(c) ?: ""
}

class SpreadsheetException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

// ---------------------------------------------------------------- CSV

object CsvIO {
    fun read(input: InputStream, name: String = "CSV"): Table {
        val text = input.readBytes().toString(Charsets.UTF_8).removePrefix("﻿")
        val delimiter = detectDelimiter(text)
        return Table(name, parse(text, delimiter))
    }

    fun detectDelimiter(text: String): Char {
        val firstLines = text.lineSequence().take(10).joinToString("\n")
        return listOf(',', ';', '\t', '|').maxByOrNull { d -> firstLines.count { it == d } }
            ?.takeIf { d -> firstLines.contains(d) } ?: ','
    }

    fun parse(text: String, delimiter: Char = ','): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        var rowHasContent = false
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> { inQuotes = true; rowHasContent = true }
                delimiter -> { row += field.toString().trim(); field.setLength(0); rowHasContent = true }
                '\r' -> {}
                '\n' -> {
                    row += field.toString().trim(); field.setLength(0)
                    if (rowHasContent || row.any { it.isNotEmpty() }) rows += row
                    row = ArrayList(); rowHasContent = false
                }
                else -> { field.append(c); rowHasContent = true }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString().trim()
            if (row.any { it.isNotEmpty() }) rows += row
        }
        return rows
    }

    fun escape(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    fun write(out: Writer, rows: List<List<String>>) {
        for (r in rows) { out.write(r.joinToString(",") { escape(it) }); out.write("\r\n") }
        out.flush()
    }
}

// ---------------------------------------------------------------- XLSX reader

object XlsxReader {
    private const val MAX_ENTRY = 200L * 1024 * 1024   // zip-bomb guard

    /** Reads every worksheet. Throws [SpreadsheetException] on malformed files. */
    fun read(input: InputStream): List<Table> {
        val entries = HashMap<String, ByteArray>()
        try {
            ZipInputStream(input).use { zin ->
                var e: ZipEntry? = zin.nextEntry
                while (e != null) {
                    val n = e.name.trimStart('/')
                    if (!e.isDirectory && (n.startsWith("xl/") && n.endsWith(".xml") || n.endsWith(".rels"))) {
                        val bos = ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val r = zin.read(buf); if (r < 0) break
                            total += r
                            if (total > MAX_ENTRY) throw SpreadsheetException("Sheet too large")
                            bos.write(buf, 0, r)
                        }
                        entries[n] = bos.toByteArray()
                    }
                    e = zin.nextEntry
                }
            }
        } catch (ex: SpreadsheetException) { throw ex } catch (ex: Exception) {
            throw SpreadsheetException("Not a valid .xlsx file (${ex.message})", ex)
        }
        if (entries.isEmpty()) throw SpreadsheetException("Not a valid .xlsx file (no workbook found). Old .xls files must be re-saved as .xlsx or CSV.")

        val shared = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val sheets = sheetList(entries)
        val tables = ArrayList<Table>()
        for ((name, path) in sheets) {
            val bytes = entries[path] ?: continue
            tables += Table(name, parseSheet(bytes, shared))
        }
        if (tables.isEmpty()) throw SpreadsheetException("The workbook has no readable worksheets")
        return tables
    }

    private fun factory() = SAXParserFactory.newInstance().apply { isNamespaceAware = true }

    private fun parse(bytes: ByteArray, h: DefaultHandler) =
        factory().newSAXParser().parse(InputSource(ByteArrayInputStream(bytes)), h)

    private fun sheetList(entries: Map<String, ByteArray>): List<Pair<String, String>> {
        val rels = HashMap<String, String>()
        entries["xl/_rels/workbook.xml.rels"]?.let { bytes ->
            parse(bytes, object : DefaultHandler() {
                override fun startElement(uri: String?, local: String, q: String?, a: Attributes) {
                    if (local == "Relationship") {
                        val id = a.getValue("Id") ?: return
                        var target = a.getValue("Target") ?: return
                        target = if (target.startsWith("/")) target.trimStart('/') else "xl/" + target
                        rels[id] = target
                    }
                }
            })
        }
        val sheets = ArrayList<Pair<String, String>>()
        entries["xl/workbook.xml"]?.let { bytes ->
            parse(bytes, object : DefaultHandler() {
                override fun startElement(uri: String?, local: String, q: String?, a: Attributes) {
                    if (local == "sheet") {
                        val name = a.getValue("name") ?: "Sheet${sheets.size + 1}"
                        var rid: String? = null
                        for (i in 0 until a.length) if (a.getLocalName(i) == "id") rid = a.getValue(i)
                        val path = rid?.let { rels[it] } ?: "xl/worksheets/sheet${sheets.size + 1}.xml"
                        sheets += name to path
                    }
                }
            })
        }
        if (sheets.isEmpty()) {
            entries.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }.sorted()
                .forEachIndexed { i, p -> sheets += "Sheet${i + 1}" to p }
        }
        return sheets
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        parse(bytes, object : DefaultHandler() {
            val sb = StringBuilder(); var inT = false; var inPhonetic = false
            override fun startElement(uri: String?, local: String, q: String?, a: Attributes) {
                when (local) {
                    "si" -> sb.setLength(0)
                    "rPh" -> inPhonetic = true
                    "t" -> inT = !inPhonetic
                }
            }
            override fun endElement(uri: String?, local: String, q: String?) {
                when (local) {
                    "si" -> out += sb.toString()
                    "rPh" -> inPhonetic = false
                    "t" -> inT = false
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inT) sb.append(ch, start, length) }
        })
        return out
    }

    /** Column letters ("A", "AB") of a cell ref like "AB12" -> 0-based index. */
    fun columnIndex(ref: String): Int {
        var n = 0
        for (c in ref) { if (c !in 'A'..'Z') break; n = n * 26 + (c - 'A' + 1) }
        return n - 1
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        parse(bytes, object : DefaultHandler() {
            var row: ArrayList<String>? = null
            var col = 0; var type: String? = null
            val value = StringBuilder(); var capture = false
            override fun startElement(uri: String?, local: String, q: String?, a: Attributes) {
                when (local) {
                    "row" -> { row = ArrayList(); col = 0 }
                    "c" -> {
                        val r = a.getValue("r")
                        col = if (r != null) columnIndex(r) else (row?.size ?: 0)
                        type = a.getValue("t"); value.setLength(0)
                    }
                    "v", "t" -> capture = true
                }
            }
            override fun endElement(uri: String?, local: String, q: String?) {
                when (local) {
                    "v", "t" -> capture = false
                    "c" -> {
                        val raw = value.toString()
                        val text = when (type) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "b" -> if (raw == "1") "TRUE" else "FALSE"
                            "inlineStr", "str", "e" -> raw
                            else -> numberText(raw)
                        }
                        val r = row ?: return
                        while (r.size < col) r += ""
                        if (r.size == col) r += text.trim() else r[col] = text.trim()
                    }
                    "row" -> { row?.let { if (it.any { s -> s.isNotEmpty() }) rows += it }; row = null }
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (capture) value.append(ch, start, length) }
        })
        return rows
    }

    private fun numberText(raw: String): String {
        val d = raw.toDoubleOrNull() ?: return raw
        return if (d == Math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 1e15) d.toLong().toString() else raw
    }
}

// ---------------------------------------------------------------- XLSX writer

object XlsxWriter {
    /** Cell fill styles available to callers. */
    enum class Fill(val styleIndex: Int) { NONE(0), HEADER(1), GREEN(2), RED(3), YELLOW(4) }

    data class Cell(val text: String, val fill: Fill = Fill.NONE, val numeric: Boolean = false)

    fun write(out: OutputStream, sheetName: String, rows: List<List<Cell>>) {
        val zos = ZipOutputStream(out)
        fun put(name: String, content: String) {
            zos.putNextEntry(ZipEntry(name)); zos.write(content.toByteArray(Charsets.UTF_8)); zos.closeEntry()
        }
        put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
        put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${xml(sheetName.take(31))}" sheetId="1" r:id="rId1"/></sheets></workbook>""")
        put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
        put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="6"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFD9D9D9"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFC6EFCE"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFC7CE"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFEB9C"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="5"><xf/><xf fontId="1" fillId="2" applyFont="1" applyFill="1"/><xf fillId="3" applyFill="1"/><xf fillId="4" applyFill="1"/><xf fillId="5" applyFill="1"/></cellXfs></styleSheet>""")

        zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
        val w = zos.bufferedWriter(Charsets.UTF_8)
        w.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        w.write("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { ri, row ->
            w.write("<row r=\"${ri + 1}\">")
            row.forEachIndexed { ci, cell ->
                val ref = colName(ci) + (ri + 1)
                val s = if (cell.fill != Fill.NONE) " s=\"${cell.fill.styleIndex}\"" else ""
                if (cell.numeric && cell.text.toDoubleOrNull() != null) {
                    w.write("<c r=\"$ref\"$s><v>${cell.text}</v></c>")
                } else {
                    w.write("<c r=\"$ref\" t=\"inlineStr\"$s><is><t xml:space=\"preserve\">${xml(cell.text)}</t></is></c>")
                }
            }
            w.write("</row>")
        }
        w.write("</sheetData></worksheet>")
        w.flush()
        zos.closeEntry()
        zos.finish()
        zos.flush()
    }

    fun colName(i: Int): String {
        var n = i + 1; val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun xml(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) when {
            c == '&' -> sb.append("&amp;"); c == '<' -> sb.append("&lt;"); c == '>' -> sb.append("&gt;")
            c == '"' -> sb.append("&quot;")
            c.code < 0x20 && c != '\t' && c != '\n' && c != '\r' -> {}
            else -> sb.append(c)
        }
        return sb.toString()
    }
}
