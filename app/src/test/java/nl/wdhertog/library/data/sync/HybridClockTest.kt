package nl.wdhertog.library.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridClockTest {

    @Test
    fun `keeps increasing when the wall clock jumps back`() {
        var wall = 1_000L
        val clock = HybridClock("a", wallClock = { wall })
        val first = clock.now()
        wall = 500 // klok teruggezet
        val second = clock.now()
        val third = clock.now()
        assertTrue(second > first)
        assertTrue(third > second)
        assertEquals(1_000L, third.millis)
        assertEquals(2, third.counter)
    }

    @Test
    fun `a clock far in the future is not taken over`() {
        val clock = HybridClock("a", wallClock = { 1_000L })
        val day = 24 * 60 * 60_000L
        val after = clock.receive(HybridClock.Timestamp(1_000L + 2 * day, 0, "b"))
        assertEquals(1_000L, after.millis)
    }

    @Test
    fun `counter rolls over into the next millisecond`() {
        val clock = HybridClock("a", wallClock = { 1_000L })
        clock.restore(HybridClock.Timestamp(1_000L, 99_999, "a").toString())
        val next = clock.now()
        assertEquals(1_001L, next.millis)
        assertEquals(0, next.counter)
        assertEquals(next.toString().length, HybridClock.Timestamp(1_000L, 0, "a").toString().length)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { HybridClock.Timestamp.parse("12-1-x") }
    }

    @Test
    fun `a newer remote time is taken over`() {
        val clock = HybridClock("a", wallClock = { 1_000L })
        clock.now()
        // De andere telefoon loopt vooruit (of heeft een verkeerde klok).
        val afterReceive = clock.receive(HybridClock.Timestamp(5_000, 3, "b"))
        assertEquals(5_000L, afterReceive.millis)
        assertEquals(4, afterReceive.counter)
        assertTrue(clock.now() > HybridClock.Timestamp(5_000, 3, "b"))
    }

    @Test
    fun `text order equals time order and survives a restart`() {
        val early = HybridClock.Timestamp(999, 12, "zzz").toString()
        val late = HybridClock.Timestamp(1_000, 0, "aaa").toString()
        assertTrue(early < late)
        assertEquals(HybridClock.Timestamp(1_000, 0, "aaa"), HybridClock.Timestamp.parse(late))

        val restarted = HybridClock("a", wallClock = { 10L })
        restarted.restore(HybridClock.Timestamp(1_000, 7, "a").toString())
        assertEquals(HybridClock.Timestamp(1_000, 8, "a"), restarted.now())
    }

    @Test
    fun `same moment on two phones still has a fixed order`() {
        val a = HybridClock.Timestamp(1_000, 0, "a")
        val b = HybridClock.Timestamp(1_000, 0, "b")
        assertTrue(a < b)
        assertTrue(a.toString() < b.toString())
    }
}
