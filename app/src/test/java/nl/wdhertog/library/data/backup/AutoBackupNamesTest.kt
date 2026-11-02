package nl.wdhertog.library.data.backup

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AutoBackupNamesTest {

    @Test
    fun `keeps the newest automatic backups and never touches other files`() {
        val names = (1..7).map { AutoBackupNames.name(LocalDate.of(2026, 10, it * 3)) } +
            listOf("bibliotheek-2026-11-02.zip", "notities.txt")
        assertEquals(
            listOf("bibliotheek-auto-2026-10-06.zip", "bibliotheek-auto-2026-10-03.zip"),
            AutoBackupNames.toDelete(names),
        )
    }
}
