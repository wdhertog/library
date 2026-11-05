package nl.wdhertog.library.data.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Twee telefoons die wijzigingen uitwisselen (ontwerp §11). */
class SyncEngineTest {

    private class Phone(id: String, wall: () -> Long) {
        val store = InMemorySyncStore()
        val engine = SyncEngine(store, HybridClock(id, wall)) {}
        var receivedUpTo = 0L
    }

    private var wallA = 1_000L
    private var wallB = 1_000L
    private val a = Phone("a") { wallA }
    private val b = Phone("b") { wallB }

    /** Eén synchronisatiesessie: beide klaarzetten, dan ophalen bij de ander. */
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
    fun `an edit made during the exchange is not lost`() = runTest {
        a.store.edit("book", "b1", "title" to "Dictator")
        sync()
        // b wijzigt de titel eerder; a klaarzetten, dan wijzigt de gebruiker op a tijdens de uitwisseling.
        wallB = 1_500; b.store.edit("book", "b1", "title" to "Dictator (b)")
        a.engine.prepareOutgoing(); b.engine.prepareOutgoing()
        val fromB = b.engine.changesAfter(a.receivedUpTo)
        wallA = 2_000; a.store.edit("book", "b1", "title" to "Dictator (a, later)")
        a.engine.applyIncoming(fromB)
        assertEquals("Dictator (a, later)", a.store.field("book", "b1", "title"))
        sync()
        assertEquals("Dictator (a, later)", b.store.field("book", "b1", "title"))
    }

    @Test
    fun `a new book arrives on the other phone`() = runTest {
        a.store.edit("book", "b1", "title" to "Dictator", "ownership" to "OWNED")
        sync()
        assertEquals("Dictator", b.store.field("book", "b1", "title"))
    }

    @Test
    fun `nothing echoes back after an exchange`() = runTest {
        a.store.edit("book", "b1", "title" to "Dictator")
        sync()
        sync()
        assertEquals(0, a.engine.prepareOutgoing())
        assertEquals(0, b.engine.prepareOutgoing())
        assertEquals(1, a.engine.changesAfter(0).size) // alleen de oorspronkelijke wijziging
        assertEquals(1, b.engine.changesAfter(0).size) // eenmaal doorgegeven, niet steeds opnieuw
        sync()
        assertEquals(1, a.engine.changesAfter(0).size)
        assertEquals(1, b.engine.changesAfter(0).size)
    }

    @Test
    fun `a phone passes changes on to its other paired phones`() = runTest {
        // Kind (c) is alleen met b gekoppeld; a en c kennen elkaar niet.
        val c = Phone("c") { 1_000L }
        var cHasFromB = 0L
        var bHasFromC = 0L
        suspend fun syncBC() {
            b.engine.prepareOutgoing(); c.engine.prepareOutgoing()
            val fromB = b.engine.changesAfter(cHasFromB)
            val fromC = c.engine.changesAfter(bHasFromC)
            c.engine.applyIncoming(fromB); b.engine.applyIncoming(fromC)
            fromB.maxOfOrNull { it.seq }?.let { cHasFromB = it }
            fromC.maxOfOrNull { it.seq }?.let { bHasFromC = it }
        }
        a.store.edit("book", "b1", "title" to "Dictator")
        c.store.edit("book", "b2", "title" to "Het smelt")
        sync(); syncBC(); sync()
        assertEquals("Dictator", c.store.field("book", "b1", "title"))
        assertEquals("Het smelt", a.store.field("book", "b2", "title"))

        a.store.remove("book", "b2")
        sync(); syncBC()
        assertNull(c.store.field("book", "b2", "title"))
    }

    @Test
    fun `edits of different fields at the same time are both kept`() = runTest {
        a.store.edit("book", "b1", "title" to "Dictator", "owner" to null, "publisher" to null)
        sync()
        wallA = 2_000; wallB = 2_000
        a.store.edit("book", "b1", "owner" to "Anna")
        b.store.edit("book", "b1", "publisher" to "Cargo")
        sync()
        for (phone in listOf(a, b)) {
            assertEquals("Anna", phone.store.field("book", "b1", "owner"))
            assertEquals("Cargo", phone.store.field("book", "b1", "publisher"))
        }
    }

    @Test
    fun `same field on both phones - the newest wins everywhere`() = runTest {
        a.store.edit("book", "b1", "location" to null)
        sync()
        wallA = 2_000; wallB = 3_000
        a.store.edit("book", "b1", "location" to "kast")
        b.store.edit("book", "b1", "location" to "plank")
        sync()
        assertEquals("plank", a.store.field("book", "b1", "location"))
        assertEquals("plank", b.store.field("book", "b1", "location"))
    }

