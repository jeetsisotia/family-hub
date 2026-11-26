import com.familyhub.app.work.ReminderRules
import java.util.Calendar
import java.util.TimeZone

// Checks for ReminderRules: repeats (including month ends and 29 February), times, all-day reminders,
// early alerts and occurrences marked done. Made-up dates only.

private var failures = 0
private var checks = 0

private fun check(name: String, ok: Boolean, detail: Any? = null) {
    checks++
    if (!ok) {
        failures++
        println("FAIL: $name" + (if (detail != null) " -> $detail" else ""))
    }
}

private fun noon(y: Int, mo: Int, d: Int): Long = Calendar.getInstance().apply {
    clear(); set(y, mo - 1, d, 12, 0, 0)
}.timeInMillis

private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long = Calendar.getInstance().apply {
    clear(); set(y, mo - 1, d, h, mi, 0)
}.timeInMillis

private fun ymd(t: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

/** The repeat code before Phase 7 (one month added at a time), kept here to show the bug it had. */
private fun oldNextOccurrence(start: Long, repeat: String, now: Long): Long {
    val today0 = ReminderRules.startOfDay(now)
    if (start >= today0) return start
    val cal = Calendar.getInstance().apply { timeInMillis = start }
    while (cal.timeInMillis < today0) {
        when (repeat) {
            "MONTHLY" -> cal.add(Calendar.MONTH, 1)
            "YEARLY" -> cal.add(Calendar.YEAR, 1)
        }
    }
    return cal.timeInMillis
}

fun main() {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
    val jan31 = noon(2026, 1, 31)

    // ---- the month-end bug, reproduced with the old code ----
    check("old code: monthly on the 31st drifts to 28 March (the bug)",
        ymd(oldNextOccurrence(jan31, "MONTHLY", at(2026, 3, 20, 10, 0))) == "2026-03-28")
    check("old code: 29 Feb birthday drifts to 28 Feb in 2028 (the bug)",
        ymd(oldNextOccurrence(noon(2024, 2, 29), "YEARLY", at(2028, 1, 10, 10, 0))) == "2028-02-28")

    // ---- fixed ----
    check("monthly 31st: February uses the 28th", ymd(ReminderRules.nextOccurrence(jan31, "Monthly", at(2026, 2, 10, 10, 0))) == "2026-02-28")
    check("monthly 31st: back to 31 March", ymd(ReminderRules.nextOccurrence(jan31, "Monthly", at(2026, 3, 20, 10, 0))) == "2026-03-31",
        ymd(ReminderRules.nextOccurrence(jan31, "Monthly", at(2026, 3, 20, 10, 0))))
    check("monthly 31st: 30 April", ymd(ReminderRules.nextOccurrence(jan31, "MONTHLY", at(2026, 4, 2, 10, 0))) == "2026-04-30")
    check("monthly 31st: a year later still the 31st", ymd(ReminderRules.nextOccurrence(jan31, "MONTHLY", at(2027, 1, 5, 10, 0))) == "2027-01-31")
    check("monthly 30th: February 2028 (leap) uses the 29th",
        ymd(ReminderRules.nextOccurrence(noon(2027, 11, 30), "MONTHLY", at(2028, 2, 1, 9, 0))) == "2028-02-29")
    check("yearly 29 Feb: 28 Feb in 2027", ymd(ReminderRules.nextOccurrence(noon(2024, 2, 29), "Yearly", at(2027, 1, 10, 10, 0))) == "2027-02-28")
    check("yearly 29 Feb: back to 29 Feb in 2028", ymd(ReminderRules.nextOccurrence(noon(2024, 2, 29), "YEARLY", at(2028, 1, 10, 10, 0))) == "2028-02-29")
    check("quarterly from 31 Jan: 30 April then 31 July",
        ymd(ReminderRules.nextOccurrence(jan31, "QUARTERLY", at(2026, 2, 1, 9, 0))) == "2026-04-30" &&
            ymd(ReminderRules.nextOccurrence(jan31, "QUARTERLY", at(2026, 5, 1, 9, 0))) == "2026-07-31")
    check("weekly: next Saturday", ymd(ReminderRules.nextOccurrence(noon(2026, 11, 7), "WEEKLY", at(2026, 11, 26, 10, 0))) == "2026-11-28")
    check("weekly: today counts", ymd(ReminderRules.nextOccurrence(noon(2026, 11, 7), "WEEKLY", at(2026, 11, 28, 23, 0))) == "2026-11-28")
    check("daily from 3 years ago is today", ymd(ReminderRules.nextOccurrence(noon(2023, 11, 27), "DAILY", at(2026, 11, 27, 18, 0))) == "2026-11-27")
    check("every 3 days", ymd(ReminderRules.nextOccurrence(noon(2026, 11, 1), "Every few days", at(2026, 11, 27, 8, 0), 3)) == "2026-11-28")
    check("every few days with 0 counts as daily", ymd(ReminderRules.nextOccurrence(noon(2026, 11, 1), "CUSTOM", at(2026, 11, 27, 8, 0), 0)) == "2026-11-27")
    check("no repeat keeps the date", ReminderRules.nextOccurrence(jan31, "None", at(2026, 5, 1, 9, 0)) == jan31)
    check("future start unchanged", ReminderRules.nextOccurrence(noon(2027, 1, 1), "MONTHLY", at(2026, 5, 1, 9, 0)) == noon(2027, 1, 1))
    check("unknown repeat keeps the date", ReminderRules.nextOccurrence(jan31, "Fortnightly", at(2026, 5, 1, 9, 0)) == jan31)
    run {
        // Every day for 3 years, checked against stepping one day at a time.
        var ok = true
        var t = at(2026, 1, 1, 7, 0)
        val start = noon(2025, 12, 25)
        repeat(400) {
            val n = ReminderRules.nextOccurrence(start, "WEEKLY", t)
            val days = (ReminderRules.startOfDay(n) - ReminderRules.startOfDay(start)) / 86_400_000L
            if (days % 7 != 0L || n < ReminderRules.startOfDay(t) || n - ReminderRules.startOfDay(t) >= 7 * 86_400_000L) ok = false
            t += 86_400_000L
        }
        check("weekly: 400 days all land on the right weekday within a week", ok)
    }

    // ---- times ----
    check("time: 24-hour kept", ReminderRules.normalizeTime("18:30") == "18:30")
    check("time: 8 pm", ReminderRules.normalizeTime("8 pm") == "20:00")
    check("time: 12 am is midnight", ReminderRules.normalizeTime("12 am") == "00:00")
    check("time: 7.05", ReminderRules.normalizeTime("7.05") == "07:05")
    check("time: empty is all day", ReminderRules.timeText("") == "All day")
    check("time: nonsense is all day", ReminderRules.timeText("25:99") == "All day")
    check("time: shown", ReminderRules.timeText("9:5") == "09:05")
    check("early labels", ReminderRules.earlyMinutes("15 minutes before") == 15 && ReminderRules.earlyMinutes("1 day before") == 1440 &&
        ReminderRules.earlyMinutes("No early alert") == 0 && ReminderRules.earlyMinutes("") == 0)
    check("event alert labels", ReminderRules.eventAlertMinutes("No alert") == null && ReminderRules.eventAlertMinutes("At the time") == 0 &&
        ReminderRules.eventAlertMinutes("") == null)
    check("early text", ReminderRules.earlyText(15) == "In 15 minutes" && ReminderRules.earlyText(60) == "In 1 hour" &&
        ReminderRules.earlyText(1440) == "Tomorrow")

    // ---- occurrences (what gets an alarm in the next 36 hours) ----
    val now = at(2026, 11, 27, 10, 0)
    val horizon = now + 36 * 3_600_000L
    run {
        val o = ReminderRules.occurrences(noon(2026, 11, 27), "18:30", "None", 0, 0, "", now, horizon)
        check("once today at 18:30", o.size == 1 && o[0].at == at(2026, 11, 27, 18, 30) && !o[0].early && o[0].slot == "20261127_1830", o)
    }
    run {
        val o = ReminderRules.occurrences(noon(2026, 11, 27), "18:30", "None", 0, 15, "", now, horizon)
        check("early alert 15 minutes before, same slot", o.size == 2 && o[0].early && o[0].at == at(2026, 11, 27, 18, 15) &&
            o[0].slot == o[1].slot, o)
    }
    run {
        val o = ReminderRules.occurrences(noon(2026, 11, 27), "09:00", "None", 0, 0, "", now, horizon)
        check("once, time already passed: nothing", o.isEmpty(), o)
    }
    run {
        val o = ReminderRules.occurrences(noon(2026, 11, 1), "09:00", "Daily", 0, 0, "", now, horizon)
        check("daily, today's time passed: tomorrow at 09:00", o.map { it.at } == listOf(at(2026, 11, 28, 9, 0)), o)
    }
    run {
        val o = ReminderRules.occurrences(noon(2026, 11, 1), "", "None", 0, 0, "", at(2026, 11, 1, 6, 0), at(2026, 11, 2, 18, 0))
        check("all day notifies at 09:00", o.size == 1 && o[0].at == at(2026, 11, 1, 9, 0), o)
    }
    run {
        val o = ReminderRules.occurrences(noon(2026, 11, 1), "20:00", "Daily", 0, 0, "20261127_2000", now, horizon)
        check("today marked done: only tomorrow", o.map { it.at } == listOf(at(2026, 11, 28, 20, 0)), o)
    }
    run {
        val o = ReminderRules.occurrences(noon(2026, 11, 1), "20:00", "Daily", 0, 0, "", now, horizon)
        check("daily within 36 h: today and tomorrow, no duplicates", o.map { it.at } == listOf(at(2026, 11, 27, 20, 0), at(2026, 11, 28, 20, 0)), o)
    }
    run {
        val o = ReminderRules.occurrences(noon(2026, 11, 28), "10:00", "None", 0, 24 * 60, "", now, horizon)
        check("1 day before, early time is right now: skipped, the on-time alert stays", o.map { it.at to it.early } ==
            listOf(at(2026, 11, 28, 10, 0) to false), o)
    }
    run {
        val o = ReminderRules.occurrences(noon(2026, 11, 28), "11:00", "None", 0, 24 * 60, "", now, horizon)
        check("1 day before at 11:00 tomorrow: alert today 11:00 and tomorrow 11:00",
            o.map { it.at to it.early } == listOf(at(2026, 11, 27, 11, 0) to true, at(2026, 11, 28, 11, 0) to false), o)
    }
    run {
        val o = ReminderRules.occurrences(jan31, "09:00", "Monthly", 0, 0, "", at(2027, 2, 27, 10, 0), at(2027, 3, 1, 22, 0))
        check("monthly 31st in February: 28 Feb, once", o.map { ymd(it.at) } == listOf("2027-02-28"), o.map { ymd(it.at) })
    }
    run {
        // Simulate the alarm planner running every hour for 10 days: each daily alarm planned once per day.
        val seen = HashSet<String>()
        var t = now
        repeat(240) {
            ReminderRules.occurrences(noon(2026, 11, 1), "21:15", "Daily", 0, 0, "", t, t + 36 * 3_600_000L).forEach { seen.add(it.slot) }
            t += 3_600_000L
        }
        check("hourly re-planning for 10 days: 11 distinct daily slots", seen.size == 11, seen.size)
    }

    println(if (failures == 0) "ALL PASSED ($checks checks)" else "$failures of $checks FAILED")
    if (failures > 0) kotlin.system.exitProcess(1)
}
