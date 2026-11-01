package nl.wdhertog.library.data.db

enum class BookFormat(val label: String) {
    PAPERBACK("Paperback"),
    HARDCOVER("Gebonden"),
    POCKET("Pocket"),
    OTHER("Anders"),
}

enum class Ownership(val label: String) {
    OWNED("Eigen boek"),
    BORROWED("Geleend van iemand"),
    NOT_OWNED("Niet (meer) in bezit"),
}

/** Hoe ver het automatisch opzoeken van gegevens voor een boek is (zie ontwerp §3). */
enum class EnrichStatus {
    NEW,
    QUICK,
    ENRICHED,
    FAILED,
    MANUAL,
}

enum class LocationType(val label: String) {
    ROOM("Kamer"),
    BOOKCASE("Kast"),
    SHELF("Plank"),
    BOX("Doos"),
    OTHER("Anders"),
}

enum class ReadingStatus(val label: String) {
    READING("Bezig"),
    FINISHED("Gelezen"),
    ABANDONED("Gestopt"),
}

enum class DatePrecision {
    DAY,
    YEAR,
}
