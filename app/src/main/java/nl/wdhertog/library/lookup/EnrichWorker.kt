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
        while (!isStopped) {
            val pending = repository.booksAwaitingEnrichment(limit = 20)
            if (pending.isEmpty()) return Result.success()
            for (bookId in pending) {
                if (isStopped) return Result.success()
                try {
                    enrichment.enrich(bookId, refresh = false)
                } catch (_: LookupException) {
                    return Result.retry() // Geen netwerk of bronnen onbereikbaar: later verder.
                }
            }
        }
        return Result.success()
    }
}

class EnrichScheduler(private val context: Context) {

    /** Start (of verlengt) de achtergrondronde. Aanroepen na elke scan of import is goedkoop. */
    fun schedule() {
        val request = OneTimeWorkRequestBuilder<EnrichWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // APPEND: een boek dat binnenkomt terwijl de vorige ronde net klaar is, wordt toch opgepakt.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val WORK_NAME = "enrich-library"
    }
}
