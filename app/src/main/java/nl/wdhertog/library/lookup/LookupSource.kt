package nl.wdhertog.library.lookup

/** De open bronnen, zoals ze in de instellingen aan en uit staan (ontwerp §4, §6.12). */
enum class LookupSource(val key: String, val label: String, val description: String) {
    KB("kb", "Koninklijke Bibliotheek", "Nederlandse Bibliografie, voor ISBN's die met 978-90 of 978-94 beginnen"),
    DNB("dnb", "Deutsche Nationalbibliothek", "Voor Duitse ISBN's (978-3)"),
    BNF("bnf", "Bibliothèque nationale de France", "Voor Franse ISBN's (978-2, 979-10)"),
    BNE("bne", "Biblioteca Nacional de España", "Voor Spaanse ISBN's (978-84) en als aanvulling voor Latijns-Amerika"),
    SBN("sbn", "Servizio Bibliotecario Nazionale", "Voor Italiaanse ISBN's (978-88, 979-12)"),
    OPEN_LIBRARY("openlibrary", "Open Library", "Voor alle andere ISBN's, en als aanvulling op de nationale bibliotheken"),
    WIKIDATA("wikidata", "Wikidata", "Serie, deelnummer, eerste uitgave en de delen van een serie"),
}
