package nl.wdhertog.library.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.wdhertog.library.covers.CoverStore
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.data.db.Ownership
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.ReadingStatus
import nl.wdhertog.library.data.repository.BookDraft
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.data.repository.LoanRepository
import nl.wdhertog.library.data.repository.LocationRepository
import nl.wdhertog.library.data.db.LocationType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synchroniseren tussen twee echte Room-databases ("twee telefoons"), via SyncEngine en RoomSyncStore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomSyncTest {

    private class Phone(context: Context, id: String) {
        val db: LibraryDatabase = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val books = BookRepository(db, CoverStore(context))
        val locations = LocationRepository(db)
        val loans = LoanRepository(db, books)
        val engine = SyncEngine(RoomSyncStore(db, books), HybridClock(id)) {}
        var receivedUpTo = 0L

        init {
            SyncTriggers.statements.forEach(db.openHelper.writableDatabase::execSQL)
            runBlocking { SyncRepository(db).setEnabled(true) }
        }

        suspend fun bookByTitle(title: String) = db.bookDao().getBooks().single { it.title == title }
    }

    private lateinit var a: Phone
    private lateinit var b: Phone

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        a = Phone(context, "a")
        b = Phone(context, "b")
    }

    @After
    fun tearDown() {
        a.db.close()
        b.db.close()
    }

    private suspend fun sync() {
        a.engine.prepareOutgoing()
        b.engine.prepareOutgoing()
        val fromA = a.engine.changesAfter(b.receivedUpTo)
        val fromB = b.engine.changesAfter(a.receivedUpTo)
        b.engine.applyIncoming(fromA)
        a.engine.applyIncoming(fromB)
        fromA.maxOfOrNull { it.seq }?.let { b.receivedUpTo = it }
        fromB.maxOfOrNull { it.seq }?.let { a.receivedUpTo = it }
    }

    @Test
    fun `a book with work, authors, series, place and loan arrives complete`() = runBlocking {
        val room = a.locations.add("Woonkamer", LocationType.ROOM, null)
        val shelf = a.locations.add("Kast 2", LocationType.BOOKCASE, room)
        val id = a.books.save(0, BookDraft(title = "Dictator", authors = listOf("Robert Harris"), seriesName = "Cicero", seriesIndex = "3", isbn = "9789023495635"))
        a.locations.moveBook(id, shelf)
        a.loans.lend(id, "Jan", dueAt = null, note = null)
        sync()

        val book = b.bookByTitle("Dictator")
        assertEquals(a.bookByTitle("Dictator").uuid, book.uuid)
        assertEquals("9789023495635", book.isbn13)
        assertEquals(listOf("Robert Harris"), b.db.bookDao().getAuthorNames(b.db.bookDao().getPrimaryWork(book.id)!!.id))
        assertEquals("Cicero", b.db.bookDao().getSeries(b.db.bookDao().getPrimaryWork(book.id)!!.seriesId!!)!!.name)
        val place = b.db.locationDao().getLocation(book.locationId!!)!!
        assertEquals("Kast 2", place.name)
        assertEquals("Woonkamer", b.db.locationDao().getLocation(place.parentId!!)!!.name)
        assertEquals("Jan", b.loans.observeActiveLoans().first().single().personName)
        // Zoektekst is bijgewerkt, dus het boek is ook te vinden.
        assertEquals(true, book.searchText.contains("harris"))
    }

    @Test
    fun `nothing echoes back and favourites stay personal`() = runBlocking {
        val id = a.books.save(0, BookDraft(title = "Stoner"))
        sync()
        a.books.setFavorite(listOf(id), true)
        sync()
        assertEquals(0, a.engine.prepareOutgoing())
        assertEquals(0, b.engine.prepareOutgoing())
        assertFalse(b.bookByTitle("Stoner").favorite)
    }

    @Test
    fun `owner on one phone and publisher on the other are both kept`() = runBlocking {
        val idA = a.books.save(0, BookDraft(title = "Circe"))
        sync()
        val idB = b.bookByTitle("Circe").id
        a.books.setOwner(listOf(idA), "Anna")
        b.db.bookDao().updateBook(b.bookByTitle("Circe").copy(publisher = "Atlas"))
        sync()
        for (phone in listOf(a, b)) {
            val book = phone.bookByTitle("Circe")
            assertEquals("Anna", book.owner)
            assertEquals("Atlas", book.publisher)
        }
        assertEquals(idB, b.bookByTitle("Circe").id)
    }

    @Test
    fun `deleting keeps my copy when I have reading history`() = runBlocking {
        val idA = a.books.save(0, BookDraft(title = "Duin"))
        sync()
        val idB = b.bookByTitle("Duin").id
        b.db.readingDao().insert(ReadingSession(bookId = idB, status = ReadingStatus.FINISHED))
        a.books.delete(idA)
        sync()
        assertNull(a.db.bookDao().getBooks().firstOrNull { it.title == "Duin" })
        assertEquals(Ownership.NOT_OWNED, b.bookByTitle("Duin").ownership)
        // De leesgeschiedenis is er nog.
        assertEquals(1, b.db.readingDao().observeSessions().first().size)
    }

    @Test
    fun `deleting without history removes it on the other phone too`() = runBlocking {
        val idA = a.books.save(0, BookDraft(title = "Kind 44"))
        sync()
        a.books.delete(idA)
        sync()
        assertNull(b.db.bookDao().getBooks().firstOrNull { it.title == "Kind 44" })
    }
}
