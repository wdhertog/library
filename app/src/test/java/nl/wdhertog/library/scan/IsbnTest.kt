package nl.wdhertog.library.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IsbnTest {

    @Test
    fun `herkent geldige ISBN-13`() {
        assertTrue(Isbn.isValidIsbn13("9789000390014"))
        assertTrue(Isbn.isValidIsbn13("9780008567125"))
        assertFalse(Isbn.isValidIsbn13("9780008567126"))
    }

    @Test
    fun `herkent ISBN-10 met X als controlecijfer`() {
        assertTrue(Isbn.isValidIsbn10("080442957X"))
        assertFalse(Isbn.isValidIsbn10("0804429579"))
    }

    @Test
    fun `zet ISBN-10 om naar ISBN-13 en terug`() {
        assertEquals("9780804429573", Isbn.toIsbn13("0-8044-2957-X"))
        assertEquals("080442957X", Isbn.toIsbn10("9780804429573"))
    }

    @Test
    fun `negeert streepjes en spaties`() {
        assertEquals("9780008567125", Isbn.toIsbn13("978-0-00-856712-5"))
        assertEquals("9780008567125", Isbn.toIsbn13(" 978 0008567125 "))
    }

    @Test
    fun `ongeldige invoer geeft null`() {
        assertNull(Isbn.toIsbn13("12345"))
        assertNull(Isbn.toIsbn13(""))
        assertNull(Isbn.toIsbn10("9791034567890"))
    }
}
