package nl.wdhertog.library.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenresTest {
    @Test
    fun `synonyms and subgenres give the genre, other names none`() {
        assertEquals("detective", Genres.genreOf("Krimi"))
        assertEquals("sciencefiction", Genres.genreOf("sci-fi"))
        assertEquals("sciencefiction", Genres.genreOf("cyberpunk"))
        assertEquals("historische roman", Genres.genreOf("historical fiction"))
        assertNull(Genres.genreOf("halloboek"))
        assertNull(Genres.genreOf("gothic fiction"))
    }

    @Test
    fun `split moves genres out of tags, subgenres and own tags stay`() {
        val (genres, tags) = Genres.split(listOf("fictie", "sci-fi", "halloboek", "high fantasy", " krimi ", "Mysterie", "maatschappij"))
        assertEquals(listOf("fictie", "sciencefiction", "fantasy", "detective"), genres)
        assertEquals(listOf("halloboek", "high fantasy", "maatschappij"), tags)
    }

    @Test
    fun `open library only real genres`() {
        assertEquals("fantasy", Genres.fromOpenLibrary("English Fantasy fiction"))
        assertNull(Genres.fromOpenLibrary("young adult fiction"))
        assertNull(Genres.fromOpenLibrary("Comics"))
        assertNull(Genres.fromOpenLibrary("Arkenstone"))
    }

    @Test
    fun `encode keeps only list genres, sorted`() {
        assertEquals("detective, fantasy", Genres.encode(listOf("fantasy", "krimi", "halloboek", "fantasy")))
        assertNull(Genres.encode(emptyList()))
        assertEquals(listOf("detective", "fantasy"), Genres.decode("detective, fantasy"))
    }
}
