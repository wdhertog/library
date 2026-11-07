package nl.wdhertog.library.lookup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import nl.wdhertog.library.LibraryApp
import java.util.concurrent.TimeUnit

/** Werkt alle boeken met status NEW/QUICK één voor één bij, tot de wachtrij leeg is. */
class EnrichWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LibraryApp).container
        val repository = container.bookRepository
        val enrichment = container.enrichmentService
        var failedInARow = 0
        while (!isStopped) {
            val pending = repository.booksAwaitingEnrichment(limit = 20)
            if (pending.isEmpty()) {
                // Nieuwe boeken zijn opgezocht: nu ook hun genres.
                GenreWorker.schedule(applicationContext, wifiOnly = container.settings.wifiOnly.value)
                return Result.success()
            }
            for (bookId in pending) {
                if (isStopped) return Result.success()
                try {
                    enrichment.enrich(bookId, refresh = false)
                    failedInARow = 0
                } catch (_: LookupException) {
                    // Geen netwerk of bronnen onbereikbaar: later verder. Lukt het na een paar pogingen
                    // nog steeds niet bij dit ene boek, dan ligt het aan het boek (bijv. een bron die er
                    // steeds een foutcode op geeft): overslaan, anders blijft de hele wachtrij erachter
                    // hangen. "Bibliotheek bijwerken" probeert het later opnieuw.
                    if (runAttemptCount < SKIP_AFTER_ATTEMPTS || ++failedInARow > 1) return Result.retry()
                    repository.markEnrichFailed(bookId)
                }
            }
        }
        return Result.success()
    }

    private companion object {
        const val SKIP_AFTER_ATTEMPTS = 3
    }
}

class EnrichScheduler(
    private val context: Context,
    private val wifiOnly: () -> Boolean = { false },
    /** Instelling "Automatisch opzoeken"; uit = alleen als de gebruiker erom vraagt ([manual]). */
    private val automatic: () -> Boolean = { true },
) {

    /**
     * Start (of verlengt) de achtergrondronde. Aanroepen na elke scan of import is goedkoop.
     * [manual]: de gebruiker vraagt erom ("Bibliotheek bijwerken"), dus ook als automatisch opzoeken uit staat.
     */
    fun schedule(manual: Boolean = false) {
        if (!manual && !automatic()) return
        val request = OneTimeWorkRequestBuilder<EnrichWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // APPEND: een boek dat binnenkomt terwijl de vorige ronde net klaar is, wordt toch opgepakt.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Stoppen, bijv. vlak voor het terugzetten van een back-up. */
    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private companion object {
        const val WORK_NAME = "enrich-library"
    }
}
