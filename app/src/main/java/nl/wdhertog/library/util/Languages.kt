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

    fun displayName(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(dutch)
            .replaceFirstChar { it.titlecase(dutch) }
            .ifBlank { code }
}
