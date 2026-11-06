package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.AuthorSummary
import nl.wdhertog.library.util.TextNormalizer

/** Twee auteurs die waarschijnlijk dezelfde zijn; [keep] heeft de meeste boeken en blijft bij samenvoegen over. */
data class AuthorDuplicate(val keep: AuthorSummary, val other: AuthorSummary) {
    /** Blijft gelijk, ook als de volgorde verandert; om "negeren" te onthouden. */
    val key: String get() = listOf(keep.id, other.id).sorted().joinToString("-")
}

/**
 * Herkent dezelfde auteur in een andere schrijfwijze: "J.R.R. Tolkien", "J. R. R. Tolkien",
 * "Tolkien, J.R.R." en "Tolkién". Voorzichtig: voornaam en voorletters ("John Tolkien" en "J. Tolkien")
 * gelden niet als gelijk.
 */
object AuthorDuplicates {
    private val separators = Regex("[\\s.]+")

    fun find(authors: List<AuthorSummary>, ignored: Set<String> = emptySet()): List<AuthorDuplicate> =
        authors.groupBy { key(it.name) }.values
            .filter { it.size >= 2 }
            .flatMap { group ->
                val keep = group.sortedWith(compareByDescending<AuthorSummary> { it.books }.thenBy { it.id }).first()
                group.filter { it.id != keep.id }.map { AuthorDuplicate(keep, it) }
            }
            .filter { it.key !in ignored }

    /** "Tolkien, J.R.R." → "jrr tolkien"; losse voorletters aan elkaar, accenten en hoofdletters weg. */
    internal fun key(name: String): String {
        val turned = name.split(',').map(String::trim).let { parts ->
            if (parts.size == 2 && parts.all { it.isNotEmpty() }) "${parts[1]} ${parts[0]}" else name
        }
        val words = TextNormalizer.normalize(turned).split(separators).filter(String::isNotEmpty)
        // Losse voorletters aan elkaar: "j r r tolkien" (uit "J. R. R.") en "jrr tolkien" (uit "J.R.R.") worden gelijk.
        val tokens = mutableListOf<String>()
        val initials = StringBuilder()
        for (word in words) {
            if (word.length == 1) {
                initials.append(word)
            } else {
                if (initials.isNotEmpty()) tokens += initials.toString().also { initials.clear() }
                tokens += word
            }
        }
        if (initials.isNotEmpty()) tokens += initials.toString()
        return tokens.joinToString(" ")
    }
}
