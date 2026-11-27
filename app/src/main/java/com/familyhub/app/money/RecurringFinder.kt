package com.familyhub.app.money

import java.util.Locale

/**
 * Finds payments that look recurring (same payee, similar amount, regular gaps) in the existing transactions, as
 * suggestions only. Nothing is created until the person confirms, and transactions are never created at all.
 * Plain Kotlin, tested in tools/phase1-test.
 */
object RecurringFinder {
    enum class Frequency(val label: String, val days: Int, val minGap: Int, val maxGap: Int, val minCount: Int) {
        WEEKLY("Weekly", 7, 5, 9, 4), MONTHLY("Monthly", 30, 25, 36, 3), QUARTERLY("Quarterly", 91, 80, 100, 3), YEARLY("Yearly", 365, 340, 390, 2)
    }

    data class Suggestion(
        /** Stable id for "dismiss" and duplicate checks: payee key + frequency. */
        val key: String,
        val payee: String,
        val amount: Double,
        val frequency: Frequency,
        val count: Int,
        val lastMillis: Long,
        val nextMillis: Long,
        val category: String,
        /** Highest when gaps and amounts are very regular. */
        val confidence: Double,
        val ids: List<String>
    )

    private const val DAY = 86_400_000L
    private val NOISE = Regex("\\b(upi|imps|neft|rtgs|pos|ecom|payment|paid|to|via|ref|txn|no|order|bill|recharge|autopay|mandate|si)\\b")

    /** "UPI/NETFLIX.COM/12345" and "Netflix" give the same key. */
    fun payeeKey(title: String): String {
        val t = title.lowercase(Locale.ROOT).replace(Regex("[^a-z ]"), " ").replace(NOISE, " ")
        return t.split(Regex("\\s+")).filter { it.length >= 3 }.take(2).joinToString(" ")
    }

    private fun median(xs: List<Double>): Double = xs.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /**
     * [records]: Finance entries. [alreadyTracked]: payee keys of existing subscriptions, bills and reminders (so the
     * same thing is never suggested twice). [dismissed]: suggestion keys the person dismissed.
     */
    fun find(records: List<Map<String, Any?>>, alreadyTracked: Set<String> = emptySet(), dismissed: Set<String> = emptySet()): List<Suggestion> {
        val spends = records.filter { Ledger.live(it) && Ledger.type(it) == "EXPENSE" && it["notExpense"] != true && (it["dateMillis"] as? Number) != null }
        val out = ArrayList<Suggestion>()
        for ((key, group) in spends.groupBy { payeeKey((it["title"] as? String).orEmpty()) }) {
            if (key.isBlank() || key in alreadyTracked || alreadyTracked.any { it.isNotBlank() && (it in key || key in it) }) continue
            // One entry per day (the same payment saved twice is not a pattern).
            val byDay = group.sortedBy { (it["dateMillis"] as Number).toLong() }.distinctBy { (it["dateMillis"] as Number).toLong() / DAY }
            if (byDay.size < 2) continue
            val amounts = byDay.map { Ledger.amount(it) }
            val mid = median(amounts)
            if (mid <= 0) continue
            // Amounts within 15% of the usual amount.
            val similar = byDay.filter { kotlin.math.abs(Ledger.amount(it) - mid) <= mid * 0.15 }
            if (similar.size < 2) continue
            val times = similar.map { (it["dateMillis"] as Number).toLong() }
            val gaps = times.zipWithNext { a, b -> ((b - a) / DAY).toInt() }
            val gap = median(gaps.map { it.toDouble() })
            val freq = Frequency.values().firstOrNull { gap >= it.minGap && gap <= it.maxGap } ?: continue
            if (similar.size < freq.minCount) continue
            val regular = gaps.count { it >= freq.minGap && it <= freq.maxGap }.toDouble() / gaps.size
            if (regular < 0.75) continue
            val sKey = "$key|${freq.name}"
            if (sKey in dismissed) continue
            val amountSpread = amounts.filter { kotlin.math.abs(it - mid) <= mid * 0.15 }.let { a -> (a.maxOrNull()!! - a.minOrNull()!!) / mid }
            val conf = (regular * 0.6 + (1 - amountSpread.coerceAtMost(1.0)) * 0.3 + (similar.size.coerceAtMost(6) / 6.0) * 0.1)
            val last = times.last()
            out += Suggestion(
                sKey, (similar.last()["title"] as? String).orEmpty().trim(), mid, freq, similar.size, last,
                last + freq.days * DAY, (similar.last()["category"] as? String).orEmpty(), conf,
                similar.mapNotNull { it["id"] as? String }
            )
        }
        return out.sortedByDescending { it.confidence }
    }
}
