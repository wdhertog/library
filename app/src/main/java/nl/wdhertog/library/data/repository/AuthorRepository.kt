package nl.wdhertog.library.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.room.withTransaction
import nl.wdhertog.library.data.db.Author
import nl.wdhertog.library.data.db.AuthorSummary
import nl.wdhertog.library.data.db.BookWorkLink
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.lookup.sources.WikidataSource
import nl.wdhertog.library.util.TextNormalizer
import java.time.Duration
import java.time.Instant

/** Auteurs: de lijst, hun boeken en wat Wikidata over ze weet (auteurspagina). */
class AuthorRepository(
    private val db: LibraryDatabase,
    private val wikidata: WikidataSource? = null,
    private val wikidataEnabled: () -> Boolean = { true },
    /** Voor zoektekst en vergrendelen na hernoemen of samenvoegen. */
    private val books: BookRepository? = null,
) {
    private val dao = db.authorDao()

    /** Op achternaam ("Gerard Reve" onder de R). */
    fun observeSummaries(): Flow<List<AuthorSummary>> =
        dao.observeSummaries().map { list -> list.sortedBy { TextNormalizer.authorSortKey(it.name) } }

    fun observeAuthor(id: Long): Flow<Author?> = dao.observeAuthor(id)

    fun observeBookWorks(id: Long): Flow<List<BookWorkLink>> = dao.observeBookWorks(id)

    fun observeAverageRating(id: Long): Flow<Double?> = dao.observeAverageRating(id)

    suspend fun findId(name: String): Long? = dao.findId(name.trim())

    /** Auteurs waarvan Wikidata nog niet is nagevraagd. */
    suspend fun withoutInfo(limit: Int): List<Long> = dao.withoutInfo(limit)

    /** Kan Wikidata nu worden gevraagd (bron aan)? */
    fun mayAskWikidata(): Boolean = wikidata != null && wikidataEnabled()

    /**
     * Naam wijzigen. Bestaat die naam al (hoofdletters tellen niet), dan worden de twee samengevoegd.
     * Geeft het id van de auteur die overblijft.
     */
    suspend fun rename(id: Long, name: String): Long = db.withTransaction {
        val trimmed = name.trim()
        val author = dao.get(id) ?: return@withTransaction id
        if (trimmed.isEmpty() || trimmed == author.name) return@withTransaction id
        val other = dao.findOther(trimmed, id)
        if (other != null) {
            mergeInto(author, other)
            other.id
        } else {
            dao.rename(id, trimmed)
            relink(id) { it }
            afterChange(dao.bookIds(id))
            id
        }
    }

    /** Samenvoegen: de werken van [id] gaan naar [intoId], en [id] verdwijnt. */
    suspend fun merge(id: Long, intoId: Long) = db.withTransaction {
        if (id == intoId) return@withTransaction
        val author = dao.get(id) ?: return@withTransaction
        val into = dao.get(intoId) ?: return@withTransaction
        mergeInto(author, into)
    }

    private suspend fun mergeInto(author: Author, into: Author) {
        val affected = dao.bookIds(author.id)
        relink(author.id) { if (it == author.id) into.id else it }
        // Wat Wikidata over de verdwijnende auteur wist, aanvullen waar het bij de andere ontbreekt.
        dao.update(
            into.copy(
                wikidataId = into.wikidataId ?: author.wikidataId,
                birthYear = into.birthYear ?: author.birthYear,
                deathYear = into.deathYear ?: author.deathYear,
                country = into.country ?: author.country,
                description = into.description ?: author.description,
            ),
        )
        dao.delete(author.id)
        afterChange(affected)
    }

    /**
     * De auteurs van alle werken van [authorId] opnieuw neerzetten, met [map] toegepast op elk auteur-id.
     * Verwijderen en invoegen (geen UPDATE): daarop reageren de synctriggers, zodat de nieuwe namen ook
     * naar de andere telefoons gaan. Staat een auteur twee keer bij een werk, dan blijft de eerste.
     */
    private suspend fun relink(authorId: Long, map: (Long) -> Long) {
        val links = dao.linksOfWorksBy(authorId)
        if (links.isEmpty()) return
        val updated = links.sortedWith(compareBy({ it.workId }, { it.position }))
            .map { it.copy(authorId = map(it.authorId)) }
            .distinctBy { it.workId to it.authorId }
        dao.deleteLinksOfWorks(links.map { it.workId }.distinct())
        dao.insertLinks(updated)
    }

    /** Zelf gekozen: verrijking mag de auteurs niet terugzetten; en de zoektekst bijwerken. */
    private suspend fun afterChange(bookIds: List<Long>) {
        val repository = books ?: return
        repository.lockField(bookIds, "authors")
        bookIds.forEach { repository.refreshSearchText(it) }
    }

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
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) { // ook een onverwacht antwoord (parsefout), niet alleen geen verbinding
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
