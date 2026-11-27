package com.familyhub.app.money

import java.util.Locale

/**
 * Suspected duplicate transactions with reasons, a confidence level and the entry to keep, in plain Kotlin
 * (no Android code), tested in tools/ledger-test.
 *
 * Matching uses [Ledger.duplicateGroups]: same direction and amount within a day and a half, then the same
 * reference number, card or account ending, or shop name. Amount alone is never enough, so two real payments
 * of the same amount on one day at different shops stay apart. Nothing is ever removed here: the screen asks
 * first, and removed entries go to Recently deleted.
 */
object Duplicates {
    enum class Confidence { HIGH, MEDIUM }

    data class Group(
        val records: List<Map<String, Any?>>,
        /** The suggested entry to keep (most detail: reference, category, receipt, notes; then the earliest). */
        val keep: Map<String, Any?>,
        /** The suggested entries to remove. */
        val remove: List<Map<String, Any?>>,
        val confidence: Confidence,
        /** Why these look like one payment. */
        val reasons: List<String>,
        /** Why they might still be real, separate payments. */
        val cautions: List<String>
    ) {
        /** Stable key for "Not duplicates" (sorted ids). */
        val key: String get() = records.mapNotNull { it["id"] as? String }.sorted().joinToString("|")
    }

    private fun s(r: Map<String, Any?>, k: String) = (r[k] as? String).orEmpty().trim()
    private fun l(r: Map<String, Any?>, k: String) = (r[k] as? Number)?.toLong()

    /** Where an entry came from: "SMS", "Email", "Statement" or "Typed in". */
    fun origin(r: Map<String, Any?>): String {
        val k = s(r, "sourceKey").lowercase(Locale.ROOT)
        val notes = s(r, "notes").lowercase(Locale.ROOT)
        return when {
            k.startsWith("sms") || notes.contains("bank sms") -> "SMS"
            k.startsWith("email") || k.startsWith("gmail") || notes.contains("bank email") -> "Email"
            k.startsWith("stmt") || k.startsWith("statement") -> "Statement"
            k.isNotEmpty() -> "Imported"
            else -> "Typed in"
        }
    }

    /** How much detail an entry holds, for choosing which one to keep. */
    fun detailScore(r: Map<String, Any?>): Int {
        var n = 0
        if (s(r, "refNo").isNotEmpty()) n += 3
        val c = s(r, "category")
        if (c.isNotEmpty() && !c.equals("Other", true) && !c.equals("None", true)) n += 2
        if (s(r, "proofPhoto").isNotEmpty()) n += 2
        if (((r["refundAmount"] as? Number)?.toDouble() ?: 0.0) > 0.0 || s(r, "refundOf").isNotEmpty()) n += 2
        if (s(r, "notes").isNotEmpty()) n += 1
        if (s(r, "subcategory").let { it.isNotEmpty() && it != "None" }) n += 1
        if (origin(r) == "Typed in") n += 1
        return n
    }

    private fun hoursApart(a: Map<String, Any?>, b: Map<String, Any?>): Long =
        kotlin.math.abs((l(a, "dateMillis") ?: 0L) - (l(b, "dateMillis") ?: 0L)) / 3_600_000L

