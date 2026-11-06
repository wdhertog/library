package nl.wdhertog.library.data.repository

/** Wat er nodig is om edities van één werk te ordenen. */
data class EditionFacts(
    val uuid: String,
    val owned: Boolean,
    val hasCover: Boolean,
    val language: String?,
    val addedAtMillis: Long,
)

/**
 * Welke editie "het" boek is voor een werk (ontwerp §12.3). De zelf gekozen hoofdeditie
 * (Work.primaryBookUuid), en anders: in bezit, met cover, Nederlands, het eerst toegevoegd.
 */
object Editions {

    /** De edities op volgorde, de hoofdeditie eerst. */
    fun <T> ordered(books: List<T>, primaryUuid: String?, facts: (T) -> EditionFacts): List<T> =
        books.sortedWith(
            compareByDescending<T> { primaryUuid != null && facts(it).uuid == primaryUuid }
                .thenByDescending { facts(it).owned }
                .thenByDescending { facts(it).hasCover }
                .thenByDescending { facts(it).language == "nl" }
                .thenBy { facts(it).addedAtMillis },
        )

    fun <T> primaryOf(books: List<T>, primaryUuid: String?, facts: (T) -> EditionFacts): T? =
        ordered(books, primaryUuid, facts).firstOrNull()

    /** Is er een zelf gekozen hoofdeditie, en hoort die nog bij deze edities? */
    fun <T> isChosen(books: List<T>, primaryUuid: String?, facts: (T) -> EditionFacts): Boolean =
        primaryUuid != null && books.any { facts(it).uuid == primaryUuid }
}
