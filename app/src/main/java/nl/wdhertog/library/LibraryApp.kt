package nl.wdhertog.library

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import nl.wdhertog.library.data.db.ReadingStatus
import nl.wdhertog.library.widget.ReadingWidget
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
import nl.wdhertog.library.data.sync.SyncRepository
import nl.wdhertog.library.importer.ImportProfiles
import nl.wdhertog.library.importer.ImportService
import nl.wdhertog.library.lookup.EnrichScheduler
import nl.wdhertog.library.lookup.EnrichmentService
import nl.wdhertog.library.lookup.LookupHttp
import nl.wdhertog.library.lookup.SourceRouter
import nl.wdhertog.library.lookup.search.BookSearch
import nl.wdhertog.library.lookup.sources.BneSource
import nl.wdhertog.library.lookup.sources.BnfSource
import nl.wdhertog.library.lookup.sources.DnbSource
import nl.wdhertog.library.lookup.sources.KbSource
import nl.wdhertog.library.lookup.sources.OpenLibrarySource
import nl.wdhertog.library.lookup.sources.SbnSource
import nl.wdhertog.library.lookup.sources.WikidataSource
import nl.wdhertog.library.reminders.LoanReminderWorker
import nl.wdhertog.library.util.AppSettings

/** Handmatige dependency injection: één container voor de hele app. */
class AppContainer(val app: Application) {
    private val databaseHolder = lazy { LibraryDatabase.create(app) }
    private val database by databaseHolder
    val backupService by lazy {
        BackupService(app, { database }, closeDatabase = { if (databaseHolder.isInitialized()) database.close() })
    }
    val bookRepository by lazy { BookRepository(database, coverStore, appScope) }
    val coverStore by lazy { CoverStore(app) }
    val locationRepository by lazy { LocationRepository(database) }
    val loanRepository by lazy { LoanRepository(database, bookRepository) }
    val readingRepository by lazy { ReadingRepository(database) }
    val badgeService by lazy {
        nl.wdhertog.library.data.repository.BadgeService(database, bookRepository, readingRepository, seriesRepository)
    }
    val seriesRepository by lazy {
        SeriesRepository(database, bookRepository, WikidataSource(http)) { settings.isSourceEnabled(nl.wdhertog.library.lookup.LookupSource.WIKIDATA) }
    }
    val authorRepository by lazy {
        nl.wdhertog.library.data.repository.AuthorRepository(
            database, WikidataSource(http),
            wikidataEnabled = { settings.isSourceEnabled(nl.wdhertog.library.lookup.LookupSource.WIKIDATA) },
            books = bookRepository,
            openLibrary = OpenLibrarySource(http),
        )
    }
    val syncRepository by lazy { SyncRepository(database) }
    val syncManager by lazy {
        nl.wdhertog.library.sync.SyncManager(
            context = app,
            repo = syncRepository,
            store = { nl.wdhertog.library.data.sync.RoomSyncStore(database, bookRepository) },
            scope = appScope,
            inBackground = { settings.syncInBackground.value },
            backup = {
                java.io.File(app.filesDir, "backups").mkdirs()
                java.io.File(app.filesDir, nl.wdhertog.library.data.backup.BackupService.BEFORE_MERGE).outputStream().use { backupService.createBackup(it) }
            },
            covers = { nl.wdhertog.library.sync.RoomCoverFiles(app, database.syncDao()) },
        )
    }
    val importProfiles by lazy { ImportProfiles(app) }
    val importService by lazy { ImportService(database, bookRepository, locationRepository) }

