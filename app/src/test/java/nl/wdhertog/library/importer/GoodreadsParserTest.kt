package nl.wdhertog.library.importer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.wdhertog.library.covers.CoverStore
import nl.wdhertog.library.data.db.BookFormat
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.data.db.Ownership
import nl.wdhertog.library.data.db.ReadingStatus
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.data.repository.LocationRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoodreadsParserTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
    private val books = BookRepository(db, CoverStore(context))

    @After
    fun close() = db.close()

    private val table = CsvReader.read(javaClass.getResourceAsStream("/import/goodreads.csv")!!.readBytes())

    @Test
    fun `recognises and reads a Goodreads export`() {
        assertTrue(GoodreadsParser.matches(table.header))
        val export = GoodreadsParser.parse(table)
        assertEquals(1, export.ownedMarked)
        val (dune, omens, smelt) = export.books

        assertEquals("Dune", dune.title)
        assertEquals("Dune" to "1", dune.seriesName to dune.seriesIndex)
        assertEquals("9780441172719", dune.isbn13)
        assertEquals(BookFormat.PAPERBACK, dune.format)
        assertEquals(1965, dune.firstPublishedYear)
        assertEquals(listOf("favorites", "sci-fi"), dune.tags)
        assertEquals("Van Jan gekregen", dune.notes)
        assertEquals(listOf("goodreads:44767458"), dune.externalIds)
        assertEquals(false, dune.owned) // een ander boek is als in bezit gemarkeerd, dit niet
        assertEquals(2, dune.readings.size) // Read Count 2: één keer eerder zonder datum
        val last = dune.readings.last()
        assertEquals(LocalDate.of(2024, 3, 2), last.finishedAt)
        assertEquals(5.0, last.rating!!, 0.0)
        assertEquals("Prachtig, zand overal.", last.review)
        assertEquals(LocalDate.of(2023, 12, 24), dune.addedAt)

        assertEquals(listOf("Terry Pratchett", "Neil Gaiman"), omens.authors)
        assertNull(omens.isbn13)
        assertEquals(ReadingStatus.READING, omens.readings.single().status)
        assertEquals(true, omens.owned)
        assertEquals(BookFormat.HARDCOVER, omens.format)

        assertEquals(true, smelt.wantToRead)
        assertTrue(smelt.readings.isEmpty())
        assertEquals(BookFormat.EBOOK, smelt.format)
        assertEquals(emptyList<String>(), smelt.tags)
    }

    @Test
    fun `importing twice does not duplicate`() = runBlocking {
        val service = ImportService(db, books, LocationRepository(db))
        suspend fun importOnce(): ImportResult {
            val export = GoodreadsParser.parse(table)
            val matches = ImportMatcher.match(export.books, service.existingBooks())
            return service.run(GoodreadsParser.SOURCE, null, export.books.zip(matches) { book, match ->
                book to (match?.let { ImportDecision.Merge(it.existing.id, overwrite = false) } ?: ImportDecision.Add)
            })
        }
        assertEquals(3, importOnce().added)
        val second = importOnce()
        assertEquals(0, second.added)
        assertEquals(3, second.updated)
        assertEquals(3, db.bookDao().getBooks().size)
        val dune = db.bookDao().getBooks().single { it.title == "Dune" }
        assertEquals("goodreads:44767458", dune.externalIds)
        assertEquals(Ownership.NOT_OWNED, dune.ownership)
        assertEquals(2, db.readingDao().observeSessions().first().count { it.bookId == dune.id })
    }
}
