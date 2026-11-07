package nl.wdhertog.library.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.wdhertog.library.covers.CoverStore
import nl.wdhertog.library.data.db.LibraryDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookListTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
    private val books = BookRepository(db, CoverStore(context))

    @After
    fun close() = db.close()

    @Test
    fun `list leaves out description and notes, the full library has them`() = runBlocking {
        books.save(0, BookDraft(title = "Dictator", authors = listOf("Robert Harris"), description = "Lang verhaal", notes = "Van oma"))
        val item = books.observeLibrary().first().single()
        assertEquals("Dictator", item.book.title)
        assertEquals(listOf("Robert Harris"), item.authors)
        assertNull(item.book.description)
        assertNull(item.book.notes)
        val full = books.fullLibrary().single()
        assertEquals("Lang verhaal", full.book.description)
        assertEquals("Van oma", full.book.notes)
    }

    @Test
    fun `author lookup is exact first, then ignores case`() = runBlocking {
        books.save(0, BookDraft(title = "Dictator", authors = listOf("Robert Harris")))
        books.save(0, BookDraft(title = "Imperium", authors = listOf("robert harris")))
        assertEquals(1, db.query("SELECT COUNT(*) FROM author", null).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test
    fun `primary edition is set, kept when linking and cleared when split off`() = runBlocking {
        val en = books.save(0, BookDraft(title = "The Lord of the Rings", authors = listOf("J.R.R. Tolkien")))
        val nl = books.save(0, BookDraft(title = "In de ban van de ring", authors = listOf("J.R.R. Tolkien")))
        books.linkAsSameWork(nl, en)
        books.setPrimaryEdition(nl)
        val work = db.bookDao().getPrimaryWork(en)!!
        assertEquals(db.bookDao().getBook(nl)!!.uuid, work.primaryBookUuid)
        books.splitFromWork(nl)
        assertNull(db.bookDao().getPrimaryWork(en)!!.primaryBookUuid)
    }

    @Test
    fun `chosen cover is locked against enrichment`() = runBlocking {
        val id = books.save(0, BookDraft(title = "The Two Towers", isbn = "9780261102361"))
        books.chooseCover(id, "https://covers.openlibrary.org/b/id/111-L.jpg")
        books.applyMetadata(id, nl.wdhertog.library.lookup.BookMetadata(source = "Open Library", title = "The Two Towers", coverUrl = "https://andere.jpg"), refresh = true, status = null)
        val book = db.bookDao().getBook(id)!!
        assertEquals("https://covers.openlibrary.org/b/id/111-L.jpg", book.coverUrl)
        assertEquals(true, "cover" in book.lockedFields.orEmpty().split(","))
    }
}