    /** Voor werk dat een scherm moet overleven, zoals de snelle lookup na een scan. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val http by lazy { LookupHttp() }
    val coverCandidates by lazy { nl.wdhertog.library.lookup.OpenLibraryCovers(http) }
    val genreService by lazy {
        nl.wdhertog.library.data.repository.GenreService(
            database, bookRepository, WikidataSource(http), OpenLibrarySource(http),
            wikidataEnabled = { settings.isSourceEnabled(nl.wdhertog.library.lookup.LookupSource.WIKIDATA) },
            openLibraryEnabled = { settings.isSourceEnabled(nl.wdhertog.library.lookup.LookupSource.OPEN_LIBRARY) },
        )
    }
    val enrichmentService by lazy {
        EnrichmentService(
            repository = bookRepository,
            router = SourceRouter(
                KbSource(http),
                OpenLibrarySource(http),
                DnbSource(http),
                BnfSource(http),
                BneSource(http),
                SbnSource(http),
                enabled = settings::isSourceEnabled,
            ),
            wikidata = WikidataSource(http),
            wikidataEnabled = { settings.isSourceEnabled(nl.wdhertog.library.lookup.LookupSource.WIKIDATA) },
        )
    }
    val settings = AppSettings(app)
    val enrichScheduler = EnrichScheduler(app, wifiOnly = { settings.wifiOnly.value }, automatic = { settings.autoLookup.value })
    val bookSearch by lazy { BookSearch(http, settings::isSourceEnabled) }
}

class LibraryApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Pak eventueel onderbroken werk weer op; doet niets als de wachtrij leeg is.
        container.enrichScheduler.schedule()
        // Land en levensjaren van auteurs (badges per land); alleen als automatisch opzoeken aan staat.
        if (container.settings.autoLookup.value) {
            nl.wdhertog.library.lookup.AuthorInfoWorker.schedule(this, wifiOnly = container.settings.wifiOnly.value)
            nl.wdhertog.library.lookup.GenreWorker.schedule(this, wifiOnly = container.settings.wifiOnly.value)
        }
        // Eenmalig (1.11): auteurs zonder koppeling opnieuw nalopen met de betere zoekmethode.
        val authorMaintenance = getSharedPreferences("onderhoud", MODE_PRIVATE)
        if (!authorMaintenance.getBoolean(KEY_AUTHORS_RETRY, false)) {
            container.appScope.launch {
                runCatching { container.authorRepository.retryUnlinked() }.onSuccess {
                    authorMaintenance.edit().putBoolean(KEY_AUTHORS_RETRY, true).apply()
                    if (container.settings.autoLookup.value) {
                        nl.wdhertog.library.lookup.AuthorInfoWorker.schedule(this@LibraryApp, wifiOnly = container.settings.wifiOnly.value)
                    }
                }
            }
        }
        // Eenmalig (1.10): genres uit je tags naar het genreveld van het werk.
        val tagMaintenance = getSharedPreferences("onderhoud", MODE_PRIVATE)
        if (!tagMaintenance.getBoolean(KEY_GENRE_TAGS, false)) {
            container.appScope.launch {
                runCatching { container.genreService.moveGenresFromTags() }
                    .onSuccess { tagMaintenance.edit().putBoolean(KEY_GENRE_TAGS, true).apply() }
            }
        }
        if (container.settings.loanReminders.value) LoanReminderWorker.schedule(this)
        if (AutoBackupSettings(this).folder != null) AutoBackupWorker.schedule(this)
        // Widget "Nu aan het lezen" bijwerken als je leessessies veranderen (ook door synchroniseren).
        container.appScope.launch {
            container.readingRepository.observeSessions()
                .map { sessions -> sessions.filter { it.status == ReadingStatus.READING } }
                .distinctUntilChanged()
                .collect { ReadingWidget.update(this@LibraryApp) }
        }
        // Eenmalig: talen aanvullen en controleren aan de hand van het ISBN (sinds 1.4).
        val maintenance = getSharedPreferences("onderhoud", MODE_PRIVATE)
        if (!maintenance.getBoolean(KEY_ISBN_LANGUAGES, false)) {
            container.appScope.launch {
                container.bookRepository.checkLanguagesFromIsbn()
                maintenance.edit().putBoolean(KEY_ISBN_LANGUAGES, true).apply()
            }
        }
    }

    private companion object {
        const val KEY_ISBN_LANGUAGES = "isbn-talen-1"
        const val KEY_GENRE_TAGS = "genres-veld-1"
        const val KEY_AUTHORS_RETRY = "auteurs-koppelen-1"
    }
}
