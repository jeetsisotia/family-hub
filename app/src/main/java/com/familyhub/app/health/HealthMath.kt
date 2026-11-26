package com.familyhub.app.health

import java.util.Calendar
import java.util.Locale

/**
 * Health sums in plain Kotlin (no Android code), tested in tools/health-test. They only count what the person
 * entered; nothing is estimated or invented, and nothing here is a medical judgement.
 */
object HealthMath {
    private const val DAY_MS = 24 * 60 * 60_000L

    /** A dose is counted as missed only after this long, so a dose just due is not "missed" yet. */
    const val GRACE_MS = 60 * 60_000L

    /** One medicine: its times each day, its dates, and the doses marked taken ("yyyyMMdd_HHmm"). */
    data class MedPlan(val times: List<Pair<Int, Int>>, val start: Long?, val end: Long?, val taken: Set<String>)

    data class Adherence(val scheduled: Int, val taken: Int) {
        val missed: Int get() = scheduled - taken
        /** Whole percent, or null when nothing was due. */
        val percent: Int? get() = if (scheduled == 0) null else (taken * 100 + scheduled / 2) / scheduled
    }

    private fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun at(day: Long, h: Int, m: Int): Long = Calendar.getInstance().apply {
        timeInMillis = day
        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun slot(t: Long, h: Int, m: Int): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return String.format(Locale.US, "%04d%02d%02d_%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), h, m)
    }

    /**
     * Doses due in the last [days] days (today included) and how many were marked taken. Doses before the
     * medicine started, after its last day, or less than an hour old are not counted.
     */
    fun adherence(meds: List<MedPlan>, now: Long, days: Int = 7): Adherence {
        var scheduled = 0
        var taken = 0
        val today = startOfDay(now)
        for (m in meds) {
            val start = m.start?.let { startOfDay(it) }
            val end = m.end?.let { startOfDay(it) }
            for (i in 0 until days) {
                val day = startOfDay(today - i * DAY_MS + 12 * 60 * 60_000L)
                if (start != null && day < start) continue
                if (end != null && day > end) continue
                for ((h, mi) in m.times.distinct()) {
                    val due = at(day, h, mi)
                    if (due + GRACE_MS > now) continue
                    scheduled++
                    if (slot(day, h, mi) in m.taken) taken++
                }
            }
        }
        return Adherence(scheduled, taken)
    }

    data class FoodEntry(val dateMillis: Long, val kcal: Double?)

    data class FoodSummary(
        /** Calories entered for today. */
        val todayKcal: Int,
        /** Average a day over the last 7 days that have any calories entered (today included); null if none. */
        val weekAverageKcal: Int?,
        /** Entries today without calories. */
        val todayWithoutKcal: Int
    )

    fun foodSummary(entries: List<FoodEntry>, now: Long): FoodSummary {
        val today = startOfDay(now)
        val weekStart = today - 6 * DAY_MS
        val todays = entries.filter { startOfDay(it.dateMillis) == today }
        val byDay = entries.filter { it.kcal != null && it.kcal > 0 && startOfDay(it.dateMillis) in weekStart..today }
            .groupBy { startOfDay(it.dateMillis) }
            .mapValues { (_, v) -> v.sumOf { it.kcal ?: 0.0 } }
        return FoodSummary(
            todayKcal = Math.round(todays.sumOf { it.kcal?.takeIf { k -> k > 0 } ?: 0.0 }).toInt(),
            weekAverageKcal = if (byDay.isEmpty()) null else Math.round(byDay.values.sum() / byDay.size).toInt(),
            todayWithoutKcal = todays.count { it.kcal == null || it.kcal <= 0 }
        )
    }
}
