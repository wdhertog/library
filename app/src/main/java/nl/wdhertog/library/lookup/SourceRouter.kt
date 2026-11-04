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
    private val bne: BookSource,
    private val sbn: BookSource,
    /** Instellingen: bronnen die uit staan, worden overgeslagen. */
    private val enabled: (LookupSource) -> Boolean = { true },
) {
    private val keys = mapOf(
        kb to LookupSource.KB, openLibrary to LookupSource.OPEN_LIBRARY, dnb to LookupSource.DNB,
        bnf to LookupSource.BNF, bne to LookupSource.BNE, sbn to LookupSource.SBN,
    )

    /** De bronnen voor dit ISBN, in volgorde, zonder de bronnen die uit staan; kan leeg zijn. */
    fun sourcesFor(isbn13: String): List<BookSource> = allSourcesFor(isbn13).filter { source -> keys[source]?.let(enabled) ?: true }

    private fun allSourcesFor(isbn13: String): List<BookSource> = when {
        isbn13.startsWith("97890") || isbn13.startsWith("97894") -> listOf(kb, openLibrary)
        isbn13.startsWith("9783") -> listOf(dnb, openLibrary)
        isbn13.startsWith("9782") || isbn13.startsWith("97910") -> listOf(bnf, openLibrary)
        isbn13.startsWith("97884") -> listOf(bne, openLibrary)
        // Spaanstalig Latijns-Amerika: de BNE heeft daar een deel van, Open Library ook.
        spanishAmerica.any(isbn13::startsWith) -> listOf(openLibrary, bne)
        isbn13.startsWith("97888") || isbn13.startsWith("97912") -> listOf(sbn, openLibrary)
        else -> listOf(openLibrary)
    }

    private companion object {
        /**
         * ISBN-groepen van Spaanstalige landen in Latijns-Amerika: Argentinië (950, 987), Chili (956),
         * Colombia (958), Cuba (959), Mexico (968, 970, 607), Peru (9972, 612), Uruguay (9974), Venezuela (980).
         */
        val spanishAmerica = listOf("978950", "978987", "978956", "978958", "978959", "978968", "978970", "978607", "9789972", "978612", "9789974", "978980")
    }
}
