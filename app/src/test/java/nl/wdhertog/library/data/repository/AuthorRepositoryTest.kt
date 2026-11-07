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
    private val authors = AuthorRepository(db, books = books)

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

    private suspend fun authorsOf(bookId: Long) = books.observeLibrary().first().single { it.book.id == bookId }.authors

    @Test
    fun `merging moves the works, keeps co-authors once and locks the authors`() = runBlocking {
        val a = books.save(0, BookDraft(title = "De Hobbit", authors = listOf("J. R. R. Tolkien")))
        val b = books.save(0, BookDraft(title = "In de ban van de ring", authors = listOf("J.R.R. Tolkien")))
        val c = books.save(0, BookDraft(title = "Samen", authors = listOf("J. R. R. Tolkien", "J.R.R. Tolkien", "Christopher Tolkien")))
        val from = authors.findId("J. R. R. Tolkien")!!
        val into = authors.findId("J.R.R. Tolkien")!!
        authors.merge(from, into)
        assertEquals(listOf("J.R.R. Tolkien"), authorsOf(a))
        assertEquals(listOf("J.R.R. Tolkien"), authorsOf(b))
        assertEquals(listOf("J.R.R. Tolkien", "Christopher Tolkien"), authorsOf(c))
        assertEquals(null, authors.findId("J. R. R. Tolkien")?.takeIf { it == from })
        val locked = db.bookDao().getBook(a)!!.lockedFields.orEmpty()
        assertEquals(true, "authors" in locked.split(","))
        assertEquals(true, db.bookDao().getBook(a)!!.searchText.contains("j.r.r. tolkien"))
    }

    @Test
    fun `renaming to an existing name merges`() = runBlocking {
        books.save(0, BookDraft(title = "Dictator", authors = listOf("Robert Harris")))
        val imperium = books.save(0, BookDraft(title = "Imperium", authors = listOf("R. Harris")))
        val short = authors.findId("R. Harris")!!
        val harris = authors.findId("Robert Harris")!!
        assertEquals(harris, authors.rename(short, "robert harris"))
        assertEquals(listOf("Robert Harris"), authorsOf(imperium))
        assertEquals(1, authors.observeSummaries().first().size)
        // Gewoon hernoemen.
        assertEquals(harris, authors.rename(harris, "Robert Dennis Harris"))
        assertEquals(listOf("Robert Dennis Harris"), authorsOf(imperium))
    }

    @Test
    fun `duplicate authors in another spelling`() {
        fun summary(id: Long, name: String, books: Int) = nl.wdhertog.library.data.db.AuthorSummary(id, name, books, books)
        val list = listOf(
            summary(1, "J.R.R. Tolkien", 5), summary(2, "Tolkien, J. R. R.", 1), summary(3, "J. R. R. Tolkién", 2),
            summary(4, "John Tolkien", 1), summary(5, "Gerard Reve", 3),
        )
        val found = AuthorDuplicates.find(list)
        assertEquals(setOf(2L, 3L), found.map { it.other.id }.toSet())
        assertEquals(setOf(1L), found.map { it.keep.id }.toSet())
        assertEquals(1, AuthorDuplicates.find(list, ignored = setOf(found.first().key)).size)
    }

    @Test
    fun `not this person removes the link and stays unlinked`() = runBlocking {
        books.save(0, BookDraft(title = "Dracula", authors = listOf("Christopher Lee")))
        val id = authors.findId("Christopher Lee")!!
        db.authorDao().setInfo(id, "Q141178698", 1950, null, "Verenigd Koninkrijk", "acteur", java.time.Instant.now())
        authors.reject(id)
        val author = db.authorDao().get(id)!!
        assertEquals(null, author.wikidataId)
        assertEquals(null, author.country)
        assertEquals(true, author.wikidataRejected)
        assertEquals(0, authors.retryUnlinked()) // afgewezen auteurs worden niet opnieuw nagelopen
    }
}
