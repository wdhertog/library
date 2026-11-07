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
        val store = RoomSyncStore(db, books)
        val clock = HybridClock(id)
        val engine = SyncEngine(store, clock) {}
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

    @Test
    fun `first merge of two libraries with the same book`() = runBlocking {
        fun Phone.dictator(owner: String?) = runBlocking {
            val room = locations.add("Woonkamer", LocationType.ROOM, null)
            val shelf = locations.add("Kast 2", LocationType.BOOKCASE, room)
            val id = books.save(0, BookDraft(title = "Dictator", authors = listOf("Robert Harris"), seriesName = "Cicero", seriesIndex = "3", isbn = "9789023495635"))
            locations.moveBook(id, shelf)
            if (owner != null) books.setOwner(listOf(id), owner)
            id
        }
        a.dictator(owner = "Willem")
        val idB = b.dictator(owner = null)
        b.books.setFavorite(listOf(idB), true)
        b.db.readingDao().insert(ReadingSession(bookId = idB, status = ReadingStatus.FINISHED))
        b.books.save(0, BookDraft(title = "Het smelt", authors = listOf("Lize Spit")))

        a.engine.prepareOutgoing()
        val remote = a.engine.snapshot()
        val local = b.engine.snapshot()
        val plan = FirstMerge.plan(local, remote)
        assertEquals(1, plan.pairs.size)
        val renames = FirstMerge.renames(plan, setOf(plan.pairs.single().local.uuid), local, remote)
        FirstMerge.apply(b.store, b.clock, renames, local, remote, a.engine.clockNow())
        sync()

        for (phone in listOf(a, b)) {
            val all = phone.db.bookDao().getBooks()
            assertEquals(setOf("Dictator", "Het smelt"), all.map { it.title }.toSet())
            assertEquals(2, phone.db.locationDao().getLocations().size)
            val dictator = phone.bookByTitle("Dictator")
            assertEquals(a.bookByTitle("Dictator").uuid, dictator.uuid)
            assertEquals("Willem", dictator.owner)
            assertEquals("Kast 2", phone.db.locationDao().getLocation(dictator.locationId!!)!!.name)
        }
        // Persoonlijk blijft persoonlijk, en blijft bij het samengevoegde boek.
        val merged = b.bookByTitle("Dictator")
        assertEquals(true, merged.favorite)
        assertEquals(false, a.bookByTitle("Dictator").favorite)
        assertEquals(merged.id, b.db.readingDao().observeSessions().first().single().bookId)
        assertEquals(0, a.db.readingDao().observeSessions().first().size)
    }

    @Test
    fun `a replaced own cover is cleaned up on the other phone`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = CoverStore.coverDir(context).apply { mkdirs() }
        val old = java.io.File(dir, "oud-1.jpg").apply { writeBytes(byteArrayOf(1)) }
        val id = a.books.save(0, BookDraft(title = "Dictator", authors = listOf("Robert Harris")))
        a.db.bookDao().setCoverFile(id, "oud-1.jpg", java.time.Instant.now())
        sync()
        a.db.bookDao().setCoverFile(id, "nieuw-2.jpg", java.time.Instant.now())
        sync()
        assertEquals("nieuw-2.jpg", b.bookByTitle("Dictator").coverFile)
        assertFalse(old.exists())
    }

    @Test
    fun `only the phone that added a book looks it up`() = runBlocking {
        val id = a.books.save(0, BookDraft(title = "Dictator", isbn = "9789023495635"))
        a.db.bookDao().setEnrichStatus(id, nl.wdhertog.library.data.db.EnrichStatus.NEW, java.time.Instant.now())
        a.db.syncDao().setMeta(SyncMeta(SyncKeys.DEVICE_ID, "a"))
        b.db.syncDao().setMeta(SyncMeta(SyncKeys.DEVICE_ID, "b"))
        sync()
        assertEquals(listOf(id), a.db.bookDao().booksAwaitingEnrichment(10))
        assertEquals(emptyList<Long>(), b.db.bookDao().booksAwaitingEnrichment(10))
        assertEquals(0, b.db.bookDao().observeAwaitingEnrichmentCount().first())
    }

    @Test
    fun `own missing parts of a series go to the other phone`() = runBlocking {
        a.books.save(0, BookDraft(title = "Dictator", authors = listOf("Robert Harris"), seriesName = "Cicero", seriesIndex = "3"))
        val series = a.db.seriesDao().observeSummaries().first().single()
        nl.wdhertog.library.data.repository.SeriesRepository(a.db, a.books).setManualPart(series.id, "1", "Imperium")
        sync()
        val other = b.db.seriesDao().observeSummaries().first().single()
        assertEquals(
            listOf(nl.wdhertog.library.data.repository.ManualPart("1", "Imperium")),
            nl.wdhertog.library.data.repository.ManualParts.decode(other.manualParts),
        )
    }

    @Test
    fun `a shelf arriving before its bookcase still ends up in it, and nothing goes back as loose`() = runBlocking {
        val room = a.locations.add("Woonkamer", LocationType.ROOM, null)
        val case = a.locations.add("Kast rechts", LocationType.BOOKCASE, room)
        a.locations.add("Plank 1", LocationType.SHELF, case)
        a.engine.prepareOutgoing()
        // De plank vóór de kast (zoals na het opruimen van de outbox kan gebeuren).
        val changes = a.engine.changesAfter(0)
        val reordered = changes.sortedByDescending { change ->
            change.entity == "location" && change.fields["name"]?.toString()?.contains("Plank") == true
        }.mapIndexed { i, c -> c.copy(seq = i + 1L) }
        b.engine.applyIncoming(reordered)
        val shelf = b.db.locationDao().getLocations().single { it.name == "Plank 1" }
        val bookcase = b.db.locationDao().getLocations().single { it.name == "Kast rechts" }
        assertEquals(bookcase.id, shelf.parentId)
        // En b stuurt geen "losse plank" terug.
        b.engine.prepareOutgoing()
        val back = b.engine.changesAfter(0).filter { it.entity == "location" && it.hlc.endsWith("-b") }
        assertEquals(emptyList<SyncChange>(), back)
    }

    @Test
    fun `a location can be moved, but not into itself`() = runBlocking {
        val room = a.locations.add("Woonkamer", LocationType.ROOM, null)
        val case = a.locations.add("Kast rechts", LocationType.BOOKCASE, room)
        val shelf = a.locations.add("Plank 1", LocationType.SHELF, null)
        assertEquals(true, a.locations.move(shelf, case))
        assertEquals(case, a.db.locationDao().getLocation(shelf)!!.parentId)
        assertEquals(false, a.locations.move(room, shelf)) // kamer in zijn eigen plank: kring
        assertEquals(null, a.db.locationDao().getLocation(room)!!.parentId)
        sync()
        val onB = b.db.locationDao().getLocations()
        assertEquals(onB.single { it.name == "Kast rechts" }.id, onB.single { it.name == "Plank 1" }.parentId)
    }
}
