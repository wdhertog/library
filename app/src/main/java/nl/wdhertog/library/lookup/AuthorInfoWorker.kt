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
 * Haalt op de achtergrond bij Wikidata op wat we van auteurs willen weten (land, levensjaren), ook voor
 * auteurs waarvan je de pagina nooit opende: nodig voor de badges per land. Eén auteur per seconde
 * (de limiet van Wikidata, zie LookupHttp); alleen de naam en de Wikidata-id's van de werken gaan mee.
 * Een auteur die Wikidata niet kent, wordt niet opnieuw gevraagd (tot "Opnieuw opzoeken").
 */
class AuthorInfoWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val authors = (applicationContext as LibraryApp).container.authorRepository
        if (!authors.mayAskWikidata()) return Result.success()
        while (!isStopped) {
            val pending = authors.withoutInfo(limit = 20)
            if (pending.isEmpty()) return Result.success()
            for (id in pending) {
                if (isStopped) return Result.success()
                // false = Wikidata nu niet bereikbaar: later verder.
                if (!authors.refreshInfo(id)) return Result.retry()
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "author-info"

        /** Bij het starten van de app; doet niets als alle auteurs al zijn nagevraagd. */
        fun schedule(context: Context, wifiOnly: Boolean) {
            val request = OneTimeWorkRequestBuilder<AuthorInfoWorker>()
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
