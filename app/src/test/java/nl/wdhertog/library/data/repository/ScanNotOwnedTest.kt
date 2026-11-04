package nl.wdhertog.library.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.wdhertog.library.covers.CoverStore
import nl.wdhertog.library.data.db.DisposalReason
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.data.db.Ownership
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Een boek scannen dat als niet in bezit staat (bijv. eerder gelezen en teruggebracht). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanNotOwnedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
    private val books = BookRepository(db, CoverStore(context))
    private val loans = LoanRepository(db, books)

    @After
    fun close() = db.close()

    @Test
    fun `scanning a book that is not owned changes nothing by itself, reclaiming makes it yours again`() = runBlocking {
        val id = books.save(0, BookDraft(title = "Dictator", isbn = "9789023495635"))
        loans.markDisposed(id, DisposalReason.GIVEN_AWAY, "Jan", null)

        val result = books.addScannedIsbn("9789023495635")
        assertEquals(ScanAddResult.AlreadyPresent(id, notOwned = true), result)
        assertEquals(Ownership.NOT_OWNED, books.getBook(id)!!.ownership)

        loans.markOwned(id)
        val book = books.getBook(id)!!
        assertEquals(Ownership.OWNED, book.ownership)
        assertNull(book.disposalReason)
        assertNull(book.disposedTo)
        assertEquals(ScanAddResult.AlreadyPresent(id, notOwned = false), books.addScannedIsbn("9789023495635"))
    }

    @Test
    fun `a missing part on the reading list becomes a book you do not own, in the series`() = runBlocking {
        books.save(0, BookDraft(title = "Dictator", authors = listOf("Robert Harris"), seriesName = "Cicero", seriesIndex = "3"))
        val series = SeriesRepository(db, books)
        val seriesId = db.seriesDao().observeSummaries().first().single().id
        series.setManualPart(seriesId, "1", "Imperium")

        val id = series.addToWishlist(seriesId, "1", "Imperium")
        val book = books.getBook(id)!!
        assertEquals(Ownership.NOT_OWNED, book.ownership)
        assertEquals(true, book.wantToRead)
        assertEquals(listOf("Robert Harris"), db.bookDao().getAuthorNames(db.bookDao().getPrimaryWork(id)!!.id))
        assertEquals("1", db.bookDao().getPrimaryWork(id)!!.seriesIndex)
        assertEquals(null, db.seriesDao().observeSummaries().first().single().manualParts)
    }
}
