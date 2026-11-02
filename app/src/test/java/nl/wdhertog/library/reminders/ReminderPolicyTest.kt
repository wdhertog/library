package nl.wdhertog.library.reminders

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReminderPolicyTest {

    private val due = LocalDate.of(2026, 11, 15)

    @Test
    fun `niet voor de herinneringsdatum`() {
        assertFalse(ReminderPolicy.shouldNotify(due, due.minusDays(1), lastNotified = null))
    }

    @Test
    fun `op de dag zelf, en een gemiste dag wordt ingehaald`() {
        assertTrue(ReminderPolicy.shouldNotify(due, due, lastNotified = null))
        assertTrue(ReminderPolicy.shouldNotify(due, due.plusDays(2), lastNotified = null))
    }

    @Test
    fun `daarna eens per week`() {
        assertFalse(ReminderPolicy.shouldNotify(due, due.plusDays(3), lastNotified = due))
        assertTrue(ReminderPolicy.shouldNotify(due, due.plusDays(7), lastNotified = due))
    }

    @Test
    fun `nieuwe datum telt als nieuwe herinnering`() {
        val later = due.plusDays(30)
        assertTrue(ReminderPolicy.shouldNotify(later, later, lastNotified = due.plusDays(10)))
    }
}
