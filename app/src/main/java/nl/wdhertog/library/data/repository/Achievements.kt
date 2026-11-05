package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.DatePrecision
import nl.wdhertog.library.data.db.ReadingGoal
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.ReadingStatus
import nl.wdhertog.library.data.db.SeriesSummary
import java.time.LocalDate

/** Soort badge; bepaalt het pictogram. */
enum class BadgeKind { BOOKS, PAGES, THICK_BOOK, GOAL, LANGUAGES, SERIES, AUTHOR, REREAD, STREAK }

/**
 * Een badge met hoe ver je bent.
 *
 * @property achievedOn wanneer je hem haalde; null als dat niet bekend is (bijv. door boeken zonder
 *   leesdatum) of als je hem nog niet hebt.
 * @property yearOnly van [achievedOn] is alleen het jaar bekend.
 * @property times hoe vaak gehaald, voor badges die vaker kunnen (leesdoel).
 */
data class Badge(
    val id: String,
    val kind: BadgeKind,
    val title: String,
    val description: String,
    val progress: Int,
    val target: Int,
    val achieved: Boolean,
    val achievedOn: LocalDate? = null,
    val yearOnly: Boolean = false,
    val times: Int = if (achieved) 1 else 0,
)

/**
 * Badges (ontwerp §12.7): mijlpalen afgeleid uit je leessessies en boeken, niets opgeslagen.
 * Daardoor kloppen ze ook met terugwerkende kracht, na een import of het terugzetten van een back-up.
 */
object Achievements {

    private val bookCounts = listOf(10, 50, 100, 250, 500)
    private val pageCounts = listOf(10_000, 50_000)
    private const val THICK = 1000
    private val languageCounts = listOf(3, 5)
    private const val AUTHOR_BOOKS = 10
    private const val REREADS = 3
    private const val MIN_SERIES = 3
    private val streakMonths = listOf(6, 12)
    private val streakDays = listOf(7, 30, 100)

    /** Een uitgelezen sessie met het moment waarop, gesorteerd: oudste eerst, zonder datum achteraan. */
    private data class Read(val session: ReadingSession, val item: BookListItem?) {
        val date: LocalDate? get() = session.finishedAt
        val yearOnly: Boolean get() = session.datePrecision == DatePrecision.YEAR
    }

    fun evaluate(
        sessions: List<ReadingSession>,
        books: Map<Long, BookListItem>,
        goals: List<ReadingGoal>,
        series: List<SeriesSummary>,
        /** Leesdagen voor de dagstreak (Streaks.readingDays). */
        readingDays: Set<LocalDate> = Streaks.readingDays(sessions, emptyList()),
    ): List<Badge> {
        val reads = sessions.filter { it.status == ReadingStatus.FINISHED }
            .map { Read(it, books[it.bookId]) }
            .sortedWith(compareBy<Read, LocalDate?>(nullsLast()) { it.date }.thenBy { it.session.id })

        return buildList {
            bookCounts.forEach { n ->
                add(threshold("books-$n", BadgeKind.BOOKS, "$n boeken", "$n boeken uitgelezen", reads.size, n, reads.getOrNull(n - 1)))
            }
            pageCounts.forEach { n -> add(pages(reads, n)) }
            add(thickBook(reads))
            add(goals(reads, goals))
            languageCounts.forEach { n -> add(languages(reads, n)) }
            add(series(reads, series))
            add(author(reads))
            add(reread(reads))
            streakMonths.forEach { n -> add(streak(sessions, n)) }
            val longestDays = if (readingDays.isEmpty()) 0 else Streaks.of(emptyList(), days = readingDays).days.longest
            streakDays.forEach { n -> add(dayStreak(readingDays, longestDays, n)) }
        }
    }

    /** Laatste [count] behaalde badges, nieuwste eerst; zonder datum achteraan. */
    fun recent(badges: List<Badge>, count: Int = 3): List<Badge> =
        badges.filter { it.achieved }.sortedWith(compareByDescending<Badge, LocalDate?>(nullsFirst()) { it.achievedOn }).take(count)

    private fun threshold(id: String, kind: BadgeKind, title: String, description: String, progress: Int, target: Int, at: Read?): Badge {
        val achieved = progress >= target
        return Badge(
            id, kind, title, description,
            progress = progress.coerceAtMost(target), target = target, achieved = achieved,
            achievedOn = at?.date.takeIf { achieved }, yearOnly = achieved && at?.yearOnly == true,
        )
    }

    private fun pages(reads: List<Read>, target: Int): Badge {
        var total = 0
        var at: Read? = null
        for (read in reads) {
            total += read.item?.book?.pages ?: 0
            if (at == null && total >= target) at = read
        }
        val label = "%,d".format(target).replace(',', '.')
        return threshold("pages-$target", BadgeKind.PAGES, "$label pagina's", "Samen $label pagina's uitgelezen", total, target, at)
    }

    private fun thickBook(reads: List<Read>): Badge {
        val at = reads.firstOrNull { (it.item?.book?.pages ?: 0) >= THICK }
        val thickest = reads.maxOfOrNull { it.item?.book?.pages ?: 0 } ?: 0
        return threshold("thick", BadgeKind.THICK_BOOK, "Dikke pil", "Een boek van $THICK pagina's of meer uitgelezen", thickest, THICK, at)
    }

