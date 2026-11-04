package nl.wdhertog.library.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.wdhertog.library.covers.CoverStore
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.ReadingStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthorRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
    private val books = BookRepository(db, CoverStore(context))
    private val authors = AuthorRepository(db)

    @After
    fun close() = db.close()

    @Test
    fun `authors sorted by surname with their books and average rating`() = runBlocking {
        val dictator = books.save(0, BookDraft(title = "Dictator", authors = listOf("Robert Harris")))
        books.save(0, BookDraft(title = "Imperium", authors = listOf("Robert Harris")))
        books.save(0, BookDraft(title = "De avonden", authors = listOf("Gerard Reve")))
        db.readingDao().insert(ReadingSession(bookId = dictator, status = ReadingStatus.FINISHED, rating = 4.0))

        val list = authors.observeSummaries().first()
        assertEquals(listOf("Robert Harris", "Gerard Reve"), list.map { it.name }) // Harris vóór Reve
        assertEquals(2, list.first().books)

        val harris = authors.findId("robert harris")!!
        assertEquals(2, authors.observeBookWorks(harris).first().size)
        assertEquals(4.0, authors.observeAverageRating(harris).first()!!, 0.0)
    }
}
