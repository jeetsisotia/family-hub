package com.familyhub.app.money

import java.util.Calendar
import java.util.Locale

/**
 * Phase 3: savings goals, budget plan, spending insights and report files. Plain Kotlin on top of Ledger (the same
 * rules as the Finance dashboard: refunds lower spending, transfers and card payments never count). Tested in
 * tools/phase3-test. Nothing here moves money or creates transactions.
 */
private fun Map<String, Any?>.s(k: String) = (this[k] as? String).orEmpty().trim()
private fun Map<String, Any?>.n(k: String) = (this[k] as? Number)?.toDouble()
private fun Map<String, Any?>.l(k: String) = (this[k] as? Number)?.toLong()

object Goals {
    data class Contribution(val atMillis: Long, val by: String, val amount: Double, val note: String)

    fun target(g: Map<String, Any?>) = g.n("amountInr") ?: 0.0
    fun saved(g: Map<String, Any?>) = g.n("savedInr") ?: 0.0
    fun remaining(g: Map<String, Any?>) = (target(g) - saved(g)).coerceAtLeast(0.0)
    fun progress(g: Map<String, Any?>) = if (target(g) <= 0) 0.0 else (saved(g) / target(g)).coerceIn(0.0, 1.0)
    fun reached(g: Map<String, Any?>) = target(g) > 0 && saved(g) >= target(g)
    fun archived(g: Map<String, Any?>) = g["archived"] == true

    @Suppress("UNCHECKED_CAST")
    fun contributions(g: Map<String, Any?>): List<Contribution> =
        ((g["contributions"] as? List<*>).orEmpty()).mapNotNull { e ->
            val m = e as? Map<String, Any?> ?: return@mapNotNull null
            Contribution(m.l("at") ?: return@mapNotNull null, m.s("by"), m.n("amount") ?: 0.0, m.s("note"))
        }.sortedByDescending { it.atMillis }

    fun byMember(g: Map<String, Any?>): List<Pair<String, Double>> =
        contributions(g).groupBy { it.by.ifBlank { "Someone" } }.map { (k, v) -> k to v.sumOf { it.amount } }.sortedByDescending { it.second }

    /** Fields to save for a new contribution (a negative amount is a withdrawal). Records completion when it is reached. */
    fun contributionFields(g: Map<String, Any?>, c: Contribution): Map<String, Any?> {
        val newSaved = (saved(g) + c.amount).coerceAtLeast(0.0)
        val list = (listOf(c) + contributions(g)).take(200).map { mapOf("at" to it.atMillis, "by" to it.by, "amount" to it.amount, "note" to it.note) }
        val out = hashMapOf<String, Any?>("savedInr" to newSaved, "contributions" to list, "updatedAt" to c.atMillis)
        if (target(g) > 0 && newSaved >= target(g) && !reached(g)) { out["done"] = true; out["completedAt"] = c.atMillis }
        return out
    }

    /** Monthly amount still needed to reach the goal by its date (null without a date or when reached). */
    fun monthlyNeeded(g: Map<String, Any?>, now: Long): Double? {
        val t = g.l("targetMillis") ?: return null
        if (reached(g)) return null
        val months = ((t - now) / (30.4 * 86_400_000.0)).coerceAtLeast(1.0)
        return remaining(g) / months
    }
}

object BudgetPlan {
    data class Limit(val category: String, val amount: Double, val yearly: Boolean, val mine: Boolean)
    enum class State { OK, NEAR, OVER }
    data class Line(val category: String, val planned: Double, val actual: Double, val mine: Boolean) {
        val remaining get() = planned - actual
        val state get() = when { actual > planned -> State.OVER; planned > 0 && actual >= planned * 0.8 -> State.NEAR; else -> State.OK }
    }

    /** Limits saved as items of type "budget_limit"; a yearly limit counts one twelfth per month. */
    fun limits(items: List<Map<String, Any?>>): List<Limit> = items.filter { it.s("type") == "budget_limit" && it["isDeleted"] != true }.mapNotNull {
        val amt = it.n("amountInr") ?: return@mapNotNull null
        Limit(it.s("category").ifBlank { return@mapNotNull null }, amt, it.s("period").equals("Yearly", true), it["_private"] == true)
    }

    private fun norm(c: String) = c.trim().lowercase(Locale.ROOT)

