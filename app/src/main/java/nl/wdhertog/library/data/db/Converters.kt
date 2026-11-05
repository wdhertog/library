package nl.wdhertog.library.data.db

import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDate

class Converters {
    @TypeConverter
    fun instantToMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun millisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun localDateToText(value: LocalDate?): String? = value?.toString()

    /** Een ongeldige datum (bijv. uit een oude import of van een andere telefoon) wordt leeg, in plaats van elke lijst te laten crashen. */
    @TypeConverter
    fun textToLocalDate(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
