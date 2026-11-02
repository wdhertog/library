package nl.wdhertog.library.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import nl.wdhertog.library.data.backup.BackupService

@Database(
    entities = [
        Book::class,
        Work::class,
        Author::class,
        WorkAuthor::class,
        BookWork::class,
        Series::class,
        Location::class,
        Person::class,
        Loan::class,
        ReadingSession::class,
        ReadingGoal::class,
        ImportRun::class,
    ],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
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

    companion object {
        fun create(context: Context): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, BackupService.DB_NAME).build()
    }
}
