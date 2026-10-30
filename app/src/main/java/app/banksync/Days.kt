package app.banksync

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Headings that group Recent activity by day, the way a bank statement reads. */
object Days {
    /**
     * "Today", "Yesterday", the weekday for the rest of the past week ("Monday"), then the date
     * ("26 Oct"), with the year only when it isn't this one ("26 Oct 2025").
     */
    fun label(day: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String {
        val ago = ChronoUnit.DAYS.between(day, today)
        val pattern = when {
            ago == 0L -> return "Today"
            ago == 1L -> return "Yesterday"
            ago in 2..6 -> "EEEE"
            day.year == today.year -> "d MMM"
            else -> "d MMM yyyy"
        }
        return day.format(DateTimeFormatter.ofPattern(pattern, locale))
    }
}
