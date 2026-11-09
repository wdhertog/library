package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.DatePrecision
import nl.wdhertog.library.data.db.ReadingGoal
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.ReadingStatus
import nl.wdhertog.library.data.db.SeriesSummary
import java.time.LocalDate

/** Soort badge; bepaalt het pictogram. */
enum class BadgeKind { BOOKS, PAGES, THICK_BOOK, GOAL, LANGUAGES, SERIES, AUTHOR, REREAD, STREAK, FAST, YEARS, LENDER, COLLECTOR, COUNTRIES, GENRES, GENRE }

/**
 * Een badge met hoe ver je bent.
 *
 * @property achievedOn wanneer je hem haalde; null als dat niet bekend is (bijv. door boeken zonder
 *   leesdatum) of als je hem nog niet hebt.
 * @property yearOnly van [achievedOn] is alleen het jaar bekend.
 * @property times hoe vaak gehaald, voor badges die vaker kunnen (leesdoel).
 * @property achievedWith waarmee je hem haalde: een boektitel, serie, auteur of jaar.
 * @property progressNote bij de voortgang, bijv. "dit jaar".
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
    val achievedWith: String? = null,
    val progressNote: String? = null,
    /** Extra regels voor de details, bijv. de landen die je al hebt. */
    val details: List<String> = emptyList(),
    /** Treden van dezelfde badge (bijv. "Fantasy · 5/10/25"): de lijst toont de hoogste behaalde en de volgende. */
    val group: String? = null,
    /** Het echte aantal (bijv. 39 fantasyboeken), ook boven de hoogste trede uit. */
    val amount: Int? = null,
)

/** Blokken op het badgesscherm. */
enum class BadgeBlock(val title: String) { READING("Lezen"), COLLECTING("Verzamelen"), PERSEVERING("Volhouden"), DISCOVERING("Ontdekken") }

/** Eén tegel: een badge met zijn treden (oplopend); de hoogste behaalde en de volgende. */
data class BadgeTile(val key: String, val title: String, val kind: BadgeKind, val levels: List<Badge>) {
    val highest: Badge? get() = levels.filter { it.achieved }.maxByOrNull { it.target }
    val next: Badge? get() = levels.filter { !it.achieved }.minByOrNull { it.target }
    val achieved: Boolean get() = levels.any { it.achieved }
}

/** Eén genre op het badgesscherm: het aantal boeken, de hoogste behaalde trede en de volgende. */
data class GenreTile(val genre: String, val amount: Int, val highest: Badge?, val next: Badge?)

/**
 * Badges (ontwerp §12.7): mijlpalen afgeleid uit je leessessies en boeken, niets opgeslagen.
 * Daardoor kloppen ze ook met terugwerkende kracht, na een import of het terugzetten van een back-up.
 */
object Achievements {

