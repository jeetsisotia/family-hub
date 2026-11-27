package com.familyhub.app.money

import java.util.Locale

/**
 * Finding the user's real transactions for an assistant request such as "change the category of Swiggy ₹645 to
 * Shopping". Plain Kotlin (no Android code), tested in tools/ledger-test.
 *
 * Nothing here comes from the AI model: the request is read on the phone and matched against the transactions the
 * person can already see in Finance. The model never supplies record ids or amounts that are trusted.
 */
object TxnFinder {

    /** What the person asked for: which transaction ([query], [amount], [latest]) and the category ([category], as typed). */
    data class Request(val query: String, val category: String, val amount: Double? = null, val latest: Boolean = false)

    private val VERB = "(?:change|update|set|move|switch|shift|correct|fix|recategori[sz]e|re-categori[sz]e|categori[sz]e|classify|put|tag|mark|file)"

    // "change the category of X to Y", "set category for X as Y"
    private val P1 = Regex("^$VERB\\s+(?:the\\s+)?(?:category|categories)\\s+(?:of|for|on)\\s+(.+?)\\s+(?:to|as|into|under)\\s+(.+)$")
    // "change X's category to Y", "change X category to Y"
    private val P2 = Regex("^$VERB\\s+(.+?)(?:'s|’s)?\\s+category\\s+(?:to|as|into|under)\\s+(.+)$")
    // "move X to Y", "recategorize X as Y", "put X under Y category"
    private val P3 = Regex("^$VERB\\s+(.+?)\\s+(?:to|as|into|under|in)\\s+(?:the\\s+)?(.+?)$")
    // Hinglish: "X ki category Y kar do", "X ko Y mein daal do", "X ko Y category mein shift karo"
    private val P4 = Regex("^(.+?)\\s+(?:ki|ka|ke)\\s+category\\s+(.+?)\\s+(?:kar|kardo|karo|kr|set|bana|badal)\\b.*$")
    // "category of X should be Y", "X's category should be Y"
    private val P6 = Regex("^(?:the\\s+)?(?:category|categories)\\s+(?:of|for)\\s+(.+?)\\s+(?:should be|must be|to|as|=)\\s+(.+)$")
    // Hinglish without "ko": "X wala payment Y category mein daal do", "X Y category me kar do"
    private val P7 = Regex("^(.+?)\\s+(\\S+)\\s+category\\s+(?:mein|me|main|mai|par|pe)\\s+(?:daal|dal|daalo|dalo|kar|karo|shift|move|rakh|set).*$")
    private val P5 = Regex("^(.+?)\\s+ko\\s+(.+?)\\s+(?:category\\s+)?(?:mein|me|main|mai)\\s+(?:daal|dal|daalo|dalo|kar|karo|shift|move|rakh).*$")

    private val STRONG = Regex("^(?:change|move|switch|shift|correct|fix|recategori[sz]e|re-categori[sz]e|categori[sz]e|classify)$")
    private val POLITE = Regex("^(?:please|pls|plz|kindly|can you|could you|would you|hey|ok|okay)[,\\s]+")
    private val AMOUNT = Regex("(?:₹|\\brs\\.?|\\binr)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)|\\b([0-9][0-9,]{1,}(?:\\.[0-9]{1,2})?)\\b")
    private val STOP = setOf(
        "the", "a", "an", "my", "our", "of", "for", "on", "from", "at", "in", "to", "this", "that", "with",
        "transaction", "transactions", "txn", "entry", "entries", "expense", "expenses", "payment", "payments",
        "spend", "spending", "purchase", "order", "record", "item", "category", "last", "latest", "recent", "most",
        "wala", "wali", "vala", "vali", "please", "rs", "inr", "₹",
        // Words of the request itself, never part of a shop or payee name (an AI may copy them into its "title").
        "change", "changed", "update", "set", "move", "switch", "shift", "correct", "fix", "recategorize", "recategorise",
        "categorize", "categorise", "classify", "categories", "ki", "ka", "ke", "ko", "kar", "karo", "kardo", "do", "mein",
        "me", "should", "be", "new", "one", "paid", "to", "named", "called"
    )
    private val LATEST = Regex("\\b(last|latest|most recent|recent|newest)\\b")

    /** True if the text asks to change a transaction's category (even if it cannot be read completely). */
    fun isIntent(text: String): Boolean {
        val s = clean(text)
        if (P1.matches(s) || P2.matches(s) || P4.matches(s) || P5.matches(s) || P6.matches(s) || P7.matches(s)) return true
        return Regex("\\b(recategori[sz]e|re-categori[sz]e|categori[sz]e)\\b").containsMatchIn(s) ||
            (Regex("\\bcategory\\b").containsMatchIn(s) && Regex("\\b(change|update|set|move|switch|shift|correct|fix|badal|kar)\\b").containsMatchIn(s) &&
                Regex("\\b(transaction|txn|entry|expense|payment|spend|purchase|order)s?\\b").containsMatchIn(s))
    }

    private fun clean(text: String): String {
        var s = text.trim().lowercase(Locale.ROOT).replace('“', '"').replace('”', '"').replace(Regex("[\"!?]+"), " ")
            .replace(Regex("\\s+"), " ").trim().trimEnd('.')
        while (true) { val n = s.replace(POLITE, ""); if (n == s) break; s = n }
        return s
    }

