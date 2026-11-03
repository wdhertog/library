package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.SeriesSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesDuplicatesTest {

    private var nextId = 1L
    private fun series(name: String, works: Int = 1) = SeriesSummary(nextId++, name, null, works, works, 0, null)

    private fun pairs(vararg names: Pair<String, Int>) =
        SeriesDuplicates.find(names.map { (name, works) -> series(name, works) }).map { it.keep.name to it.other.name }

    @Test
    fun `same name apart from words like series`() {
        assertEquals(listOf("Arsène Lupin" to "Arsène Lupin literary series"), pairs("Arsène Lupin" to 3, "Arsène Lupin literary series" to 2))
        assertEquals(listOf("Zeven zussen" to "De zeven zussen-serie"), pairs("De zeven zussen-serie" to 2, "Zeven zussen" to 5))
    }

    @Test
    fun `one name is the start of the other`() {
        assertEquals(listOf("Harry Potter" to "Harry Potter boeken"), pairs("Harry Potter" to 4, "Harry Potter boeken" to 1))
    }

    @Test
    fun `different series that only look alike are left alone`() {
        assertEquals(
            emptyList<Pair<String, String>>(),
            pairs(
                "Ranger's Apprentice The Royal Ranger" to 1, "Ranger's Apprentice: The Early Years" to 1,
                "Department Q" to 8, "Serie Q" to 1, "Penguin classics" to 1, "Chronicles of Narnia" to 1,
                "De Grijze Jager" to 2, "Commissario Montalbano" to 1,
            ),
        )
    }

    @Test
    fun `ignored pairs stay away`() {
        val list = listOf(series("Arsène Lupin", 3), series("Arsène Lupin literary series", 2))
        val found = SeriesDuplicates.find(list).single()
        assertEquals(emptyList<SeriesDuplicate>(), SeriesDuplicates.find(list, setOf(found.key)))
    }
}
