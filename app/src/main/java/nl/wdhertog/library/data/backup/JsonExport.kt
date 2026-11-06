package nl.wdhertog.library.data.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.wdhertog.library.data.db.Author
import nl.wdhertog.library.data.db.Book
import nl.wdhertog.library.data.db.ReadingDay
import nl.wdhertog.library.data.db.ReadingGoal
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.sync.SnapshotRecord
import nl.wdhertog.library.data.sync.SyncSchema
import java.time.Instant

/**
 * Alles als leesbare JSON (ontwerp §12.1), om de gegevens ergens anders te gebruiken: in een script, een
 * spreadsheet of een andere app. Geen back-up: terugzetten gaat met de ZIP.
 *
 * Verwijzingen zijn uuid's (zoals bij synchroniseren), geen interne id's. De gedeelde records komen uit
 * dezelfde omzetting als de sync ([SnapshotRecord]); daarbij komt wat persoonlijk is: leessessies,
 * leesdoelen, leesdagen, favoriet en "wil ik lezen", en wat Wikidata over de auteurs weet.
 */
object JsonExport {
    const val FORMAT = 1

    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun write(
        records: List<SnapshotRecord>,
        books: List<Book>,
        authors: List<Author>,
        sessions: List<ReadingSession>,
        goals: List<ReadingGoal>,
        readingDays: List<ReadingDay>,
        schemaVersion: Int,
        appVersion: String,
        exportedAt: Instant = Instant.now(),
    ): String {
        val bookUuid = books.associate { it.id to it.uuid }
        val personal = books.associateBy { it.uuid }
        val byEntity = records.groupBy { it.entity }
        fun list(entity: String, extra: (String) -> Map<String, JsonElement> = { emptyMap() }) = JsonArray(
            byEntity[entity].orEmpty().sortedBy { it.uuid }.map { record ->
                JsonObject(mapOf("uuid" to JsonPrimitive(record.uuid)) + record.fields + extra(record.uuid))
            },
        )
        val root = buildJsonObject {
            put("app", "nl.wdhertog.library")
            put("appVersion", appVersion)
            put("format", FORMAT)
            put("schemaVersion", schemaVersion)
            put("exportedAt", exportedAt.toString())
            put("books", list(SyncSchema.BOOK) { uuid ->
                val book = personal[uuid]
                mapOf(
                    "favorite" to JsonPrimitive(book?.favorite == true),
                    "wantToRead" to JsonPrimitive(book?.wantToRead == true),
                )
            })
            put("works", list(SyncSchema.WORK))
            put("authors", buildJsonArray {
                authors.sortedBy { it.name.lowercase() }.forEach { a ->
                    add(buildJsonObject {
                        put("name", a.name)
                        put("wikidataId", a.wikidataId)
                        put("birthYear", a.birthYear)
                        put("deathYear", a.deathYear)
                        put("country", a.country)
                        put("description", a.description)
                    })
                }
            })
            put("series", list(SyncSchema.SERIES))
            put("locations", list(SyncSchema.LOCATION))
            put("persons", list(SyncSchema.PERSON))
            put("loans", list(SyncSchema.LOAN))
            put("readingSessions", buildJsonArray {
                sessions.sortedWith(compareBy({ it.finishedAt ?: it.startedAt }, { it.id })).forEach { s ->
                    add(buildJsonObject {
                        put("uuid", s.uuid)
                        put("book", bookUuid[s.bookId]?.let(::JsonPrimitive) ?: JsonNull)
                        put("status", s.status.name)
                        put("startedAt", s.startedAt?.toString())
                        put("finishedAt", s.finishedAt?.toString())
                        put("datePrecision", s.datePrecision.name)
                        put("progressPage", s.progressPage)
                        put("progressPercent", s.progressPercent)
                        put("rating", s.rating)
                        put("review", s.review)
                    })
                }
            })
            put("readingGoals", JsonObject(goals.sortedBy { it.year }.associate { it.year.toString() to JsonPrimitive(it.target) }))
            put("readingDays", buildJsonArray {
                readingDays.sortedWith(compareBy({ it.date }, { it.bookId })).forEach { day ->
                    add(buildJsonObject {
                        put("date", day.date.toString())
                        put("book", bookUuid[day.bookId]?.let(::JsonPrimitive) ?: JsonNull)
                    })
                }
            })
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }
}
