package nl.wdhertog.library.lookup

import kotlinx.coroutines.CancellationException
import nl.wdhertog.library.data.db.EnrichStatus
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.lookup.sources.WikidataSource

/**
 * Zoekt gegevens op bij de open bronnen en schrijft ze naar de database.
 * Snel (één bron, direct na het scannen) of volledig (alle bronnen + Wikidata, op de achtergrond).
 */
class EnrichmentService(
    private val repository: BookRepository,
    private val router: SourceRouter,
    private val wikidata: WikidataSource,
) {

    /** Eerste bron alleen; fouten worden genegeerd, de volledige ronde volgt toch. */
    suspend fun quickLookup(bookId: Long) {
        val isbn = repository.isbnFor(bookId) ?: return
        val source = router.sourcesFor(isbn).first()
        val metadata = try {
            source.quickLookup(isbn)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        repository.applyMetadata(bookId, metadata, refresh = false, status = EnrichStatus.QUICK)
    }

    /**
     * Volledige ronde. Gooit [LookupException] als geen enkele bron bereikbaar was,
     * zodat de achtergrondtaak het later opnieuw kan proberen.
     * @param refresh true = niet-vergrendelde velden overschrijven ("Gegevens opnieuw ophalen").
     */
    suspend fun enrich(bookId: Long, refresh: Boolean): Boolean {
        val isbn = repository.isbnFor(bookId) ?: return false
        val sources = router.sourcesFor(isbn)
        var unreachable = 0
        val results = sources.mapNotNull { source ->
            try {
                source.lookup(isbn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LookupException) {
                unreachable++
                null
            } catch (_: Exception) {
                null // Onverwacht antwoord van deze bron: overslaan.
            }
        }
        if (unreachable == sources.size) throw LookupException("Geen bron bereikbaar")

        val merged = MetadataMerger.merge(results)
        if (merged == null) {
            repository.markEnrichFailed(bookId)
            return false
        }
        val work = try {
            wikidata.findWork(
                titles = listOfNotNull(merged.title, merged.originalTitle),
                authorIds = merged.authorWikidataIds,
                authorNames = merged.authors,
                preferredLanguage = merged.language,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // Wikidata is een aanvulling; zonder gaat het ook.
        }
        repository.applyMetadata(bookId, MetadataMerger.withWikidata(merged, work), refresh, EnrichStatus.ENRICHED)
        return true
    }
}
