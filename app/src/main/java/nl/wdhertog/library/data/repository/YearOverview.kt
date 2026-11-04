package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.DatePrecision
import nl.wdhertog.library.util.Languages
import java.text.NumberFormat
import java.time.temporal.ChronoUnit
import java.util.Locale

/** "Je jaar in boeken": de hoogtepunten van een leesjaar, voor het overzicht en om te delen. */
data class YearOverview(
    val year: Int,
    val count: Int,
    val goal: Int?,
    val pages: Int,
    val averagePages: Int?,
    val thickest: ReadEntry?,
    /** Auteur van wie je dit jaar het meest las (minstens twee boeken). */
    val topAuthor: Pair<String, Int>?,
    /** Je hoogste beoordeling(en), hooguit drie, vanaf vier sterren. */
    val bestRated: List<ReadEntry>,
    /** Maand (januari = 0) met de meeste uitgelezen boeken. */
    val busiestMonth: Pair<Int, Int>?,
    /** Snelst uitgelezen: boek en aantal dagen (begin- en einddatum bekend). */
    val fastest: Pair<ReadEntry, Long>?,
    val first: ReadEntry?,
    val last: ReadEntry?,
    val languages: List<Pair<String, Int>>,
    val averageRating: Double?,
) {
    /** Platte tekst om te delen. */
    fun shareText(): String = buildList {
        add("Mijn $year in boeken 📚")
        add("$count ${if (count == 1) "boek" else "boeken"} gelezen" + (goal?.let { " (doel: $it)" } ?: "") +
            pages.takeIf { it > 0 }?.let { ", ${number(it)} pagina's" }.orEmpty())
        thickest?.let { add("Dikste boek: ${it.item.book.displayTitle} (${it.item.book.pages} p.)") }
        topAuthor?.let { (name, n) -> add("Meest gelezen: $name ($n boeken)") }
        if (bestRated.isNotEmpty()) add("Favorieten: " + bestRated.joinToString(", ") { it.item.book.displayTitle })
        busiestMonth?.let { (month, n) -> add("Drukste maand: ${monthName(month)} ($n)") }
        if (languages.size > 1) add("Talen: " + languages.joinToString(", ") { (code, n) -> "${Languages.displayName(code)} $n" })
    }.joinToString("\n")

    companion object {
        private val dutch = Locale.forLanguageTag("nl")
        private val months = listOf("januari", "februari", "maart", "april", "mei", "juni", "juli", "augustus", "september", "oktober", "november", "december")

        fun monthName(index: Int) = months[index]
        fun number(n: Int): String = NumberFormat.getIntegerInstance(dutch).format(n)

        fun of(stats: YearStats): YearOverview {
            val finished = stats.finished
            val withPages = finished.filter { (it.item.book.pages ?: 0) > 0 }
            val dated = finished.filter { it.session.finishedAt != null && it.session.datePrecision != DatePrecision.YEAR }
                .sortedBy { it.session.finishedAt }
            val authorCounts = finished.flatMap { it.item.authors.distinct() }.groupingBy { it }.eachCount()
            val topAuthor = authorCounts.entries.filter { it.value >= 2 }.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })
            val topRating = finished.mapNotNull { it.session.rating }.maxOrNull()?.takeIf { it >= 4.0 }
            val fastest = dated.mapNotNull { entry ->
                val start = entry.session.startedAt ?: return@mapNotNull null
                val days = ChronoUnit.DAYS.between(start, entry.session.finishedAt) + 1
                (entry to days).takeIf { days in 1..365 }
            }.minByOrNull { it.second }
            return YearOverview(
                year = stats.year,
                count = finished.size,
                goal = stats.goal,
                pages = stats.pages,
                averagePages = withPages.takeIf { it.isNotEmpty() }?.let { list -> list.sumOf { it.item.book.pages ?: 0 } / list.size },
                thickest = withPages.maxByOrNull { it.item.book.pages ?: 0 },
                topAuthor = topAuthor?.let { it.key to it.value },
                bestRated = topRating?.let { best -> finished.filter { it.session.rating == best }.take(3) }.orEmpty(),
                busiestMonth = stats.perMonth.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.let { it.index to it.value },
                fastest = fastest,
                first = dated.firstOrNull(),
                last = dated.lastOrNull()?.takeIf { dated.size > 1 },
                languages = stats.languages,
                averageRating = stats.averageRating,
            )
        }
    }
}
