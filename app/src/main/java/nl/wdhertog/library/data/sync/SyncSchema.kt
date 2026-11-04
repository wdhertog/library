package nl.wdhertog.library.data.sync

/**
 * Wat er tussen telefoons wordt uitgewisseld (ontwerp §11): per gedeelde tabel de velden, in een
 * vorm die niet van lokale id's afhangt. Verwijzingen naar andere records zijn uuid's
 * ("location", "borrowedFrom", "series", …), koppeltabellen zijn lijsten ("works", "authors").
 */
object SyncSchema {
    const val BOOK = "book"
    const val WORK = "work"
    const val SERIES = "series"
    const val LOCATION = "location"
    const val PERSON = "person"
    const val LOAN = "loan"

    /** Volgorde van toepassen: wat verwezen wordt, eerst. */
    val applyOrder = listOf(SERIES, LOCATION, PERSON, WORK, BOOK, LOAN)

    val fields: Map<String, List<String>> = mapOf(
        BOOK to listOf(
            "isbn13", "isbn10", "title", "subtitle", "language", "translators", "publisher", "publishedYear", "pages",
            "format", "description", "coverUrl", "coverFile", "tags", "ownership", "borrowedFrom", "borrowedDueDate",
            "disposalReason", "disposedTo", "disposedAt", "owner", "location", "shelfPosition", "notes", "enrichStatus",
            "enrichedSources", "lockedFields", "openLibraryEditionId", "storygraphId", "hirtId", "addedAt", "works",
        ),
        WORK to listOf(
            "title", "originalTitle", "originalLanguage", "firstPublishedYear", "series", "seriesIndex", "awards",
            "openLibraryWorkId", "wikidataId", "authors",
        ),
        SERIES to listOf("name", "totalVolumes", "wikidataId", "manualParts"),
        LOCATION to listOf("parent", "name", "type", "sortOrder"),
        PERSON to listOf("name", "contactUri"),
        LOAN to listOf("book", "person", "lentAt", "dueAt", "returnedAt", "note"),
    )

    /** Per persoon en dus nooit uitgewisseld (naast leessessies en leesdoel, die geen trigger hebben). */
    val personalBookFields = setOf("favorite", "wantToRead")
}
