package nl.wdhertog.library.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

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
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    abstract fun locationDao(): LocationDao

    abstract fun loanDao(): LoanDao

    companion object {
        fun create(context: Context): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db").build()
    }
}
