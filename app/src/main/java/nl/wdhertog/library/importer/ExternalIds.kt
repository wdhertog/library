package nl.wdhertog.library.importer

/** Book.externalIds: komma-gescheiden "dienst:id", bijv. "goodreads:12345,storygraph:abc". */
object ExternalIds {
    fun parse(text: String?): Set<String> =
        text?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty()

    fun join(ids: Collection<String>): String? = ids.map(String::trim).filter(String::isNotEmpty).distinct().sorted().joinToString(",").ifEmpty { null }

    fun goodreads(id: String) = "goodreads:$id"
}
