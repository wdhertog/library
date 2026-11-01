package nl.wdhertog.library.lookup

import nl.wdhertog.library.lookup.sources.WikidataWork

object MetadataMerger {

    /** Per veld wint de eerste bron (in routervolgorde) die het heeft. */
    fun merge(results: List<BookMetadata>): BookMetadata? {
        if (results.isEmpty()) return null
        fun <T> first(selector: (BookMetadata) -> T?): T? = results.firstNotNullOfOrNull(selector)
        fun list(selector: (BookMetadata) -> List<String>) = results.map(selector).firstOrNull { it.isNotEmpty() }.orEmpty()
        // Een serie mét volgnummer is betrouwbaarder dan alleen een naam.
        val series = results.firstOrNull { it.seriesName != null && it.seriesIndex != null }
            ?: results.firstOrNull { it.seriesName != null }

        return BookMetadata(
            source = results.joinToString(", ") { it.source },
            title = first { it.title },
            subtitle = first { it.subtitle },
            authors = list { it.authors },
            authorWikidataIds = results.flatMap { it.authorWikidataIds }.distinct(),
            translators = list { it.translators },
            language = first { it.language },
            originalTitle = first { it.originalTitle },
            originalLanguage = first { it.originalLanguage },
            publisher = first { it.publisher },
            publishedYear = first { it.publishedYear },
            firstPublishedYear = first { it.firstPublishedYear },
            pages = first { it.pages },
            format = first { it.format },
            description = first { it.description },
            coverUrl = first { it.coverUrl },
            seriesName = series?.seriesName,
            seriesIndex = series?.seriesIndex,
            openLibraryEditionId = first { it.openLibraryEditionId },
            openLibraryWorkId = first { it.openLibraryWorkId },
            workWikidataId = first { it.workWikidataId },
        )
    }

    /** Wikidata kent volgnummers en geeft dezelfde serienaam voor alle edities; die gaat voor. */
    fun withWikidata(metadata: BookMetadata, work: WikidataWork?): BookMetadata {
        if (work == null) return metadata
        if (work.seriesName == null) return metadata.copy(workWikidataId = work.workId)
        return metadata.copy(
            source = metadata.source + ", Wikidata",
            workWikidataId = work.workId,
            seriesName = work.seriesName,
            seriesIndex = work.seriesIndex ?: metadata.seriesIndex,
        )
    }
}
