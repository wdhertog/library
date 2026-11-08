package nl.wdhertog.library.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import nl.wdhertog.library.covers.CoverStore
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.data.db.Ownership
import nl.wdhertog.library.data.db.ReadingDay
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.ReadingStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Een dubbel boek samenvoegen zonder leesgeschiedenis kwijt te raken. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MergeBooksTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
    private val books = BookRepository(db, CoverStore(context))
    private val loans = LoanRepository(db, books)

    @After
    fun close() = db.close()

    @Test
    fun `reading history, loans and import ids move to the book that stays`() = runBlocking {
        val keep = books.save(0, BookDraft(title = "The Hobbit", authors = listOf("J.R.R. Tolkien"), isbn = "9780261102217"))
        val dup = books.save(0, BookDraft(title = "The Hobbit, or There and Back Again", authors = listOf("J.R.R. Tolkien")))
        val dupBook = books.getBook(dup)!!
        db.bookDao().updateBook(
            dupBook.copy(ownership = Ownership.NOT_OWNED, externalIds = "goodreads:5907", hirtId = 469, favorite = true, coverUrl = "https://example.org/c.jpg"),
        )
        val reading = db.readingDao()
        reading.insert(ReadingSession(bookId = dup, status = ReadingStatus.FINISHED, finishedAt = LocalDate.of(2012, 1, 5)))
        reading.insert(ReadingSession(bookId = keep, status = ReadingStatus.FINISHED, finishedAt = LocalDate.of(2020, 3, 1)))
        reading.logDay(ReadingDay(LocalDate.of(2012, 1, 4), dup))
        reading.logDay(ReadingDay(LocalDate.of(2012, 1, 4), keep))
        reading.logDay(ReadingDay(LocalDate.of(2012, 1, 3), dup))
        loans.lend(dup, "Jan", null, null)
        val keptCover = books.getBook(keep)!!.coverUrl

        books.merge(dup, keep)

        assertNull(books.getBook(dup))
        assertEquals(listOf(keep, keep), reading.allSessions().map { it.bookId })
        assertEquals(setOf(LocalDate.of(2012, 1, 3), LocalDate.of(2012, 1, 4)), reading.allDays().map { it.date }.toSet())
        assertTrue(reading.allDays().all { it.bookId == keep })
        val loanCount = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM loan WHERE bookId = ?", arrayOf<Any>(keep)).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(1, loanCount)
        val kept = books.getBook(keep)!!
        assertEquals("goodreads:5907", kept.externalIds)
        assertEquals(469L, kept.hirtId)
        assertTrue(kept.favorite)
        assertEquals(Ownership.OWNED, kept.ownership)
        assertEquals(keptCover ?: "https://example.org/c.jpg", kept.coverUrl)
    }
}
