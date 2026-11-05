package nl.wdhertog.library.scan

object Isbn {

    /** Haalt streepjes, spaties en andere tekens weg; laat cijfers en een eventuele X staan. */
    fun clean(raw: String): String = raw.filter { it.isDigit() || it == 'X' || it == 'x' }.uppercase()

    fun isValidIsbn13(isbn: String): Boolean {
        if (isbn.length != 13 || !isbn.all(Char::isDigit)) return false
        val sum = isbn.mapIndexed { i, c -> c.digitToInt() * if (i % 2 == 0) 1 else 3 }.sum()
        return sum % 10 == 0
    }

    fun isValidIsbn10(isbn: String): Boolean {
        if (isbn.length != 10 || !isbn.take(9).all(Char::isDigit)) return false
        val last = isbn[9]
        val lastValue = when {
            last.isDigit() -> last.digitToInt()
            last == 'X' -> 10
            else -> return false
        }
        val sum = isbn.take(9).mapIndexed { i, c -> c.digitToInt() * (10 - i) }.sum() + lastValue
        return sum % 11 == 0
    }

    /**
     * Geeft een geldig ISBN-13 terug voor elke geldige ISBN-10/13-invoer, anders null. Een 13-cijferige
     * code moet met 978 of 979 beginnen: andere EAN-13's (een pak koffie) zijn geen boek.
     */
    fun toIsbn13(raw: String): String? {
        val isbn = clean(raw)
        return when {
            isValidIsbn13(isbn) -> isbn.takeIf { it.startsWith("978") || it.startsWith("979") }
            isValidIsbn10(isbn) -> {
                val body = "978" + isbn.take(9)
                body + checkDigit13(body)
            }
            else -> null
        }
    }

    /** ISBN-10 bestaat alleen voor 978-nummers. */
    fun toIsbn10(isbn13: String): String? {
        if (!isValidIsbn13(isbn13) || !isbn13.startsWith("978")) return null
        val body = isbn13.substring(3, 12)
        val sum = body.mapIndexed { i, c -> c.digitToInt() * (10 - i) }.sum()
        val check = (11 - sum % 11) % 11
        return body + if (check == 10) "X" else check.toString()
    }

    private fun checkDigit13(body12: String): Int {
        val sum = body12.mapIndexed { i, c -> c.digitToInt() * if (i % 2 == 0) 1 else 3 }.sum()
        return (10 - sum % 10) % 10
    }
}