    /**
     * Groups to review, highest confidence first. [excluded] holds keys of groups the person marked
     * "Not duplicates"; a group is hidden only while it has exactly the same entries.
     */
    fun find(records: List<Map<String, Any?>>, excluded: Set<String> = emptySet()): List<Group> {
        val groups = Ledger.duplicateGroups(records)
        val out = ArrayList<Group>()
        for (g in groups) {
            val sorted = g.sortedWith(
                compareByDescending<Map<String, Any?>> { detailScore(it) }
                    .thenBy { l(it, "createdAt") ?: l(it, "dateMillis") ?: Long.MAX_VALUE }
            )
            val keep = sorted.first()
            val remove = sorted.drop(1)
            val reasons = ArrayList<String>()
            val cautions = ArrayList<String>()
            var high = false

            val amount = Ledger.rupees(Ledger.paiseOf(keep))
            reasons += "Same amount (₹${"%,.2f".format(Locale.ENGLISH, amount).removeSuffix(".00")}) and direction"
            val maxHours = remove.maxOf { hoursApart(keep, it) }
            reasons += if (maxHours < 1) "Saved within the same hour" else "$maxHours ${if (maxHours == 1L) "hour" else "hours"} apart"

            val refs = g.map { s(it, "refNo") }.filter { it.isNotEmpty() }
            if (refs.size >= 2 && refs.distinct().size == 1) { reasons += "Same reference number (${refs.first().takeLast(6)})"; high = true }
            val ends = g.map { s(it, "last4") }.filter { it.isNotEmpty() }
            if (ends.size >= 2 && ends.distinct().size == 1) reasons += "Same card or account ending ••${ends.first()}"
            val shops = g.map { Ledger.merchantKey(s(it, "merchant").ifBlank { s(it, "title") }) }.filter { it.isNotEmpty() }
            val sameShop = shops.size >= 2 && shops.all { a -> shops.all { b -> a.startsWith(b.take(5)) || b.startsWith(a.take(5)) } }
            if (sameShop) reasons += "Same shop or payee (${s(keep, "merchant").ifBlank { s(keep, "title") }.take(30)})"
            val origins = g.map { origin(it) }.distinct()
            if (origins.size > 1) {
                reasons += "Saved from " + origins.joinToString(" and ") { if (it == "Typed in") "typed in by hand" else it }
                if (sameShop || (ends.size >= 2 && ends.distinct().size == 1)) high = true
            }
            if (g.all { Ledger.type(it) == "CARD_PAYMENT" }) {
                reasons += "A card bill payment is often recorded twice: by the bank and by the card"
                high = true
            }
            val exactSame = g.map { Triple(l(it, "dateMillis")?.div(60_000L), s(it, "title").lowercase(Locale.ROOT), Ledger.paiseOf(it)) }.distinct().size == 1
            if (exactSame && maxHours < 1) { reasons += "Same title and time"; high = true }

            // Reasons to look twice before removing.
            val people = g.mapNotNull { (it["createdByUid"] as? String) ?: (it["memberUid"] as? String) }.distinct()
            if (origins == listOf("Typed in") && people.size > 1) cautions += "Added by different people: could be two real purchases"
            if (origins == listOf("Typed in") && !exactSame && maxHours >= 1) cautions += "Both typed in at different times: check they are not two real payments"
            val key = shops.firstOrNull()
            if (key != null) {
                val monthsSeen = records.filter { r ->
                    Ledger.live(r) && Ledger.paiseOf(r) == Ledger.paiseOf(keep) && r !in g &&
                        Ledger.merchantKey(s(r, "merchant").ifBlank { s(r, "title") }).let { it.isNotEmpty() && (it.startsWith(key.take(5)) || key.startsWith(it.take(5))) }
                }.mapNotNull { l(it, "dateMillis")?.let { d -> FinanceMonthKey.of(d) } }.distinct().size
                if (monthsSeen >= 2) cautions += "A regular payment at this shop: make sure these are not two different months or bills"
            }
            if (refs.size >= 2 && refs.distinct().size > 1) cautions += "Different reference numbers"
            if (cautions.isNotEmpty() && !(refs.size >= 2 && refs.distinct().size == 1)) high = false

            val grp = Group(g, keep, remove, if (high) Confidence.HIGH else Confidence.MEDIUM, reasons, cautions)
            if (grp.key !in excluded) out += grp
        }
        return out.sortedWith(compareBy<Group> { it.confidence.ordinal }.thenByDescending { l(it.keep, "dateMillis") ?: 0L })
    }

    /** The fields that remove one duplicate: it goes to Recently deleted and remembers which entry was kept. */
    fun removeFields(keep: Map<String, Any?>, now: Long): Map<String, Any?> =
        mapOf("isDeleted" to true, "deletedAt" to now, "duplicateOf" to (keep["id"] as? String), "updatedAt" to now)

    /** The fields that put a removed duplicate back. */
    fun restoreFields(now: Long): Map<String, Any?> =
        mapOf("isDeleted" to false, "deletedAt" to null, "duplicateOf" to null, "updatedAt" to now)
}

/** Year and month of a date, as one number (for "seen in other months"). */
object FinanceMonthKey {
    fun of(millis: Long): Int {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        return c.get(java.util.Calendar.YEAR) * 12 + c.get(java.util.Calendar.MONTH)
    }
}
