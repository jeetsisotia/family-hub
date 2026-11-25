package com.familyhub.app.ui.common

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** "20261126" style key for one calendar day. */
fun dayKey(millis: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(millis))

fun shortDate(millis: Long): String = SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(millis))

fun monthLabel(millis: Long): String = SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(millis))

fun shortMonth(millis: Long): String = SimpleDateFormat("MMM", Locale.getDefault()).format(Date(millis))

/** Midnight at the start of the month that contains [millis], moved by [addMonths]. */
fun monthStart(millis: Long, addMonths: Int = 0): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = millis
    c.set(Calendar.DAY_OF_MONTH, 1)
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    c.add(Calendar.MONTH, addMonths)
    return c.timeInMillis
}

/** Last millisecond of the day that contains [millis]. */
fun endOfDay(millis: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = millis
    c.set(Calendar.HOUR_OF_DAY, 23)
    c.set(Calendar.MINUTE, 59)
    c.set(Calendar.SECOND, 59)
    c.set(Calendar.MILLISECOND, 999)
    return c.timeInMillis
}

/** Noon on [day] of the month containing [monthMillis]; days past the month's end use its last day. */
fun dayInMonth(monthMillis: Long, day: Int): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = monthMillis
    c.set(Calendar.DAY_OF_MONTH, 1)
    val max = c.getActualMaximum(Calendar.DAY_OF_MONTH)
    c.set(Calendar.DAY_OF_MONTH, day.coerceIn(1, max))
    c.set(Calendar.HOUR_OF_DAY, 12)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

/** Reads "08:00, 20:30" or "8 am, 9:15pm" into (hour, minute) pairs. Bad parts are skipped. */
fun parseTimes(text: String): List<Pair<Int, Int>> {
    val out = ArrayList<Pair<Int, Int>>()
    for (raw in text.split(",", ";", "/", "\n")) {
        val t = raw.trim().lowercase(Locale.US)
        if (t.isEmpty()) continue
        val pm = t.endsWith("pm") || t.endsWith("p.m.")
        val am = t.endsWith("am") || t.endsWith("a.m.")
        val digits = t.replace(Regex("[^0-9:.]"), "").replace('.', ':')
        val parts = digits.split(":").filter { it.isNotEmpty() }
        val h0 = parts.getOrNull(0)?.toIntOrNull() ?: continue
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        var h = h0
        if (pm && h < 12) h += 12
        if (am && h == 12) h = 0
        if (h in 0..23 && m in 0..59) out.add(h to m)
    }
    return out.distinct().sortedWith(compareBy({ it.first }, { it.second }))
}

fun timeLabel(h: Int, m: Int): String = "%02d:%02d".format(h, m)

/** The given day at hour:minute. */
fun atTime(dayMillis: Long, h: Int, m: Int): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = dayMillis
    c.set(Calendar.HOUR_OF_DAY, h)
    c.set(Calendar.MINUTE, m)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}
