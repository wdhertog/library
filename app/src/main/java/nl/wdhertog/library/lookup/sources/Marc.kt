package nl.wdhertog.library.lookup.sources

import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Eén MARC-veld: tag, indicatoren en subvelden (code → waarde, in volgorde). */
internal data class MarcField(val tag: String, val subfields: List<Pair<Char, String>>) {
    operator fun get(code: Char): String? = subfields.firstOrNull { it.first == code }?.second?.trim()
    fun all(code: Char): List<String> = subfields.filter { it.first == code }.map { it.second.trim() }
}

/** Leest de eerste MARC21- of UNIMARC-record uit een SRU-antwoord (namespaces worden genegeerd). */
internal fun parseFirstMarcRecord(xml: String): List<MarcField>? {
    val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    val record = document.getElementsByTagNameNS("*", "record")
        .let { list -> (0 until list.length).map { list.item(it) as Element } }
        .firstOrNull { it.getElementsByTagNameNS("*", "datafield").length > 0 }
        ?: return null
    val fields = record.getElementsByTagNameNS("*", "datafield")
    return (0 until fields.length).map { i ->
        val field = fields.item(i) as Element
        val subs = field.getElementsByTagNameNS("*", "subfield")
        MarcField(
            tag = field.getAttribute("tag"),
            subfields = (0 until subs.length).map { j ->
                val sub = subs.item(j) as Element
                (sub.getAttribute("code").firstOrNull() ?: ' ') to sub.textContent
            },
        )
    }
}

internal fun List<MarcField>.field(tag: String): MarcField? = firstOrNull { it.tag == tag }

internal fun List<MarcField>.fields(tag: String): List<MarcField> = filter { it.tag == tag }

/** "Monaldi, Rita" → "Rita Monaldi". */
internal fun invertName(name: String): String {
    val clean = name.trim().trimEnd(',', '.')
    val parts = clean.split(", ", limit = 2)
    return if (parts.size == 2) "${parts[1]} ${parts[0]}" else clean
}

/** Verwijdert ISBD-leestekens aan het eind van MARC-waarden: "Veritas /" → "Veritas". */
internal fun String.isbdTrim(): String = trim().trimEnd('/', ':', ';', ',', '=', ' ').trim()

/** Uitgeversreeksen (Rororo 24612, Étonnants classiques 112) zijn geen verhaalseries. */
internal fun isPublisherSeriesNumber(index: String?): Boolean = (index?.filter(Char::isDigit)?.toIntOrNull() ?: 0) > 200