    /** Planned against actual for the month [from]..[to]. "Only me" limits count only the person's own spending. */
    fun lines(limits: List<Limit>, expenses: List<Map<String, Any?>>, from: Long, to: Long, myUid: String): List<Line> {
        val all = Ledger.totals(expenses, from, to)
        val mine = Ledger.totals(expenses, from, to) { (it["memberUid"] as? String) == myUid || it["_private"] == true }
        fun actual(cat: String, onlyMine: Boolean) = Ledger.rupees((if (onlyMine) mine else all).byCategory.filter { norm(it.first) == norm(cat) }.sumOf { it.second })
        return limits.map { l -> Line(l.category, if (l.yearly) l.amount / 12 else l.amount, actual(l.category, l.mine), l.mine) }
            .sortedByDescending { if (it.planned > 0) it.actual / it.planned else 0.0 }
    }

    /** Month start for [offset] months from the month of [now] (0 = this month). */
    fun monthStart(now: Long, offset: Int, cal: Calendar = Calendar.getInstance()): Long {
        cal.timeInMillis = now; cal.set(Calendar.DAY_OF_MONTH, 1); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0); cal.add(Calendar.MONTH, offset); return cal.timeInMillis
    }

    /** Total spending for each of the last [n] months, oldest first (month start, rupees). */
    fun trend(expenses: List<Map<String, Any?>>, now: Long, n: Int = 6): List<Pair<Long, Double>> =
        (n - 1 downTo 0).map { k -> val s = monthStart(now, -k); val e = monthStart(now, -k + 1) - 1; s to Ledger.totals(expenses, s, e).spentRupees }

    data class Change(val category: String, val thisMonth: Double, val lastMonth: Double) {
        val diff get() = thisMonth - lastMonth
        val pct get() = if (lastMonth > 0) diff / lastMonth * 100 else null
    }

    /** Category spending this month against last month, biggest increase first. */
    fun monthOnMonth(expenses: List<Map<String, Any?>>, now: Long): List<Change> {
        val a = Ledger.totals(expenses, monthStart(now, 0), now).byCategory.associate { norm(it.first) to (it.first to Ledger.rupees(it.second)) }
        val b = Ledger.totals(expenses, monthStart(now, -1), monthStart(now, 0) - 1).byCategory.associate { norm(it.first) to (it.first to Ledger.rupees(it.second)) }
        return (a.keys + b.keys).map { k -> Change((a[k] ?: b[k])!!.first, a[k]?.second ?: 0.0, b[k]?.second ?: 0.0) }.sortedByDescending { it.diff }
    }
}

object Intel {
    data class Unusual(val rec: Map<String, Any?>, val amount: Double, val typical: Double)

    private fun median(xs: List<Double>) = xs.sorted().let { if (it.isEmpty()) 0.0 else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }

    /**
     * Single spends this month that are far above that category's usual spend (at least 3× the median of the last
     * 90 days, at least ₹1,000, and with at least 4 earlier entries to compare with).
     */
    fun unusual(expenses: List<Map<String, Any?>>, now: Long): List<Unusual> {
        val since = now - 90 * 86_400_000L
        val monthStart = BudgetPlan.monthStart(now, 0)
        val spends = expenses.filter { Ledger.isSpend(it) && (it.l("dateMillis") ?: 0L) in since..now }
        val byCat = spends.groupBy { it.s("category").ifBlank { "Other" } }
        val out = ArrayList<Unusual>()
        for ((_, list) in byCat) {
            val earlier = list.filter { (it.l("dateMillis") ?: 0L) < monthStart }.map { Ledger.spendAmount(it) }
            if (earlier.size < 4) continue
            val m = median(earlier)
            list.filter { (it.l("dateMillis") ?: 0L) >= monthStart }.forEach { r ->
                val a = Ledger.spendAmount(r)
                if (a >= 1000 && m > 0 && a >= 3 * m) out += Unusual(r, a, m)
            }
        }
        return out.sortedByDescending { it.amount / it.typical }
    }

    /** Plain suggestions from the numbers (never financial advice or guarantees). */
    fun suggestions(lines: List<BudgetPlan.Line>, mom: List<BudgetPlan.Change>, recurringMonthly: Double, unusual: List<Unusual>): List<String> {
        val out = ArrayList<String>()
        lines.filter { it.state == BudgetPlan.State.OVER }.take(2).forEach { out += "${it.category} is ${money(it.actual - it.planned)} over its limit this month. Check the biggest ${it.category.lowercase(Locale.ROOT)} entries in Transactions." }
        mom.firstOrNull { it.diff >= 1000 && it.lastMonth > 0 && (it.pct ?: 0.0) >= 30 }?.let { out += "${it.category} is up ${money(it.diff)} on last month. If that was a one-off, you can ignore this." }
        if (recurringMonthly >= 500) out += "Repeating payments come to about ${money(recurringMonthly)} a month. Review Subscriptions for any you no longer use."
        unusual.firstOrNull()?.let { out += "One ${it.rec.s("category").ifBlank { "Other" }.lowercase(Locale.ROOT)} spend (${it.rec.s("title")}, ${money(it.amount)}) is much higher than usual. Make sure it is correct." }
        return out
    }

