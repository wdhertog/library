package nl.wdhertog.library.data.sync

import kotlinx.coroutines.test.runTest
import nl.wdhertog.library.importer.MatchReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Twee telefoons die allebei al boeken hebben, voor het eerst samen (ontwerp §11, S4). */
class FirstMergeTest {

    private class Phone(id: String, wall: () -> Long) {
        val store = InMemorySyncStore()
        val clock = HybridClock(id, wall)
        val engine = SyncEngine(store, clock) {}
        var receivedUpTo = 0L

        fun book(uuid: String, updatedAt: Long, vararg fields: Pair<String, Any?>) {
            store.edit("book", uuid, *fields)
            store.updatedAt["book" to uuid] = updatedAt
        }
    }

    // De klok van B loopt achter: wat B laat winnen, moet toch een latere tijd krijgen.
    private val a = Phone("a") { 50_000L }
    private val b = Phone("b") { 1_000L }

    private suspend fun sync() {
        a.engine.prepareOutgoing(); b.engine.prepareOutgoing()
        val fromA = a.engine.changesAfter(b.receivedUpTo)
        val fromB = b.engine.changesAfter(a.receivedUpTo)
        b.engine.applyIncoming(fromA); a.engine.applyIncoming(fromB)
        fromA.maxOfOrNull { it.seq }?.let { b.receivedUpTo = it }
        fromB.maxOfOrNull { it.seq }?.let { a.receivedUpTo = it }
    }

    private fun setUp() {
        // A (al in gebruik)
        a.store.edit("location", "la", "name" to "Kast", "parent" to null)
        a.store.edit("work", "wa1", "title" to "Dictator", "authors" to null)
        a.book("a1", 100, "title" to "Dictator", "isbn13" to "9789400000001", "owner" to "Willem", "publisher" to null, "notes" to "van A", "location" to "la", "works" to listOf("wa1"))
        a.store.edit("work", "wa2", "title" to "Lustrum")
        a.book("a2", 300, "title" to "Lustrum", "isbn13" to "9789400000002", "notes" to "van A", "works" to listOf("wa2"))
        a.book("a3", 100, "title" to "Imperium", "isbn13" to "9789400000003")
        a.book("a4", 100, "title" to "Alleen bij A")
        // B (nieuw in de groep)
        b.store.edit("location", "lb", "name" to "kast ", "parent" to null)
        b.store.edit("work", "wb1", "title" to "Dictator")
        b.book("b1", 200, "title" to "Dictator", "isbn13" to "9789400000001", "owner" to null, "publisher" to "Cargo", "notes" to "van B", "location" to "lb", "works" to listOf("wb1"))
        b.store.edit("work", "wb2", "title" to "Lustrum")
        b.book("b2", 200, "title" to "Lustrum", "isbn13" to "9789400000002", "notes" to "van B", "works" to listOf("wb2"))
        b.book("b3", 100, "title" to "Imperium", "isbn13" to "9789400000099")
        b.book("b4", 100, "title" to "Alleen bij B")
    }

    /** Wat er bij het koppelen gebeurt: A zet klaar en stuurt zijn overzicht; B voegt samen; dan de gewone uitwisseling. */
    private suspend fun merge(accept: (MergePair) -> Boolean = { it.certain }): MergePlan {
        a.engine.prepareOutgoing()
        val remote = a.engine.snapshot()
        val local = b.engine.snapshot()
        val plan = FirstMerge.plan(local, remote)
        val renames = FirstMerge.renames(plan, plan.pairs.filter(accept).map { it.local.uuid }.toSet(), local, remote)
        FirstMerge.apply(b.store, b.clock, renames, local, remote, a.engine.clockNow())
        sync()
        return plan
    }

    private fun books(phone: Phone) = phone.store.records.keys.filter { it.first == "book" }.map { it.second }.toSet()

    @Test
    fun `plan finds certain matches, doubts and the rest`() = runTest {
        setUp()
        val plan = FirstMerge.plan(b.engine.snapshot(), a.engine.snapshot())
        // Zonder auteurs geen titel-match; Imperium heeft twee ISBN's en geen auteur, dus geen paar.
        assertEquals(setOf("b1" to "a1", "b2" to "a2"), plan.pairs.map { it.local.uuid to it.remote.uuid }.toSet())
        assertTrue(plan.pairs.all { it.reason == MatchReason.ISBN })
        assertEquals(2, plan.onlyLocal)
        assertEquals(2, plan.onlyRemote)
    }

    @Test
    fun `same books become one, the rest is combined`() = runTest {
        setUp()
        merge()
        val expected = setOf("a1", "a2", "a3", "a4", "b3", "b4")
        assertEquals(expected, books(a))
        assertEquals(expected, books(b))
        // Plek op pad samengevoegd, werken van samengevoegde boeken ook.
        assertNull(b.store.records["location" to "lb"])
        assertNull(b.store.records["work" to "wb1"])
        assertEquals("[\"wa1\"]", b.store.field("book", "a1", "works"))
        assertEquals("la", b.store.field("book", "a1", "location"))
    }

    @Test
    fun `a filled field wins from an empty one, the newest record wins a conflict`() = runTest {
        setUp()
        merge()
        for (phone in listOf(a, b)) {
            assertEquals("Willem", phone.store.field("book", "a1", "owner")) // alleen A had een eigenaar
            assertEquals("Cargo", phone.store.field("book", "a1", "publisher")) // alleen B had een uitgever
            assertEquals("van B", phone.store.field("book", "a1", "notes")) // B later gewijzigd (200 > 100)
            assertEquals("van A", phone.store.field("book", "a2", "notes")) // A later gewijzigd (300 > 200)
        }
    }

    @Test
    fun `keeping a pair separate keeps both books`() = runTest {
        setUp()
        merge { false }
        assertTrue("b1" in books(a))
        assertTrue("a1" in books(b))
    }

    @Test
    fun `after merging nothing keeps going back and forth`() = runTest {
        setUp()
        merge()
        sync()
        assertEquals(0, a.engine.prepareOutgoing())
        assertEquals(0, b.engine.prepareOutgoing())
        assertFalse(b.engine.changesAfter(0).any { it.uuid == "b1" })
    }
}
