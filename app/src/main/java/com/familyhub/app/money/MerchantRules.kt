package com.familyhub.app.money

/**
 * "Use this category for every transaction from this merchant" (like the Axio app).
 *
 * When someone changes a transaction's category, the app offers to apply it to the other transactions
 * from the same merchant and to remember it. A remembered rule is saved as the person's own private record
 * (type [TYPE] in their "items"), so it is included in their backup and costs no extra reads; SMS and
 * statement imports then start with that category.
 *
 * Plain Kotlin, no Android code, so it is unit-tested on any computer (tools/ledger-test).
 */
object MerchantRules {
    const val TYPE = "merchant_rule"

    /** Words that say how money moved, not who was paid. */
    private val noise = setOf(
        "upi", "pos", "ecom", "neft", "imps", "rtgs", "vpa", "ref", "refno", "txn", "txnid", "payment", "paid", "pay", "to",
        "at", "via", "from", "by", "for", "the", "and", "of", "order", "purchase", "india", "in", "pvt", "ltd", "private",
        "limited", "llp", "www", "com", "co", "online", "debit", "credit", "card", "sent", "received", "transfer", "bill"
    )

    /** "Swiggy order from Meghana Foods" → [swiggy, meghana, foods]; "swiggy@icici" → [swiggy]. */
    fun words(name: String): List<String> =
        name.substringBefore('@')
            .lowercase()
            .split(Regex("[^a-z]+"))
            .filter { it.length >= 2 && it !in noise }

    /** The saved form of a merchant name: its meaningful words, at most four. */
    fun key(name: String): String = words(name).take(4).joinToString(" ")

    /** A tidy name to show: "SWIGGY*ORDER" → "Swiggy". */
    fun label(name: String): String =
        words(name).take(3).joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }.ifBlank { name.trim() }

    /**
     * True when both names are the same merchant: one name's words start the other's ("Swiggy" and
     * "Swiggy order from Meghana Foods"), and the first word has at least three letters, so "Indian Oil" and
     * "Indian Bank" stay apart.
     */
    fun same(a: String, b: String): Boolean {
        val wa = words(a).take(4)
        val wb = words(b).take(4)
        if (wa.isEmpty() || wb.isEmpty()) return false
        if (wa[0].length < 3 || wa[0] != wb[0]) return false
        val (short, long) = if (wa.size <= wb.size) wa to wb else wb to wa
        return long.subList(0, short.size) == short
    }

    /** Who was paid (or who paid): the merchant read from the SMS or statement, else the entry's title. */
    fun merchantOf(r: Map<String, Any?>): String =
        ((r["merchant"] as? String).orEmpty()).ifBlank { (r["title"] as? String).orEmpty() }.trim()

    private fun type(r: Map<String, Any?>): String = ((r["type"] as? String).orEmpty()).uppercase().ifBlank { "EXPENSE" }

    /** Money moved between your own accounts never takes a shop category. */
    private val moves = setOf("TRANSFER", "CARD_PAYMENT")

    /**
     * Other live transactions from the same merchant, in the same direction (money in or money out), whose
     * category is not already [category]. Transfers and card bill payments are left alone.
     */
    fun matches(rec: Map<String, Any?>, all: List<Map<String, Any?>>, category: String): List<Map<String, Any?>> {
        val merchant = merchantOf(rec)
        if (key(merchant).isBlank()) return emptyList()
        val income = type(rec) == "INCOME"
        val id = rec["id"] as? String
        return all.filter { r ->
            r["isDeleted"] != true &&
                (id == null || r["id"] != id) &&
                type(r) !in moves &&
                (type(r) == "INCOME") == income &&
                (r["category"] as? String).orEmpty() != category &&
                same(merchant, merchantOf(r))
        }
    }

    /**
     * The remembered category for this merchant, if any. A rule whose name starts the merchant's name wins
     * (the most exact one): "Swiggy Instamart" rules "Swiggy Instamart Bangalore" but not plain "Swiggy".
     * A rule that is longer than the merchant's name is used only when all such rules agree.
     */
    fun categoryFor(merchant: String, items: List<Map<String, Any?>>): String? {
        val mw = words(merchant).take(4)
        if (mw.isEmpty()) return null
        val rules = items.filter { it["type"] == TYPE && it["isDeleted"] != true && !(it["category"] as? String).isNullOrBlank() }
        fun ruleWords(r: Map<String, Any?>) = words((r["merchant"] as? String).orEmpty()).take(4)
        val inside = rules.filter { r -> ruleWords(r).let { rw -> rw.isNotEmpty() && rw.size <= mw.size && same(merchant, (r["merchant"] as? String).orEmpty()) } }
        if (inside.isNotEmpty()) return inside.maxByOrNull { ruleWords(it).size }?.get("category") as? String
        val wider = rules.filter { r -> same(merchant, (r["merchant"] as? String).orEmpty()) }
            .map { it["category"] as String }.distinct()
        return wider.singleOrNull()
    }

    /** Same id for the same merchant, so changing the category again updates the rule instead of adding one. */
    fun ruleId(merchant: String): String = "mrule_" + key(merchant).replace(' ', '_')

    fun ruleData(merchant: String, category: String, now: Long): Map<String, Any?> = mapOf(
        "type" to TYPE,
        "title" to label(merchant),
        "merchant" to key(merchant),
        "category" to category,
        "updatedAt" to now,
        "isDeleted" to false
    )
}
