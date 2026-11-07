package nl.wdhertog.library.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenresTest {
    @Test
    fun `variants become one genre, unknown names stay`() {
        assertEquals("detective", Genres.canonical("Krimi"))
        assertEquals("detective", Genres.canonical("detectivefictie"))
        assertEquals("sciencefiction", Genres.canonical("sci-fi"))
        assertEquals("spionage", Genres.canonical("spy-novel"))
        assertEquals("historische roman", Genres.canonical("historical fiction"))
        assertEquals("bildungsroman", Genres.canonical("Bildungsroman"))
    }

    @Test
    fun `open library only known genres`() {
        assertEquals("fantasy", Genres.fromOpenLibrary("Fantasy"))
        assertEquals("fantasy", Genres.fromOpenLibrary("English Fantasy fiction"))
        assertNull(Genres.fromOpenLibrary("young adult fiction")) // doelgroep: niet uit Open Library
        assertNull(Genres.fromOpenLibrary("Comics")) // stripversie van een ander boek
        assertNull(Genres.fromOpenLibrary("Fiction"))
        assertNull(Genres.fromOpenLibrary("Arkenstone"))
        assertNull(Genres.fromOpenLibrary("translations into Spanish"))
    }

    @Test
    fun `tags normalized without duplicates, own tags kept`() {
        assertEquals(
            listOf("fictie", "sciencefiction", "halloboek", "detective"),
            Genres.normalizeTags(listOf("fictie", "sci-fi", "halloboek", "sciencefiction", " krimi ", "mysterie")),
        )
    }
}
