package nl.wdhertog.library.ui.backup

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.provider.DocumentsContract
import androidx.work.WorkManager
import nl.wdhertog.library.data.backup.AutoBackupSettings
import nl.wdhertog.library.data.backup.AutoBackupWorker
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.wdhertog.library.data.backup.BackupFormat
import nl.wdhertog.library.data.backup.BackupManifest
import nl.wdhertog.library.data.backup.BackupService
import nl.wdhertog.library.data.backup.CsvExport
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.data.repository.LoanRepository
import nl.wdhertog.library.data.repository.LocationRepository
import nl.wdhertog.library.data.repository.LocationTree
import nl.wdhertog.library.data.repository.ReadingRepository
import nl.wdhertog.library.lookup.EnrichScheduler
import java.io.File
import java.time.Instant

data class AutoBackupState(val folderName: String?, val lastAuto: Instant?, val error: String?, val running: Boolean)

sealed interface BackupUiState {
    data object Idle : BackupUiState
    data class Busy(val message: String) : BackupUiState
    data class ConfirmRestore(val manifest: BackupManifest, val file: File) : BackupUiState
}

class BackupViewModel(
    private val backup: BackupService,
    private val books: BookRepository,
    private val locations: LocationRepository,
    private val reading: ReadingRepository,
    private val loans: LoanRepository,
    private val scheduler: EnrichScheduler,
    private val prefs: SharedPreferences,
    private val app: Application,
) : ViewModel() {

    private val auto = AutoBackupSettings(app)
    private val running = MutableStateFlow(false)
    val autoState = MutableStateFlow(readAuto())

    init {
        // Klaar met "nu een back-up": status opnieuw lezen.
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(AutoBackupWorker.NOW_WORK_NAME).collect { infos ->
                running.value = infos.any { !it.state.isFinished }
                refresh()
            }
        }
    }

    fun refresh() {
        autoState.value = readAuto()
        lastBackup.value = prefs.getLong(AutoBackupSettings.KEY_LAST, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
    }

    private fun readAuto() = AutoBackupState(
        folderName = auto.folder?.let(::folderName),
        lastAuto = if (auto.folder != null) auto.lastBackup else null,
        error = auto.lastError.takeIf { auto.folder != null },
        running = running.value,
    )

    /** Map gekozen: toegang onthouden, wekelijks plannen en meteen de eerste back-up maken. */
    fun chooseFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        auto.folder?.let { old -> if (old != uri) runCatching { app.contentResolver.releasePersistableUriPermission(old, flags) } }
        app.contentResolver.takePersistableUriPermission(uri, flags)
        auto.folder = uri
        auto.lastError = null
        AutoBackupWorker.schedule(app, replace = true)
        AutoBackupWorker.runNow(app)
        refresh()
    }

    fun backupNow() = AutoBackupWorker.runNow(app)

    fun disableAuto() {
        auto.folder?.let { runCatching { app.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        auto.folder = null
        auto.lastError = null
        AutoBackupWorker.cancel(app)
        refresh()
    }

    private fun folderName(tree: Uri): String = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        app.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').ifEmpty { "gekozen map" }

    val state = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val lastBackup = MutableStateFlow(prefs.getLong(KEY_LAST, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli))

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun createBackup(resolver: ContentResolver, uri: Uri) = work("Back-up maken…") {
        val manifest = resolver.openOutputStream(uri)?.use { backup.createBackup(it) } ?: error("Kan niet schrijven")
        val now = Instant.now()
        prefs.edit().putLong(KEY_LAST, now.toEpochMilli()).apply()
        lastBackup.value = now
        "Back-up gemaakt: ${manifest.books} boeken, ${manifest.covers} eigen covers"
    }

    fun exportCsv(resolver: ContentResolver, uri: Uri) = work("Exporteren…") {
        val library = books.observeLibrary().first()
        val index = LocationTree.index(locations.observeTree().first())
        val sessions = reading.observeSessions().first().groupBy { it.bookId }
        val lent = loans.observeActiveLoans().first().associate { it.bookId to it.personName }
        val csv = CsvExport.write(library, { index[it]?.pathLabel }, sessions, lent)
        withContext(Dispatchers.IO) { resolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } ?: error("Kan niet schrijven") }
        "${library.size} boeken geëxporteerd"
    }

    fun inspect(resolver: ContentResolver, uri: Uri) {
        state.value = BackupUiState.Busy("Back-up controleren…")
        viewModelScope.launch {
            val input = resolver.openInputStream(uri)
            if (input == null) {
                state.value = BackupUiState.Idle
                _messages.send("Bestand niet te openen")
                return@launch
            }
            val (file, check) = backup.inspect(input)
            when (check) {
                is BackupFormat.Check.Ok -> state.value = BackupUiState.ConfirmRestore(check.manifest, file)
                is BackupFormat.Check.Problem -> {
                    file.delete()
                    state.value = BackupUiState.Idle
                    _messages.send(check.message)
                }
            }
        }
    }

    /** Automatische kopieën in de app (vóór terugzetten, vóór samenvoegen). */
    val safetyCopies = MutableStateFlow(backup.safetyCopies())

    fun inspectCopy(file: File) {
        state.value = BackupUiState.Busy("Back-up controleren…")
        viewModelScope.launch {
            val (temp, check) = backup.inspect(file.inputStream())
            when (check) {
                is BackupFormat.Check.Ok -> state.value = BackupUiState.ConfirmRestore(check.manifest, temp)
                is BackupFormat.Check.Problem -> {
                    temp.delete()
                    state.value = BackupUiState.Idle
                    _messages.send(check.message)
                }
            }
        }
    }

    fun cancelRestore() {
        (state.value as? BackupUiState.ConfirmRestore)?.file?.delete()
        state.value = BackupUiState.Idle
    }

    /** Zet terug en start de app opnieuw met de teruggezette bibliotheek. */
    fun restore(restartApp: () -> Unit) {
        val confirm = state.value as? BackupUiState.ConfirmRestore ?: return
        state.value = BackupUiState.Busy("Terugzetten…")
        viewModelScope.launch {
            try {
                scheduler.cancel()
                backup.restore(confirm.file)
                restartApp()
            } catch (e: Exception) {
                state.value = BackupUiState.Idle
                _messages.send("Terugzetten mislukt: ${e.message}")
            }
        }
    }

    private fun work(busy: String, action: suspend () -> String) {
        state.value = BackupUiState.Busy(busy)
        viewModelScope.launch {
            val message = try {
                action()
            } catch (e: Exception) {
                "Mislukt: ${e.message}"
            }
            state.value = BackupUiState.Idle
            _messages.send(message)
        }
    }

    companion object {
        const val PREFS = AutoBackupSettings.PREFS
        private const val KEY_LAST = AutoBackupSettings.KEY_LAST
    }
}
