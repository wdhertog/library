package nl.wdhertog.library.util

/**
 * De taal die bij een ISBN hoort, uit de registratiegroep (het begin van het ISBN): 978-90 en 978-94
 * Nederlands, 978-0 en 978-1 Engels, enzovoort. Een gok, maar wel een goede: uitgevers gebruiken de
 * groep van hun eigen land of taalgebied. Groepen met meerdere grote talen (bijv. 978-80, Tsjechië en
 * Slowakije) staan er niet in.
 */
object IsbnLanguage {

    /** Langste voorvoegsel eerst. */
    private val groups: List<Pair<String, String>> = listOf(
        "97890" to "nl", "97894" to "nl",
        "9780" to "en", "9781" to "en",
        "9782" to "fr", "97910" to "fr",
        "9783" to "de",
        "97884" to "es",
        "97888" to "it", "97912" to "it",
        "97887" to "da", "97891" to "sv", "97882" to "no", "97883" to "pl", "97885" to "pt",
        "978951" to "fi", "978952" to "fi", "978963" to "hu",
        "978972" to "pt", "978989" to "pt",
        "978960" to "el", "978618" to "el",
        "978975" to "tr", "978605" to "tr",
        "978973" to "ro", "978606" to "ro",
        "9784" to "ja", "9785" to "ru",
    ).sortedByDescending { it.first.length }

    /** Talen die in zo'n land of taalgebied gewoon ook worden uitgegeven: geen reden om te twijfelen. */
    private val alsoCommon: Map<String, Set<String>> = mapOf(
        "nl" to setOf("fy", "li"),
        "es" to setOf("ca", "eu", "gl"),
        "fr" to setOf("br", "oc", "co"),
        "it" to setOf("de", "lld", "sc"),
        "de" to setOf("lb", "rm"),
        "fi" to setOf("sv"),
        "no" to setOf("nn", "nb"),
        "ru" to setOf("uk", "be"),
    )

    fun guess(isbn13: String?): String? {
        val isbn = isbn13?.filter(Char::isDigit)?.takeIf { it.length == 13 } ?: return null
        return groups.firstOrNull { isbn.startsWith(it.first) }?.second
    }

    /** Spreekt [language] het ISBN tegen? Alleen als de groep een duidelijke taal heeft. */
    fun conflicts(isbn13: String?, language: String?): Boolean {
        val lang = language?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return false
        val expected = guess(isbn13) ?: return false
        return lang != expected && lang !in alsoCommon[expected].orEmpty()
    }

    /** Tag waarmee boeken gemarkeerd worden waarvan de taal niet bij het ISBN lijkt te passen. */
    const val CHECK_TAG = "taal controleren"
}
