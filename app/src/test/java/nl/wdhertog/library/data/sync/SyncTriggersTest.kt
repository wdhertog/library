package nl.wdhertog.library.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** De triggers op echte SQLite, met precies de tabellen uit het door Room geëxporteerde schema. */
class SyncTriggersTest {

    private lateinit var db: Connection

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("PRAGMA foreign_keys = ON") // zoals Room
        val schema = Json.parseToJsonElement(File("schemas/nl.wdhertog.library.data.db.LibraryDatabase/4.json").readText())
            .jsonObject["database"]!!.jsonObject
        for (entity in schema["entities"]!!.jsonArray.map { it.jsonObject }) {
            val table = entity["tableName"]!!.jsonPrimitive.content
            exec(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            (entity["indices"] as? JsonArray).orEmpty().forEach { index ->
                exec((index as JsonObject)["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
        SyncTriggers.statements.forEach(::exec)
    }

    @After
    fun tearDown() = db.close()

    private fun exec(sql: String) {
        db.createStatement().use { it.execute(sql) }
    }

    private fun dirty(): Set<String> = db.createStatement().use { st ->
        st.executeQuery("SELECT entity, uuid, deleted FROM sync_dirty").use { rs ->
            buildSet { while (rs.next()) add("${rs.getString(1)}:${rs.getString(2)}:${rs.getInt(3)}") }
        }
    }

    private fun enable() = exec("INSERT OR REPLACE INTO sync_meta (key, value) VALUES ('enabled', '1')")

    private fun insertBook(id: Long, uuid: String) = exec(
        "INSERT INTO book (id, uuid, title, ownership, enrichStatus, searchText, addedAt, updatedAt, favorite, wantToRead) " +
            "VALUES ($id, '$uuid', 'Titel', 'OWNED', 'NEW', '', 0, 0, 0, 0)",
    )

    private fun insertWork(id: Long, uuid: String) = exec("INSERT INTO work (id, uuid, title, updatedAt) VALUES ($id, '$uuid', 'Werk', 0)")

    @Test
    fun `nothing is recorded while sync is off`() {
        insertBook(1, "b1")
        exec("UPDATE book SET title = 'Anders' WHERE id = 1")
        assertEquals(emptySet<String>(), dirty())
    }

    @Test
    fun `inserts, updates and deletes of shared records are recorded once per record`() {
        enable()
        insertBook(1, "b1")
        exec("UPDATE book SET title = 'Anders' WHERE id = 1")
        exec("UPDATE book SET publisher = 'Cargo' WHERE id = 1")
        exec("INSERT INTO location (id, uuid, name, type, sortOrder, updatedAt) VALUES (1, 'l1', 'Kast', 'BOOKCASE', 0, 0)")
        assertEquals(setOf("book:b1:0", "location:l1:0"), dirty())

        exec("DELETE FROM location WHERE id = 1")
        assertEquals(setOf("book:b1:0", "location:l1:1"), dirty())
    }

    @Test
    fun `authors and works count as a change of the work and the book`() {
        insertBook(1, "b1")
        insertWork(1, "w1")
        exec("INSERT INTO author (id, name) VALUES (1, 'Robert Harris')")
        enable()
        exec("INSERT INTO book_work (bookId, workId, position) VALUES (1, 1, 0)")
        exec("INSERT INTO work_author (workId, authorId, position) VALUES (1, 1, 0)")
        assertEquals(setOf("book:b1:0", "work:w1:0"), dirty())
    }

    @Test
    fun `deleting a book ends as a deletion, also with cascading links`() {
        insertBook(1, "b1")
        insertWork(1, "w1")
        exec("INSERT INTO book_work (bookId, workId, position) VALUES (1, 1, 0)")
        enable()
        exec("DELETE FROM book WHERE id = 1")
        assertEquals(setOf("book:b1:1"), dirty())
    }

    @Test
    fun `repeated changes with Room's conflict rule do not fail`() {
        enable()
        exec("INSERT OR ABORT INTO book (id, uuid, title, ownership, enrichStatus, searchText, addedAt, updatedAt, favorite, wantToRead) " +
            "VALUES (1, 'b1', 'Titel', 'OWNED', 'NEW', '', 0, 0, 0, 0)")
        exec("UPDATE OR ABORT book SET title = 'Een' WHERE id = 1")
        exec("UPDATE OR ABORT book SET title = 'Twee' WHERE id = 1")
        insertWork(1, "w1")
        exec("INSERT OR ABORT INTO book_work (bookId, workId, position) VALUES (1, 1, 0)")
        exec("UPDATE OR ABORT book_work SET position = 0 WHERE bookId = 1")
        assertEquals(setOf("book:b1:0", "work:w1:0"), dirty())
    }

    @Test
    fun `personal data is not recorded`() {
        insertBook(1, "b1")
        enable()
        exec("INSERT INTO reading_session (id, uuid, bookId, status, datePrecision, updatedAt) VALUES (1, 's1', 1, 'FINISHED', 'DAY', 0)")
        exec("INSERT INTO reading_goal (year, target) VALUES (2026, 16)")
        assertEquals(emptySet<String>(), dirty())
    }
}
