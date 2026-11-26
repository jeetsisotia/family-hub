package com.familyhub.app.money

import java.util.Calendar
import java.util.Locale

/**
 * The Finance dashboard and Transactions screen in plain Kotlin (no Android code), tested in tools/ledger-test.
 * Every figure comes from [Ledger] (amounts in whole paise), so the dashboard, Transactions, Insights and Home
 * always agree.
 */
object FinanceMonth {
    data class Range(val from: Long, val to: Long)

    /** The calendar month containing [at], moved by [offset] months: first to last millisecond. */
    fun month(at: Long, offset: Int = 0): Range {
        val c = Calendar.getInstance().apply {
            timeInMillis = at
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, offset)
        }
        val from = c.timeInMillis
        c.add(Calendar.MONTH, 1)
        return Range(from, c.timeInMillis - 1)
    }

    /** Whole-percent change from [before] to [now]; null when there is nothing to compare with. */
    fun changePct(now: Long, before: Long): Int? =
        if (before <= 0L) null else Math.round((now - before) * 100.0 / before).toInt()

    data class MonthView(
        val income: Long,
        val spent: Long,
        /** Income minus spending for the month (can be negative). */
        val balance: Long,
        val prevIncome: Long,
        val prevSpent: Long,
        val spentChangePct: Int?,
        val incomeChangePct: Int?,
        /** Up to five categories, largest spending first. */
        val top: List<Pair<String, Long>>,
        /** The five newest live entries in the month (unpaid dues left out). */
        val latest: List<Map<String, Any?>>,
        /** Refunds that lowered spending this month. */
        val refunds: Long
    )

    private fun newestFirst(a: Map<String, Any?>, b: Map<String, Any?>): Int {
        val d = ((b["dateMillis"] as? Number)?.toLong() ?: 0L).compareTo((a["dateMillis"] as? Number)?.toLong() ?: 0L)
        if (d != 0) return d
        return ((b["createdAt"] as? Number)?.toLong() ?: (b["updatedAt"] as? Number)?.toLong() ?: 0L)
            .compareTo((a["createdAt"] as? Number)?.toLong() ?: (a["updatedAt"] as? Number)?.toLong() ?: 0L)
    }

    fun view(records: List<Map<String, Any?>>, at: Long, include: (Map<String, Any?>) -> Boolean = { true }): MonthView {
        val cur = month(at)
        val prev = month(at, -1)
        val t = Ledger.totals(records, cur.from, cur.to, include)
        val p = Ledger.totals(records, prev.from, prev.to, include)
        val latest = records.filter { r ->
            Ledger.live(r) && include(r) && ((r["dateMillis"] as? Number)?.toLong() ?: -1L) in cur.from..cur.to &&
                // An unpaid bill or EMI is a due, not a transaction yet (it shows in Dues).
                !(Ledger.type(r) in setOf("BILL", "EMI") && r["isPaid"] == false)
        }.sortedWith { a, b -> newestFirst(a, b) }.take(5)
        return MonthView(
            income = t.income, spent = t.spent, balance = t.income - t.spent,
            prevIncome = p.income, prevSpent = p.spent,
            spentChangePct = changePct(t.spent, p.spent), incomeChangePct = changePct(t.income, p.income),
            top = t.byCategory.filter { it.second > 0 }.take(5), latest = latest, refunds = t.refunds
        )
    }

    enum class Kind { EXPENSE, INCOME }

    /** Spending or income entries in a period, newest first, matching the search text and category if given. */
    fun list(
        records: List<Map<String, Any?>>, kind: Kind, from: Long, to: Long,
        query: String = "", category: String = "", include: (Map<String, Any?>) -> Boolean = { true }
    ): List<Map<String, Any?>> {
        val q = query.trim().lowercase(Locale.ROOT)
        return records.filter { r ->
            val d = (r["dateMillis"] as? Number)?.toLong() ?: return@filter false
            d in from..to && include(r) &&
                (if (kind == Kind.EXPENSE) Ledger.isSpend(r) else Ledger.isIncome(r)) &&
                (category.isBlank() || categoryOf(r).equals(category, true)) &&
                (q.isEmpty() || matches(r, q))
        }.sortedWith { a, b -> newestFirst(a, b) }
    }

    fun categoryOf(r: Map<String, Any?>): String = (r["category"] as? String).orEmpty().ifBlank { "Other" }

    private fun matches(r: Map<String, Any?>, q: String): Boolean {
        val text = listOf("title", "merchant", "category", "subcategory", "notes", "account", "card")
            .joinToString(" ") { (r[it] as? String).orEmpty() }.lowercase(Locale.ROOT)
        if (q in text) return true
        // "450" finds ₹450 and ₹450.50.
        val amt = (r["amountInr"] as? Number)?.toDouble() ?: return false
        return q.toDoubleOrNull()?.let { Math.floor(amt) == Math.floor(it) } ?: false
    }

    /** The amount an entry counts for: spending after its own refunds, or the income received. In paise. */
    fun paiseFor(r: Map<String, Any?>, kind: Kind): Long =
        if (kind == Kind.EXPENSE) Ledger.paise(Ledger.spendAmount(r)) else Ledger.paiseOf(r)

    fun total(list: List<Map<String, Any?>>, kind: Kind): Long = list.sumOf { paiseFor(it, kind) }

    /** Category totals, largest first, with each one's share of the total in whole percent. */
    fun breakdown(list: List<Map<String, Any?>>, kind: Kind): List<Triple<String, Long, Int>> {
        val total = total(list, kind)
        return list.groupBy { categoryOf(it) }
            .map { (c, l) -> c to l.sumOf { paiseFor(it, kind) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { (c, p) -> Triple(c, p, if (total > 0) Math.round(p * 100.0 / total).toInt() else 0) }
    }

    /** Entries grouped by day, newest day first (the list must already be newest first). */
    fun byDay(list: List<Map<String, Any?>>): List<Pair<Long, List<Map<String, Any?>>>> {
        val out = LinkedHashMap<Long, MutableList<Map<String, Any?>>>()
        for (r in list) {
            val d = Ledger.startOfDay((r["dateMillis"] as? Number)?.toLong() ?: continue)
            out.getOrPut(d) { ArrayList() }.add(r)
        }
        return out.map { it.key to it.value }
    }

    // ---------- custom categories ----------

    /** "  Pet  care " and "pet CARE" are the same name. */
    fun normName(name: String): String = name.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    /** Spending, Income or Both: which kinds a category is used for. */
    private fun kinds(kind: String): Set<String> = when (kind.trim().lowercase(Locale.ROOT)) {
        "income" -> setOf("income")
        "both" -> setOf("income", "spending")
        else -> setOf("spending")
    }

    /**
     * True if [name] already exists for the same kind (built-in or custom), ignoring case and spaces.
     * [existing] is (name, kind) of the family's custom categories; [ignoreName] is the one being edited.
     */
    fun isDuplicateCategory(
        name: String, kind: String, existing: List<Pair<String, String>>,
        builtInSpending: List<String>, builtInIncome: List<String>, ignoreName: String = ""
    ): Boolean {
        val n = normName(name)
        if (n.isEmpty()) return false
        val ks = kinds(kind)
        if (n == normName(ignoreName)) return false
        if ("spending" in ks && builtInSpending.any { normName(it) == n }) return true
        if ("income" in ks && builtInIncome.any { normName(it) == n }) return true
        return existing.any { (en, ek) -> normName(en) == n && kinds(ek).any { it in ks } }
    }
}
