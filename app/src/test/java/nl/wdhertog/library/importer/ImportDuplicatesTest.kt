package nl.wdhertog.library.importer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.wdhertog.library.covers.CoverStore
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.data.db.ReadingStatus
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.data.repository.LocationRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportDuplicatesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
    private val books = BookRepository(db, CoverStore(context))

    @After
    fun close() = db.close()

    @Test
    fun `same ISBN twice in one file becomes one book with both readings`() = runBlocking {
        fun read(year: Int) = ImportedReading(ReadingStatus.FINISHED, finishedAt = LocalDate.of(year, 5, 1))
        val result = ImportService(db, books, LocationRepository(db)).run(
            "Test", null,
            listOf(
                ImportedBook(title = "Dictator", isbn13 = "9789023495635", readings = listOf(read(2016))) to ImportDecision.Add,
                ImportedBook(title = "Dictator", isbn13 = "9789023495635", readings = listOf(read(2024))) to ImportDecision.Add,
                ImportedBook(title = "Zonder ISBN") to ImportDecision.Add,
                ImportedBook(title = "Zonder ISBN") to ImportDecision.Add,
            ),
        )
        assertEquals(3, result.added) // zonder ISBN niet samen te voegen
        assertEquals(1, result.updated)
        val dictator = db.bookDao().getBooks().single { it.title == "Dictator" }
        assertEquals(2, db.readingDao().observeSessions().first().count { it.bookId == dictator.id })
    }
}
