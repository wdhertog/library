package nl.wdhertog.library.data.backup

import nl.wdhertog.library.data.db.Book
import nl.wdhertog.library.data.repository.BookListItem
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvExportTest {

    @Test
    fun `text that looks like a formula stays text`() {
        val books = listOf(
            BookListItem(Book(id = 1, title = "=HYPERLINK(\"x\")", notes = "+31 6 1234"), listOf("@auteur"), null),
            BookListItem(Book(id = 2, title = "Gewoon; met puntkomma"), emptyList(), null),
        )
        val rows = CsvExport.write(books, { null }, emptyMap(), emptyMap()).removePrefix("﻿").split("\r\n")
        val formula = rows.first { it.contains("HYPERLINK") }
        assertEquals("\"'=HYPERLINK(\"\"x\"\")\"", formula.split(";")[0])
        assertEquals(true, formula.contains(";'@auteur;"))
        assertEquals(true, formula.contains(";'+31 6 1234;"))
        assertEquals("\"Gewoon; met puntkomma\"", rows.first { it.startsWith("\"Gewoon") }.substringBefore("\";") + "\"")
    }
}
