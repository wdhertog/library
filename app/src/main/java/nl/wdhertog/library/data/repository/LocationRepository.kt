package nl.wdhertog.library.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.data.db.Location
import nl.wdhertog.library.data.db.LocationType
import java.time.Instant

/** Waar een boek stond, zodat een verplaatsing ongedaan kan worden gemaakt. */
data class Placement(val locationId: Long?, val shelfPosition: Int?)

class LocationRepository(private val db: LibraryDatabase) {

    private val dao = db.locationDao()
    private val bookDao = db.bookDao()

    fun observeTree(): Flow<List<LocationNode>> =
        combine(dao.observeLocations(), dao.observeBookCounts()) { locations, counts ->
            LocationTree.build(locations, counts.associate { it.locationId to it.count })
        }

    suspend fun add(name: String, type: LocationType, parentId: Long?): Long = db.withTransaction {
        dao.insert(Location(parentId = parentId, name = name.trim(), type = type, sortOrder = dao.nextSortOrder(parentId)))
    }

    suspend fun update(id: Long, name: String, type: LocationType) {
        val location = dao.getLocation(id) ?: return
        dao.update(location.copy(name = name.trim(), type = type, updatedAt = Instant.now()))
    }

    /** Verwijdert de locatie met alle sublocaties; de boeken blijven, zonder locatie. */
    suspend fun delete(id: Long) = dao.delete(id)

    /**
     * Zet een boek achteraan op een plank (of haalt het van zijn plek bij null).
     * Geeft de vorige plek terug voor "ongedaan maken".
     */
    suspend fun moveBook(bookId: Long, locationId: Long?): Placement? = db.withTransaction {
        val book = bookDao.getBook(bookId) ?: return@withTransaction null
        val previous = Placement(book.locationId, book.shelfPosition)
        if (book.locationId == locationId && locationId != null) return@withTransaction previous
        val position = locationId?.let { dao.nextShelfPosition(it) }
        dao.setBookLocation(bookId, locationId, position, Instant.now())
        previous
    }

    suspend fun restore(bookId: Long, placement: Placement) =
        dao.setBookLocation(bookId, placement.locationId, placement.shelfPosition, Instant.now())
}
