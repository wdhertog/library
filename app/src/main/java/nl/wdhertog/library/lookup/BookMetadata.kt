package nl.wdhertog.library.lookup

import nl.wdhertog.library.data.db.BookFormat

/** Wat één bron over een boek weet. Alles optioneel; de merger combineert bronnen. */
data class BookMetadata(
    val source: String,
    val title: String? = null,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    /** Wikidata-id's van de auteurs, voor het opzoeken van series. */
    val authorWikidataIds: List<String> = emptyList(),
    val translators: List<String> = emptyList(),
    /** ISO 639-1. */
    val language: String? = null,
    val originalTitle: String? = null,
    val originalLanguage: String? = null,
    val publisher: String? = null,
    val publishedYear: Int? = null,
    val firstPublishedYear: Int? = null,
    val pages: Int? = null,
    val format: BookFormat? = null,
    val description: String? = null,
    val coverUrl: String? = null,
    val seriesName: String? = null,
    val seriesIndex: String? = null,
    val openLibraryEditionId: String? = null,
    val openLibraryWorkId: String? = null,
    val workWikidataId: String? = null,
    /** Wikidata-id van de serie (bij [seriesName]). */
    val seriesWikidataId: String? = null,
) {
    val hasContent: Boolean get() = title != null || authors.isNotEmpty()
}
