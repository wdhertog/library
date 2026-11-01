package nl.wdhertog.library.lookup

import nl.wdhertog.library.lookup.sources.BookSource

/**
 * Kiest de bronvolgorde op basis van het ISBN-prefix (ontwerp §4): de nationale bibliografie
 * van het land van de uitgever eerst, Open Library als aanvulling.
 */
class SourceRouter(
    private val kb: BookSource,
    private val openLibrary: BookSource,
    private val dnb: BookSource,
    private val bnf: BookSource,
) {
    fun sourcesFor(isbn13: String): List<BookSource> = when {
        isbn13.startsWith("97890") || isbn13.startsWith("97894") -> listOf(kb, openLibrary)
        isbn13.startsWith("9783") -> listOf(dnb, openLibrary)
        isbn13.startsWith("9782") || isbn13.startsWith("97910") -> listOf(bnf, openLibrary)
        else -> listOf(openLibrary)
    }
}
