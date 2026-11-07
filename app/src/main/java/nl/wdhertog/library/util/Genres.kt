package nl.wdhertog.library.util

/**
 * Een vaste Nederlandse lijst van genres (het veld Work.genres). Namen uit Wikidata, Open Library en je
 * eigen tags vallen daarop samen:
 * - een **synoniem** ("krimi", "sci-fi", "kinderliteratuur") ís het genre en verdwijnt uit de tags;
 * - een **subgenre** ("high fantasy", "cyberpunk", "legal thriller") geeft het hoofdgenre, en blijft als
 *   tag staan om verder te verfijnen.
 * Andere namen zijn geen genre in deze lijst en blijven gewoon tag.
 */
object Genres {

    private class Entry(val genre: String, val synonyms: List<String>, val subgenres: List<String> = emptyList())

    private val list = listOf(
        Entry("fantasy", listOf("fantasy fiction", "fantastic fiction", "english fantasy fiction"),
            listOf("high fantasy", "historical fantasy", "science fantasy", "epic fantasy", "urban fantasy", "juvenile fantasy")),
        Entry("sciencefiction", listOf("science fiction", "sci-fi", "scifi"),
            listOf("zachte sciencefiction", "harde sciencefiction", "maatschappelijke sciencefiction", "militaire sciencefiction", "planetary romance", "space opera", "cyberpunk")),
        Entry("dystopie", listOf("dystopische fictie", "dystopian fiction", "dystopia", "dystopias")),
        Entry("detective", listOf("krimi", "detectivefictie", "detectiveverhaal", "misdaadliteratuur", "misdaadroman", "mysterie",
            "detective and mystery stories", "mystery and detective stories", "mystery fiction", "crime fiction"),
            listOf("historical mystery")),
        Entry("thriller", listOf("thrillers", "suspense"),
            listOf("psychologische thriller", "legal thriller", "politieke thriller", "psychological thriller")),
        Entry("spionage", listOf("spy-fiction", "spy-novel", "spy fiction", "spionageroman", "spy stories")),
        Entry("historische roman", listOf("historische fictie", "historical fiction")),
        Entry("avontuur", listOf("avonturenfictie", "avonturenroman", "adventure stories", "adventure fiction")),
        Entry("kinderboek", listOf("kinderliteratuur", "kinderfictie", "kinder- en jeugdliteratuur", "children's fiction", "children's literature", "juvenile fiction")),
        Entry("jeugd", listOf("young adult fiction", "young adult", "jeugdliteratuur", "jeugdboek")),
        Entry("humor", listOf("humour", "comic novel", "humorous fiction", "humorous stories"), listOf("komedie", "tragikomedie")),
        Entry("romantiek", listOf("romance", "romantische fictie", "love stories", "romance fiction")),
        Entry("horror", listOf("horror fiction", "horror tales", "griezelroman")),
        Entry("biografie", listOf("biography"), listOf("autobiografie", "autobiography")),
        Entry("memoires", listOf("memoir", "memoirs")),
        Entry("geschiedenis", listOf("history")),
        Entry("militaire geschiedenis", listOf("militaire-geschiedenis", "military history")),
        Entry("filosofie", listOf("philosophy")),
        Entry("poëzie", listOf("poetry", "gedichten")),
        Entry("strip", listOf("graphic-novel", "graphic novel", "graphic novels", "comics", "comic books, strips, etc", "stripverhaal")),
        Entry("naslag", listOf("reference", "naslagwerk", "dictionaries"), listOf("woordenboek")),
        Entry("zelfhulp", listOf("self-help", "self help")),
        Entry("essay", listOf("essays")),
        Entry("mythologie", listOf("mythology")),
        Entry("sprookjes", listOf("sprookje", "fairy tales", "tale")),
        Entry("oorlogsroman", listOf("war stories", "war fiction")),
        Entry("wetenschap", listOf("science", "popular science")),
        Entry("kunst", listOf("art")),
        Entry("non-fictie", listOf("non-fictieliteratuur", "nonfiction", "non-fiction")),
        Entry("fictie", listOf("fiction")),
    )

    /** De genres van de lijst, voor kiezen en filteren. */
    val all: List<String> = list.map { it.genre }.sorted()

    private val genreOf: Map<String, String> = buildMap {
        list.forEach { e -> (listOf(e.genre) + e.synonyms + e.subgenres).forEach { put(it, e.genre) } }
    }
    private val subgenres: Set<String> = list.flatMap { it.subgenres }.toSet()

    /** Het genre van de lijst bij deze naam (genre, synoniem of subgenre); null = geen genre uit de lijst. */
    fun genreOf(name: String): String? = genreOf[name.trim().lowercase()]

    /** Een subgenre blijft als tag staan (om te verfijnen); genres en synoniemen niet. */
    fun isSubgenre(name: String): Boolean = name.trim().lowercase() in subgenres

    /** Tags splitsen in genres (voor Work.genres) en de tags die blijven (eigen tags en subgenres). */
    fun split(tags: List<String>): Pair<List<String>, List<String>> {
        val clean = tags.map(String::trim).filter(String::isNotEmpty)
        val genres = clean.mapNotNull(::genreOf).distinct()
        val remaining = clean.filter { genreOf(it) == null || isSubgenre(it) }.distinctBy(String::lowercase)
        return genres to remaining
    }

    /**
     * Genres die we uit Open Library overnemen. De onderwerpen daar komen van alle edities samen (ook een
     * stripversie of kinderuitgave van hetzelfde werk), dus geen doelgroep of vorm: alleen echte genres.
     */
    private val fromOpenLibraryAllowed = setOf(
        "fantasy", "sciencefiction", "dystopie", "detective", "thriller", "spionage", "historische roman", "horror",
        "romantiek", "biografie", "memoires", "poëzie", "zelfhulp", "mythologie", "oorlogsroman", "humor",
    )

    fun fromOpenLibrary(subject: String): String? = genreOf(subject)?.takeIf { it in fromOpenLibraryAllowed }

    /** Komma-gescheiden opslag in Work.genres; op lijstvolgorde, zonder dubbelen. */
    fun encode(genres: Collection<String>): String? = genres.mapNotNull(::genreOf).distinct().sorted().joinToString(", ").ifEmpty { null }

    fun decode(text: String?): List<String> = text?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
}
