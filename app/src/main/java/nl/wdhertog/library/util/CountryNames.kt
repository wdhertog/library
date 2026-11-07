package nl.wdhertog.library.util

/**
 * Landen van auteurs gelijktrekken voor de badges per land. Wikidata geeft de staat waarvan iemand
 * burger was, ook historische ("Koninkrijk der Nederlanden", "Verenigd Koninkrijk van
 * Groot-Brittannië en Ierland", "Koninkrijk Italië"); die tellen als het land van nu, anders telt
 * hetzelfde land dubbel. Staten zonder duidelijk huidig land (Sovjet-Unie, Oostenrijk-Hongarije,
 * het oude Rome) blijven zoals ze zijn.
 */
object CountryNames {
    private val same = mapOf(
        "koninkrijk der nederlanden" to "Nederland",
        "republiek der zeven verenigde nederlanden" to "Nederland",
        "verenigd koninkrijk van groot-brittannië en ierland" to "Verenigd Koninkrijk",
        "koninkrijk groot-brittannië" to "Verenigd Koninkrijk",
        "koninkrijk engeland" to "Verenigd Koninkrijk",
        "koninkrijk schotland" to "Verenigd Koninkrijk",
        "engeland" to "Verenigd Koninkrijk",
        "schotland" to "Verenigd Koninkrijk",
        "wales" to "Verenigd Koninkrijk",
        "noord-ierland" to "Verenigd Koninkrijk",
        "lombardije-venetië" to "Italië",
        "koninkrijk sardinië" to "Italië",
        "koninkrijk beide siciliën" to "Italië",
        "kerkelijke staat" to "Italië",
        "republiek venetië" to "Italië",
        "duitse keizerrijk" to "Duitsland",
        "duitse rijk" to "Duitsland",
        "tweede poolse republiek" to "Polen",
        "ionische bond" to "Griekenland",
        "weimarrepubliek" to "Duitsland",
        "nazi-duitsland" to "Duitsland",
        "bondsrepubliek duitsland" to "Duitsland",
        "duitse democratische republiek" to "Duitsland",
        "koninkrijk pruisen" to "Duitsland",
        "keizerrijk rusland" to "Rusland",
        "russische rijk" to "Rusland",
        "russische federatie" to "Rusland",
        "unie van zuid-afrika" to "Zuid-Afrika",
        "franse republiek" to "Frankrijk",
        "koninkrijk frankrijk" to "Frankrijk",
        "tweede franse keizerrijk" to "Frankrijk",
        "eerste franse keizerrijk" to "Frankrijk",
        "wu" to "China",
        "volksrepubliek china" to "China",
        "republiek china" to "Taiwan",
        "keizerrijk japan" to "Japan",
        "verenigde staten van amerika" to "Verenigde Staten",
    )

    fun normalize(country: String): String {
        val key = country.trim().lowercase()
        same[key]?.let { return it }
        // "Koninkrijk Denemarken", "Koninkrijk Spanje": het land zelf.
        return Regex("^(koninkrijk|keizerrijk|groothertogdom|vorstendom) ", RegexOption.IGNORE_CASE).replace(country.trim(), "")
            .replaceFirstChar { it.uppercase() }
    }
}