    /**
     * Reads a request. [categories] are the Finance categories the person has (built-in and the family's own).
     * For P3 ("move X to Y") the target must be a known category, so "move the meeting to Friday" is not read as one.
     * Returns null when the text is not a category change.
     */
    fun parse(text: String, categories: List<String>): Request? {
        val s = clean(text)
        val (what, target) = P1.matchEntire(s)?.destructured?.let { (a, b) -> a to b }
            ?: P2.matchEntire(s)?.destructured?.let { (a, b) -> a to b }
            ?: P4.matchEntire(s)?.destructured?.let { (a, b) -> a to b }
            ?: P5.matchEntire(s)?.destructured?.let { (a, b) -> a to b }
            ?: P6.matchEntire(s)?.destructured?.let { (a, b) -> a to b }
            ?: P7.matchEntire(s)?.destructured?.let { (a, b) -> a to b }
            ?: P3.matchEntire(s)?.let { m ->
                val (a, b) = m.destructured
                // Weak verbs ("put milk in shopping", "mark it as paid") count only with the word "category".
                val strong = STRONG.containsMatchIn(s.substringBefore(' '))
                val t = b.removeSuffix(" category").trim()
                if ("category" in b || (strong && resolveCategory(t, categories) != null)) a to b else null
            }
            ?: return null
        val cat = target.trim().removeSuffix(" category").removeSuffix(" categories").removePrefix("the ").trim().trimEnd('.')
        var q = what.trim()
        var amount: Double? = null
        AMOUNT.find(q)?.let { m ->
            val raw = (m.groups[1]?.value ?: m.groups[2]?.value).orEmpty().replace(",", "")
            raw.toDoubleOrNull()?.takeIf { it > 0 }?.let { amount = it; q = q.removeRange(m.range) }
        }
        val latest = LATEST.containsMatchIn(q)
        val words = q.replace(Regex("[^\\p{L}\\p{N}@&.' -]"), " ").split(Regex("\\s+"))
            .map { it.trim('.', '\'', '-', '’').removeSuffix("'s").removeSuffix("’s") }.filter { it.isNotBlank() && it !in STOP }
        return Request(words.joinToString(" "), cat, amount, latest)
    }

    /** The person's category for what they typed ("shopping" -> "Shopping"), or null if there is none. */
    fun resolveCategory(typed: String, categories: List<String>): String? {
        val t = FinanceMonth.normName(typed)
        if (t.isEmpty()) return null
        categories.firstOrNull { FinanceMonth.normName(it) == t }?.let { return it }
        // "groceries" vs "grocery", "bills" vs "bill"
        fun stem(x: String) = x.replace(Regex("ies$"), "y").trimEnd('s')
        categories.firstOrNull { stem(FinanceMonth.normName(it)) == stem(t) }?.let { return it }
        return null
    }

    private fun hay(r: Map<String, Any?>): String =
        listOf("title", "notes", "payee", "merchant", "account", "card", "paymentMethod", "reference", "smsSender")
            .joinToString(" ") { (r[it] as? String).orEmpty() }.lowercase(Locale.ROOT)

    /**
     * Transactions that match, newest first. Every word of the query must appear in the entry's text (title, notes,
     * payee, account, card…); the amount, if given, must match to the rupee. Deleted entries, transfers and card
     * payments never match (they cannot have a category). With [Request.latest], only the newest match is returned.
     */
    fun match(records: List<Map<String, Any?>>, req: Request): List<Map<String, Any?>> {
        val words = req.query.lowercase(Locale.ROOT).split(" ").filter { it.isNotBlank() }
        if (words.isEmpty() && req.amount == null) return emptyList()
        val found = records.filter { r ->
            Recategorize.categorisable(r) && (r["id"] as? String).orEmpty().isNotEmpty() &&
                (req.amount == null || kotlin.math.abs(Ledger.amount(r) - req.amount) < 1.0) &&
                hay(r).let { h -> words.all { w -> w in h } }
        }.distinctBy { it["id"] }
            .sortedByDescending { (it["dateMillis"] as? Number)?.toLong() ?: 0L }
        return if (req.latest) found.take(1) else found
    }

    /** Cleans words an AI copied into a search ("Change category of Mukul" -> "mukul"). */
    fun cleanQuery(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}@&. -]"), " ").split(Regex("\\s+"))
            .map { it.trim('.', '-') }.filter { it.isNotBlank() && it !in STOP }.joinToString(" ")

    /**
     * When nothing matches every word: the closest entries (some of the words, or the words run together, as in
     * "MUKULKUMAR"), best first, so the person can still pick the right one. Never changes anything by itself.
     */
    fun near(records: List<Map<String, Any?>>, req: Request, max: Int = 5): List<Map<String, Any?>> {
        val words = req.query.lowercase(Locale.ROOT).split(" ").filter { it.length >= 3 }
        if (words.isEmpty()) return emptyList()
        val joined = words.joinToString("")
        return records.asSequence()
            .filter { Recategorize.categorisable(it) && (it["id"] as? String).orEmpty().isNotEmpty() }
            .map { r ->
                val h = hay(r); val flat = h.filter { it.isLetterOrDigit() }
                val score = words.count { it in h } * 2 + (if (joined.length >= 4 && joined in flat) 3 else 0) +
                    words.count { w -> w.length >= 4 && Regex("\\b" + Regex.escape(w.take(4))).containsMatchIn(h) }
                val amountOk = req.amount == null || kotlin.math.abs(Ledger.amount(r) - req.amount) < 1.0
                r to (if (amountOk) score else 0)
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Map<String, Any?>, Int>> { it.second }.thenByDescending { (it.first["dateMillis"] as? Number)?.toLong() ?: 0L })
            .map { it.first }.distinctBy { it["id"] }.take(max).toList()
    }
}
