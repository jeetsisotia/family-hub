package com.familyhub.app.money

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Shared-expense splitting (Settle up and trips). Pure, unit-tested in tools/split-test.
 * All sums work in paise (whole numbers) so shares always add up exactly to the amount.
 */
object Split {
    const val EQUAL = "Equally"
    const val EXACT = "Exact amounts"
    const val PERCENT = "Percentages"
    const val SHARES = "Shares"
    val MODES = listOf(EQUAL, EXACT, PERCENT, SHARES)

    data class Expense(
        val id: String,
        val amount: Double,
        val paidBy: String,
        /** Who shares it; empty means everyone in [Split.balances]'s people list. */
        val with: List<String> = emptyList(),
        val mode: String = EQUAL,
        /** For Exact / Percentages / Shares: "Asha 500, Ravi 300" (name then number). */
        val detail: String = "",
        val group: String = ""
    )

    data class Transfer(val from: String, val to: String, val amount: Double)

    /** Names separated by commas (or "and"); blank parts dropped, matched to [people] ignoring case. */
    fun names(text: String, people: List<String> = emptyList()): List<String> =
        text.split(',', ';', '\n').flatMap { it.split(Regex("\\s+and\\s+", RegexOption.IGNORE_CASE)) }
            .map { it.trim() }.filter { it.isNotBlank() }
            .map { n -> people.firstOrNull { it.equals(n, ignoreCase = true) } ?: n }
            .distinctBy { it.lowercase() }

    /** "Asha 500, Ravi: 300, Dadi=2" → name to number. */
    fun parseDetail(text: String, people: List<String> = emptyList()): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        // A comma between two digits is a thousands separator (1,200 or 1,20,000), not a new person.
        for (part in text.split(Regex("(?<!\\d),|,(?!\\d)|[;\\n]"))) {
            val m = Regex("^\\s*(.+?)\\s*[:=]?\\s*₹?\\s*(-?[0-9][0-9,]*(?:\\.[0-9]+)?)\\s*%?\\s*$").find(part) ?: continue
            val raw = m.groupValues[1].trim()
            val name = people.firstOrNull { it.equals(raw, ignoreCase = true) } ?: raw
            val v = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: continue
            out[name] = (out[name] ?: 0.0) + v
        }
        return out
    }

    private fun paise(x: Double) = (x * 100).roundToLong()

    /** Splits [total] paise by [weights]; leftover paise go one each to the largest remainders (fair and exact). */
    private fun byWeights(total: Long, weights: Map<String, Double>): Map<String, Long>? {
        val sum = weights.values.sum()
        if (weights.isEmpty() || sum <= 0 || weights.values.any { it < 0 }) return null
        val raw = weights.mapValues { total * it.value / sum }
        val floor = raw.mapValues { kotlin.math.floor(it.value).toLong() }.toMutableMap()
        var left = total - floor.values.sum()
        for (k in raw.keys.sortedByDescending { raw.getValue(it) - floor.getValue(it) }) {
            if (left <= 0) break
            floor[k] = floor.getValue(k) + 1; left--
        }
        return floor
    }

    /**
     * Each person's share of one expense, in rupees. Null when the split cannot be worked out
     * (exact amounts that do not add up, percentages that are not 100, nobody to split with).
     */
    fun shares(e: Expense, people: List<String>): Map<String, Double>? {
        val total = paise(e.amount)
        if (total <= 0) return null
        val with = names(e.with.joinToString(","), people).ifEmpty { people }
        val p = when (e.mode) {
            EXACT -> {
                val d = parseDetail(e.detail, people).mapValues { paise(it.value) }
                if (d.isEmpty() || d.values.sum() != total || d.values.any { it < 0 }) null else d
            }
            PERCENT -> {
                val d = parseDetail(e.detail, people)
                if (d.isEmpty() || abs(d.values.sum() - 100.0) > 0.01) null else byWeights(total, d)
            }
            SHARES -> byWeights(total, parseDetail(e.detail, people))
            else -> if (with.isEmpty()) null else byWeights(total, with.associateWith { 1.0 })
        } ?: return null
        return p.filterValues { it != 0L }.mapValues { it.value / 100.0 }
    }

    /** What each person is owed (+) or owes (−) over open expenses. Expenses that cannot be split are listed in [skipped]. */
    data class Balances(val net: Map<String, Double>, val total: Double, val skipped: List<String>)

    fun balances(expenses: List<Expense>, people: List<String>): Balances {
        val net = LinkedHashMap<String, Long>()
        var total = 0L
        val skipped = mutableListOf<String>()
        for (e in expenses) {
            val s = shares(e, people)
            if (s == null || e.paidBy.isBlank()) { skipped += e.id; continue }
            val payer = people.firstOrNull { it.equals(e.paidBy, ignoreCase = true) } ?: e.paidBy
            total += paise(e.amount)
            net[payer] = (net[payer] ?: 0L) + paise(e.amount)
            s.forEach { (who, amt) -> net[who] = (net[who] ?: 0L) - paise(amt) }
        }
        return Balances(net.filterValues { it != 0L }.mapValues { it.value / 100.0 }, total / 100.0, skipped)
    }

    /** The fewest simple payments that settle everyone (largest debtor pays largest creditor first). */
    fun settlements(net: Map<String, Double>): List<Transfer> {
        val cred = net.filter { it.value > 0.004 }.map { it.key to paise(it.value) }.sortedByDescending { it.second }.toMutableList()
        val debt = net.filter { it.value < -0.004 }.map { it.key to -paise(it.value) }.sortedByDescending { it.second }.toMutableList()
        val out = mutableListOf<Transfer>()
        var i = 0; var j = 0
        while (i < debt.size && j < cred.size) {
            val pay = minOf(debt[i].second, cred[j].second)
            if (pay > 0) out += Transfer(debt[i].first, cred[j].first, pay / 100.0)
            debt[i] = debt[i].first to debt[i].second - pay
            cred[j] = cred[j].first to cred[j].second - pay
            if (debt[i].second == 0L) i++
            if (cred[j].second == 0L) j++
        }
        return out
    }

    /** A payment from one person to another, recorded as an expense paid by [from] and owed entirely by [to]. */
    fun payment(id: String, t: Transfer) = Expense(id, t.amount, t.from, listOf(t.to), EXACT, "${t.to} ${t.amount}")
}
