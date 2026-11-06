package nl.wdhertog.library.importer

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Excel (.xlsx) zonder extra bibliotheek: een xlsx is een zip met XML. Leest per tabblad de
 * celwaarden als tekst (gedeelde teksten, losse teksten, getallen, ja/nee). Opmaak, datums
 * als datum en formules zelf worden niet gelezen; van een formule telt de laatst berekende waarde.
 */
object XlsxReader {

    class FormatException(message: String) : Exception(message)

    /** Samen uitgepakte XML; een gewone bibliotheek van duizenden boeken is maar een paar MB. */
    private const val MAX_UNZIPPED = 100L * 1024 * 1024

    /** Zoveel kolommen kent Excel (A t/m XFD). */
    private const val MAX_COLUMNS = 16_384

    fun read(bytes: ByteArray): List<Sheet> {
        val files = unzip(bytes)
        val workbook = files["xl/workbook.xml"] ?: throw FormatException("Geen Excel-bestand (.xlsx)")
        val strings = files["xl/sharedStrings.xml"]?.let(::sharedStrings).orEmpty()
        val targets = files["xl/_rels/workbook.xml.rels"]?.let(::relationships).orEmpty()
        return sheetRefs(workbook).mapNotNull { (name, relId) ->
            val target = targets[relId] ?: return@mapNotNull null
            val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
            files[path]?.let { Sheet(name, tableOf(cells(it, strings))) }
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                // Alleen de XML-delen die nodig zijn; afbeeldingen en printerinstellingen niet.
                if (!entry.isDirectory && (entry.name.endsWith(".xml") || entry.name.endsWith(".rels"))) {
                    files[entry.name] = readLimited(zip, MAX_UNZIPPED - files.values.sumOf { it.size })
                }
            }
        }
        if (files.isEmpty()) throw FormatException("Geen Excel-bestand (.xlsx)")
        return files
    }

    /** Uitpakken met een grens: een "zip-bom" (klein bestand, enorm uitgepakt) mag het geheugen niet vullen. */
    private fun readLimited(zip: ZipInputStream, budget: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            total += n
            if (total > budget) throw FormatException("Dit Excel-bestand is te groot om te importeren")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun parse(xml: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
        // Geen DOCTYPE of externe entiteiten uit een onbekend bestand.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        factory.newSAXParser().parse(ByteArrayInputStream(xml), handler)
    }

    /** Naam en relatie-id van elk tabblad, in volgorde. */
    private fun sheetRefs(xml: ByteArray): List<Pair<String, String>> {
        val sheets = mutableListOf<Pair<String, String>>()
        parse(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                if (qName.endsWith("sheet")) {
                    val name = attributes.getValue("name") ?: return
                    val id = attributes.getValue("r:id") ?: return
                    sheets += name to id
                }
            }
        })
        return sheets
    }

    private fun relationships(xml: ByteArray): Map<String, String> {
        val rels = mutableMapOf<String, String>()
        parse(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                if (qName.endsWith("Relationship")) rels[attributes.getValue("Id")] = attributes.getValue("Target")
            }
        })
        return rels
    }

    /** Gedeelde teksten; een tekst met opmaak bestaat uit meerdere stukjes (<r><t>). */
    private fun sharedStrings(xml: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        parse(xml, object : DefaultHandler() {
            private val current = StringBuilder()
            private var inText = false
            private var inPhonetic = false

            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                when (qName) {
                    "si" -> current.clear()
                    "rPh" -> inPhonetic = true
                    "t" -> inText = !inPhonetic
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText) current.appendRange(ch, start, start + length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName) {
                    "si" -> strings += current.toString()
                    "rPh" -> inPhonetic = false
                    "t" -> inText = false
                }
            }
        })
        return strings
    }

    private fun cells(xml: ByteArray, strings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        parse(xml, object : DefaultHandler() {
            private var row = mutableMapOf<Int, String>()
            private var column = 0
            private var type: String? = null
            private val value = StringBuilder()
            private var collecting = false

            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                when (qName) {
                    "row" -> row = mutableMapOf()
                    "c" -> {
                        column = attributes.getValue("r")?.let(::columnIndex) ?: (row.keys.maxOrNull()?.plus(1) ?: 0)
                        // Verder dan Excel zelf kan (kolom XFD): negeren, anders een enorme lege rij.
                        if (column !in 0 until MAX_COLUMNS) column = -1
                        type = attributes.getValue("t")
                        value.clear()
                    }
                    "v" -> collecting = true
                    "t" -> collecting = type == "inlineStr"
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (collecting) value.appendRange(ch, start, start + length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName) {
                    "v", "t" -> collecting = false
                    "c" -> if (column >= 0) row[column] = when (type) {
                        "s" -> value.toString().trim().toIntOrNull()?.let { strings.getOrNull(it) }.orEmpty()
                        "b" -> if (value.toString() == "1") "1" else "0"
                        "str", "inlineStr", "e" -> value.toString()
                        else -> number(value.toString())
                    }
                    "row" -> rows += List((row.keys.maxOrNull() ?: -1) + 1) { row[it].orEmpty() }
                }
            }
        })
        return rows
    }

    /** "B12" → 1. */
    internal fun columnIndex(ref: String): Int =
        ref.takeWhile(Char::isLetter).fold(0) { acc, c -> acc * 26 + (c.uppercaseChar() - 'A' + 1) } - 1

    /** Excel slaat getallen op als 9.78907137632E+12 of 4.0; een ISBN of jaartal hoort er zo uit te zien als je het intypt. */
    internal fun number(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        return runCatching { BigDecimal(trimmed).stripTrailingZeros().toPlainString() }.getOrDefault(trimmed)
    }
}
