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

    /**
     * Locatie bij een pad als "Woonkamer/Kast 2/Plank 3" (ook met > of ›): bestaande plekken
     * worden hergebruikt (hoofdletters maken niet uit), ontbrekende aangemaakt.
     */
    suspend fun findOrCreatePath(path: String): Long? = db.withTransaction {
        val names = path.split('/', '>', '›', '\\').map(String::trim).filter(String::isNotEmpty)
        if (names.isEmpty()) return@withTransaction null
        val all = dao.getLocations().toMutableList()
        var parent: Location? = null
        for (name in names) {
            parent = all.firstOrNull { it.parentId == parent?.id && it.name.equals(name, ignoreCase = true) }
                ?: Location(
                    parentId = parent?.id,
                    name = name,
                    type = LocationTree.defaultChildType(parent?.type),
                    sortOrder = dao.nextSortOrder(parent?.id),
                ).let { it.copy(id = dao.insert(it)) }.also { all += it }
        }
        parent?.id
    }

    suspend fun update(id: Long, name: String, type: LocationType) {
        val location = dao.getLocation(id) ?: return
        dao.update(location.copy(name = name.trim(), type = type, updatedAt = Instant.now()))
    }

    /**
     * Een locatie (met alles erin) verplaatsen, bijv. een plank terug in zijn kast. Niet in zichzelf of in
     * een eigen sublocatie. Het volgnummer blijft, zodat planken op hun oude plek terugkomen.
     */
    suspend fun move(id: Long, newParentId: Long?): Boolean = db.withTransaction {
        val location = dao.getLocation(id) ?: return@withTransaction false
        if (newParentId != null) {
            // Omhoog lopen vanaf de nieuwe plek: komen we langs [id], dan zou het een kring worden.
            var current: Long? = newParentId
            while (current != null) {
                if (current == id) return@withTransaction false
                current = dao.getLocation(current)?.parentId
            }
        }
        dao.update(location.copy(parentId = newParentId, updatedAt = Instant.now()))
        true
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

    /** Waar het boek nu staat. */
    suspend fun placementOf(bookId: Long): Placement? = bookDao.getBook(bookId)?.let { Placement(it.locationId, it.shelfPosition) }

    suspend fun restore(bookId: Long, placement: Placement) =
        dao.setBookLocation(bookId, placement.locationId, placement.shelfPosition, Instant.now())
}
