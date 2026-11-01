package nl.wdhertog.library.util

import java.text.Normalizer
import java.util.Locale

object TextNormalizer {

    private val diacritics = Regex("\\p{Mn}+")
    private val whitespace = Regex("\\s+")

    /** Kleine letters, zonder accenten: "Café Ü" → "cafe u". */
    fun normalize(text: String): String =
        Normalizer.normalize(text.replace("ß", "ss"), Normalizer.Form.NFD)
            .replace(diacritics, "")
            .lowercase(Locale.ROOT)
            .replace(whitespace, " ")
            .trim()

    fun searchKey(parts: List<String?>): String =
        parts.filterNot { it.isNullOrBlank() }.joinToString(" ") { normalize(it!!) }

    private val leadingArticles = listOf(
        "de ", "het ", "een ", "'t ",
        "the ", "a ", "an ",
        "der ", "die ", "das ", "ein ", "eine ",
        "le ", "la ", "les ", "un ", "une ", "l'",
    )

    /** Sorteersleutel voor titels: lidwoord vooraan telt niet mee ("De avonden" → "avonden"). */
    fun titleSortKey(title: String): String {
        val normalized = normalize(title)
        val article = leadingArticles.firstOrNull { normalized.startsWith(it) && normalized.length > it.length }
        return if (article != null) normalized.removePrefix(article).trimStart() else normalized
    }

    private val nameParticles = setOf(
        "van", "de", "der", "den", "het", "ter", "ten", "te", "'t", "in",
        "von", "zu", "vom", "du", "des", "le", "la", "di", "da", "del",
    )

    /**
     * Sorteersleutel voor auteurs op achternaam, met tussenvoegsels na de achternaam
     * zoals in Nederlandse catalogi: "Jan van der Berg" → "berg jan van der".
     */
    fun authorSortKey(name: String): String {
        val trimmed = name.trim()
        if (trimmed.contains(',')) return normalize(trimmed.replace(",", " "))
        val tokens = trimmed.split(whitespace)
        if (tokens.size < 2) return normalize(trimmed)
        var start = tokens.lastIndex
        while (start > 1 && tokens[start - 1].lowercase(Locale.ROOT) in nameParticles) start--
        val particles = tokens.subList(start, tokens.lastIndex)
        val firstNames = tokens.subList(0, start)
        return normalize((listOf(tokens.last()) + firstNames + particles).joinToString(" "))
    }
}
