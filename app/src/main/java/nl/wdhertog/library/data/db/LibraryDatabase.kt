package nl.wdhertog.library.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import nl.wdhertog.library.data.backup.BackupService
import nl.wdhertog.library.data.sync.SyncDao
import nl.wdhertog.library.data.sync.SyncDirty
import nl.wdhertog.library.data.sync.SyncFieldClock
import nl.wdhertog.library.data.sync.SyncMeta
import nl.wdhertog.library.data.sync.SyncOutbox
import nl.wdhertog.library.data.sync.SyncPeer
import nl.wdhertog.library.data.sync.SyncShadow
import nl.wdhertog.library.data.sync.SyncTriggers

@Database(
    entities = [
        Book::class,
        Work::class,
        Author::class,
        WorkAuthor::class,
        BookWork::class,
        Series::class,
        SeriesPart::class,
        Location::class,
        Person::class,
        Loan::class,
        ReadingSession::class,
        ReadingGoal::class,
        ReadingDay::class,
        ImportRun::class,
        SyncMeta::class,
        SyncDirty::class,
        SyncShadow::class,
        SyncFieldClock::class,
        SyncOutbox::class,
        SyncPeer::class,
    ],
    version = 8,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        // Dagstreak (reading_log) en indexen op uuid voor synchronisatie.
        AutoMigration(from = 6, to = 7),
        // Hoofdeditie (work.primaryBookUuid) en id's bij andere diensten (book.externalIds, Goodreads).
        AutoMigration(from = 7, to = 8),
    ],
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    abstract fun locationDao(): LocationDao

    abstract fun loanDao(): LoanDao

    abstract fun importDao(): ImportDao

    abstract fun readingDao(): ReadingDao

    abstract fun seriesDao(): SeriesDao

    abstract fun authorDao(): AuthorDao

    abstract fun syncDao(): SyncDao

    companion object {
        fun create(context: Context): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, BackupService.DB_NAME)
                .addCallback(
                    object : Callback() {
                        // Triggers horen niet bij Room's schema; bij elke start (ook na terugzetten) controleren.
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            SyncTriggers.statements.forEach(db::execSQL)
                            // Na terugzetten: nieuw telefoon-id, geen koppelingen, opnieuw beginnen met bijhouden.
                            val marker = BackupService.syncResetMarker(context)
                            if (marker.exists()) {
                                SyncTriggers.resetStatements.forEach(db::execSQL)
                                marker.delete()
                            }
                        }
                    },
                )
                .build()
    }
}
