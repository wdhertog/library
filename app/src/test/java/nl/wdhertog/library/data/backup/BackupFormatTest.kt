package nl.wdhertog.library.data.backup

import nl.wdhertog.library.data.db.Book
import nl.wdhertog.library.data.db.BookSeriesRow
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.ReadingStatus
import nl.wdhertog.library.data.repository.BookListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BackupFormatTest {

    private fun manifest(schema: Int, app: String = BackupManifest.APP_ID) = BackupFormat.json.encodeToString(
        BackupManifest.serializer(),
        BackupManifest(app = app, schemaVersion = schema, appVersion = "0.9", createdAt = "2026-11-02T20:00:00Z", books = 3, covers = 1),
    )

    @Test
    fun `only known entries, no path tricks`() {
        assertTrue(BackupFormat.isKnownEntry("database/library.db"))
        assertTrue(BackupFormat.isKnownEntry("covers/a8e9c7d5-8ab0-4da9-a7b8-a077b526d3a8.jpg"))
        assertFalse(BackupFormat.isKnownEntry("covers/../../databases/library.db"))
        assertFalse(BackupFormat.isKnownEntry("covers/x/y.jpg"))
        assertFalse(BackupFormat.isKnownEntry("../evil"))
    }

    @Test
    fun `older backups are fine, newer ones and foreign files are not`() {
        assertTrue(BackupFormat.check(manifest(2), hasDatabase = true, currentSchema = 3) is BackupFormat.Check.Ok)
        assertTrue(BackupFormat.check(manifest(4), hasDatabase = true, currentSchema = 3) is BackupFormat.Check.Problem)
        assertTrue(BackupFormat.check(manifest(3), hasDatabase = false, currentSchema = 3) is BackupFormat.Check.Problem)
        assertTrue(BackupFormat.check(manifest(3, app = "iets.anders"), hasDatabase = true, currentSchema = 3) is BackupFormat.Check.Problem)
        assertTrue(BackupFormat.check("geen json", hasDatabase = true, currentSchema = 3) is BackupFormat.Check.Problem)
    }

    @Test
    fun `csv for excel`() {
        val book = Book(id = 1, title = "Leven; lot", isbn13 = "9789028260191", tags = "fictie", favorite = true, locationId = 5)
        val csv = CsvExport.write(
            listOf(BookListItem(book, listOf("Vasili Grossman"), BookSeriesRow(1, 1, "Serie", "2"))),
            locationPath = { "Woonkamer › Kast 2" },
            sessions = mapOf(1L to listOf(ReadingSession(bookId = 1, finishedAt = LocalDate.of(2024, 3, 1), status = ReadingStatus.FINISHED, rating = 4.5))),
            lentTo = mapOf(1L to "Jan"),
        )
        assertTrue(csv.startsWith("﻿Titel;Ondertitel;Auteur"))
        val row = csv.lines()[1].split(';')
        assertEquals("\"Leven", row[0])
        val fields = csv.lines()[1]
        assertTrue(fields.startsWith("\"Leven; lot\";;Vasili Grossman;;9789028260191;"))
        assertTrue(fields.contains(";Serie;2;Woonkamer/Kast 2;"))
        assertTrue(fields.contains(";Jan;fictie;ja;;2024-03-01;1;4,5;"))
    }
}
