package nl.wdhertog.library.ui.scan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.wdhertog.library.data.db.LocationType
import nl.wdhertog.library.data.repository.BookListItem
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.data.repository.LocationNode
import nl.wdhertog.library.data.repository.LocationRepository
import nl.wdhertog.library.data.repository.LocationTree
import nl.wdhertog.library.data.repository.Placement
import nl.wdhertog.library.data.repository.ScanAddResult
import nl.wdhertog.library.lookup.EnrichScheduler
import nl.wdhertog.library.lookup.EnrichmentService
import nl.wdhertog.library.scan.Isbn
import nl.wdhertog.library.ui.navigation.ScanRoute

enum class ScanOutcome {
    ADDED,
    ALREADY_PRESENT,
    /** Plank-modus: bestaand boek naar deze plank verplaatst. */
    MOVED,
    /** Plank-modus: stond al op deze plank. */
    ALREADY_HERE,
    /** Verplaatsing ongedaan gemaakt. */
    UNDONE,
}

data class ScannedEntry(
    val isbn13: String,
    val bookId: Long,
    val outcome: ScanOutcome,
    /** Waar het boek stond vóór een verplaatsing, voor "ongedaan maken". */
    val previous: Placement? = null,
)

data class SessionRow(val entry: ScannedEntry, val item: BookListItem?, val previousLocation: String?)

class ScanViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: BookRepository,
    private val locations: LocationRepository,
    private val enrichment: EnrichmentService,
    private val scheduler: EnrichScheduler,
    /** Scope die het scherm overleeft, zodat opzoeken doorloopt als je het scanscherm sluit. */
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val entries = MutableStateFlow<List<ScannedEntry>>(emptyList())
    private val candidates = mutableMapOf<String, Long>()
    private val lastSeen = mutableMapOf<String, Long>()
    private val inFlight = mutableSetOf<String>()

    private val _signals = Channel<ScanSignal>(Channel.CONFLATED)
    val signals = _signals.receiveAsFlow()

    val torchOn = MutableStateFlow(false)

    /** Plank-modus: gescande boeken komen op deze locatie. null = uit. */
    val shelfId = MutableStateFlow(savedStateHandle.toRoute<ScanRoute>().locationId.takeIf { it != 0L })

    val locationTree: StateFlow<List<LocationNode>> = locations.observeTree()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Nieuwste scan bovenaan; titels verschijnen vanzelf zodra ze zijn opgezocht. */
    val session: StateFlow<List<SessionRow>> =
        combine(entries, repository.observeLibrary(), locationTree) { scanned, library, tree ->
            val byId = library.associateBy { it.book.id }
            val index = LocationTree.index(tree)
            scanned.asReversed().map { entry ->
                val previous = entry.previous?.locationId?.let { index[it]?.pathLabel }
                SessionRow(entry, byId[entry.bookId], previous)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setShelf(locationId: Long?) {
        shelfId.value = locationId
    }

    suspend fun createLocation(name: String, type: LocationType, parentId: Long?): Long =
        locations.add(name, type, parentId)

    /** Wordt per cameraframe aangeroepen met elke herkende EAN-13. */
    fun onBarcode(raw: String) {
        val now = System.currentTimeMillis()
        // Een losse misread kan toevallig een geldig controlecijfer hebben; een echte barcode
        // staat vele frames in beeld. Pas bij de tweede waarneming kort na elkaar telt hij.
        val candidate = candidates[raw]
        if (candidate == null || now - candidate > CONFIRM_WINDOW_MS) {
            candidates[raw] = now
            return
        }
        val previous = lastSeen[raw]
        lastSeen[raw] = now
        // Dezelfde code blijft in beeld; alleen de eerste bevestigde waarneming telt.
        if (previous != null && now - previous < REPEAT_WINDOW_MS) return

        val isbn13 = Isbn.toIsbn13(raw)?.takeIf { it.startsWith("978") || it.startsWith("979") }
        if (isbn13 == null) {
            _signals.trySend(ScanSignal.NOT_AN_ISBN)
            return
        }
        if (entries.value.any { it.isbn13 == isbn13 } || !inFlight.add(isbn13)) return

        val shelf = shelfId.value
        viewModelScope.launch {
            val entry = when (val result = repository.addScannedIsbn(isbn13)) {
                is ScanAddResult.Added -> {
                    shelf?.let { locations.moveBook(result.bookId, it) }
                    appScope.launch {
                        try {
                            enrichment.quickLookup(result.bookId)
                        } finally {
                            scheduler.schedule()
                        }
                    }
                    ScannedEntry(isbn13, result.bookId, ScanOutcome.ADDED)
                }
                is ScanAddResult.AlreadyPresent -> {
                    if (shelf == null) {
                        ScannedEntry(isbn13, result.bookId, ScanOutcome.ALREADY_PRESENT)
                    } else {
                        val before = locations.moveBook(result.bookId, shelf)
                        if (before?.locationId == shelf) ScannedEntry(isbn13, result.bookId, ScanOutcome.ALREADY_HERE)
                        else ScannedEntry(isbn13, result.bookId, ScanOutcome.MOVED, previous = before)
                    }
                }
            }
            inFlight.remove(isbn13)
            entries.update { it + entry }
            _signals.trySend(
                when (entry.outcome) {
                    ScanOutcome.ADDED, ScanOutcome.MOVED -> ScanSignal.ADDED
                    else -> ScanSignal.ALREADY_PRESENT
                },
            )
        }
    }

    /** Zet een in plank-modus verplaatst boek terug op zijn vorige plek. */
    fun undoMove(isbn13: String) {
        val entry = entries.value.firstOrNull { it.isbn13 == isbn13 && it.outcome == ScanOutcome.MOVED } ?: return
        val previous = entry.previous ?: return
        viewModelScope.launch {
            locations.restore(entry.bookId, previous)
            entries.update { list -> list.map { if (it == entry) it.copy(outcome = ScanOutcome.UNDONE) else it } }
        }
    }

    fun toggleTorch() = torchOn.update { !it }

    private companion object {
        const val REPEAT_WINDOW_MS = 3_000L
        const val CONFIRM_WINDOW_MS = 1_000L
    }
}
