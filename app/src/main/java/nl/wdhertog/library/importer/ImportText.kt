package nl.wdhertog.library.importer

/** Tekst-hulpjes die meerdere importformaten delen (Have I Read That, Goodreads). */
object ImportText {
    private val seriesInTitle = Regex("""^(.*?)\s*\(([^()]*?),?\s*#\s*([\w.]+)\)\s*$""")

    data class TitleParts(val title: String, val seriesName: String?, val seriesIndex: String?)

    /** "Duin (Duin, #1)" → titel "Duin", serie "Duin", deel "1". Zonder serie blijft de titel zoals hij is. */
    fun splitSeries(title: String): TitleParts {
        val match = seriesInTitle.matchEntire(title)
        val parts = if (match != null) TitleParts(match.groupValues[1], match.groupValues[2].trim(), match.groupValues[3])
        else TitleParts(title, null, null)
        return parts.copy(title = parts.title.trim().removeSuffix(":").trim())
    }
}
