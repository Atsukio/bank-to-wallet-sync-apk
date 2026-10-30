package app.banksync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class DaysTest {
    // A Friday.
    private val today = LocalDate.of(2026, 10, 30)

    private fun label(day: LocalDate) = Days.label(day, today, Locale.UK)

    @Test
    fun todayAndYesterdayByName() {
        assertEquals("Today", label(today))
        assertEquals("Yesterday", label(today.minusDays(1)))
    }

    @Test
    fun theRestOfTheWeekByWeekday() {
        assertEquals("Wednesday", label(today.minusDays(2)))
        assertEquals("Saturday", label(today.minusDays(6)))
    }

    @Test
    fun olderDaysByDateWithTheYearOnlyWhenItDiffers() {
        assertEquals("23 Oct", label(today.minusDays(7)))
        assertEquals("1 Jan", label(LocalDate.of(2026, 1, 1)))
        assertEquals("31 Dec 2025", label(LocalDate.of(2025, 12, 31)))
    }

    @Test
    fun aDayAheadOfThePhoneClockIsShownAsADate() {
        assertEquals("31 Oct", label(today.plusDays(1)))
    }
}
