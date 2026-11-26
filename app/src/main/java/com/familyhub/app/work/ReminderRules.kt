package com.familyhub.app.work

import java.util.Calendar
import java.util.Locale

/**
 * Dates and times for reminders and calendar events, in plain Kotlin (no Android code) so they can be tested
 * on any computer (tools/reminder-test).
 *
 * Dates are stored at local noon; a reminder's time is "HH:mm" text, and an empty time means all day (it
 * notifies at [ALL_DAY_HOUR]). Old reminders keep working: nothing is rewritten.
 */
object ReminderRules {
    const val ALL_DAY_HOUR = 9
    private const val DAY_MS = 24 * 60 * 60_000L

    /** Repeat choices shown in the form (stored as shown). */
    val REPEATS = listOf("None", "Daily", "Weekly", "Monthly", "Yearly", "Every few days")
    const val EVERY_FEW_DAYS = "Every few days"

    /** Early alert choices and their minutes before. */
    val EARLY_ALERTS: List<Pair<String, Int>> = listOf(
        "No early alert" to 0,
        "5 minutes before" to 5,
        "15 minutes before" to 15,
        "30 minutes before" to 30,
        "1 hour before" to 60,
        "1 day before" to 24 * 60
    )

    /** Alert choices for calendar events (events used to have none, so the default is none). */
    val EVENT_ALERTS: List<Pair<String, Int?>> = listOf(
        "No alert" to null,
        "At the time" to 0,
        "15 minutes before" to 15,
        "30 minutes before" to 30,
        "1 hour before" to 60,
        "1 day before" to 24 * 60
    )

    fun earlyMinutes(label: String): Int = EARLY_ALERTS.firstOrNull { it.first.equals(label.trim(), true) }?.second ?: 0

    fun eventAlertMinutes(label: String): Int? = EVENT_ALERTS.firstOrNull { it.first.equals(label.trim(), true) }?.second

    /** "HH:mm" from what was typed or picked ("8 pm" -> "20:00"); empty for all day or anything unreadable. */
    fun normalizeTime(text: String): String {
        val t = parseTime(text) ?: return ""
        return String.format(Locale.US, "%02d:%02d", t.first, t.second)
    }

    /** The first time in "08:00", "8:30 pm" or "20.15"; null when empty or unreadable. */
    fun parseTime(text: String): Pair<Int, Int>? {
        val t = text.trim().lowercase(Locale.US).split(",", ";").firstOrNull()?.trim().orEmpty()
        if (t.isEmpty()) return null
        val pm = t.endsWith("pm") || t.endsWith("p.m.")
        val am = t.endsWith("am") || t.endsWith("a.m.")
        val digits = t.replace(Regex("[^0-9:.]"), "").replace('.', ':')
        val parts = digits.split(":").filter { it.isNotEmpty() }
        var h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (pm && h < 12) h += 12
        if (am && h == 12) h = 0
        return if (h in 0..23 && m in 0..59) h to m else null
    }

    /** What lists, the calendar and notifications show: "18:30", or "All day" when there is no time. */
    fun timeText(time: String): String = normalizeTime(time).ifEmpty { "All day" }

    fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun atTime(day: Long, h: Int, m: Int): Long = Calendar.getInstance().apply {
        timeInMillis = day
        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** "20261127" for the day that contains [t]. */
    fun dayKey(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun normRepeat(repeat: String): String = when (repeat.trim().uppercase(Locale.US)) {
        "", "NONE" -> "NONE"
        "EVERY FEW DAYS", "CUSTOM" -> "CUSTOM"
        else -> repeat.trim().uppercase(Locale.US)
    }

    /** The [k]-th repeat of [start], always counted from the original date (so the 31st never drifts to the 28th). */
    private fun nth(start: Long, rep: String, k: Int, everyDays: Int): Long? {
        val c = Calendar.getInstance().apply { timeInMillis = start }
        when (rep) {
            "YEARLY" -> c.add(Calendar.YEAR, k)
            "QUARTERLY" -> c.add(Calendar.MONTH, 3 * k)
            "MONTHLY" -> c.add(Calendar.MONTH, k)
            "WEEKLY" -> c.add(Calendar.DAY_OF_YEAR, 7 * k)
            "DAILY" -> c.add(Calendar.DAY_OF_YEAR, k)
            "CUSTOM" -> c.add(Calendar.DAY_OF_YEAR, everyDays.coerceAtLeast(1) * k)
            else -> return null
        }
        return c.timeInMillis
    }

    /**
     * For repeating items (birthdays, renewals, reminders) the first date on or after the start of [now]'s day.
     * Monthly and yearly repeats are counted from the original date: a reminder on the 31st falls on the last day
     * of shorter months and returns to the 31st; a 29 February birthday is on 28 February in other years.
     */
    fun nextOccurrence(start: Long, repeat: String, now: Long, everyDays: Int = 0): Long {
        val rep = normRepeat(repeat)
        val today0 = startOfDay(now)
        if (rep == "NONE" || start >= today0) return start
        // Jump close first so daily repeats over years stay fast, then step.
        val approxDays = ((today0 - start) / DAY_MS).toInt()
        var k = when (rep) {
            "DAILY" -> approxDays - 1
            "WEEKLY" -> approxDays / 7 - 1
            "CUSTOM" -> approxDays / everyDays.coerceAtLeast(1) - 1
            "MONTHLY" -> approxDays / 31 - 1
            "QUARTERLY" -> approxDays / 92 - 1
            "YEARLY" -> approxDays / 366 - 1
            else -> return start
        }.coerceAtLeast(1)
        var guard = 0
        while (guard < 10_000) {
            val d = nth(start, rep, k, everyDays) ?: return start
            if (d >= today0) {
                // Step back if the jump overshot an earlier match.
                var back = k - 1
                var best = d
                while (back >= 1) {
                    val p = nth(start, rep, back, everyDays) ?: break
                    if (p >= today0) { best = p; back-- } else break
                }
                return best
            }
            k++
            guard++
        }
        return start
    }

    /** One notification for a reminder or event. */
    data class Occurrence(
        val at: Long,
        /** Day and time of the occurrence itself ("20261127_1830"), the same for its early alert. */
        val slot: String,
        val early: Boolean
    )

    /**
     * The notifications a reminder needs between [now] and [horizon]: the on-time one and, if chosen, an early
     * alert. A repeating reminder moves to its next date once today's time has passed. [doneSlot] is an occurrence
     * already marked done (from the notification), which is skipped without ending the repeat.
     */
    fun occurrences(
        date: Long,
        time: String,
        repeat: String,
        everyDays: Int,
        earlyMinutes: Int,
        doneSlot: String,
        now: Long,
        horizon: Long
    ): List<Occurrence> {
        val (h, m) = parseTime(time) ?: (ALL_DAY_HOUR to 0)
        val rep = normRepeat(repeat)
        val out = ArrayList<Occurrence>()
        var from = now
        // This occurrence and, for repeats, the next few (an early alert can be due before an on-time one).
        for (i in 0 until 4) {
            val day = nextOccurrence(date, rep, from, everyDays)
            val at = atTime(day, h, m)
            val slot = dayKey(at) + "_" + String.format(Locale.US, "%02d%02d", h, m)
            if (slot != doneSlot) {
                if (at in (now + 1)..horizon) out.add(Occurrence(at, slot, false))
                if (earlyMinutes > 0) {
                    val e = at - earlyMinutes * 60_000L
                    if (e in (now + 1)..horizon) out.add(Occurrence(e, slot, true))
                }
            }
            if (rep == "NONE" || at - earlyMinutes * 60_000L > horizon) break
            // The day after this occurrence; the next date is again counted from the original date.
            from = startOfDay(day) + DAY_MS
        }
        return out.sortedBy { it.at }
    }

    /** "In 15 minutes", "In 1 hour", "Tomorrow" for early alerts. */
    fun earlyText(minutes: Int): String = when {
        minutes >= 24 * 60 -> "Tomorrow"
        minutes >= 60 && minutes % 60 == 0 -> "In ${minutes / 60} hour" + if (minutes / 60 == 1) "" else "s"
        else -> "In $minutes minutes"
    }
}
