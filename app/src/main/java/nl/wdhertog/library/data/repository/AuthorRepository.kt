package nl.wdhertog.library.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import nl.wdhertog.library.data.db.Author
import nl.wdhertog.library.data.db.AuthorSummary
import nl.wdhertog.library.data.db.BookWorkLink
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.lookup.LookupException
import nl.wdhertog.library.lookup.sources.WikidataSource
import nl.wdhertog.library.util.TextNormalizer
import java.time.Duration
import java.time.Instant

/** Auteurs: de lijst, hun boeken en wat Wikidata over ze weet (auteurspagina). */
class AuthorRepository(
    db: LibraryDatabase,
    private val wikidata: WikidataSource? = null,
    private val wikidataEnabled: () -> Boolean = { true },
) {
    private val dao = db.authorDao()

    /** Op achternaam ("Gerard Reve" onder de R). */
    fun observeSummaries(): Flow<List<AuthorSummary>> =
        dao.observeSummaries().map { list -> list.sortedBy { TextNormalizer.authorSortKey(it.name) } }

    fun observeAuthor(id: Long): Flow<Author?> = dao.observeAuthor(id)

    fun observeBookWorks(id: Long): Flow<List<BookWorkLink>> = dao.observeBookWorks(id)

    fun observeAverageRating(id: Long): Flow<Double?> = dao.observeAverageRating(id)

    suspend fun findId(name: String): Long? = dao.findId(name.trim())

    /**
     * Gegevens bij Wikidata ophalen (hooguit eens per [INFO_MAX_AGE], tenzij [force]).
     * Geeft false als het nu niet lukte (geen verbinding, Wikidata uit).
     */
    suspend fun refreshInfo(id: Long, force: Boolean = false): Boolean {
        val source = wikidata?.takeIf { wikidataEnabled() } ?: return false
        val author = dao.get(id) ?: return false
        val fetched = author.infoFetchedAt
        if (!force && fetched != null && fetched.isAfter(Instant.now().minus(INFO_MAX_AGE))) return true
        val info = try {
            source.authorInfo(author.wikidataId, dao.workWikidataIds(id), author.name)
        } catch (e: LookupException) {
            Log.i(TAG, "Auteur ${author.name} niet opgehaald: ${e.message}")
            return false
        }
        dao.setInfo(
            id,
            wikidataId = info?.id ?: author.wikidataId,
            birthYear = info?.birthYear ?: author.birthYear,
            deathYear = info?.deathYear ?: author.deathYear,
            country = info?.country ?: author.country,
            description = info?.description ?: author.description,
            at = Instant.now(),
        )
        return true
    }

    companion object {
        private const val TAG = "AuthorRepository"
        val INFO_MAX_AGE: Duration = Duration.ofDays(90)
    }
}
