package nl.wdhertog.library.ui.scan

import nl.wdhertog.library.util.ShelfExisting
import org.junit.Assert.assertEquals
import org.junit.Test

/** Plank-modus: wat er gebeurt met een boek dat je al hebt (instelling §6.12). */
class ShelfOutcomeTest {
    private val shelf = 7L

    @Test
    fun `already on this shelf - nothing happens, whatever the setting`() {
        ShelfExisting.entries.forEach { assertEquals(ScanOutcome.ALREADY_HERE, shelfOutcome(shelf, shelf, it)) }
    }

    @Test
    fun `not placed anywhere yet - always put on this shelf`() {
        ShelfExisting.entries.forEach { assertEquals(ScanOutcome.MOVED, shelfOutcome(null, shelf, it)) }
    }

    @Test
    fun `elsewhere - depends on the setting`() {
        assertEquals(ScanOutcome.MOVED, shelfOutcome(3, shelf, ShelfExisting.MOVE))
        assertEquals(ScanOutcome.KEPT, shelfOutcome(3, shelf, ShelfExisting.ASK))
        assertEquals(ScanOutcome.KEPT, shelfOutcome(3, shelf, ShelfExisting.KEEP))
    }
}
