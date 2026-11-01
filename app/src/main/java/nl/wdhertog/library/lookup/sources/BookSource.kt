package nl.wdhertog.library.lookup.sources

import nl.wdhertog.library.lookup.BookMetadata

interface BookSource {
    /** Naam zoals getoond bij "Bronnen" in het boekdetail. */
    val name: String

    /** Gegevens voor dit ISBN-13, of null als de bron het boek niet kent. */
    suspend fun lookup(isbn13: String): BookMetadata?

    /** Snelle variant voor direct na het scannen (titel, auteur, cover); standaard gelijk aan [lookup]. */
    suspend fun quickLookup(isbn13: String): BookMetadata? = lookup(isbn13)
}
