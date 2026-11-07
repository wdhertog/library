package nl.wdhertog.library.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import nl.wdhertog.library.covers.CoverStore
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.importer.ImportDecision
import nl.wdhertog.library.importer.ImportService
import nl.wdhertog.library.importer.ImportedBook
import nl.wdhertog.library.lookup.LookupHttp
import nl.wdhertog.library.lookup.sources.OpenLibrarySource
import nl.wdhertog.library.lookup.sources.WikidataSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenreServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
    private val books = BookRepository(db, CoverStore(context))
    private val http = LookupHttp()
    // Bronnen uit: deze tests gaan niet het netwerk op.
    private val service = GenreService(db, books, WikidataSource(http), OpenLibrarySource(http), { false }, { false })

    @After
    fun close() = db.close()

    private suspend fun work(bookId: Long) = db.bookDao().getPrimaryWork(bookId)!!

    @Test
    fun `genres move from tags to the work, own tags and subgenres stay`() = runBlocking {
        val en = books.save(0, BookDraft(title = "Neuromancer", tags = "sci-fi, cyberpunk, halloboek"))
        val nl = books.save(0, BookDraft(title = "Neuromancer (nl)", tags = "fictie, sciencefiction"))
        books.linkAsSameWork(nl, en)
        assertEquals(2, service.moveGenresFromTags())
        assertEquals("fictie, sciencefiction", work(en).genres)
        assertEquals("cyberpunk, halloboek", db.bookDao().getBook(en)!!.tags)
        assertEquals(null, db.bookDao().getBook(nl)!!.tags)
        assertEquals(true, db.bookDao().getBook(nl)!!.searchText.contains("sciencefiction"))
    }

    @Test
    fun `chosen genres replace and count as fetched`() = runBlocking {
        val id = books.save(0, BookDraft(title = "Dune"))
        service.setGenres(work(id).id, listOf("sciencefiction", "avontuur", "halloboek"))
        assertEquals("avontuur, sciencefiction", work(id).genres)
        assertNotNull(work(id).genresFetchedAt)
    }

    @Test
    fun `import puts list genres on the work`() = runBlocking {
        ImportService(db, books, LocationRepository(db)).run(
            "Test", null, listOf(ImportedBook(title = "Dune", tags = listOf("sci-fi", "to-reread", "space opera")) to ImportDecision.Add),
        )
        val book = db.bookDao().getBooks().single()
        assertEquals("sciencefiction", work(book.id).genres)
        assertEquals("to-reread, space opera", book.tags)
    }
}
