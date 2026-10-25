package app.banksync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SummaryTest {
    private val zone = ZoneId.of("Asia/Kuala_Lumpur")
    private fun at(h: Int, m: Int, day: Int = 25) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun nextIsTonightBeforeNineAndTomorrowAfter() {
        assertEquals(at(21, 0), Summary.nextAt(at(9, 30), zone))
        assertEquals(at(21, 0, 26), Summary.nextAt(at(21, 0), zone))
        assertEquals(at(21, 0, 26), Summary.nextAt(at(23, 15), zone))
    }

    @Test
    fun dayStartsAtMidnight() {
        assertEquals(at(0, 0), Summary.dayStart(at(21, 5), zone))
    }

    @Test
    fun textSaysOnlyWhatMatters() {
        assertNull(Summary.text(0, 0, 0))
        assertEquals("4 sent to Wallet", Summary.text(4, 0, 0))
        assertEquals("0 sent to Wallet · 1 needs you", Summary.text(0, 0, 1))
        assertEquals("2 sent to Wallet · 1 waiting to send · 3 need you", Summary.text(2, 1, 3))
    }
}
