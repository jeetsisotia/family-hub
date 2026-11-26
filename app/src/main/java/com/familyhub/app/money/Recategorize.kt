package com.familyhub.app.money

import java.util.Locale

/**
 * Moving existing transactions to another category, in plain Kotlin (no Android code), tested in
 * tools/ledger-test.
 *
 * Only the category (and the subcategory, which belongs to the old category) changes. Amount, date, account,
 * card, notes, refunds and every other field are kept, and the record keeps its id, so no entry is copied or
 * lost. Totals, budgets and Insights follow at once because they group by the category field.
 */
object Recategorize {
    /** One planned change: the record, the fields to write, and what to write back for Undo. */
    data class Change(val rec: Map<String, Any?>, val fields: Map<String, Any?>, val undo: Map<String, Any?>)

    data class Plan(
        val changes: List<Change>,
        /** Entries asked for but left alone: already in that category, deleted, or a transfer / card payment. */
        val skipped: Int
    ) {
        val count: Int get() = changes.size
    }

    /** Transfers and card-bill payments move money between your own accounts; they have no category. */
    fun categorisable(r: Map<String, Any?>): Boolean =
        Ledger.live(r) && Ledger.type(r) !in setOf("TRANSFER", "CARD_PAYMENT")

    /** True for entries with no real category yet (blank, "None" or "Other"). */
    fun uncategorised(r: Map<String, Any?>): Boolean {
        val c = (r["category"] as? String).orEmpty().trim()
        return c.isEmpty() || c.equals("None", true) || c.equals("Other", true)
    }

    private fun id(r: Map<String, Any?>): String? = r["id"] as? String

    private fun change(r: Map<String, Any?>, category: String, now: Long): Change =
        Change(
            rec = r,
            fields = mapOf("category" to category, "subcategory" to "None", "updatedAt" to now),
            undo = mapOf(
                "category" to (r["category"] as? String),
                "subcategory" to (r["subcategory"] as? String),
                "updatedAt" to now
            )
        )

    /** Moves the chosen entries ([ids]) to [category]. */
    fun forIds(records: List<Map<String, Any?>>, ids: Set<String>, category: String, now: Long = System.currentTimeMillis()): Plan {
        val target = category.trim()
        val chosen = records.filter { id(it) in ids }.distinctBy { id(it) }
        val out = ArrayList<Change>()
        var skipped = 0
        for (r in chosen) {
            val already = (r["category"] as? String).orEmpty().isNotBlank() &&
                FinanceMonth.normName(FinanceMonth.categoryOf(r)) == FinanceMonth.normName(target)
            if (target.isEmpty() || !categorisable(r) || already) { skipped++; continue }
            out += change(r, target, now)
        }
        // Ids that were asked for but are not in the list (for example deleted since) count as skipped too.
        skipped += ids.size - chosen.size
        return Plan(out, skipped)
    }

    /** Renaming a category: every live entry using [oldName] gets [newName]. */
    fun rename(records: List<Map<String, Any?>>, oldName: String, newName: String, now: Long = System.currentTimeMillis()): Plan {
        val o = FinanceMonth.normName(oldName)
        val n = newName.trim()
        if (o.isEmpty() || n.isEmpty() || o == FinanceMonth.normName(n) && oldName.trim() == n) return Plan(emptyList(), 0)
        val changes = records.filter { categorisable(it) && FinanceMonth.normName((it["category"] as? String).orEmpty()) == o }
            .distinctBy { id(it) }
            .map { change(it, n, now) }
        return Plan(changes, 0)
    }

    /** Deleting a category: its entries move to [moveTo] (for example "Other"). */
    fun moveAll(records: List<Map<String, Any?>>, fromName: String, moveTo: String, now: Long = System.currentTimeMillis()): Plan =
        rename(records, fromName, moveTo, now)

    /** How many live entries use a category (for the confirmations). */
    fun usage(records: List<Map<String, Any?>>, name: String): Int {
        val n = FinanceMonth.normName(name)
        return records.count { categorisable(it) && FinanceMonth.normName((it["category"] as? String).orEmpty()) == n }
    }

    /**
     * Entries offered by "Apply to existing transactions": spending or income (by [kind]) in the period,
     * optionally only uncategorised ones, matching the search text. Newest first.
     */
    fun eligible(
        records: List<Map<String, Any?>>, kind: FinanceMonth.Kind, from: Long, to: Long,
        query: String = "", onlyUncategorised: Boolean = false, exclude: String = ""
    ): List<Map<String, Any?>> =
        FinanceMonth.list(records, kind, from, to, query).filter { r ->
            categorisable(r) && (!onlyUncategorised || uncategorised(r)) &&
                (exclude.isBlank() || FinanceMonth.normName(FinanceMonth.categoryOf(r)) != FinanceMonth.normName(exclude))
        }

    /** The account or card an entry used, for the Transactions filter ("" when neither is set). */
    fun sourceOf(r: Map<String, Any?>): String {
        fun s(k: String) = (r[k] as? String).orEmpty().trim().takeIf { it.isNotEmpty() && !it.equals("None", true) }
        return s("card") ?: s("account") ?: ""
    }

    /** The accounts and cards used in a list, most used first. */
    fun sources(list: List<Map<String, Any?>>): List<String> =
        list.map { sourceOf(it) }.filter { it.isNotEmpty() }
            .groupingBy { it.lowercase(Locale.ROOT) }.eachCount().entries
            .sortedByDescending { it.value }
            .map { e -> list.first { sourceOf(it).lowercase(Locale.ROOT) == e.key }.let { sourceOf(it) } }
}
