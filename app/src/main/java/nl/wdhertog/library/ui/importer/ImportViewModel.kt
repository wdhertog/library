package nl.wdhertog.library.ui.importer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.wdhertog.library.data.db.ImportRun
import nl.wdhertog.library.importer.HirtParser
import nl.wdhertog.library.importer.ImportDecision
import nl.wdhertog.library.importer.ImportMatcher
import nl.wdhertog.library.importer.ImportResult
import nl.wdhertog.library.importer.ImportService
import nl.wdhertog.library.importer.ImportedBook
import nl.wdhertog.library.importer.Match
import nl.wdhertog.library.lookup.EnrichScheduler

/** Wat er met een boek gebeurt dat al in je bibliotheek staat. */
enum class MergeChoice(val label: String) {
    FILL("Aanvullen"),
    OVERWRITE("Bestand gaat voor"),
    SKIP("Overslaan"),
    /** Alleen bij twijfelgevallen (ander ISBN): toch als apart boek toevoegen. */
    ADD("Apart toevoegen"),
}

data class ReviewItem(val book: ImportedBook, val match: Match?)

sealed interface ImportUiState {
    data object Start : ImportUiState
    data object Reading : ImportUiState
    data class Error(val message: String) : ImportUiState

    data class Review(
        val source: String,
        val fileName: String?,
        val items: List<ReviewItem>,
        val goals: Map<Int, Int>,
        val skippedInFile: Int,
        /** Keuze voor alle bestaande boeken samen. */
        val defaultChoice: MergeChoice = MergeChoice.FILL,
        /** Afwijkende keuze per boek (index in [items]). */
        val overrides: Map<Int, MergeChoice> = emptyMap(),
    ) : ImportUiState {
        val newCount get() = items.count { it.match == null }
        val matchedCount get() = items.count { it.match?.certain == true }
        val possibleCount get() = items.count { it.match?.certain == false }

        /** Twijfelgevallen worden standaard een apart boek; de algemene keuze geldt voor zekere koppelingen. */
        fun defaultFor(index: Int) = if (items[index].match?.certain == false) MergeChoice.ADD else defaultChoice
        fun choiceFor(index: Int) = overrides[index] ?: defaultFor(index)
    }

    data class Running(val done: Int, val total: Int) : ImportUiState
    data class Done(val result: ImportResult) : ImportUiState
}

class ImportViewModel(
    private val service: ImportService,
    private val scheduler: EnrichScheduler,
    /** Importeren loopt door als je het scherm verlaat. */
    private val appScope: CoroutineScope,
) : ViewModel() {

    val state = MutableStateFlow<ImportUiState>(ImportUiState.Start)

    val runs: StateFlow<List<ImportRun>> = service.observeRuns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openHirt(resolver: ContentResolver, uri: Uri) {
        state.value = ImportUiState.Reading
        viewModelScope.launch {
            state.value = try {
                val (text, name) = withContext(Dispatchers.IO) { readText(resolver, uri) to displayName(resolver, uri) }
                val export = withContext(Dispatchers.Default) { HirtParser.parse(text) }
                val matches = ImportMatcher.match(export.books, service.existingBooks())
                ImportUiState.Review(
                    source = HirtParser.SOURCE,
                    fileName = name,
                    items = export.books.zip(matches, ::ReviewItem),
                    goals = export.goals,
                    skippedInFile = export.skipped,
                )
            } catch (e: HirtParser.FormatException) {
                ImportUiState.Error(e.message ?: "Onbekend formaat")
            } catch (e: Exception) {
                ImportUiState.Error("Bestand niet te lezen: ${e.message}")
            }
        }
    }

    /** Geldt voor alle zekere koppelingen; eigen keuzes bij twijfelgevallen blijven staan. */
    fun setDefaultChoice(choice: MergeChoice) = updateReview { review ->
        review.copy(defaultChoice = choice, overrides = review.overrides.filterKeys { review.items[it].match?.certain == false })
    }

    fun setChoice(index: Int, choice: MergeChoice) = updateReview { review ->
        review.copy(overrides = if (choice == review.defaultFor(index)) review.overrides - index else review.overrides + (index to choice))
    }

    fun startImport() {
        val review = state.value as? ImportUiState.Review ?: return
        val items = review.items.mapIndexed { index, item ->
            val decision = when {
                item.match == null -> ImportDecision.Add
                else -> when (review.choiceFor(index)) {
                    MergeChoice.FILL -> ImportDecision.Merge(item.match.existing.id, overwrite = false)
                    MergeChoice.OVERWRITE -> ImportDecision.Merge(item.match.existing.id, overwrite = true)
                    MergeChoice.SKIP -> ImportDecision.Skip
                    MergeChoice.ADD -> ImportDecision.Add
                }
            }
            item.book to decision
        }
        state.value = ImportUiState.Running(0, items.size)
        appScope.launch {
            val result = service.run(review.source, review.fileName, items, review.goals) { done ->
                state.value = ImportUiState.Running(done, items.size)
            }
            scheduler.schedule()
            state.value = ImportUiState.Done(result)
        }
    }

    fun undo(runId: Long) {
        appScope.launch {
            service.undo(runId)
            if ((state.value as? ImportUiState.Done)?.result?.runId == runId) state.value = ImportUiState.Start
        }
    }

    fun reset() {
        state.value = ImportUiState.Start
    }

    private fun updateReview(change: (ImportUiState.Review) -> ImportUiState.Review) =
        state.update { (it as? ImportUiState.Review)?.let(change) ?: it }

    private fun readText(resolver: ContentResolver, uri: Uri): String =
        resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("leeg")

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
