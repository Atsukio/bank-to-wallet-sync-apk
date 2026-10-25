package app.banksync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The optional evening summary. One inexact, non-waking alarm a day: it fires the next time the phone
 * is awake after 9 pm, so it costs no extra wake-ups. Alarms don't survive a reboot; the listener
 * reconnecting after one schedules it again.
 */
object Summary {
    private val AT: LocalTime = LocalTime.of(21, 0)

    /** The next 9 pm after [now]. */
    fun nextAt(now: Long, zone: ZoneId): Long {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val today = t.with(AT)
        return (if (today.isAfter(t)) today else today.plusDays(1)).toInstant().toEpochMilli()
    }

    /** The start of the day [now] falls in. */
    fun dayStart(now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** What the notification says; null on a day with nothing to report. */
    fun text(sent: Int, waiting: Int, needsYou: Int): String? {
        if (sent == 0 && waiting == 0 && needsYou == 0) return null
        return listOfNotNull(
            "$sent sent to Wallet",
            "$waiting waiting to send".takeIf { waiting > 0 },
            "$needsYou need${if (needsYou == 1) "s" else ""} you".takeIf { needsYou > 0 },
        ).joinToString(" · ")
    }

    /** Sets or clears the alarm to match the setting. */
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, SummaryReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (Settings(context).dailySummary) {
            am.set(AlarmManager.RTC, nextAt(System.currentTimeMillis(), ZoneId.systemDefault()), pi)
        } else {
            am.cancel(pi)
        }
    }
}

class SummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Settings(context).dailySummary) return
        val now = System.currentTimeMillis()
        val store = Store.get(context)
        val today = store.countsSince(Summary.dayStart(now, ZoneId.systemDefault()))
        val all = store.counts()
        // Today's sends, but everything still waiting or needing attention, whenever it arrived.
        Summary.text(today[Store.SYNCED], all[Store.PENDING], all[Store.FAILED] + all[Store.REVIEW] + all[Store.UNPARSED])
            ?.let { Notifier.summary(context, it) }
        Summary.schedule(context)
    }
}
