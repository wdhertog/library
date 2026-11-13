package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.Book
import nl.wdhertog.library.data.db.BookSeriesRow
import nl.wdhertog.library.data.db.DatePrecision
import nl.wdhertog.library.data.db.ReadingGoal
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.ReadingStatus
import nl.wdhertog.library.data.db.SeriesSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AchievementsTest {

    private var nextId = 1L

    private fun item(id: Long, pages: Int? = 200, language: String? = "nl", author: String = "Auteur $id", series: BookSeriesRow? = null) =
        BookListItem(Book(id = id, title = "Boek $id", pages = pages, language = language), listOf(author), series)

    private fun read(bookId: Long, date: LocalDate?, precision: DatePrecision = DatePrecision.DAY) =
        ReadingSession(id = nextId++, bookId = bookId, finishedAt = date, datePrecision = precision, status = ReadingStatus.FINISHED)

    private fun List<Badge>.get(id: String) = first { it.id == id }

    @Test
    fun `book counts with the date of the tenth book`() {
        val books = (1L..12L).associateWith { item(it) }
        val start = LocalDate.of(2025, 1, 1)
        val sessions = (1L..12L).map { read(it, start.plusDays(it)) } +
            ReadingSession(id = 999, bookId = 1, status = ReadingStatus.ABANDONED, finishedAt = start)
        val badges = Achievements.evaluate(sessions, books, emptyList(), emptyList())
        val ten = badges.get("books-10")
        assertTrue(ten.achieved)
        assertEquals(start.plusDays(10), ten.achievedOn)
        val fifty = badges.get("books-50")
        assertFalse(fifty.achieved)
        assertEquals(12, fifty.progress)
        assertNull(fifty.achievedOn)
        // Pagina's: 12 × 200 = 2.400, en geen dikke pil.
        assertEquals(2400, badges.get("pages-10000").progress)
        assertFalse(badges.get("thick").achieved)
    }

    @Test
    fun `undated books count but give no date`() {
        val books = (1L..10L).associateWith { item(it) }
        val sessions = (1L..9L).map { read(it, LocalDate.of(2024, 5, it.toInt())) } + read(10, null)
        val ten = Achievements.evaluate(sessions, books, emptyList(), emptyList()).get("books-10")
        assertTrue(ten.achieved)
        assertNull(ten.achievedOn)
        // Een jaar-datum geeft alleen het jaar.
        val yearOnly = (1L..9L).map { read(it, LocalDate.of(2024, 5, it.toInt())) } + read(10, LocalDate.of(2025, 1, 1), DatePrecision.YEAR)
        val badge = Achievements.evaluate(yearOnly, books, emptyList(), emptyList()).get("books-10")
        assertEquals(LocalDate.of(2025, 1, 1), badge.achievedOn)
        assertTrue(badge.yearOnly)
    }

    @Test
    fun `goal, languages, thick book, author and rereads`() {
        val books = mapOf(
            1L to item(1, pages = 1200, language = "en", author = "Tolkien"),
            2L to item(2, language = "de", author = "Tolkien"),
            3L to item(3, language = "NL", author = "Tolkien"),
            4L to item(4, language = null, author = "Tolkien"),
        )
        val sessions = listOf(
            read(1, LocalDate.of(2025, 2, 1)), read(2, LocalDate.of(2025, 3, 1)), read(3, LocalDate.of(2025, 4, 1)),
            read(4, LocalDate.of(2026, 1, 1)), read(4, LocalDate.of(2026, 2, 1)), read(4, LocalDate.of(2026, 3, 1)),
        )
        val goals = listOf(ReadingGoal(2025, 3), ReadingGoal(2026, 5))
        val badges = Achievements.evaluate(sessions, books, goals, emptyList())
        badges.get("goal").let {
            assertTrue(it.achieved)
            assertEquals(1, it.times)
            assertEquals(LocalDate.of(2025, 4, 1), it.achievedOn)
        }
        assertEquals(LocalDate.of(2025, 4, 1), badges.get("languages-3").achievedOn)
        assertEquals(3, badges.get("languages-5").progress)
        assertEquals(LocalDate.of(2025, 2, 1), badges.get("thick").achievedOn)
        assertEquals(4, badges.get("author").progress) // boek 4 drie keer telt één keer
        assertEquals(LocalDate.of(2026, 3, 1), badges.get("reread").achievedOn)
        assertEquals(listOf("reread", "goal", "languages-3"), Achievements.recent(badges).map { it.id })
    }

    @Test
    fun `series completed when every part is read`() {
        fun row(book: Long, index: String) = BookSeriesRow(book, 7, "Serie", index)
        val books = mapOf(1L to item(1, series = row(1, "1")), 2L to item(2, series = row(2, "2")), 3L to item(3, series = row(3, "3")))
        val sessions = listOf(read(1, LocalDate.of(2025, 1, 1)), read(3, LocalDate.of(2025, 2, 1)), read(2, LocalDate.of(2025, 6, 1)), read(1, LocalDate.of(2026, 1, 1)))
        val done = SeriesSummary(id = 7, name = "Serie", totalVolumes = 3, works = 3, owned = 3, read = 3, maxIndex = 3)
        val badge = Achievements.evaluate(sessions, books, emptyList(), listOf(done)).get("series")
        assertTrue(badge.achieved)
        assertEquals(LocalDate.of(2025, 6, 1), badge.achievedOn) // herlezen van deel 1 telt niet
        val halfway = done.copy(totalVolumes = 5)
        val open = Achievements.evaluate(sessions, books, emptyList(), listOf(halfway)).get("series")
        assertFalse(open.achieved)
        assertEquals(60, open.progress)
    }

    @Test
    fun `new badges, what earned them and goal progress`() {
        val books = (1L..12L).associateWith { item(it, author = "Auteur") }
        val sessions = (1L..12L).map { id ->
            ReadingSession(
                id = id, bookId = id, status = ReadingStatus.FINISHED,
                startedAt = LocalDate.of(2014 + id.toInt(), 1, 1), finishedAt = LocalDate.of(2014 + id.toInt(), 1, if (id == 3L) 5 else 20),
            )
        }
        val badges = Achievements.evaluate(
            sessions, books, listOf(ReadingGoal(2026, 16)), emptyList(),
            lentDates = List(10) { LocalDate.of(2020, 1, it + 1) }, today = LocalDate.of(2026, 11, 6),
        )
        badges.get("fast").let {
            assertTrue(it.achieved)
            assertEquals(LocalDate.of(2017, 1, 5), it.achievedOn)
            assertEquals("Boek 3", it.achievedWith)
        }
        badges.get("years").let {
            assertTrue(it.achieved)
            assertEquals("2024", it.achievedWith)
        }
        assertEquals(LocalDate.of(2020, 1, 10), badges.get("lender").achievedOn)
        assertEquals(12, badges.get("collector").progress)
        badges.get("collector-1000").let { assertEquals("Bibliothecaris", it.title); assertEquals(1000, it.target) }
        assertEquals(1000, badges.get("books-1000").target)
        assertEquals(listOf(10, 50, 100, 250, 500, 750, 1000, 1250, 1500, 1750, 2000), badges.filter { it.group == "books" }.map { it.target })
        assertEquals(20, badges.count { it.group == "streak-months" })
        assertEquals("10 jaar op rij", badges.get("streak-120").title)
        assertEquals("18 maanden op rij", badges.get("streak-18").title)
        assertEquals(listOf(2, 3, 5, 7, 10, 15), badges.filter { it.group == "languages" }.map { it.target })
        assertEquals(100_000, badges.get("pages-100000").target)
        badges.get("pages-1000000").let { assertEquals("1.000.000 pagina's", it.title); assertEquals("pages", it.group) }
        assertEquals(21, badges.count { it.group == "pages" })
        assertEquals("Een jaar lang", badges.get("streak-days-365").title)
        badges.get("author").let { assertEquals("Auteur", it.achievedWith) }
        badges.get("goal").let {
            assertFalse(it.achieved)
            assertEquals(1, it.progress) // één boek in 2026
            assertEquals(16, it.target)
            assertEquals("dit jaar", it.progressNote)
        }
    }

    @Test
    fun `newly earned badges`() {
        val before = listOf(Badge("a", BadgeKind.BOOKS, "A", "", 9, 10, false), Badge("goal", BadgeKind.GOAL, "Doel", "", 1, 1, true, times = 1))
        val after = listOf(Badge("a", BadgeKind.BOOKS, "A", "", 10, 10, true), Badge("goal", BadgeKind.GOAL, "Doel", "", 1, 1, true, times = 2))
        assertEquals(listOf("a", "goal"), BadgeService.newlyEarned(before, after).map { it.id })
        assertEquals(emptyList<String>(), BadgeService.newlyEarned(after, after).map { it.id })
    }

    @Test
    fun `countries in steps of five`() {
        val books = (1L..7L).associateWith { item(it, author = "Auteur $it") }
        val sessions = (1L..7L).map { read(it, LocalDate.of(2025, 1, it.toInt())) }
        val countries = mapOf(
            "Auteur 1" to "Nederland", "Auteur 2" to "België", "Auteur 3" to "Nederland", "Auteur 4" to "Japan",
            "Auteur 5" to "Chili", "Auteur 6" to "Nigeria", // Auteur 7: land onbekend
        )
        val badges = Achievements.evaluate(sessions, books, emptyList(), emptyList(), authorCountries = countries)
            .filter { it.kind == BadgeKind.COUNTRIES }
        assertEquals(listOf("countries-5", "countries-10"), badges.map { it.id }) // behaald + de volgende
        badges.get("countries-5").let {
            assertTrue(it.achieved)
            assertEquals(LocalDate.of(2025, 1, 6), it.achievedOn)
            assertEquals("Nigeria: Auteur 6 (Boek 6)", it.achievedWith)
            assertEquals("Landen tot nu toe (5): België, Chili, Japan, Nederland, Nigeria", it.details.single())
        }
        badges.get("countries-10").let {
            assertFalse(it.achieved)
            assertEquals(5, it.progress)
        }
        // Zonder bekende landen: alleen het eerste doel.
        assertEquals(listOf("countries-5"), Achievements.evaluate(sessions, books, emptyList(), emptyList()).filter { it.kind == BadgeKind.COUNTRIES }.map { it.id })
    }

    @Test
    fun `genre explorer and levels per genre`() {
        val books = (1L..30L).associateWith { item(it) }
        val sessions = (1L..30L).map { read(it, LocalDate.of(2020, 1, 1).plusDays(it)) }
        // 26 fantasyboeken (ook fictie), en 4 boeken in elk een ander genre.
        val genresOf = (1L..26L).associateWith { listOf("fantasy", "fictie") } +
            mapOf(27L to listOf("thriller"), 28L to listOf("detective"), 29L to listOf("humor"), 30L to listOf("poëzie", "non-fictie"))
        val badges = Achievements.evaluate(sessions, books, emptyList(), emptyList(), genresOf = genresOf)

        badges.get("genres-5").let {
            assertTrue(it.achieved) // fantasy, thriller, detective, humor, poëzie; fictie/non-fictie tellen niet
            assertEquals("poëzie (Boek 30)", it.achievedWith)
        }
        assertFalse(badges.get("genres-10").achieved)

        val fantasy = badges.filter { it.group == "genre-fantasy" }
        assertEquals(listOf(5, 10, 25, 50), fantasy.map { it.target }) // behaalde treden + de volgende
        assertTrue(fantasy.first { it.target == 25 }.achieved)
        assertEquals(LocalDate.of(2020, 1, 26), fantasy.first { it.target == 25 }.achievedOn)
        assertEquals(26, fantasy.first { it.target == 50 }.progress)
        assertEquals(true, fantasy.first().details.single().startsWith("Treden: 5: 6 jan 2020 (Boek 5); 10: "))
        assertEquals(listOf(5), badges.filter { it.group == "genre-thriller" }.map { it.target })
        assertTrue(badges.none { it.group == "genre-fictie" })
    }

    @Test
    fun `genre tiles and a count per badge, not per level`() {
        val books = (1L..12L).associateWith { item(it) }
        val sessions = (1L..12L).map { read(it, LocalDate.of(2021, 1, 1).plusDays(it)) }
        val genresOf = (1L..11L).associateWith { listOf("fantasy") } + mapOf(12L to listOf("thriller"))
        val badges = Achievements.evaluate(sessions, books, emptyList(), emptyList(), genresOf = genresOf)
        val tiles = Achievements.genreTiles(badges)
        assertEquals(listOf("Fantasy", "Thriller"), tiles.map { it.genre })
        tiles.first().let {
            assertEquals(11, it.amount)
            assertEquals(10, it.highest?.target)
            assertEquals(25, it.next?.target)
        }
        tiles.last().let {
            assertEquals(1, it.amount)
            assertNull(it.highest)
            assertEquals(5, it.next?.target)
        }
        val (got, total) = Achievements.summary(badges)
        // Fantasy (5 en 10 behaald, 25 open) telt als één behaalde badge; Thriller als één niet behaalde.
        assertEquals(badges.groupBy { it.group ?: it.id }.size, total)
        assertEquals(true, got < badges.count { it.achieved })
    }

    @Test
    fun `badges grouped in blocks, one tile per badge with its levels`() {
        val books = (1L..12L).associateWith { item(it) }
        val sessions = (1L..12L).map { read(it, LocalDate.of(2021, 1, 1).plusDays(it)) }
        val badges = Achievements.evaluate(sessions, books, emptyList(), emptyList())
        val blocks = Achievements.blocks(badges).toMap()
        assertEquals(listOf(BadgeBlock.READING, BadgeBlock.COLLECTING, BadgeBlock.PERSEVERING, BadgeBlock.DISCOVERING), blocks.keys.toList())
        val reading = blocks.getValue(BadgeBlock.READING)
        reading.first { it.key == "books" }.let {
            assertEquals("Boeken gelezen", it.title)
            assertEquals(10, it.highest?.target)
            assertEquals(50, it.next?.target)
        }
        assertEquals(21, reading.first { it.key == "pages" }.levels.size)
        blocks.getValue(BadgeBlock.COLLECTING).first { it.key == "collector" }.let {
            assertEquals(listOf(100) + (250..2000 step 250), it.levels.map { level -> level.target })
            assertEquals(listOf("Boekenplank", "Boekenkast", "Verzamelaar", "Boekenwurm", "Bibliothecaris"), it.levels.take(5).map { level -> level.title })
            assertEquals(12, it.next?.progress)
        }
        assertEquals(setOf("streak-days", "streak-months"), blocks.getValue(BadgeBlock.PERSEVERING).map { it.key }.filter { it.startsWith("streak") }.toSet())
        // Elke badge zit in precies één tegel.
        assertEquals(badges.size, blocks.values.flatten().sumOf { it.levels.size })
    }
}
