package nl.wdhertog.library.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LoanDatesTest {

    private val today = LocalDate.of(2026, 11, 2)

    @Test
    fun `datum zonder jaar binnen hetzelfde jaar`() {
        assertEquals("15 nov", LoanDates.format(LocalDate.of(2026, 11, 15), today))
        assertEquals("3 jan 2027", LoanDates.format(LocalDate.of(2027, 1, 3), today))
    }

    @Test
    fun `hoe lang geleden`() {
        assertEquals("sinds vandaag", LoanDates.since(today, today))
        assertEquals("sinds gisteren", LoanDates.since(today.minusDays(1), today))
        assertEquals("sinds 5 dagen", LoanDates.since(today.minusDays(5), today))
        assertEquals("sinds 3 weken", LoanDates.since(today.minusDays(21), today))
        assertEquals("sinds 4 maanden", LoanDates.since(today.minusMonths(4), today))
        assertEquals("sinds 3 jaar", LoanDates.since(today.minusYears(3), today))
    }

    @Test
    fun `status terugbrengdatum`() {
        val late = LoanDates.due(today.minusDays(2), today)
        assertEquals("2 dagen te laat", late.text)
        assertTrue(late.overdue)

        assertEquals("1 dag te laat", LoanDates.due(today.minusDays(1), today).text)
        assertTrue(LoanDates.due(today, today).dueSoon)
        assertEquals("terug vóór 4 nov", LoanDates.due(today.plusDays(2), today).text)

        val later = LoanDates.due(today.plusDays(20), today)
        assertFalse(later.overdue)
        assertFalse(later.dueSoon)
    }
}
