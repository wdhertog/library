package nl.wdhertog.library.util

import nl.wdhertog.library.data.db.Book
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ShareTest {

    @Test
    fun `a book with authors, year, ISBN and an Open Library link`() {
        val book = Book(title = "Dictator", isbn13 = "9789023495635", publishedYear = 2015)
        assertEquals(
            "Dictator — Robert Harris (2015)\nISBN 9789023495635\nhttps://openlibrary.org/isbn/9789023495635",
            Share.book(book, listOf("Robert Harris")),
        )
        assertEquals("Zonder ISBN", Share.book(Book(title = "Zonder ISBN"), emptyList()))
    }

    @Test
    fun `asking about a loan, with the date and an overdue return date`() {
        val today = LocalDate.of(2026, 11, 5)
        assertEquals(
            "Hoi Anna, heb je \u2018Dictator\u2019 van Robert Harris nog? Je hebt het sinds 3 september. Het zou 1 november terug zijn.",
            Share.askAboutLoan("Dictator", listOf("Robert Harris"), "Anna de Vries", LocalDate.of(2026, 9, 3), LocalDate.of(2026, 11, 1), today),
        )
        assertEquals(
            "Hoi, heb je \u2018Dictator\u2019 nog? Je hebt het sinds 3 september 2025.",
            Share.askAboutLoan("Dictator", emptyList(), null, LocalDate.of(2025, 9, 3), LocalDate.of(2026, 12, 1), today),
        )
    }
}
