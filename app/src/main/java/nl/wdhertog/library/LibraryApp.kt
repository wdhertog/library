package nl.wdhertog.library

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.wdhertog.library.covers.CoverStore
import nl.wdhertog.library.data.backup.AutoBackupSettings
import nl.wdhertog.library.data.backup.AutoBackupWorker
import nl.wdhertog.library.data.backup.BackupService
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.data.repository.LoanRepository
import nl.wdhertog.library.data.repository.LocationRepository
import nl.wdhertog.library.data.repository.ReadingRepository
import nl.wdhertog.library.data.repository.SeriesRepository
import nl.wdhertog.library.importer.ImportProfiles
import nl.wdhertog.library.importer.ImportService
import nl.wdhertog.library.lookup.EnrichScheduler
import nl.wdhertog.library.lookup.EnrichmentService
import nl.wdhertog.library.lookup.LookupHttp
import nl.wdhertog.library.lookup.SourceRouter
import nl.wdhertog.library.lookup.search.BookSearch
import nl.wdhertog.library.lookup.sources.BnfSource
import nl.wdhertog.library.lookup.sources.DnbSource
import nl.wdhertog.library.lookup.sources.KbSource
import nl.wdhertog.library.lookup.sources.OpenLibrarySource
import nl.wdhertog.library.lookup.sources.WikidataSource
import nl.wdhertog.library.reminders.LoanReminderWorker

/** Handmatige dependency injection: één container voor de hele app. */
class AppContainer(val app: Application) {
    private val databaseHolder = lazy { LibraryDatabase.create(app) }
    private val database by databaseHolder
    val backupService by lazy {
        BackupService(app, { database }, closeDatabase = { if (databaseHolder.isInitialized()) database.close() })
    }
    val bookRepository by lazy { BookRepository(database, coverStore) }
    val coverStore by lazy { CoverStore(app) }
    val locationRepository by lazy { LocationRepository(database) }
    val loanRepository by lazy { LoanRepository(database, bookRepository) }
    val readingRepository by lazy { ReadingRepository(database) }
    val seriesRepository by lazy { SeriesRepository(database, bookRepository) }
    val importProfiles by lazy { ImportProfiles(app) }
    val importService by lazy { ImportService(database, bookRepository, locationRepository) }

    /** Voor werk dat een scherm moet overleven, zoals de snelle lookup na een scan. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val http by lazy { LookupHttp() }
    val enrichmentService by lazy {
        EnrichmentService(
            repository = bookRepository,
            router = SourceRouter(KbSource(http), OpenLibrarySource(http), DnbSource(http), BnfSource(http)),
            wikidata = WikidataSource(http),
        )
    }
    val enrichScheduler = EnrichScheduler(app)
    val bookSearch by lazy { BookSearch(http) }
}

class LibraryApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Pak eventueel onderbroken werk weer op; doet niets als de wachtrij leeg is.
        container.enrichScheduler.schedule()
        LoanReminderWorker.schedule(this)
        if (AutoBackupSettings(this).folder != null) AutoBackupWorker.schedule(this)
    }
}