    private val bookCounts = listOf(10, 50, 100, 250, 500, 1000)
    /** 10.000, dan per 50.000 tot een miljoen; als treden van één badge. */
    private val pageCounts = listOf(10_000) + (50_000..1_000_000 step 50_000)
    private const val THICK = 1000
    private val languageCounts = listOf(3, 5)
    private const val AUTHOR_BOOKS = 10
    private const val REREADS = 3
    private const val MIN_SERIES = 3
    private val streakMonths = listOf(6, 12)
    private val streakDays = listOf(7, 30, 100, 365)
    private const val FAST_DAYS = 7
    private const val YEARS = 10
    private const val LOANS = 10
    private const val COUNTRY_STEP = 5
    private val genreCounts = listOf(5, 10, 15, 20)
    private val perGenreLevels = listOf(5, 10, 25, 50, 100)
    private val shortDate = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.forLanguageTag("nl"))

    /** Te breed om als genre mee te tellen. */
    private val broadGenres = setOf("fictie", "non-fictie")
    /** Boeken in bezit: 100, 250, Verzamelaar (500) en Bibliothecaris (1000). */
    private val collections = listOf(100 to "100 in bezit", 250 to "250 in bezit", 500 to "Verzamelaar", 1000 to "Bibliothecaris")

    /** Een uitgelezen sessie met het moment waarop, gesorteerd: oudste eerst, zonder datum achteraan. */
    private data class Read(val session: ReadingSession, val item: BookListItem?) {
        val date: LocalDate? get() = session.finishedAt
        val yearOnly: Boolean get() = session.datePrecision == DatePrecision.YEAR
        val title: String? get() = item?.book?.displayTitle
    }

    fun evaluate(
        sessions: List<ReadingSession>,
        books: Map<Long, BookListItem>,
        goals: List<ReadingGoal>,
        series: List<SeriesSummary>,
        /** Leesdagen voor de dagstreak (Streaks.readingDays). */
        readingDays: Set<LocalDate> = Streaks.readingDays(sessions, emptyList()),
        /** Wanneer je een boek uitleende (leeg = onbekend), één per uitlening. */
        lentDates: List<LocalDate?> = emptyList(),
        /** Auteursnaam → land (Wikidata), voor de badges per land. */
        authorCountries: Map<String, String> = emptyMap(),
        /** Boek-id → genres van zijn werk, voor de badges per genre. */
        genresOf: Map<Long, List<String>> = emptyMap(),
        today: LocalDate = LocalDate.now(),
    ): List<Badge> {
        val reads = sessions.filter { it.status == ReadingStatus.FINISHED }
            .map { Read(it, books[it.bookId]) }
            .sortedWith(compareBy<Read, LocalDate?>(nullsLast()) { it.date }.thenBy { it.session.id })

        return buildList {
            bookCounts.forEach { n ->
                add(threshold("books-$n", BadgeKind.BOOKS, "$n boeken", "$n boeken uitgelezen", reads.size, n, reads.getOrNull(n - 1)).copy(group = "books", amount = reads.size))
            }
            pageCounts.forEach { n -> add(pages(reads, n)) }
            add(thickBook(reads))
            add(goals(reads, goals, today))
            languageCounts.forEach { n -> add(languages(reads, n)) }
            add(series(reads, series))
            add(author(reads))
            add(reread(reads))
            streakMonths.forEach { n -> add(streak(sessions, n).copy(group = "streak-months")) }
            val longestDays = if (readingDays.isEmpty()) 0 else Streaks.of(emptyList(), days = readingDays).days.longest
            streakDays.forEach { n -> add(dayStreak(readingDays, longestDays, n).copy(group = "streak-days", amount = longestDays)) }
            add(fast(reads))
            add(years(reads))
            add(lender(lentDates))
            collections.forEach { (n, title) -> add(collector(books.values, n, title)) }
            addAll(countries(reads, authorCountries))
            addAll(genreExplorer(reads, genresOf))
            addAll(perGenre(reads, genresOf))
        }
    }

    /** Behaald en totaal, per badge geteld (treden van één badge tellen samen als één). */
    fun summary(badges: List<Badge>): Pair<Int, Int> {
        val groups = badges.groupBy { it.group ?: it.id }.values
        return groups.count { level -> level.any { it.achieved } } to groups.size
    }

    /** Tegels per blok (zonder de genres, die hebben eigen tegels): elke badge met zijn treden één tegel. */
    fun blocks(badges: List<Badge>): List<Pair<BadgeBlock, List<BadgeTile>>> {
        val tiles = badges.filter { it.kind != BadgeKind.GENRE }.groupBy { it.group ?: it.id }.map { (key, levels) ->
            BadgeTile(key, tileTitles[key] ?: levels.first().title, levels.first().kind, levels.sortedBy { it.target })
        }
        return BadgeBlock.entries.map { block -> block to tiles.filter { blockOf(it.kind) == block } }.filter { it.second.isNotEmpty() }
    }

    private val tileTitles = mapOf(
        "books" to "Boeken gelezen", "pages" to "Pagina's", "collector" to "Boeken in bezit",
        "streak-days" to "Dagen op rij", "streak-months" to "Maanden op rij",
        "languages" to "Talen", "countries" to "Landen", "genres" to "Genres verkend",
    )

    private fun blockOf(kind: BadgeKind): BadgeBlock = when (kind) {
        BadgeKind.BOOKS, BadgeKind.PAGES, BadgeKind.THICK_BOOK, BadgeKind.FAST -> BadgeBlock.READING
        BadgeKind.COLLECTOR, BadgeKind.LENDER -> BadgeBlock.COLLECTING
        BadgeKind.STREAK, BadgeKind.GOAL, BadgeKind.YEARS -> BadgeBlock.PERSEVERING
        BadgeKind.LANGUAGES, BadgeKind.COUNTRIES, BadgeKind.GENRES, BadgeKind.SERIES, BadgeKind.AUTHOR, BadgeKind.REREAD, BadgeKind.GENRE -> BadgeBlock.DISCOVERING
    }

    /** De badges per genre als tegels, meeste boeken eerst. */
    fun genreTiles(badges: List<Badge>): List<GenreTile> =
        badges.filter { it.kind == BadgeKind.GENRE }.groupBy { it.group!! }.values.map { levels ->
            val highest = levels.filter { it.achieved }.maxByOrNull { it.target }
            val next = levels.filter { !it.achieved }.minByOrNull { it.target }
            val genre = (highest ?: next)!!.title.substringBefore(" · ")
            GenreTile(genre, levels.firstNotNullOfOrNull { it.amount } ?: 0, highest, next)
        }.sortedWith(compareByDescending<GenreTile> { it.amount }.thenBy { it.genre })

    /** Laatste [count] behaalde badges, nieuwste eerst; zonder datum achteraan. */
    fun recent(badges: List<Badge>, count: Int = 3): List<Badge> =
        badges.filter { it.achieved }.sortedWith(compareByDescending<Badge, LocalDate?>(nullsFirst()) { it.achievedOn }).take(count)

    private fun threshold(
        id: String, kind: BadgeKind, title: String, description: String, progress: Int, target: Int, at: Read?,
        with: String? = at?.title,
    ): Badge {
        val achieved = progress >= target
        return Badge(
            id, kind, title, description,
            progress = progress.coerceAtMost(target), target = target, achieved = achieved,
            achievedOn = at?.date.takeIf { achieved }, yearOnly = achieved && at?.yearOnly == true,
            achievedWith = with.takeIf { achieved },
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
            .copy(group = "pages", amount = total)
    }

    private fun thickBook(reads: List<Read>): Badge {
        val at = reads.firstOrNull { (it.item?.book?.pages ?: 0) >= THICK }
        val thickest = reads.maxOfOrNull { it.item?.book?.pages ?: 0 } ?: 0
        return threshold("thick", BadgeKind.THICK_BOOK, "Dikke pil", "Een boek van $THICK pagina's of meer uitgelezen", thickest, THICK, at)
    }

    /**
     * Leesdoel gehaald; telt per jaar. Behaald op: het boek waarmee je het doel haalde.
     * Nog niet gehaald: de voortgang van dit jaar ("14 van 16 dit jaar"), als je een doel hebt.
     */
    private fun goals(reads: List<Read>, goals: List<ReadingGoal>, today: LocalDate): Badge {
        val reached = goals.filter { it.target > 0 }.mapNotNull { goal ->
            val inYear = reads.filter { it.date?.year == goal.year }
            inYear.getOrNull(goal.target - 1)?.let { goal.year to it }
        }.sortedWith(compareBy<Pair<Int, Read>, LocalDate?>(nullsLast()) { it.second.date })
        val last = reached.lastOrNull()
        val thisYear = goals.firstOrNull { it.year == today.year && it.target > 0 }
        val doneThisYear = reads.count { it.date?.year == today.year }
        return Badge(
            "goal", BadgeKind.GOAL, "Doel gehaald", "Je leesdoel voor een jaar gehaald",
            progress = if (last != null || thisYear == null) reached.size.coerceAtMost(1) else doneThisYear.coerceAtMost(thisYear.target),
            target = if (last != null || thisYear == null) 1 else thisYear.target,
            achieved = last != null,
            achievedOn = last?.second?.date, yearOnly = last?.second?.yearOnly == true,
            times = reached.size,
            achievedWith = last?.let { (year, read) -> "leesdoel $year" + (read.title?.let { " (met $it)" } ?: "") },
            progressNote = "dit jaar".takeIf { last == null && thisYear != null },
        )
    }

    private fun languages(reads: List<Read>, target: Int): Badge {
        val seen = mutableSetOf<String>()
        var at: Read? = null
        for (read in reads) {
            val language = read.item?.book?.language?.lowercase() ?: continue
            if (seen.add(language) && seen.size == target) at = read
        }
        val with = at?.let { read -> listOfNotNull(read.title, read.item?.book?.language?.uppercase()).joinToString(" · ") }
        return threshold("languages-$target", BadgeKind.LANGUAGES, "$target talen", "Boeken in $target verschillende talen uitgelezen", seen.size, target, at, with)
            .copy(group = "languages", amount = seen.size)
    }

    /** Een serie van minstens [MIN_SERIES] delen helemaal uitgelezen. */
    private fun series(reads: List<Read>, series: List<SeriesSummary>): Badge {
        fun total(s: SeriesSummary) = maxOf(s.totalVolumes ?: 0, s.maxIndex ?: 0, s.works, ManualParts.highest(s.manualParts) ?: 0)
        val complete = series.filter { total(it) >= MIN_SERIES && it.read >= total(it) }.associate { it.id to it.name }
        // Wanneer: het deel dat als laatste voor het eerst werd uitgelezen.
        val finished = complete.mapNotNull { (id, name) ->
            reads.filter { it.item?.series?.seriesId == id }
                .groupBy { it.item!!.series!!.seriesIndex ?: "boek-${it.session.bookId}" }
                .values.map { it.first() }
                .maxWithOrNull(compareBy<Read, LocalDate?>(nullsFirst()) { it.date })
                ?.let { name to it }
        }.sortedWith(compareBy<Pair<String, Read>, LocalDate?>(nullsLast()) { it.second.date })
        val finishedOn = finished.map { it.second }
        val bestProgress = series.filter { total(it) >= MIN_SERIES }.maxOfOrNull { it.read * 100 / total(it) } ?: 0
        return Badge(
            "series", BadgeKind.SERIES, "Serie uit", "Alle delen van een serie (minstens $MIN_SERIES) uitgelezen",
            progress = if (complete.isNotEmpty()) 100 else bestProgress.coerceAtMost(99), target = 100,
            achieved = complete.isNotEmpty(),
            achievedOn = finishedOn.firstOrNull()?.date, yearOnly = finishedOn.firstOrNull()?.yearOnly == true,
            times = complete.size,
            achievedWith = finished.firstOrNull()?.first,
        )
    }

    /** [AUTHOR_BOOKS] verschillende boeken van dezelfde auteur. */
    private fun author(reads: List<Read>): Badge {
        val perAuthor = mutableMapOf<String, MutableSet<Long>>()
        var at: Read? = null
        var atAuthor: String? = null
        var best = 0
        for (read in reads) {
            for (author in read.item?.authors.orEmpty()) {
                val set = perAuthor.getOrPut(author) { mutableSetOf() }
                if (set.add(read.session.bookId) && set.size == AUTHOR_BOOKS && at == null) {
                    at = read
                    atAuthor = author
                }
                best = maxOf(best, set.size)
            }
        }
        return threshold("author", BadgeKind.AUTHOR, "Fan", "$AUTHOR_BOOKS boeken van dezelfde auteur uitgelezen", best, AUTHOR_BOOKS, at, atAuthor)
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

    /** Vlotte lezer: een boek binnen een week van begin tot eind (beide datums bekend). */
    private fun fast(reads: List<Read>): Badge {
        val at = reads.firstOrNull { read ->
            val s = read.session
            s.datePrecision == DatePrecision.DAY && s.startedAt != null && s.finishedAt != null &&
                !s.finishedAt.isBefore(s.startedAt) && java.time.temporal.ChronoUnit.DAYS.between(s.startedAt, s.finishedAt) < FAST_DAYS
        }
        return threshold("fast", BadgeKind.FAST, "Vlotte lezer", "Een boek binnen een week uitgelezen", if (at != null) 1 else 0, 1, at)
    }

    /** Trouwe lezer: in [YEARS] verschillende jaren een boek uitgelezen. */
    private fun years(reads: List<Read>): Badge {
        val seen = mutableSetOf<Int>()
        var at: Read? = null
        for (read in reads) {
            val year = read.date?.year ?: continue
            if (seen.add(year) && seen.size == YEARS) at = read
        }
        return threshold("years", BadgeKind.YEARS, "Trouwe lezer", "In $YEARS verschillende jaren boeken uitgelezen", seen.size, YEARS, at, at?.date?.year?.toString())
    }

    /** Uitlener: [LOANS] keer een boek uitgeleend. */
    private fun lender(lentDates: List<LocalDate?>): Badge {
        val sorted = lentDates.sortedWith(nullsLast())
        val achieved = sorted.size >= LOANS
        return Badge(
            "lender", BadgeKind.LENDER, "Uitlener", "$LOANS keer een boek uitgeleend",
            progress = sorted.size.coerceAtMost(LOANS), target = LOANS, achieved = achieved,
            achievedOn = sorted.getOrNull(LOANS - 1).takeIf { achieved },
        )
    }

    /** [target] boeken in bezit; behaald op de dag dat het [target]e erbij kwam. */
    private fun collector(books: Collection<BookListItem>, target: Int, title: String): Badge {
        val owned = books.filter { it.book.ownership == nl.wdhertog.library.data.db.Ownership.OWNED }.sortedBy { it.book.addedAt }
        val at = owned.getOrNull(target - 1)
        return Badge(
            if (target == 500) "collector" else "collector-$target", BadgeKind.COLLECTOR, title, "$target boeken in bezit",
            progress = owned.size.coerceAtMost(target), target = target, achieved = at != null,
            achievedOn = at?.book?.addedAt?.atZone(java.time.ZoneId.systemDefault())?.toLocalDate(),
            achievedWith = at?.book?.displayTitle,
            group = "collector", amount = owned.size,
        )
    }

    /**
     * Landen: boeken uitgelezen van auteurs uit 5, 10, 15, … landen. Elke behaalde trede is een badge, plus
     * de eerstvolgende om naartoe te werken. Behaald met het boek dat het zoveelste land opleverde.
     */
    private fun countries(reads: List<Read>, authorCountries: Map<String, String>): List<Badge> {
        val firstRead = linkedMapOf<String, Pair<Read, String>>() // land → (boek, auteur), in leesvolgorde
        for (read in reads) {
            for (author in read.item?.authors.orEmpty()) {
                val country = authorCountries[author] ?: continue
                if (country !in firstRead) firstRead[country] = read to author
            }
        }
        val found = firstRead.entries.toList()
        val all = found.map { it.key }.sorted()
        val levels = (COUNTRY_STEP..(found.size / COUNTRY_STEP + 1) * COUNTRY_STEP step COUNTRY_STEP).toList()
        return levels.map { n ->
            val at = found.getOrNull(n - 1)
            val achieved = at != null
            Badge(
                "countries-$n", BadgeKind.COUNTRIES, "$n landen", "Boeken uitgelezen van auteurs uit $n verschillende landen",
                progress = found.size.coerceAtMost(n), target = n, achieved = achieved,
                achievedOn = at?.value?.first?.date, yearOnly = at?.value?.first?.yearOnly == true,
                achievedWith = at?.let { (country, pair) -> "$country: ${pair.second}" + (pair.first.title?.let { " ($it)" } ?: "") },
                details = listOf("Landen tot nu toe (${all.size}): " + all.joinToString(", ")).takeIf { all.isNotEmpty() }.orEmpty(),
                group = "countries", amount = found.size,
            )
        }
    }

    /** Genreverkenner: boeken uitgelezen in 5, 10, 15, 20 verschillende genres (zonder fictie/non-fictie). */
    private fun genreExplorer(reads: List<Read>, genresOf: Map<Long, List<String>>): List<Badge> {
        val first = linkedMapOf<String, Read>()
        for (read in reads) {
            genresOf[read.session.bookId].orEmpty().filter { it !in broadGenres }.forEach { if (it !in first) first[it] = read }
        }
        val found = first.entries.toList()
        return genreCounts.map { n ->
            val at = found.getOrNull(n - 1)
            Badge(
                "genres-$n", BadgeKind.GENRES, "$n genres", "Boeken uitgelezen in $n verschillende genres",
                progress = found.size.coerceAtMost(n), target = n, achieved = at != null,
                achievedOn = at?.value?.date, yearOnly = at?.value?.yearOnly == true,
                achievedWith = at?.let { (genre, read) -> genre + (read.title?.let { " ($it)" } ?: "") },
                details = listOf("Genres tot nu toe (${found.size}): " + found.map { it.key }.sorted().joinToString(", ")).takeIf { found.isNotEmpty() }.orEmpty(),
                group = "genres", amount = found.size,
            )
        }
    }

    /**
     * Per genre 5, 10, 25, 50, 100 uitgelezen boeken. Alleen genres waarin je las; per genre alle behaalde
     * treden plus de volgende (de lijst toont daarvan de hoogste en de volgende, zie [Badge.group]).
     */
    private fun perGenre(reads: List<Read>, genresOf: Map<Long, List<String>>): List<Badge> {
        val byGenre = linkedMapOf<String, MutableList<Read>>()
        for (read in reads) genresOf[read.session.bookId].orEmpty().filter { it !in broadGenres }.forEach { byGenre.getOrPut(it) { mutableListOf() } += read }
        return byGenre.toSortedMap().flatMap { (genre, list) ->
            val name = genre.replaceFirstChar { it.uppercase() }
            val achievedLevels = perGenreLevels.filter { list.size >= it }
            val history = achievedLevels.map { n ->
                val read = list[n - 1]
                "$n: " + (read.date?.let { if (read.yearOnly) "${it.year}" else shortDate.format(it) } ?: "datum onbekend") + (read.title?.let { " ($it)" } ?: "")
            }
            val shown = achievedLevels + listOfNotNull(perGenreLevels.firstOrNull { it > list.size })
            shown.map { n ->
                val at = list.getOrNull(n - 1)?.takeIf { list.size >= n }
                Badge(
                    "genre-$genre-$n", BadgeKind.GENRE, "$name · $n", "$n boeken in het genre $genre uitgelezen",
                    progress = list.size.coerceAtMost(n), target = n, achieved = at != null,
                    achievedOn = at?.date, yearOnly = at?.yearOnly == true, achievedWith = at?.title,
                    details = listOf("Treden: " + history.joinToString("; ")).takeIf { history.isNotEmpty() }.orEmpty(),
                    group = "genre-$genre",
                    amount = list.size,
                )
            }
        }
    }

    private fun dayStreak(days: Set<LocalDate>, longest: Int, length: Int): Badge {
        val on = Streaks.daysReachedOn(days, length)
        return Badge(
            "streak-days-$length", BadgeKind.STREAK, if (length == 365) "Een jaar lang" else "$length dagen op rij",
            (if (length == 365) "Een jaar lang elke dag" else "$length dagen achter elkaar") + " gelezen (één dag per week overslaan mag)",
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
