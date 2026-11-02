package nl.wdhertog.library.importer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TableReadersTest {

    @Test
    fun `csv with semicolons, quotes and windows-1252`() {
        val text = "Titel;Auteur;Jaar\r\n\"Leven; lot\";\"Grossman, \"\"Vasili\"\"\";2008\r\nCafé;;\r\n"
        val table = CsvReader.read(text.toByteArray(charset("windows-1252")))
        assertEquals(listOf("Titel", "Auteur", "Jaar"), table.header)
        assertEquals(listOf("Leven; lot", "Grossman, \"Vasili\"", "2008"), table.rows[0])
        assertEquals("Café", table.rows[1][0])
    }

    @Test
    fun `csv delimiter is detected outside quotes`() {
        assertEquals('\t', CsvReader.detectDelimiter("a\tb\t\"c,d,e\"\n1,2"))
        assertEquals(',', CsvReader.detectDelimiter("title,author\n"))
    }

    @Test
    fun `xlsx with shared strings, numbers, empty first column and two sheets`() {
        val sheets = XlsxReader.read(xlsx())
        assertEquals(listOf("Boeken", "DVD's"), sheets.map { it.name })
        val table = sheets[0].table
        assertEquals(listOf("Voornaam", "Achternaam", "Titel", "ISBN", "Fictie"), table.header)
        assertEquals(listOf("Kader", "Abdollah", "Leven & lot", "9789071376320", "1"), table.rows[0])
        assertEquals(listOf("", "Reve", "De avonden", "", "0"), table.rows[1])
    }

    @Test
    fun `excel numbers look like typed`() {
        assertEquals("9789071376320", XlsxReader.number("9.78907137632E+12"))
        assertEquals("4", XlsxReader.number("4.0"))
        assertEquals("100", XlsxReader.number("100"))
        assertEquals(27, XlsxReader.columnIndex("AB12"))
    }

    private fun xlsx(): ByteArray {
        val files = mapOf(
            "xl/workbook.xml" to """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>
                <sheet name="Boeken" sheetId="1" r:id="rId1"/><sheet name="DVD's" sheetId="2" r:id="rId2"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/>
                <Relationship Id="rId2" Target="/xl/worksheets/sheet2.xml"/></Relationships>""",
            "xl/sharedStrings.xml" to """<sst><si><t>Voornaam</t></si><si><t>Achternaam</t></si><si><t>Titel</t></si><si><t>ISBN</t></si>
                <si><t>Fictie</t></si><si><t>Kader</t></si><si><t>Abdollah</t></si><si><r><t>Leven </t></r><r><t>&amp; lot</t></r></si>
                <si><t>Reve</t></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData>
                <row r="1"><c r="B1" t="s"><v>0</v></c><c r="C1" t="s"><v>1</v></c><c r="D1" t="s"><v>2</v></c><c r="E1" t="s"><v>3</v></c><c r="F1" t="s"><v>4</v></c></row>
                <row r="2"><c r="B2" t="s"><v>5</v></c><c r="C2" t="s"><v>6</v></c><c r="D2" t="s"><v>7</v></c><c r="E2"><v>9789071376320</v></c><c r="F2"><v>1</v></c></row>
                <row r="3"><c r="C3" t="s"><v>8</v></c><c r="D3" t="inlineStr"><is><t>De avonden</t></is></c><c r="F3" t="b"><v>0</v></c></row>
                </sheetData></worksheet>""",
            "xl/worksheets/sheet2.xml" to """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Titel</t></is></c></row></sheetData></worksheet>""",
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
