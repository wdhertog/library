package nl.wdhertog.library.ui.locations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.wdhertog.library.data.db.LocationType
import nl.wdhertog.library.data.repository.BookListItem
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.data.repository.LocationNode
import nl.wdhertog.library.data.repository.LocationRepository
import nl.wdhertog.library.data.repository.LocationTree
import nl.wdhertog.library.ui.navigation.LocationDetailRoute

class LocationsViewModel(private val repository: LocationRepository) : ViewModel() {

    /** null = nog aan het laden. */
    val tree: StateFlow<List<LocationNode>?> = repository.observeTree()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun add(name: String, type: LocationType, parentId: Long?) {
        viewModelScope.launch { repository.add(name, type, parentId) }
    }
}

data class LocationDetailState(
    val node: LocationNode?,
    /** Boeken direct op deze locatie, in plankvolgorde. */
    val books: List<BookListItem>,
    val loaded: Boolean,
    /** De hele boom, om naartoe te verplaatsen. */
    val tree: List<LocationNode> = emptyList(),
)

class LocationDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: LocationRepository,
    bookRepository: BookRepository,
) : ViewModel() {

    val locationId = savedStateHandle.toRoute<LocationDetailRoute>().locationId

    val state: StateFlow<LocationDetailState> =
        combine(repository.observeTree(), bookRepository.observeLibrary()) { tree, library ->
            val node = LocationTree.index(tree)[locationId]
            val books = library
                .filter { it.book.locationId == locationId }
                .sortedWith(compareBy<BookListItem, Int?>(nullsLast()) { it.book.shelfPosition }.thenBy { it.book.title })
            LocationDetailState(node, books, loaded = true, tree = tree)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocationDetailState(null, emptyList(), loaded = false))

    fun addChild(name: String, type: LocationType) {
        viewModelScope.launch { repository.add(name, type, locationId) }
    }

    /** Verplaatsen naar een andere locatie (null = hoofdniveau). */
    fun move(newParentId: Long?) {
        viewModelScope.launch { repository.move(locationId, newParentId) }
    }

    suspend fun createLocation(name: String, type: LocationType, parentId: Long?): Long = repository.add(name, type, parentId)

    fun update(name: String, type: LocationType) {
        viewModelScope.launch { repository.update(locationId, name, type) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.delete(locationId)
            onDeleted()
        }
    }

    /** Boek van deze plank halen (blijft in de bibliotheek). */
    fun removeBook(bookId: Long) {
        viewModelScope.launch { repository.moveBook(bookId, null) }
    }
}