    fun money(v: Double) = "₹" + String.format(Locale("en", "IN"), "%,.0f", v)
}

object Reports {
    private fun esc(v: Any?): String { val t = v?.toString().orEmpty(); return if (t.any { it == ',' || it == '"' || it == '\n' }) "\"" + t.replace("\"", "\"\"") + "\"" else t }
    private fun row(vararg v: Any?) = v.joinToString(",") { esc(it) }
    private fun day(t: Long?) = if (t == null) "" else java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(t))
    private fun r2(v: Double) = String.format(Locale.US, "%.2f", v)

    /** Every spend in the period (refunds already taken off; transfers and card payments left out). */
    fun monthlyCsv(expenses: List<Map<String, Any?>>, from: Long, to: Long): String {
        val t = Ledger.totals(expenses, from, to)
        return (listOf(row("Date", "Title", "Category", "Amount (INR)", "Paid with", "Member")) +
            t.spends.sortedBy { it.l("dateMillis") }.map { row(day(it.l("dateMillis")), it.s("title"), it.s("category").ifBlank { "Other" }, r2(Ledger.spendAmount(it)), it.s("paymentMethod"), it.s("memberName")) } +
            row("Total", "", "", r2(t.spentRupees), "", "")).joinToString("\n")
    }

    fun categoryCsv(expenses: List<Map<String, Any?>>, from: Long, to: Long): String {
        val t = Ledger.totals(expenses, from, to)
        val total = t.spentRupees
        return (listOf(row("Category", "Amount (INR)", "Share %")) + t.byCategory.map { (c, p) -> val r = Ledger.rupees(p); row(c, r2(r), if (total > 0) r2(r / total * 100) else "0") }).joinToString("\n")
    }

    fun memberCsv(expenses: List<Map<String, Any?>>, from: Long, to: Long): String {
        val t = Ledger.totals(expenses, from, to)
        return (listOf(row("Member", "Amount (INR)", "Entries")) + t.spends.groupBy { it.s("memberName").ifBlank { "Not set" } }
            .map { (m, l) -> m to l }.sortedByDescending { (_, l) -> l.sumOf { Ledger.spendAmount(it) } }
            .map { (m, l) -> row(m, r2(l.sumOf { Ledger.spendAmount(it) }), l.size) }).joinToString("\n")
    }

    fun recurringCsv(suggestions: List<RecurringFinder.Suggestion>, subscriptions: List<Map<String, Any?>>): String =
        (listOf(row("Name", "Amount (INR)", "Frequency", "Source")) +
            subscriptions.map { row(it.s("title"), r2(it.n("amountInr") ?: 0.0), it.s("cycle").ifBlank { "Monthly" }, "Subscription (" + it.s("status").ifBlank { "Active" } + ")") } +
            suggestions.map { row(it.payee, r2(it.amount), it.frequency.label, "Found in transactions") }).joinToString("\n")

    fun goalsCsv(goals: List<Map<String, Any?>>): String =
        (listOf(row("Goal", "Target (INR)", "Saved (INR)", "Remaining (INR)", "Progress %", "Target date", "Status")) + goals.map {
            row(it.s("title"), r2(Goals.target(it)), r2(Goals.saved(it)), r2(Goals.remaining(it)), r2(Goals.progress(it) * 100), day(it.l("targetMillis")),
                if (Goals.archived(it)) "Archived" else if (Goals.reached(it)) "Reached" else "In progress")
        }).joinToString("\n")

    fun budgetCsv(lines: List<BudgetPlan.Line>): String =
        (listOf(row("Category", "Planned (INR)", "Actual (INR)", "Remaining (INR)", "Status", "Scope")) +
            lines.map { row(it.category, r2(it.planned), r2(it.actual), r2(it.remaining), it.state.name.lowercase(Locale.ROOT), if (it.mine) "Only me" else "Family") }).joinToString("\n")
}
