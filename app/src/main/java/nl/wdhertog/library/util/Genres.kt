package nl.wdhertog.library.util

/**
 * Een vaste Nederlandse lijst van genres (ontwerp §12, genres), zodat varianten uit Wikidata, Open Library
 * en je eigen tags samenvallen: "krimi", "detectivefictie" en "mysterie" worden "detective", "sci-fi"
 * wordt "sciencefiction". Wat niet in de lijst staat, blijft zoals het is (in kleine letters).
 */
object Genres {

    /** Variant (kleine letters) → genre. */
    private val variants: Map<String, String> = buildMap {
        fun to(genre: String, vararg names: String) = names.forEach { put(it, genre) }
        to("fantasy", "fantasy", "high fantasy", "historical fantasy", "science fantasy", "epic fantasy", "urban fantasy", "fantastic fiction", "fantasy fiction", "english fantasy fiction")
        to("sciencefiction", "sciencefiction", "science fiction", "sci-fi", "scifi", "zachte sciencefiction", "harde sciencefiction", "maatschappelijke sciencefiction", "planetary romance", "space opera", "cyberpunk", "militaire sciencefiction")
        to("dystopie", "dystopische fictie", "dystopian fiction", "dystopie", "dystopia", "dystopias")
        to("detective", "detective", "krimi", "detectivefictie", "detectiveverhaal", "misdaadliteratuur", "misdaadroman", "mysterie", "historical mystery", "detective and mystery stories", "mystery fiction", "crime fiction", "mystery and detective stories")
        to("thriller", "thriller", "thrillers", "psychologische thriller", "legal thriller", "politieke thriller", "psychological thriller", "suspense")
        to("spionage", "spionage", "spy-fiction", "spy-novel", "spy fiction", "spionageroman", "spy stories")
        to("historische roman", "historische roman", "historische fictie", "historical fiction")
        to("avontuur", "avontuur", "avonturenfictie", "avonturenroman", "adventure stories", "adventure fiction")
        to("kinderboek", "kinderboek", "kinderliteratuur", "kinderfictie", "kinder- en jeugdliteratuur", "children's fiction", "children's literature", "juvenile fiction")
        to("jeugd", "jeugd", "young adult fiction", "young adult", "jeugdliteratuur", "jeugdboek")
        to("humor", "humor", "humour", "comic novel", "komedie", "tragikomedie", "humorous fiction", "humorous stories")
        to("romantiek", "romantiek", "romance", "romantische fictie", "love stories", "romance fiction")
        to("horror", "horror", "horror fiction", "horror tales", "griezelroman")
        to("biografie", "biografie", "biography", "autobiografie", "autobiography")
        to("memoires", "memoires", "memoir", "memoirs")
        to("geschiedenis", "geschiedenis", "history")
        to("militaire geschiedenis", "militaire geschiedenis", "militaire-geschiedenis", "military history")
        to("filosofie", "filosofie", "philosophy")
        to("poëzie", "poëzie", "poetry", "gedichten")
        to("strip", "strip", "graphic-novel", "graphic novel", "graphic novels", "comics", "comic books, strips, etc", "stripverhaal")
        to("naslag", "naslag", "reference", "naslagwerk", "dictionaries", "woordenboek")
        to("zelfhulp", "zelfhulp", "self-help", "self help")
        to("essay", "essay", "essays")
        to("mythologie", "mythologie", "mythology")
        to("sprookjes", "sprookje", "sprookjes", "fairy tales", "tale")
        to("oorlogsroman", "oorlogsroman", "war stories", "war fiction")
        to("wetenschap", "wetenschap", "science", "popular science")
        to("kunst", "kunst", "art")
        to("non-fictie", "non-fictie", "non-fictieliteratuur", "nonfiction", "non-fiction")
        to("fictie", "fictie", "fiction")
    }

    /** Het genre voor een tag of label: uit de lijst, of de tekst zelf in kleine letters. */
    fun canonical(name: String): String {
        val key = name.trim().lowercase()
        return variants[key] ?: key
    }

    /**
     * Genres die we uit Open Library overnemen. De onderwerpen daar komen van alle edities samen (ook een
     * stripversie of kinderuitgave van hetzelfde werk), dus geen doelgroep of vorm: alleen echte genres.
     */
    private val fromOpenLibraryAllowed = setOf(
        "fantasy", "sciencefiction", "dystopie", "detective", "thriller", "spionage", "historische roman", "horror",
        "romantiek", "biografie", "memoires", "poëzie", "zelfhulp", "mythologie", "oorlogsroman", "humor",
    )

    /** Alleen bekende genres uit Open Library-subjects (die zijn vaak rommelig); null = overslaan. */
    fun fromOpenLibrary(subject: String): String? = variants[subject.trim().lowercase()]?.takeIf { it in fromOpenLibraryAllowed }

    /** Tags gelijktrekken: varianten naar het genre, dubbelen weg, volgorde behouden. */
    fun normalizeTags(tags: List<String>): List<String> =
        tags.map(String::trim).filter(String::isNotEmpty).map { tag -> variants[tag.lowercase()] ?: tag }.distinctBy(String::lowercase)
}