    /** Leesdoel gehaald; telt per jaar. Behaald op: het boek waarmee je het doel haalde. */
    private fun goals(reads: List<Read>, goals: List<ReadingGoal>): Badge {
        val reached = goals.filter { it.target > 0 }.mapNotNull { goal ->
            val inYear = reads.filter { it.date?.year == goal.year }
            inYear.getOrNull(goal.target - 1)
        }.sortedWith(compareBy<Read, LocalDate?>(nullsLast()) { it.date })
        val first = reached.firstOrNull()
        return Badge(
            "goal", BadgeKind.GOAL, "Doel gehaald", "Je leesdoel voor een jaar gehaald",
            progress = reached.size.coerceAtMost(1), target = 1, achieved = first != null,
            achievedOn = reached.lastOrNull()?.date, yearOnly = reached.lastOrNull()?.yearOnly == true,
            times = reached.size,
        )
    }

    private fun languages(reads: List<Read>, target: Int): Badge {
        val seen = mutableSetOf<String>()
        var at: Read? = null
        for (read in reads) {
            val language = read.item?.book?.language?.lowercase() ?: continue
            if (seen.add(language) && seen.size == target) at = read
        }
        return threshold("languages-$target", BadgeKind.LANGUAGES, "$target talen", "Boeken in $target verschillende talen uitgelezen", seen.size, target, at)
    }

    /** Een serie van minstens [MIN_SERIES] delen helemaal uitgelezen. */
    private fun series(reads: List<Read>, series: List<SeriesSummary>): Badge {
        fun total(s: SeriesSummary) = maxOf(s.totalVolumes ?: 0, s.maxIndex ?: 0, s.works, ManualParts.highest(s.manualParts) ?: 0)
        val complete = series.filter { total(it) >= MIN_SERIES && it.read >= total(it) }.map { it.id }.toSet()
        // Wanneer: het deel dat als laatste voor het eerst werd uitgelezen.
        val finishedOn = complete.mapNotNull { id ->
            reads.filter { it.item?.series?.seriesId == id }
                .groupBy { it.item!!.series!!.seriesIndex ?: "boek-${it.session.bookId}" }
                .values.map { it.first() }
                .maxWithOrNull(compareBy<Read, LocalDate?>(nullsFirst()) { it.date })
        }.sortedWith(compareBy<Read, LocalDate?>(nullsLast()) { it.date })
        val bestProgress = series.filter { total(it) >= MIN_SERIES }.maxOfOrNull { it.read * 100 / total(it) } ?: 0
        return Badge(
            "series", BadgeKind.SERIES, "Serie uit", "Alle delen van een serie (minstens $MIN_SERIES) uitgelezen",
            progress = if (complete.isNotEmpty()) 100 else bestProgress.coerceAtMost(99), target = 100,
            achieved = complete.isNotEmpty(),
            achievedOn = finishedOn.firstOrNull()?.date, yearOnly = finishedOn.firstOrNull()?.yearOnly == true,
            times = complete.size,
        )
    }

    /** [AUTHOR_BOOKS] verschillende boeken van dezelfde auteur. */
    private fun author(reads: List<Read>): Badge {
        val perAuthor = mutableMapOf<String, MutableSet<Long>>()
        var at: Read? = null
        var best = 0
        for (read in reads) {
            for (author in read.item?.authors.orEmpty()) {
                val set = perAuthor.getOrPut(author) { mutableSetOf() }
                if (set.add(read.session.bookId) && set.size == AUTHOR_BOOKS && at == null) at = read
                best = maxOf(best, set.size)
            }
        }
        return threshold("author", BadgeKind.AUTHOR, "Fan", "$AUTHOR_BOOKS boeken van dezelfde auteur uitgelezen", best, AUTHOR_BOOKS, at)
    }

    private fun reread(reads: List<Read>): Badge {
        val counts = mutableMapOf<Long, Int>()
        var at: Read? = null
        for (read in reads) {
            val n = counts.merge(read.session.bookId, 1, Int::plus)!!
            if (n == REREADS && at == null) at = read
        }
        val best = counts.values.maxOrNull() ?: 0
        return threshold("reread", BadgeKind.REREAD, "Herlezer", "Hetzelfde boek $REREADS keer uitgelezen", best, REREADS, at)
    }

    private fun dayStreak(days: Set<LocalDate>, longest: Int, length: Int): Badge {
        val on = Streaks.daysReachedOn(days, length)
        return Badge(
            "streak-days-$length", BadgeKind.STREAK, "$length dagen op rij",
            "$length dagen achter elkaar gelezen (één dag per week overslaan mag)",
            progress = longest.coerceAtMost(length), target = length, achieved = on != null, achievedOn = on,
        )
    }

    private fun streak(sessions: List<ReadingSession>, months: Int): Badge {
        val on = Streaks.monthsReachedOn(sessions, months)
        val longest = Streaks.of(sessions).months.longest
        return Badge(
            "streak-$months", BadgeKind.STREAK, "$months maanden op rij",
            "$months maanden achter elkaar elke maand een boek uitgelezen",
            progress = longest.coerceAtMost(months), target = months, achieved = on != null, achievedOn = on,
        )
    }
}
