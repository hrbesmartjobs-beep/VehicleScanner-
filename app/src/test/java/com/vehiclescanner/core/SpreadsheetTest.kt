package com.vehiclescanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringWriter

class SpreadsheetTest {

    @Test fun csvParsingQuotesAndDelimiters() {
        val rows = CsvIO.parse("Sl,Vehicle No,Owner\n1,\"KL-08-AB-1234\",\"Nair, R\"\n2,TN 09 CD 5678,\"Say \"\"hi\"\"\"\n\n")
        assertEquals(3, rows.size)
        assertEquals("Nair, R", rows[1][2])
        assertEquals("Say \"hi\"", rows[2][2])
        assertEquals(';', CsvIO.detectDelimiter("a;b;c\n1;2;3"))
    }

    @Test fun csvRoundTrip() {
        val sw = StringWriter()
        CsvIO.write(sw, listOf(listOf("a,b", "c\"d"), listOf("1", "2")))
        val back = CsvIO.parse(sw.toString())
        assertEquals(listOf("a,b", "c\"d"), back[0])
    }

    @Test fun xlsxWriteThenRead() {
        val bos = ByteArrayOutputStream()
        val rows = mutableListOf(listOf(XlsxWriter.Cell("Vehicle Number", XlsxWriter.Fill.HEADER), XlsxWriter.Cell("Count", XlsxWriter.Fill.HEADER)))
        for (i in 0 until 3000) rows += listOf(XlsxWriter.Cell("KL 08 AB ${1000 + i}", XlsxWriter.Fill.GREEN), XlsxWriter.Cell("$i", numeric = true))
        XlsxWriter.write(bos, "Vehicles <&>", rows)
        val tables = XlsxReader.read(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(1, tables.size)
        val t = tables[0]
        assertEquals("Vehicles <&>", t.name)
        assertEquals(3001, t.rows.size)
        assertEquals("KL 08 AB 1000", t.cell(1, 0))
        assertEquals("2999", t.cell(3000, 1))
    }

    @Test fun xlsxColumnIndex() {
        assertEquals(0, XlsxReader.columnIndex("A1"))
        assertEquals(27, XlsxReader.columnIndex("AB12"))
        assertEquals("AB", XlsxWriter.colName(27))
    }

    @Test fun importAnalysisCountsDuplicatesInvalidAndExisting() {
        val table = Table("t", listOf(
            listOf("S.No", "Owner", "Reg Number"),
            listOf("1", "A", "KL-08-AB-1234"),
            listOf("2", "B", "kl 08 ab 1234"),   // duplicate after normalisation
            listOf("3", "C", "TN09CD5678"),      // already in DB
            listOf("4", "D", "garbage!!"),       // invalid
            listOf("5", "E", ""),                // empty
            listOf("6", "F", "MH 12 XY 9876")
        ))
        val guess = ImportAnalyzer.guessColumn(table)!!
        assertEquals(2, guess.column)
        assertTrue(guess.hasHeader)
        val p = ImportAnalyzer.analyze(table, guess.column, guess.hasHeader, setOf("TN09CD5678"), allowNonStandard = false)
        assertEquals(6, p.totalRows)
        assertEquals(listOf("KL08AB1234", "MH12XY9876"), p.toImport)
        assertEquals(1, p.duplicatesInFile)
        assertEquals(1, p.alreadyInDatabase)
        assertEquals(1, p.invalid.size)
        assertEquals(1, p.emptyRows)
    }

    @Test fun largeImport() {
        val rows = ArrayList<List<String>>()
        rows += listOf("Vehicle")
        val states = listOf("KL", "TN", "KA", "MH")
        for (i in 0 until 20000) rows += listOf("${states[i % 4]}-${(i % 99 + 1).toString().padStart(2, '0')}-AB-${i % 10000}")
        val start = System.nanoTime()
        val p = ImportAnalyzer.analyze(Table("big", rows), 0, true, emptySet(), false)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(20000, p.totalRows)
        assertEquals(20000, p.toImport.size + p.duplicatesInFile)
        assertTrue("took $ms ms", ms < 5000)
    }
}
