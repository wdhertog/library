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
            fun parse(text: String): Timestamp {
                val (millis, counter, device) = text.split('-', limit = 3)
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
        last = if (wall > last.millis) Timestamp(wall, 0, deviceId) else last.copy(counter = last.counter + 1)
        return last
    }

    /** Een tijdstempel van de andere telefoon gezien: de eigen klok loopt daarna nooit achter. */
    @Synchronized
    fun receive(remote: Timestamp): Timestamp {
        val wall = wallClock()
        val millis = maxOf(wall, last.millis, remote.millis)
        val counter = when {
            millis == last.millis && millis == remote.millis -> maxOf(last.counter, remote.counter) + 1
            millis == last.millis -> last.counter + 1
            millis == remote.millis -> remote.counter + 1
            else -> 0
        }
        last = Timestamp(millis, counter, deviceId)
        return last
    }

    val current: Timestamp get() = last
}
