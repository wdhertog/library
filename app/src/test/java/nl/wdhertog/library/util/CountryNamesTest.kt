package nl.wdhertog.library.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CountryNamesTest {
    @Test
    fun `historical states count as the country of today`() {
        assertEquals("Nederland", CountryNames.normalize("Koninkrijk der Nederlanden"))
        assertEquals("Verenigd Koninkrijk", CountryNames.normalize("Verenigd Koninkrijk van Groot-Brittannië en Ierland"))
        assertEquals("Italië", CountryNames.normalize("Koninkrijk Italië"))
        assertEquals("Italië", CountryNames.normalize("Lombardije-Venetië"))
        assertEquals("Denemarken", CountryNames.normalize("Koninkrijk Denemarken"))
        assertEquals("Rusland", CountryNames.normalize("Keizerrijk Rusland"))
        assertEquals("Zuid-Afrika", CountryNames.normalize("Unie van Zuid-Afrika"))
        assertEquals("China", CountryNames.normalize("Wu"))
        assertEquals("Verenigde Staten", CountryNames.normalize("Verenigde Staten"))
        assertEquals("Sovjet-Unie", CountryNames.normalize("Sovjet-Unie"))
    }
}
