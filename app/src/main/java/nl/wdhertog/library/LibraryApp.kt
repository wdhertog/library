package nl.wdhertog.library

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.lookup.EnrichScheduler
import nl.wdhertog.library.lookup.EnrichmentService
import nl.wdhertog.library.lookup.LookupHttp
import nl.wdhertog.library.lookup.SourceRouter
import nl.wdhertog.library.lookup.sources.BnfSource
import nl.wdhertog.library.lookup.sources.DnbSource
import nl.wdhertog.library.lookup.sources.KbSource
import nl.wdhertog.library.lookup.sources.OpenLibrarySource
import nl.wdhertog.library.lookup.sources.WikidataSource

/** Handmatige dependency injection: één container voor de hele app. */
class AppContainer(app: Application) {
    private val database by lazy { LibraryDatabase.create(app) }
    val bookRepository by lazy { BookRepository(database) }

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
}

class LibraryApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Pak eventueel onderbroken werk weer op; doet niets als de wachtrij leeg is.
        container.enrichScheduler.schedule()
    }
}
