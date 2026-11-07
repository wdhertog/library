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

/**
 * Zet op de achtergrond genres als tags bij boeken die er nog geen hebben gekregen (zie GenreService).
 * Per ronde één Wikidata-vraag voor tot 40 werken, plus Open Library voor werken zonder Wikidata-genre.
 */
class GenreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val genres = (applicationContext as LibraryApp).container.genreService
        while (!isStopped) {
            val done = genres.runBatch() ?: return Result.retry() // bron nu niet bereikbaar
            if (done == 0) return Result.success()
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "genres"

        /** Bij het starten van de app en na het opzoeken van nieuwe boeken; doet niets als alles al genres heeft. */
        fun schedule(context: Context, wifiOnly: Boolean) {
            val request = OneTimeWorkRequestBuilder<GenreWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
