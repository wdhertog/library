package nl.wdhertog.library.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IsbnLanguageTest {

    @Test
    fun `language from the registration group`() {
        assertEquals("nl", IsbnLanguage.guess("9789023495635"))
        assertEquals("nl", IsbnLanguage.guess("9789463658027"))
        assertEquals("en", IsbnLanguage.guess("9780804705233"))
        assertEquals("en", IsbnLanguage.guess("9781509836215"))
        assertEquals("de", IsbnLanguage.guess("9783499246128"))
        assertEquals("fr", IsbnLanguage.guess("9782081285880"))
        assertEquals("fr", IsbnLanguage.guess("9791032102947"))
        assertEquals("it", IsbnLanguage.guess("9788806219376"))
        assertEquals("fi", IsbnLanguage.guess("9789510123456"))
        assertNull(IsbnLanguage.guess("9788012345678")) // Tsjechië/Slowakije: geen eenduidige taal
        assertNull(IsbnLanguage.guess("978-90"))
        assertNull(IsbnLanguage.guess(null))
    }

    @Test
    fun `conflicts only for a clear mismatch`() {
        // Het geval uit Have I Read That: Engels ISBN, gemarkeerd als Nederlands.
        assertTrue(IsbnLanguage.conflicts("9780804705233", "nl"))
        assertFalse(IsbnLanguage.conflicts("9780804705233", "en"))
        assertFalse(IsbnLanguage.conflicts("9788412345678", "ca")) // Catalaans uit Spanje
        assertFalse(IsbnLanguage.conflicts("9789023495635", "fy"))
        assertFalse(IsbnLanguage.conflicts("9789023495635", null))
        assertFalse(IsbnLanguage.conflicts("9788012345678", "en"))
    }
}
