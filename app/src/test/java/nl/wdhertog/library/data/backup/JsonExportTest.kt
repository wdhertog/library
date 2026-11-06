package nl.wdhertog.library.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.wdhertog.library.covers.CoverStore
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.data.db.ReadingDay
import nl.wdhertog.library.data.db.ReadingGoal
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.ReadingStatus
import nl.wdhertog.library.data.repository.BookDraft
import nl.wdhertog.library.data.repository.BookRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JsonExportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
    private val books = BookRepository(db, CoverStore(context))
    private val service = BackupService(context, { db }, {})

    @After
    fun close() = db.close()

    @Test
    fun `everything with uuid references`() = runBlocking {
        val id = books.save(0, BookDraft(title = "Dictator", authors = listOf("Robert Harris"), seriesName = "Cicero", seriesIndex = "3", notes = "Van oma"))
        db.readingDao().insert(ReadingSession(bookId = id, status = ReadingStatus.FINISHED, finishedAt = LocalDate.of(2025, 5, 1), rating = 4.5))
        db.readingDao().upsertGoal(ReadingGoal(2026, 16))
        db.readingDao().logDay(ReadingDay(LocalDate.of(2026, 11, 5), id))

        val out = ByteArrayOutputStream()
        assertEquals(1, service.exportJson(out, books))
        val root = Json.parseToJsonElement(out.toString(Charsets.UTF_8)).jsonObject

        assertEquals(JsonExport.FORMAT.toString(), root["format"]!!.jsonPrimitive.content)
        val book = root["books"]!!.jsonArray.single().jsonObject
        val bookUuid = book["uuid"]!!.jsonPrimitive.content
        assertEquals("Dictator", book["title"]!!.jsonPrimitive.content)
        assertEquals("Van oma", book["notes"]!!.jsonPrimitive.content)
        assertEquals("false", book["favorite"]!!.jsonPrimitive.content)
        val work = root["works"]!!.jsonArray.single().jsonObject
        assertEquals(work["uuid"], book["works"]!!.jsonArray.single())
        assertEquals("Robert Harris", work["authors"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(root["series"]!!.jsonArray.single().jsonObject["uuid"], work["series"])
        assertEquals("Robert Harris", root["authors"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        val session = root["readingSessions"]!!.jsonArray.single().jsonObject
        assertEquals(bookUuid, session["book"]!!.jsonPrimitive.content)
        assertEquals("2025-05-01", session["finishedAt"]!!.jsonPrimitive.content)
        assertEquals("16", root["readingGoals"]!!.jsonObject["2026"]!!.jsonPrimitive.content)
        assertEquals(bookUuid, root["readingDays"]!!.jsonArray.single().jsonObject["book"]!!.jsonPrimitive.content)
    }
}
