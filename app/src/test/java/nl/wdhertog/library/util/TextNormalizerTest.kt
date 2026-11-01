package nl.wdhertog.library.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {

    @Test
    fun `normaliseert accenten en hoofdletters`() {
        assertEquals("der zauberberg strasse", TextNormalizer.normalize("Der  Zauberberg Straße"))
        assertEquals("les miserables", TextNormalizer.normalize("Les Misérables"))
    }

    @Test
    fun `titels sorteren zonder lidwoord`() {
        assertEquals("avonden", TextNormalizer.titleSortKey("De avonden"))
        assertEquals("hobbit", TextNormalizer.titleSortKey("The Hobbit"))
        assertEquals("etranger", TextNormalizer.titleSortKey("L'Étranger"))
        assertEquals("de", TextNormalizer.titleSortKey("De"))
    }

    @Test
    fun `auteurs sorteren op achternaam met tussenvoegsel erachter`() {
        assertEquals("reve gerard", TextNormalizer.authorSortKey("Gerard Reve"))
        assertEquals("gogh vincent van", TextNormalizer.authorSortKey("Vincent van Gogh"))
        assertEquals("berg jan van der", TextNormalizer.authorSortKey("Jan van der Berg"))
        assertEquals("rowling j.k.", TextNormalizer.authorSortKey("J.K. Rowling"))
        assertEquals("tolkien j r r", TextNormalizer.authorSortKey("Tolkien, J R R"))
        assertEquals("multatuli", TextNormalizer.authorSortKey("Multatuli"))
    }

    @Test
    fun `zoeksleutel slaat lege delen over`() {
        assertEquals("eragon christopher paolini", TextNormalizer.searchKey(listOf("Eragon", null, "", "Christopher Paolini")))
    }
}
