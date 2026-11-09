package nl.wdhertog.library.ui.scan

import kotlinx.coroutines.flow.flowOn
import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import nl.wdhertog.library.covers.Box
import nl.wdhertog.library.covers.CoverImages
import nl.wdhertog.library.covers.CoverStore
import nl.wdhertog.library.data.repository.BookDraft
import nl.wdhertog.library.lookup.search.BookSearch
import nl.wdhertog.library.lookup.search.CoverLine
import nl.wdhertog.library.lookup.search.CoverText
import nl.wdhertog.library.lookup.search.SearchCandidate
import nl.wdhertog.library.scan.CoverRecognizer
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
import nl.wdhertog.library.util.ShelfExisting
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
    /** Plank-modus: staat op een andere plek en is (nog) niet verplaatst. */
    KEPT,
    /** Staat als niet (meer) in bezit; met "In bezit" wordt het weer een eigen boek. */
    NOT_OWNED,
    /** Was niet in bezit, nu weer wel (in plank-modus ook op deze plank gezet). */
    RECLAIMED,
    /** Leeslijst-modus: nieuw, niet in bezit en op de leeslijst. */
    WISHLIST_ADDED,
    /** Leeslijst-modus: stond al in de bibliotheek, nu (ook) op de leeslijst. */
    ON_WISHLIST,
    /** Leeslijst-modus: stond al op de leeslijst. */
    ALREADY_ON_WISHLIST,
}

/**
 * Plank-modus, een boek dat je al hebt: staat het hier al, dan niets; staat het nog nergens, of is de
 * instelling "verplaatsen", dan hierheen; anders blijft het staan (bij "eerst vragen" volgt de vraag).
 */
fun shelfOutcome(currentLocation: Long?, shelf: Long, setting: ShelfExisting): ScanOutcome = when {
    currentLocation == shelf -> ScanOutcome.ALREADY_HERE
    currentLocation == null || setting == ShelfExisting.MOVE -> ScanOutcome.MOVED
    else -> ScanOutcome.KEPT
}

data class ScannedEntry(
    val isbn13: String,
    val bookId: Long,
    val outcome: ScanOutcome,
    /** Waar het boek stond vóór een verplaatsing, voor "ongedaan maken". */
    val previous: Placement? = null,
)

data class SessionRow(val entry: ScannedEntry, val item: BookListItem?, val previousLocation: String?)

enum class ScanMode { BARCODE, COVER }

/** Cover-herkenning: foto → tekst → kandidaten. */
sealed interface CoverSearchState {
    val photo: Bitmap

    data class Working(override val photo: Bitmap, val message: String) : CoverSearchState
    data class Results(override val photo: Bitmap, val query: String, val candidates: List<SearchCandidate>) : CoverSearchState
}

class ScanViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: BookRepository,
    private val locations: LocationRepository,
    private val enrichment: EnrichmentService,
    private val scheduler: EnrichScheduler,
    /** Scope die het scherm overleeft, zodat opzoeken doorloopt als je het scanscherm sluit. */
    private val appScope: CoroutineScope,
    private val coverStore: CoverStore,
    private val bookSearch: BookSearch,
    /** Alleen-wifi: op mobiele data wacht het opzoeken tot de achtergrondronde. */
    private val mayLookUpNow: () -> Boolean = { true },
    /** Instelling: wat te doen met een boek dat al op een andere plek staat. */
    private val shelfExisting: () -> ShelfExisting = { ShelfExisting.MOVE },
    /** Weer eigen boek: bezit terug, gegevens over weggedaan gewist (LoanRepository.markOwned). */
    private val markOwned: suspend (Long) -> Unit = {},
) : ViewModel() {

    /** Plank-modus: een boek dat als niet in bezit staat; eerst vragen of het weer van jou is. */
    val askReclaim = MutableStateFlow<SessionRow?>(null)

    /** "Eerst vragen": het boek waarover nu een vraag openstaat; scannen wacht zolang. */
    val askMove = MutableStateFlow<SessionRow?>(null)

    val mode = MutableStateFlow(ScanMode.BARCODE)
    val coverSearch = MutableStateFlow<CoverSearchState?>(null)

    private val entries = MutableStateFlow<List<ScannedEntry>>(emptyList())
    private val candidates = mutableMapOf<String, Long>()
    private val lastSeen = mutableMapOf<String, Long>()
    private val inFlight = mutableSetOf<String>()

    private val _signals = Channel<ScanSignal>(Channel.CONFLATED)
    val signals = _signals.receiveAsFlow()

    val torchOn = MutableStateFlow(false)

    /** Plank-modus: gescande boeken komen op deze locatie. null = uit. */
    val shelfId = MutableStateFlow(savedStateHandle.toRoute<ScanRoute>().locationId.takeIf { it != 0L })

    /** Leeslijst-modus: gescande boeken komen niet in je bezit, wel op je leeslijst. Gaat niet samen met plank-modus. */
    val wishlist = MutableStateFlow(savedStateHandle.toRoute<ScanRoute>().wishlist && shelfId.value == null)

    fun setWishlist(on: Boolean) {
        wishlist.value = on
        if (on) shelfId.value = null
    }

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
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setShelf(locationId: Long?) {
        shelfId.value = locationId
        if (locationId != null) wishlist.value = false
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

        val isbn13 = Isbn.toIsbn13(raw)
        if (isbn13 == null) {
            _signals.trySend(ScanSignal.NOT_AN_ISBN)
            return
        }
        if (askMove.value != null || askReclaim.value != null) return
        if (entries.value.any { it.isbn13 == isbn13 } || !inFlight.add(isbn13)) return

        viewModelScope.launch {
            // finally: ook bij een fout mag hetzelfde ISBN later opnieuw gescand worden.
            try {
                addIsbn(isbn13)
            } finally {
                inFlight.remove(isbn13)
            }
        }
    }

    /** Toevoegen (of in plank-modus verplaatsen) en in de sessielijst zetten; voor barcode én cover. */
    private suspend fun addIsbn(isbn13: String): ScannedEntry {
        val shelf = shelfId.value
        val toWishlist = wishlist.value
        val entry = when (val result = repository.addScannedIsbn(isbn13, toWishlist)) {
            is ScanAddResult.Added -> {
                shelf?.let { locations.moveBook(result.bookId, it) }
                appScope.launch {
                    try {
                        if (mayLookUpNow()) enrichment.quickLookup(result.bookId)
                    } finally {
                        scheduler.schedule()
                    }
                }
                ScannedEntry(isbn13, result.bookId, if (toWishlist) ScanOutcome.WISHLIST_ADDED else ScanOutcome.ADDED)
            }
            is ScanAddResult.AlreadyPresent -> {
                if (toWishlist) {
                    // Al in de bibliotheek (in bezit of niet): alleen op de leeslijst zetten, verder niets veranderen.
                    if (result.wantToRead) {
                        ScannedEntry(isbn13, result.bookId, ScanOutcome.ALREADY_ON_WISHLIST)
                    } else {
                        repository.setWantToRead(listOf(result.bookId), true)
                        ScannedEntry(isbn13, result.bookId, ScanOutcome.ON_WISHLIST)
                    }
                } else if (result.notOwned) {
                    // Niet (meer) in bezit: niets vanzelf veranderen; in de lijst (of in plank-modus via de vraag) kiezen.
                    ScannedEntry(isbn13, result.bookId, ScanOutcome.NOT_OWNED, previous = locations.placementOf(result.bookId))
                } else if (shelf == null) {
                    ScannedEntry(isbn13, result.bookId, ScanOutcome.ALREADY_PRESENT)
                } else {
                    val current = locations.placementOf(result.bookId)
                    when (shelfOutcome(current?.locationId, shelf, shelfExisting())) {
                        ScanOutcome.ALREADY_HERE -> ScannedEntry(isbn13, result.bookId, ScanOutcome.ALREADY_HERE)
                        ScanOutcome.MOVED -> {
                            val before = locations.moveBook(result.bookId, shelf)
                            ScannedEntry(isbn13, result.bookId, ScanOutcome.MOVED, previous = before)
                        }
                        else -> ScannedEntry(isbn13, result.bookId, ScanOutcome.KEPT, previous = current)
                    }
                }
            }
        }
        record(entry)
        if (entry.outcome == ScanOutcome.NOT_OWNED && shelf != null) {
            askReclaim.value = session.value.firstOrNull { it.entry.isbn13 == entry.isbn13 } ?: SessionRow(entry, null, null)
        }
        if (entry.outcome == ScanOutcome.KEPT && shelfExisting() == ShelfExisting.ASK) {
            askMove.value = session.value.firstOrNull { it.entry.isbn13 == entry.isbn13 } ?: SessionRow(entry, null, null)
        }
        return entry
    }

    /** Een niet-verplaatst boek (laten staan, of de vraag) alsnog naar deze plank. */
    fun moveHere(isbn13: String) {
        askMove.value = null
        val shelf = shelfId.value ?: return
        val entry = entries.value.firstOrNull { it.isbn13 == isbn13 && it.outcome == ScanOutcome.KEPT } ?: return
        viewModelScope.launch {
            val before = locations.moveBook(entry.bookId, shelf)
            entries.update { list -> list.map { if (it == entry) it.copy(outcome = ScanOutcome.MOVED, previous = before) else it } }
        }
    }

    /** Weer in bezit nemen; in plank-modus meteen op deze plank. */
    fun reclaim(isbn13: String) {
        askReclaim.value = null
        val entry = entries.value.firstOrNull { it.isbn13 == isbn13 && it.outcome == ScanOutcome.NOT_OWNED } ?: return
        val shelf = shelfId.value
        viewModelScope.launch {
            markOwned(entry.bookId)
            val before = shelf?.let { locations.moveBook(entry.bookId, it) } ?: entry.previous
            entries.update { list -> list.map { if (it == entry) it.copy(outcome = ScanOutcome.RECLAIMED, previous = before) else it } }
        }
    }

    /** Vraag beantwoord met "niet in bezit laten". */
    fun keepNotOwned() {
        askReclaim.value = null
    }

    /** Vraag beantwoord met "laten staan". */
    fun keepWhereItIs() {
        askMove.value = null
    }

    private fun record(entry: ScannedEntry) {
        entries.update { list -> list.filterNot { it.isbn13 == entry.isbn13 } + entry }
        _signals.trySend(
            when (entry.outcome) {
                ScanOutcome.ADDED, ScanOutcome.MOVED, ScanOutcome.WISHLIST_ADDED, ScanOutcome.ON_WISHLIST -> ScanSignal.ADDED
                else -> ScanSignal.ALREADY_PRESENT
            },
        )
    }

    // --- Cover-herkenning ---

    private var coverLines: List<CoverLine> = emptyList()

    fun onCoverCaptured(image: ImageProxy, frame: Box, viewWidth: Float, viewHeight: Float) {
        // ATOMIC + NonCancellable: het camerabeeld wordt altijd verwerkt en gesloten (zie CoverViewModel).
        viewModelScope.launch(start = CoroutineStart.ATOMIC) {
            val photo = withContext(Dispatchers.Default + NonCancellable) { CoverImages.cropToFrame(image, frame, viewWidth, viewHeight) }
            coverSearch.value = CoverSearchState.Working(photo, "Tekst lezen…")
            coverLines = runCatching { CoverRecognizer.recognize(photo) }.getOrDefault(emptyList())
            val query = CoverText.searchText(coverLines)
            if (query.isBlank()) {
                coverSearch.value = CoverSearchState.Results(photo, "", emptyList())
            } else {
                search(photo, query, CoverText.searchWords(coverLines, query))
            }
        }
    }

    /** Opnieuw zoeken met een aangepaste zoektekst. */
    fun searchAgain(query: String) {
        val photo = coverSearch.value?.photo ?: return
        viewModelScope.launch { search(photo, query, CoverText.keywords(query).sortedByDescending { it.length }.take(3)) }
    }

    private suspend fun search(photo: Bitmap, query: String, words: List<String>) {
        coverSearch.value = CoverSearchState.Working(photo, "Zoeken naar \"$query\"…")
        val candidates = runCatching { bookSearch.search(query, words) }.getOrDefault(emptyList())
        coverSearch.value = CoverSearchState.Results(photo, query, candidates)
    }

    /** Gekozen kandidaat toevoegen; de foto wordt de cover van een nieuw boek. */
    fun pick(candidate: SearchCandidate) {
        val photo = coverSearch.value?.photo ?: return
        coverSearch.value = null
        viewModelScope.launch {
            val isbn = candidate.isbn13
            val entry = if (isbn != null) {
                addIsbn(isbn)
            } else {
                val bookId = repository.save(0, BookDraft(title = candidate.title, subtitle = candidate.subtitle.orEmpty(), authors = candidate.authors.ifEmpty { listOf("") }, publishedYear = candidate.year?.toString().orEmpty()))
                if (wishlist.value) {
                    repository.markWishlist(bookId)
                    ScannedEntry("boek-$bookId", bookId, ScanOutcome.WISHLIST_ADDED).also(::record)
                } else {
                    shelfId.value?.let { locations.moveBook(bookId, it) }
                    ScannedEntry("boek-$bookId", bookId, ScanOutcome.ADDED).also(::record)
                }
            }
            if (entry.outcome == ScanOutcome.ADDED || entry.outcome == ScanOutcome.WISHLIST_ADDED) repository.setCoverFile(entry.bookId, coverStore.save(photo))
        }
    }

    fun closeCoverSearch() {
        coverSearch.value = null
    }

    /**
     * Voor "zelf invoeren": een voorstel voor titel en auteur (de gebruiker past aan), en de foto
     * alvast bewaard zodat die de cover van het nieuwe boek wordt.
     */
    suspend fun manualGuess(): Triple<String, String, String?> {
        val photo = coverSearch.value?.photo
        val (title, author) = CoverText.guessTitleAndAuthor(coverLines)
        return Triple(title, author, photo?.let { coverStore.save(it) })
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
