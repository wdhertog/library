package nl.wdhertog.library.util

import java.util.Locale

object Languages {

    private val dutch = Locale.forLanguageTag("nl")

    /** De vier hoofdtalen van de collectie bovenaan. */
    val primary = listOf("nl", "en", "de", "fr")

    val others: List<String> by lazy {
        Locale.getISOLanguages()
            .filter { it !in primary && it.length == 2 }
            .sortedBy { displayName(it) }
    }

    /**
     * Taalcode bij een code ("nl", "dut") of een naam in het Nederlands, Engels of de taal zelf
     * ("Nederlands", "Dutch", "ITALIANO", "español"); null als die niet te herkennen is.
     */
    fun codeForName(text: String): String? {
        LanguageCodes.toIso1(text)?.takeIf { it in Locale.getISOLanguages() }?.let { return it }
        val name = TextNormalizer.normalize(text)
        return Locale.getISOLanguages().firstOrNull { code ->
            val locale = Locale.forLanguageTag(code)
            listOf(dutch, Locale.ENGLISH, locale).any { TextNormalizer.normalize(locale.getDisplayLanguage(it)) == name }
        }
    }

    fun displayName(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(dutch)
            .replaceFirstChar { it.titlecase(dutch) }
            .ifBlank { code }
}
