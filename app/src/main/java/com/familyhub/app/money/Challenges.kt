package com.familyhub.app.money

import java.util.Calendar

/**
 * Savings challenges. Money saved is recorded like a goal's contributions ([Goals.contributionFields]);
 * "No-spend days" is worked out from Finance (days with no spending), so nothing has to be entered.
 */
object Challenges {
    const val WEEK52 = "52-week challenge"
    const val DAILY = "Same amount every day"
    const val WEEKLY = "Same amount every week"
    const val NO_SPEND = "No-spend days"
    val KINDS = listOf(WEEK52, DAILY, WEEKLY, NO_SPEND)

    private const val DAY = 86_400_000L

    private fun Map<String, Any?>.num(k: String) = (this[k] as? Number)?.toDouble()
    private fun Map<String, Any?>.long(k: String) = (this[k] as? Number)?.toLong()

    fun kind(c: Map<String, Any?>): String = (c["kind"] as? String)?.takeIf { it in KINDS } ?: WEEK52
    fun base(c: Map<String, Any?>): Double = c.num("stepInr") ?: 0.0
    fun start(c: Map<String, Any?>): Long = c.long("startMillis") ?: c.long("createdAt") ?: 0L

    /** How long the challenge lasts: weeks for weekly ones, days otherwise. */
    fun length(c: Map<String, Any?>): Int = when (kind(c)) {
        WEEK52 -> 52
        else -> (c.num("length")?.toInt() ?: if (kind(c) == WEEKLY) 12 else 30).coerceIn(1, 366)
    }

    fun dayIndex(c: Map<String, Any?>, now: Long): Int = (startOfDay(now) - startOfDay(start(c))).div(DAY).toInt()
    /** Step number we are in now (1-based), capped at the length; 0 before the start. */
    fun step(c: Map<String, Any?>, now: Long): Int {
        val d = dayIndex(c, now)
        if (d < 0) return 0
        val s = when (kind(c)) { WEEK52, WEEKLY -> d / 7 + 1; else -> d + 1 }
        return s.coerceAtMost(length(c))
    }

    fun finished(c: Map<String, Any?>, now: Long): Boolean = dayIndex(c, now) >= length(c) * (if (kind(c) == WEEK52 || kind(c) == WEEKLY) 7 else 1)

    /** Amount to save in step [n] (week n of the 52-week challenge saves n × the base). */
    fun stepAmount(c: Map<String, Any?>, n: Int): Double = when (kind(c)) {
        WEEK52 -> base(c) * n
        NO_SPEND -> 0.0
        else -> base(c)
    }

    /** Total to save over the whole challenge (52-week: 1,378 × the base). */
    fun target(c: Map<String, Any?>): Double = (1..length(c)).sumOf { stepAmount(c, it) }

    /** What should have been saved by now (all steps up to and including the current one). */
    fun dueSoFar(c: Map<String, Any?>, now: Long): Double = (1..step(c, now)).sumOf { stepAmount(c, it) }

    fun saved(c: Map<String, Any?>): Double = c.num("savedInr") ?: 0.0

    /** Ahead (+) or behind (−) the plan, in rupees. */
    fun versusPlan(c: Map<String, Any?>, now: Long): Double = saved(c) - dueSoFar(c, now)

    /** Fields to save when money is put aside (reuses goal contributions; the target is the challenge total). */
    fun saveFields(c: Map<String, Any?>, at: Long, by: String, amount: Double, note: String): Map<String, Any?> =
        Goals.contributionFields(c + ("amountInr" to target(c)), Goals.Contribution(at, by, amount, note)) + ("amountInr" to target(c))

    data class NoSpend(val days: Int, val goal: Int, val streak: Int, val spentDays: Int)

    /**
     * No-spend days from Finance: days since the start with no spending at all (transfers, refunds and income
     * do not count as spending), the current run of such days, and the goal.
     */
    fun noSpend(c: Map<String, Any?>, spendDays: Set<Long>, now: Long): NoSpend {
        val first = startOfDay(start(c))
        val today = startOfDay(now)
        val last = minOf(today, first + (length(c) - 1) * DAY)
        if (last < first) return NoSpend(0, length(c), 0, 0)
        val days = generateSequence(first) { it + DAY }.takeWhile { it <= last }.toList()
        val clean = days.count { it !in spendDays }
        var streak = 0
        for (d in days.asReversed()) { if (d in spendDays) break; streak++ }
        return NoSpend(clean, length(c), streak, days.size - clean)
    }

    fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
