package nl.wdhertog.library.importer

/** Een tabel uit een CSV- of Excel-bestand: kopregel plus rijen, alles als tekst. */
data class Table(val header: List<String>, val rows: List<List<String>>) {
    fun column(index: Int): List<String> = rows.map { it.getOrElse(index) { "" } }
}

data class Sheet(val name: String, val table: Table)

/**
 * Van rauwe rijen naar een tabel: de eerste niet-lege rij is de kopregel, en kolommen
 * zonder kop én zonder waarden (zoals een lege kolom A) vallen weg.
 */
internal fun tableOf(rawRows: List<List<String>>): Table {
    val rows = rawRows.map { row -> row.map(String::trim) }.filter { row -> row.any(String::isNotEmpty) }
    if (rows.isEmpty()) return Table(emptyList(), emptyList())
    val width = rows.maxOf { it.size }
    val padded = rows.map { it + List(width - it.size) { "" } }
    val used = (0 until width).filter { col -> padded.any { it[col].isNotEmpty() } }
    val header = used.map { padded.first()[it] }
    return Table(header, padded.drop(1).map { row -> used.map { row[it] } })
}
