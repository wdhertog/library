package nl.wdhertog.library.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import nl.wdhertog.library.data.repository.BookListItem
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.data.repository.ScanAddResult
import nl.wdhertog.library.lookup.EnrichScheduler
import nl.wdhertog.library.lookup.EnrichmentService
import nl.wdhertog.library.scan.Isbn

data class ScannedEntry(val isbn13: String, val bookId: Long, val alreadyPresent: Boolean)

data class SessionRow(val entry: ScannedEntry, val item: BookListItem?)

class ScanViewModel(
    private val repository: BookRepository,
    private val enrichment: EnrichmentService,
    private val scheduler: EnrichScheduler,
    /** Scope die het scherm overleeft, zodat opzoeken doorloopt als je het scanscherm sluit. */
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val entries = MutableStateFlow<List<ScannedEntry>>(emptyList())
    private val lastSeen = mutableMapOf<String, Long>()
    private val inFlight = mutableSetOf<String>()

    private val _signals = Channel<ScanSignal>(Channel.CONFLATED)
    val signals = _signals.receiveAsFlow()

    val torchOn = MutableStateFlow(false)

    /** Nieuwste scan bovenaan; titels verschijnen vanzelf zodra ze zijn opgezocht. */
    val session: StateFlow<List<SessionRow>> =
        combine(entries, repository.observeLibrary()) { scanned, library ->
            val byId = library.associateBy { it.book.id }
            scanned.asReversed().map { SessionRow(it, byId[it.bookId]) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Wordt per cameraframe aangeroepen met elke herkende EAN-13. */
    fun onBarcode(raw: String) {
        val now = System.currentTimeMillis()
        val previous = lastSeen[raw]
        lastSeen[raw] = now
        // Dezelfde code staat meerdere frames in beeld; alleen de eerste keer telt.
        if (previous != null && now - previous < REPEAT_WINDOW_MS) return

        val isbn13 = Isbn.toIsbn13(raw)?.takeIf { it.startsWith("978") || it.startsWith("979") }
        if (isbn13 == null) {
            _signals.trySend(ScanSignal.NOT_AN_ISBN)
            return
        }
        if (entries.value.any { it.isbn13 == isbn13 } || !inFlight.add(isbn13)) return

        viewModelScope.launch {
            val result = repository.addScannedIsbn(isbn13)
            inFlight.remove(isbn13)
            val present = result is ScanAddResult.AlreadyPresent
            entries.update { it + ScannedEntry(isbn13, result.bookId, present) }
            _signals.trySend(if (present) ScanSignal.ALREADY_PRESENT else ScanSignal.ADDED)
            if (!present) {
                appScope.launch {
                    try {
                        enrichment.quickLookup(result.bookId)
                    } finally {
                        scheduler.schedule()
                    }
                }
            }
        }
    }

    fun toggleTorch() = torchOn.update { !it }

    private companion object {
        const val REPEAT_WINDOW_MS = 3_000L
    }
}
