package nl.wdhertog.library.lookup.search

import nl.wdhertog.library.util.TextNormalizer
import java.util.Locale

/** Een regel tekst van een cover, met de hoogte van de letters (groter = belangrijker). */
data class CoverLine(val text: String, val height: Int)

/** Een boek dat bij een zoekopdracht gevonden is. */
data class SearchCandidate(
    val title: String,
    val subtitle: String?,
    val authors: List<String>,
    val isbn13: String?,
    val year: Int?,
    val source: String,
    val coverUrl: String? = null,
)

/**
 * Van herkende covertekst naar een zoekopdracht, en kandidaten op volgorde zetten.
 * Titel en auteur zijn meestal de grootste regels; welke van de twee bovenaan staat, verschilt.
 */
object CoverText {

    private val stopwords = setOf(
        "de", "het", "een", "van", "en", "in", "op", "der", "den", "voor", "met", "aan", "te",
        "the", "a", "an", "of", "and", "to", "for", "der", "die", "das", "und", "le", "la", "les", "et", "du",
        "roman", "thriller", "novel", "auteur", "bestseller", "nederlandse", "vertaling",
    )
    private val wordPattern = Regex("""[\p{L}\p{N}]+""")

    /** De grootste regels (maximaal drie) als zoektekst, in leesvolgorde. */
    fun searchText(lines: List<CoverLine>): String {
        val usable = lines.filter { keywords(it.text).isNotEmpty() }
        val biggest = usable.sortedByDescending { it.height }.take(3).toSet()
        return usable.filter { it in biggest }.joinToString(" ") { it.text.trim() }
    }

    /** Woorden die iets zeggen: minstens drie tekens, geen lidwoorden of flapteksten; kleine letters, accenten blijven. */
    fun keywords(text: String): List<String> =
        wordPattern.findAll(text.lowercase(Locale.ROOT)).map { it.value }
            .filter { it.length >= 3 && TextNormalizer.normalize(it) !in stopwords && !it.all(Char::isDigit) }
            .distinct().toList()

    /**
     * Woorden om de catalogus mee te doorzoeken: het langste woord van elk van de drie grootste regels.
     * Titel en auteur staan daar meestal tussen, maar de auteur kan over twee regels verdeeld zijn.
     */
    fun searchWords(lines: List<CoverLine>, query: String): List<String> {
        val fromLines = lines.sortedByDescending { it.height }
            .mapNotNull { line -> keywords(line.text).maxByOrNull { it.length } }
            .distinct().take(3)
        return fromLines.ifEmpty { keywords(query).sortedByDescending { it.length }.take(3) }
    }

    private val namePart = Regex("""[\p{Lu}][\p{L}.'-]*""")

    /** Lijkt op een naam: twee tot vier woorden die met een hoofdletter beginnen (ook in hoofdletters). */
    fun looksLikeName(text: String): Boolean {
        val parts = text.trim().split(Regex("\\s+"))
        return parts.size in 2..4 && parts.all { namePart.matches(it) } && keywords(text).isNotEmpty()
    }

    /**
     * Voorstel voor het formulier: een regel die op een naam lijkt wordt de auteur, de grootste
     * andere regel met echte woorden de titel. Losse grote letters (vormgeving) tellen niet mee.
     */
    fun guessTitleAndAuthor(lines: List<CoverLine>): Pair<String, String> {
        val usable = lines.filter { keywords(it.text).isNotEmpty() }.sortedByDescending { it.height }
        val author = usable.firstOrNull { looksLikeName(it.text) }
        val authorWords = author?.let { keywords(it.text).toSet() }.orEmpty()
        // Een regel met alleen (een deel van) de naam is geen titel.
        val title = usable.firstOrNull { it != author && !authorWords.containsAll(keywords(it.text)) }
        return title?.text?.let(::tidy).orEmpty() to author?.text?.let(::tidy).orEmpty()
    }

    /** Covers zetten titel en naam vaak in hoofdletters: "ILYAZ NASRULLAH" → "Ilyaz Nasrullah". */
    private fun tidy(text: String): String {
        val trimmed = text.trim()
        if (trimmed.any(Char::isLowerCase)) return trimmed
        return trimmed.lowercase().split(' ').joinToString(" ") { word -> word.replaceFirstChar(Char::titlecase) }
    }

    /** Hoeveel woorden van de zoektekst in titel en auteur voorkomen; beste eerst, dubbele ISBN's eruit. */
    fun rank(candidates: List<SearchCandidate>, query: String): List<SearchCandidate> {
        val words = keywords(query).map(TextNormalizer::normalize).toSet()
        fun score(c: SearchCandidate): Int {
            val haystack = TextNormalizer.normalize(listOfNotNull(c.title, c.subtitle).joinToString(" ") + " " + c.authors.joinToString(" "))
            val found = TextNormalizer.normalize(haystack).split(' ').toSet()
            return words.count { it in found }
        }
        return candidates
            .map { it to score(it) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<SearchCandidate, Int>> { it.second }.thenBy { it.first.isbn13 == null })
            .map { it.first }
            .distinctBy { it.isbn13 ?: (TextNormalizer.normalize(it.title) + "|" + it.authors.firstOrNull()) }
    }
}
