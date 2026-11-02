package nl.wdhertog.library.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import nl.wdhertog.library.LibraryApp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Namen en opruimen van automatische back-ups; los van Android zodat het te testen is. */
object AutoBackupNames {
    const val PREFIX = "bibliotheek-auto-"
    const val KEEP = 5

    fun name(date: LocalDate) = "$PREFIX$date.zip"

    /** Oudste automatische back-ups boven de [keep] nieuwste; andere bestanden blijven altijd staan. */
    fun toDelete(names: List<String>, keep: Int = KEEP): List<String> =
        names.filter { it.startsWith(PREFIX) && it.endsWith(".zip") }.sortedDescending().drop(keep)
}

/** Instellingen en status van de automatische back-up (gedeeld met het scherm). */
class AutoBackupSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var folder: Uri?
        get() = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)
        set(value) = prefs.edit().putString(KEY_FOLDER, value?.toString()).apply()

    var lastBackup: Instant?
        get() = prefs.getLong(KEY_LAST, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        set(value) = prefs.edit().putLong(KEY_LAST, value?.toEpochMilli() ?: 0L).apply()

    var lastError: String?
        get() = prefs.getString(KEY_ERROR, null)
        set(value) = prefs.edit().putString(KEY_ERROR, value).apply()

    companion object {
        /** Dezelfde voorkeuren als het back-upscherm, zodat "laatste back-up" voor beide geldt. */
        const val PREFS = "backup"
        private const val KEY_FOLDER = "auto-folder"
        const val KEY_LAST = "last-backup"
        private const val KEY_ERROR = "auto-error"
    }
}

/** Schrijft wekelijks een back-up naar de gekozen map en ruimt oude automatische back-ups op. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = lock.withLock { backup() }

    private suspend fun backup(): Result {
        val settings = AutoBackupSettings(applicationContext)
        val tree = settings.folder ?: return Result.success()
        val resolver = applicationContext.contentResolver
        return try {
            val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val existing = children(tree)
            val name = AutoBackupNames.name(LocalDate.now())
            // Vandaag al een automatische back-up: die vervangen.
            existing[name]?.let { DocumentsContract.deleteDocument(resolver, it) }
            val file = DocumentsContract.createDocument(resolver, dir, "application/zip", name) ?: error("Kan geen bestand maken in de map")
            resolver.openOutputStream(file)?.use { (applicationContext as LibraryApp).container.backupService.createBackup(it) }
                ?: error("Kan niet schrijven in de map")
            AutoBackupNames.toDelete(children(tree).keys.toList()).forEach { old ->
                children(tree)[old]?.let { DocumentsContract.deleteDocument(resolver, it) }
            }
            settings.lastBackup = Instant.now()
            settings.lastError = null
            Result.success()
        } catch (e: Exception) {
            settings.lastError = when (e) {
                is SecurityException -> "Geen toegang meer tot de map. Kies de map opnieuw."
                else -> e.message ?: e.javaClass.simpleName
            }
            Result.failure()
        }
    }

    /** Bestandsnaam → document-URI van alles in de map. */
    private fun children(tree: Uri): Map<String, Uri> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val result = mutableMapOf<String, Uri>()
        applicationContext.contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getString(1)] = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
            }
        }
        return result
    }

    companion object {
        private const val WORK_NAME = "auto-backup"

        /** De wekelijkse en de "nu"-back-up mogen elkaars bestanden niet tegelijk opruimen. */
        private val lock = Mutex()

        fun schedule(context: Context, replace: Boolean = false) {
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(7, TimeUnit.DAYS)
                // De eerste back-up maakt runNow; de wekelijkse begint pas over een week.
                .setInitialDelay(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)

        const val NOW_WORK_NAME = "auto-backup-now"

        /** Meteen een back-up naar de gekozen map, bijv. direct na het kiezen. */
        fun runNow(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<AutoBackupWorker>().build())
        }
    }
}
