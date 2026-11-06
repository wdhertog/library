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
}
