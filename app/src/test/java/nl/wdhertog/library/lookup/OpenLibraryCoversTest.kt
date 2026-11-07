package nl.wdhertog.library.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenLibraryCoversTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/openlibrary/$name")!!.readBytes().decodeToString()

    @Test
    fun `own cover first, then same language, no duplicates or missing covers`() {
        val edition = OpenLibraryCovers.parseEdition(resource("edition.json"))
        assertEquals(listOf(14625772L), edition.covers)
        assertEquals("OL27479W", edition.workId)
        val others = OpenLibraryCovers.parseEditions(resource("editions.json"))
        assertTrue(others.none { it.covers.isEmpty() }) // covers [-1] = geen
        val list = OpenLibraryCovers.combine(edition, others, language = "en", limit = 60)
        assertEquals(14625772L, list.first().id)
        assertTrue(list.first().sameIsbn)
        assertEquals(list.size, list.map { it.id }.distinct().size) // 14625772 komt bij een andere editie terug
        assertEquals(111L, list[1].id) // Engelse editie vóór de andere talen
        assertTrue(list[1].label.startsWith("Houghton Mifflin · 1999"))
        assertEquals("https://covers.openlibrary.org/b/id/111-L.jpg", list[1].url)
    }

    @Test
    fun `search picks the work with exactly this title`() {
        // Volgorde omgedraaid: de verzameleditie staat eerst, maar Mickey7 zelf heeft precies deze titel.
        assertEquals("OL24546118W", OpenLibraryCovers.parseSearch(resource("search.json"), "Mickey7"))
        assertEquals(null, OpenLibraryCovers.parseSearch("{\"docs\":[]}", "Mickey7"))
    }
}