    @Test
    fun `a phone with a clock far ahead cannot overrule newer edits forever`() = runTest {
        wallA = 1_000_000 // klok van A staat ver vooruit
        a.store.edit("book", "b1", "owner" to "Willem")
        sync()
        // B wijzigt daarna; dankzij de hybride klok is dat toch nieuwer dan die van A.
        b.store.edit("book", "b1", "owner" to "Anna")
        sync()
        assertEquals("Anna", a.store.field("book", "b1", "owner"))
        assertEquals("Anna", b.store.field("book", "b1", "owner"))
    }

    @Test
    fun `a deletion is passed on and wins from a later edit`() = runTest {
        a.store.edit("book", "b1", "title" to "Dictator")
        sync()
        wallA = 2_000; wallB = 3_000
        a.store.remove("book", "b1")
        b.store.edit("book", "b1", "notes" to "later gewijzigd")
        sync()
        assertNull(a.store.read("book", "b1"))
        assertNull(b.store.read("book", "b1"))
        // En het komt ook niet terug bij een volgende ronde.
        sync()
        assertNull(a.store.read("book", "b1"))
    }

    @Test
    fun `a book with my reading history stays with me as not owned`() = runTest {
        a.store.edit("book", "b1", "title" to "Dictator", "ownership" to "OWNED")
        sync()
        b.store.personalHistory += "book" to "b1"
        a.store.remove("book", "b1")
        sync()
        assertNull(a.store.read("book", "b1"))
        assertEquals("NOT_OWNED", b.store.field("book", "b1", "ownership"))
        // Wat B daarna aan dat boek verandert, gaat niet meer naar A.
        b.store.edit("book", "b1", "notes" to "alleen van mij")
        sync()
        assertNull(a.store.read("book", "b1"))
    }

    @Test
    fun `something created and deleted before syncing never travels`() = runTest {
        a.store.edit("book", "b1", "title" to "Vergissing")
        a.store.remove("book", "b1")
        sync()
        assertEquals(0, a.engine.changesAfter(0).size)
        assertNull(b.store.read("book", "b1"))
    }

    @Test
    fun `referenced records are applied first, whatever the order of arrival`() = runTest {
        a.store.edit("book", "b1", "title" to "Dictator", "location" to "l1")
        a.store.edit("location", "l1", "name" to "Kast")
        val received = mutableListOf<String>()
        val recorder = object : SyncStore by b.store {
            override suspend fun write(entity: String, uuid: String, fields: kotlinx.serialization.json.JsonObject) {
                received += entity
                b.store.write(entity, uuid, fields)
            }
        }
        a.engine.prepareOutgoing()
        SyncEngine(recorder, HybridClock("b") { wallB }) {}.applyIncoming(a.engine.changesAfter(0))
        assertEquals(listOf("location", "book"), received)
    }

    @Test
    fun `changed fields compare missing as null`() {
        val old = kotlinx.serialization.json.buildJsonObject { put("title", kotlinx.serialization.json.JsonPrimitive("A")) }
        val new = kotlinx.serialization.json.buildJsonObject {
            put("title", kotlinx.serialization.json.JsonPrimitive("A"))
            put("notes", kotlinx.serialization.json.JsonNull)
            put("owner", kotlinx.serialization.json.JsonPrimitive("Anna"))
        }
        assertEquals(setOf("owner"), SyncEngine.changedFields(old, new).keys)
        assertTrue(SyncEngine.changedFields(new, old).isEmpty().not())
    }

    @Test
    fun `outbox entries that add nothing are removable`() {
        fun change(seq: Long, uuid: String, vararg fields: String, deleted: Boolean = false) = SyncChange(
            seq, "book", uuid,
            kotlinx.serialization.json.JsonObject(fields.associateWith { kotlinx.serialization.json.JsonPrimitive("x$seq") }),
            "%015d-00000-a".format(seq), deleted,
        )
        val entries = listOf(
            change(1, "b1", "title", "pages"),
            change(2, "b1", "title"),
            change(3, "b2", "title"),
            change(4, "b1", "pages"), // nu is 1 helemaal overschreven
            change(5, "b2", deleted = true), // 3 is overbodig
            change(6, "b3", "title"),
        )
        assertEquals(listOf(3L, 1L), SyncEngine.removableOutbox(entries))
    }

    @Test
    fun `a phone that missed compacted entries still ends up the same`() = runTest {
        a.store.edit("book", "b1", "title" to "Een", "pages" to 100)
        a.engine.prepareOutgoing()
        repeat(SyncEngine.COMPACT_EVERY + 5) { i ->
            wallA += 1
            a.store.edit("book", "b1", "title" to "Titel $i")
            a.engine.prepareOutgoing()
        }
        // Opgeruimd: van de ruim duizend regels blijven er maar een paar over.
        assertTrue(a.engine.changesAfter(0).size < 20)
        sync()
        assertEquals("Titel ${SyncEngine.COMPACT_EVERY + 4}", b.store.field("book", "b1", "title"))
        assertEquals(100, b.store.field("book", "b1", "pages")?.toString()?.toInt())
    }
}
