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
import nl.wdhertog.library.importer.GoodreadsParser
import nl.wdhertog.library.importer.HirtParser
import nl.wdhertog.library.importer.XlsxReader
import nl.wdhertog.library.importer.TableImporter
import nl.wdhertog.library.importer.Table
import nl.wdhertog.library.importer.Sheet
import nl.wdhertog.library.importer.ImportProfiles
import nl.wdhertog.library.importer.CsvReader
import nl.wdhertog.library.importer.ColumnTarget
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

    /** CSV/Excel: per kolom kiezen waar hij terechtkomt. */
    data class Mapping(
        val fileName: String?,
        val sheets: List<Sheet>,
        val sheetIndex: Int,
        val targets: List<ColumnTarget>,
    ) : ImportUiState {
        val table get() = sheets[sheetIndex].table
        val hasTitle get() = ColumnTarget.TITLE in targets
    }

    data class Review(
        val source: String,
        val fileName: String?,
        val items: List<ReviewItem>,
        val goals: Map<Int, Int>,
        /** Bijv. "336 boeken in je bezit (364 andere overgeslagen…)". */
        val summary: String,
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
    private val profiles: ImportProfiles,
    private val scheduler: EnrichScheduler,
    /** Importeren loopt door als je het scherm verlaat. */
    private val appScope: CoroutineScope,
) : ViewModel() {

    val state = MutableStateFlow<ImportUiState>(ImportUiState.Start)

    val runs: StateFlow<List<ImportRun>> = service.observeRuns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Have I Read That: ook boeken die je niet (meer) hebt, en e-books en luisterboeken. */
    val hirtIncludeAll = MutableStateFlow(false)

    fun openHirt(resolver: ContentResolver, uri: Uri) {
        val includeAll = hirtIncludeAll.value
        state.value = ImportUiState.Reading
        viewModelScope.launch {
            state.value = try {
                val (text, name) = withContext(Dispatchers.IO) { readText(resolver, uri) to displayName(resolver, uri) }
                val export = withContext(Dispatchers.Default) { HirtParser.parse(text, includeAll) }
                val matches = ImportMatcher.match(export.books, service.existingBooks())
                ImportUiState.Review(
                    source = HirtParser.SOURCE,
                    fileName = name,
                    items = export.books.zip(matches, ::ReviewItem),
                    goals = export.goals,
                    summary = if (includeAll) {
                        val notOwned = export.books.count { !it.owned }
                        "${export.books.size} boeken: ${export.books.size - notOwned} in je bezit, $notOwned niet (meer) in bezit"
                    } else {
                        "${export.books.size} boeken in je bezit" +
                            if (export.skipped > 0) " (${export.skipped} andere overgeslagen: niet in bezit, e-book of luisterboek)" else ""
                    },
                )
            } catch (e: HirtParser.FormatException) {
                ImportUiState.Error(e.message ?: "Onbekend formaat")
            } catch (e: Exception) {
                ImportUiState.Error("Bestand niet te lezen: ${e.message}")
            }
        }
    }

    /** CSV of Excel: eerst kolommen koppelen. */
    fun openTable(resolver: ContentResolver, uri: Uri) {
        state.value = ImportUiState.Reading
        viewModelScope.launch {
            state.value = try {
                val (bytes, name) = withContext(Dispatchers.IO) {
                    (resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("leeg")) to displayName(resolver, uri)
                }
                val sheets = withContext(Dispatchers.Default) {
                    // Een xlsx is een zip-bestand en begint met "PK".
                    if (bytes.size > 1 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                        XlsxReader.read(bytes)
                    } else {
                        listOf(Sheet(name.orEmpty(), CsvReader.read(bytes)))
                    }
                }.filter { it.table.header.isNotEmpty() }
                val goodreads = sheets.singleOrNull()?.table?.takeIf { GoodreadsParser.matches(it.header) }
                if (sheets.isEmpty()) {
                    ImportUiState.Error("Geen gegevens gevonden in dit bestand")
                } else if (goodreads != null) {
                    // Een export van Goodreads: kolommen zijn bekend, meteen naar de controle.
                    val export = withContext(Dispatchers.Default) { GoodreadsParser.parse(goodreads) }
                    val matches = ImportMatcher.match(export.books, service.existingBooks())
                    val notOwned = export.books.count { !it.owned }
                    ImportUiState.Review(
                        source = GoodreadsParser.SOURCE,
                        fileName = name,
                        items = export.books.zip(matches, ::ReviewItem),
                        goals = emptyMap(),
                        summary = "Goodreads: ${export.books.size} boeken" + when {
                            export.ownedMarked == 0 -> " (geen als „in bezit” gemarkeerd: allemaal als eigen boek)"
                            else -> ", ${export.books.size - notOwned} in bezit, $notOwned niet (meer) in bezit"
                        },
                    )
                } else {
                    ImportUiState.Mapping(name, sheets, 0, targetsFor(sheets.first().table))
                }
            } catch (e: XlsxReader.FormatException) {
                ImportUiState.Error(e.message ?: "Onbekend formaat")
            } catch (e: Exception) {
                ImportUiState.Error("Bestand niet te lezen: ${e.message}")
            }
        }
    }

    fun selectSheet(index: Int) = updateMapping { it.copy(sheetIndex = index, targets = targetsFor(it.sheets[index].table)) }

    fun setTarget(column: Int, target: ColumnTarget) = updateMapping { mapping ->
        mapping.copy(targets = mapping.targets.toMutableList().also { it[column] = target })
    }

    fun confirmMapping() {
        val mapping = state.value as? ImportUiState.Mapping ?: return
        if (!mapping.hasTitle) return
        profiles.save(mapping.table.header, mapping.targets)
        state.value = ImportUiState.Reading
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { TableImporter.toBooks(mapping.table, mapping.targets) }
            val matches = ImportMatcher.match(result.books, service.existingBooks())
            val sheet = mapping.sheets[mapping.sheetIndex].name.takeIf { mapping.sheets.size > 1 }
            state.value = ImportUiState.Review(
                source = TABLE_SOURCE,
                fileName = listOfNotNull(mapping.fileName, sheet).joinToString(" · ").ifEmpty { null },
                items = result.books.zip(matches, ::ReviewItem),
                goals = emptyMap(),
                summary = "${result.books.size} boeken" + if (result.skipped > 0) " (${result.skipped} rijen zonder titel overgeslagen)" else "",
            )
        }
    }

    /** Eerder opgeslagen toewijzing voor deze kopregel, anders een gok per kolom. */
    private fun targetsFor(table: Table): List<ColumnTarget> =
        profiles.load(table.header) ?: table.header.mapIndexed { index, header -> ColumnTarget.guess(header, table.column(index)) }

    private fun updateMapping(change: (ImportUiState.Mapping) -> ImportUiState.Mapping) =
        state.update { (it as? ImportUiState.Mapping)?.let(change) ?: it }

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
            try {
                val result = service.run(review.source, review.fileName, items, review.goals) { done ->
                    state.value = ImportUiState.Running(done, items.size)
                }
                state.value = ImportUiState.Done(result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Niet de app laten crashen en niet op "bezig" blijven staan. Wat al was ingelezen, staat
                // bij de eerdere imports en is daar ongedaan te maken.
                android.util.Log.w("ImportViewModel", "Importeren mislukt", e)
                state.value = ImportUiState.Error(
                    "Importeren mislukt: ${e.message ?: e.javaClass.simpleName}. Wat al was ingelezen, kun je hieronder bij de eerdere imports ongedaan maken.",
                )
            } finally {
                scheduler.schedule()
            }
        }
    }

    fun undo(runId: Long) {
        appScope.launch {
            runCatching { service.undo(runId) }.onFailure {
                android.util.Log.w("ImportViewModel", "Ongedaan maken mislukt", it)
                state.value = ImportUiState.Error("Ongedaan maken mislukt: ${it.message}")
                return@launch
            }
            if ((state.value as? ImportUiState.Done)?.result?.runId == runId) state.value = ImportUiState.Start
        }
    }

    fun reset() {
        state.value = ImportUiState.Start
    }

    private fun updateReview(change: (ImportUiState.Review) -> ImportUiState.Review) =
        state.update { (it as? ImportUiState.Review)?.let(change) ?: it }

    private companion object {
        const val TABLE_SOURCE = "Import"
    }

    private fun readText(resolver: ContentResolver, uri: Uri): String =
        resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("leeg")

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
