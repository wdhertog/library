package nl.wdhertog.library.data.sync

/**
 * Hybride logische klok (HLC): wandtijd plus teller plus telefoon-id. Tijdstempels zijn als tekst
 * te vergelijken, lopen op deze telefoon altijd op (ook als de klok terugspringt), en nemen een
 * nieuwere tijd van de andere telefoon over. Zo kan een telefoon met een verkeerd ingestelde klok
 * geen nieuwere wijzigingen overschrijven, en hebben twee gelijktijdige wijzigingen toch een vaste volgorde.
 */
class HybridClock(private val deviceId: String, private val wallClock: () -> Long = System::currentTimeMillis) {

    data class Timestamp(val millis: Long, val counter: Int, val deviceId: String) : Comparable<Timestamp> {
        override fun compareTo(other: Timestamp): Int =
            compareValuesBy(this, other, Timestamp::millis, Timestamp::counter, Timestamp::deviceId)

        /** Vaste breedte, zodat tekstvolgorde gelijk is aan tijdvolgorde (ook in SQL). */
        override fun toString(): String = "%015d-%05d-%s".format(millis, counter, deviceId)

        companion object {
            private val format = Regex("(\\d{15})-(\\d{5})-(.+)")

            /** Alleen de vaste breedte: een afwijkende tijdstempel zou de tekstvolgorde verstoren. */
            fun parse(text: String): Timestamp {
                val (millis, counter, device) = format.matchEntire(text)?.destructured
                    ?: throw IllegalArgumentException("Ongeldige tijdstempel")
                return Timestamp(millis.toLong(), counter.toInt(), device)
            }
        }
    }

    private var last = Timestamp(0, 0, deviceId)

    /** Doorgaan vanaf een eerder bewaarde stand (na het opnieuw starten van de app). */
    fun restore(saved: String?) {
        saved?.let { last = Timestamp.parse(it).copy(deviceId = deviceId) }
    }

    /** Tijdstempel voor een wijziging op deze telefoon. */
    @Synchronized
    fun now(): Timestamp {
        val wall = wallClock()
        last = if (wall > last.millis) Timestamp(wall, 0, deviceId) else next(last.millis, last.counter + 1)
        return last
    }

    /** Past de teller niet meer in vijf cijfers, dan een milliseconde verder. */
    private fun next(millis: Long, counter: Int) =
        if (counter > MAX_COUNTER) Timestamp(millis + 1, 0, deviceId) else Timestamp(millis, counter, deviceId)

    /** Een tijdstempel van de andere telefoon gezien: de eigen klok loopt daarna nooit achter. */
    @Synchronized
    fun receive(remote: Timestamp): Timestamp {
        val wall = wallClock()
        // Een telefoon met een klok ver in de toekomst mag de klok van alle andere niet vastzetten.
        if (remote.millis > wall + MAX_DRIFT_MILLIS) return now()
        val millis = maxOf(wall, last.millis, remote.millis)
        val counter = when {
            millis == last.millis && millis == remote.millis -> maxOf(last.counter, remote.counter) + 1
            millis == last.millis -> last.counter + 1
            millis == remote.millis -> remote.counter + 1
            else -> 0
        }
        last = next(millis, counter)
        return last
    }

    private companion object {
        const val MAX_COUNTER = 99_999
        const val MAX_DRIFT_MILLIS = 24 * 60 * 60_000L
    }

    val current: Timestamp get() = last
}
