package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.Location
import nl.wdhertog.library.data.db.LocationType
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationTreeTest {

    private val woonkamer = Location(id = 1, name = "Woonkamer", type = LocationType.ROOM)
    private val kast1 = Location(id = 2, parentId = 1, name = "Kast 1", type = LocationType.BOOKCASE, sortOrder = 0)
    private val kast2 = Location(id = 3, parentId = 1, name = "Kast 2", type = LocationType.BOOKCASE, sortOrder = 1)
    private val plank1 = Location(id = 4, parentId = 2, name = "Plank 1", type = LocationType.SHELF)
    private val zolder = Location(id = 5, name = "Zolder", type = LocationType.ROOM)

    private val tree = LocationTree.build(
        listOf(woonkamer, kast1, kast2, plank1, zolder),
        directCounts = mapOf(4L to 10, 2L to 2, 3L to 5, 5L to 1),
    )

    @Test
    fun `paden en tellingen inclusief sublocaties`() {
        val index = LocationTree.index(tree)
        assertEquals("Woonkamer › Kast 1 › Plank 1", index[4]!!.pathLabel)
        assertEquals(10, index[4]!!.totalCount)
        assertEquals(12, index[2]!!.totalCount) // 2 direct + 10 op de plank
        assertEquals(17, index[1]!!.totalCount)
        assertEquals(2, index[4]!!.depth)
    }

    @Test
    fun `platte lijst in boomvolgorde, ingeklapt verbergt kinderen`() {
        assertEquals(listOf(1L, 2L, 4L, 3L, 5L), LocationTree.flatten(tree).map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 5L), LocationTree.flatten(tree, expanded = setOf(1L)).map { it.id })
    }

    @Test
    fun `filteren op een kamer neemt alles erbinnen mee`() {
        val woonkamerNode = LocationTree.index(tree)[1]!!
        assertEquals(setOf(1L, 2L, 3L, 4L), LocationTree.descendantIds(woonkamerNode))
    }

    @Test
    fun `onbekende ouder of kring breekt de boom niet`() {
        val wees = Location(id = 10, parentId = 99, name = "Doos", type = LocationType.BOX)
        val a = Location(id = 20, parentId = 21, name = "A")
        val b = Location(id = 21, parentId = 20, name = "B")
        val roots = LocationTree.build(listOf(wees, a, b), emptyMap())
        assertEquals(listOf("Doos"), roots.map { it.location.name })
    }

    @Test
    fun `logische volgende laag`() {
        assertEquals(LocationType.ROOM, LocationTree.defaultChildType(null))
        assertEquals(LocationType.BOOKCASE, LocationTree.defaultChildType(LocationType.ROOM))
        assertEquals(LocationType.SHELF, LocationTree.defaultChildType(LocationType.BOOKCASE))
    }
}
