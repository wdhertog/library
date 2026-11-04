package nl.wdhertog.library.importer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import nl.wdhertog.library.covers.CoverStore
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.data.repository.BookDraft
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.data.repository.LocationRepository
import nl.wdhertog.library.util.IsbnLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Taal en ISBN: aanvullen, controleren en markeren bij import (ontwerp §6.11). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IsbnLanguageCheckTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
    private val books = BookRepository(db, CoverStore(context))

    @After
    fun close() = db.close()

    private suspend fun book(title: String) = db.bookDao().getBooks().single { it.title == title }

    @Test
    fun `existing library - empty language filled, contradiction tagged`() = runBlocking {
        books.save(0, BookDraft(title = "Dictator", isbn = "9789023495635"))
        books.save(0, BookDraft(title = "A History of Japan to 1334", isbn = "9780804705233", language = "nl"))
        books.save(0, BookDraft(title = "Goed", isbn = "9781509836215", language = "en"))

        assertEquals(1 to 1, books.checkLanguagesFromIsbn())
        assertEquals("nl", book("Dictator").language)
        assertTrue(book("A History of Japan to 1334").tags.orEmpty().contains(IsbnLanguage.CHECK_TAG))
        assertEquals("nl", book("A History of Japan to 1334").language) // niet zelf veranderd: jij kijkt het na
        assertFalse(book("Goed").tags.orEmpty().contains(IsbnLanguage.CHECK_TAG))
    }

    @Test
    fun `import tags a book whose language contradicts the ISBN`() = runBlocking {
        val service = ImportService(db, books, LocationRepository(db))
        val result = service.run(
            "Test", null,
            listOf(
                ImportedBook(title = "A History of Japan to 1334", isbn13 = "9780804705233", language = "nl") to ImportDecision.Add,
                ImportedBook(title = "Dictator", isbn13 = "9789023495635", language = "nl") to ImportDecision.Add,
            ),
        )
        assertEquals(1, result.languageChecks)
        assertTrue(book("A History of Japan to 1334").tags.orEmpty().contains(IsbnLanguage.CHECK_TAG))
        assertFalse(book("Dictator").tags.orEmpty().contains(IsbnLanguage.CHECK_TAG))
    }
}
