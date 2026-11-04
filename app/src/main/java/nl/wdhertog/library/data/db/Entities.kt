package nl.wdhertog.library.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/*
 * Datamodel uit docs/ONTWERP.md §5.
 * Work = het werk los van taal en uitgave; Book = een fysiek exemplaar (een editie).
 * Elk record heeft een uuid en updatedAt, zodat synchronisatie later mogelijk is.
 */

@Entity(
    tableName = "book",
    foreignKeys = [
        ForeignKey(Location::class, ["id"], ["locationId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(Person::class, ["id"], ["borrowedFromPersonId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("isbn13"), Index("locationId"), Index("borrowedFromPersonId"), Index("hirtId"), Index("importRunId")],
)
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val isbn13: String? = null,
    val isbn10: String? = null,
    val title: String,
    val subtitle: String? = null,
    /** ISO 639-1 taalcode, bijv. "nl". */
    val language: String? = null,
    val translators: String? = null,
    val publisher: String? = null,
    val publishedYear: Int? = null,
    val pages: Int? = null,
    val format: BookFormat? = null,
    val description: String? = null,
    val coverUrl: String? = null,
    val coverFile: String? = null,
    /** Komma-gescheiden. */
    val tags: String? = null,
    val ownership: Ownership = Ownership.OWNED,
    /** Bij "niet (meer) in bezit": hoe het boek weg is gegaan, aan wie en wanneer. */
    val disposalReason: DisposalReason? = null,
    val disposedTo: String? = null,
    val disposedAt: LocalDate? = null,
    @ColumnInfo(defaultValue = "0")
    val favorite: Boolean = false,
    /** Van wie het boek is bij een gedeelde collectie, bijv. "Willem" of "samen"; leeg = niet ingevuld. */
    val owner: String? = null,
    val borrowedFromPersonId: Long? = null,
    val borrowedDueDate: LocalDate? = null,
    val locationId: Long? = null,
    val shelfPosition: Int? = null,
    val notes: String? = null,
    val wantToRead: Boolean = false,
    val enrichStatus: EnrichStatus = EnrichStatus.NEW,
    /** Komma-gescheiden namen van geraadpleegde bronnen. */
    val enrichedSources: String? = null,
    /** Komma-gescheiden veldnamen die de gebruiker zelf heeft ingevuld; verrijking laat die met rust. */
    val lockedFields: String? = null,
    val openLibraryEditionId: String? = null,
    val storygraphId: String? = null,
    /** Id uit de Have I Read That-export, voor herhaald importeren zonder dubbelen. */
    val hirtId: Long? = null,
    /** De import die dit boek heeft aangemaakt; zo kan een import ongedaan worden gemaakt. */
    val importRunId: Long? = null,
    /** Genormaliseerde zoektekst (titel, auteurs, serie, ISBN, tags) voor snel zoeken. */
    val searchText: String = "",
    val addedAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Entity(
    tableName = "work",
    foreignKeys = [ForeignKey(Series::class, ["id"], ["seriesId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("seriesId")],
)
data class Work(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val title: String,
    val originalTitle: String? = null,
    val originalLanguage: String? = null,
    val firstPublishedYear: Int? = null,
    val seriesId: Long? = null,
    /** Tekst, want series nummeren ook als "0.5" of "II". */
    val seriesIndex: String? = null,
    val awards: String? = null,
    val openLibraryWorkId: String? = null,
    val wikidataId: String? = null,
    val updatedAt: Instant = Instant.now(),
)

@Entity(tableName = "author", indices = [Index("name", unique = true)])
data class Author(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(
    tableName = "work_author",
    primaryKeys = ["workId", "authorId"],
    foreignKeys = [
        ForeignKey(Work::class, ["id"], ["workId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Author::class, ["id"], ["authorId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("authorId")],
)
data class WorkAuthor(
    val workId: Long,
    val authorId: Long,
    val position: Int,
)

/** Koppelt een exemplaar aan de werken die erin staan (meestal één; een omnibus meerdere). */
@Entity(
    tableName = "book_work",
    primaryKeys = ["bookId", "workId"],
    foreignKeys = [
        ForeignKey(Book::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Work::class, ["id"], ["workId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("workId")],
)
data class BookWork(
    val bookId: Long,
    val workId: Long,
    val position: Int,
)

@Entity(tableName = "series", indices = [Index("name")])
data class Series(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val name: String,
    val totalVolumes: Int? = null,
    val wikidataId: String? = null,
    val updatedAt: Instant = Instant.now(),
    /** Wanneer de delen voor het laatst bij Wikidata zijn opgehaald (alleen op deze telefoon). */
    val partsFetchedAt: Instant? = null,
    /**
     * Zelf toegevoegde ontbrekende delen (nummer en titel), als JSON; zie ManualParts. Gedeeld via
     * synchroniseren, anders dan de delen uit Wikidata.
     */
    val manualParts: String? = null,
)

/**
 * Een deel van een serie volgens Wikidata: nummer, titel en jaar. Afgeleid en per telefoon opgehaald,
 * dus niet gesynchroniseerd; vult de ontbrekende delen in het serie-scherm aan.
 */
@Entity(
    tableName = "series_part",
    primaryKeys = ["seriesId", "ordinal"],
    foreignKeys = [ForeignKey(entity = Series::class, parentColumns = ["id"], childColumns = ["seriesId"], onDelete = ForeignKey.CASCADE)],
)
data class SeriesPart(
    val seriesId: Long,
    val ordinal: String,
    val title: String,
    val year: Int?,
    val wikidataId: String?,
)

@Entity(
    tableName = "location",
    foreignKeys = [ForeignKey(Location::class, ["id"], ["parentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("parentId")],
)
data class Location(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val parentId: Long? = null,
    val name: String,
    val type: LocationType = LocationType.OTHER,
    val sortOrder: Int = 0,
    val updatedAt: Instant = Instant.now(),
)

@Entity(tableName = "person", indices = [Index("name")])
data class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val name: String,
    val contactUri: String? = null,
    val updatedAt: Instant = Instant.now(),
)

/** Een boek dat aan iemand is uitgeleend. Geleende boeken van anderen staan op Book.ownership. */
@Entity(
    tableName = "loan",
    foreignKeys = [
        ForeignKey(Book::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Person::class, ["id"], ["personId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("bookId"), Index("personId")],
)
data class Loan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val bookId: Long,
    /** Leeg = onbekend (bijv. uit de Have I Read That-import). */
    val personId: Long? = null,
    val lentAt: LocalDate? = null,
    val dueAt: LocalDate? = null,
    val returnedAt: LocalDate? = null,
    val note: String? = null,
    val updatedAt: Instant = Instant.now(),
)

@Entity(
    tableName = "reading_session",
    foreignKeys = [ForeignKey(Book::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
data class ReadingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val bookId: Long,
    val startedAt: LocalDate? = null,
    val finishedAt: LocalDate? = null,
    /** YEAR = alleen het jaar is bekend (oude leesdatums). */
    val datePrecision: DatePrecision = DatePrecision.DAY,
    val status: ReadingStatus,
    /** Leesvoortgang bij een boek dat je nu leest. */
    val progressPage: Int? = null,
    val progressPercent: Int? = null,
    /** 0–5, decimaal. */
    val rating: Double? = null,
    val review: String? = null,
    val updatedAt: Instant = Instant.now(),
)

@Entity(tableName = "reading_goal")
data class ReadingGoal(
    @PrimaryKey val year: Int,
    val target: Int,
)

/** Eén keer importeren uit een bestand. */
@Entity(tableName = "import_run")
data class ImportRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val fileName: String?,
    val importedAt: Instant = Instant.now(),
    val added: Int = 0,
    val updated: Int = 0,
)
