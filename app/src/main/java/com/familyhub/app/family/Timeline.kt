package com.familyhub.app.family

import java.util.Locale

/**
 * The family activity timeline, built from records the app already has in memory (no extra database reads).
 * Plain Kotlin, tested in tools/phase1-test.
 *
 * Privacy: "Only me" records, records shared as private, and health, ID and note-type records are never shown.
 * Documents show only their name and type, never numbers or notes. Amounts are shown for money entries.
 */
object Timeline {
    enum class Kind(val label: String) { MONEY("Money"), TASK("Tasks"), EVENT("Calendar"), REMINDER("Reminders"), DOCUMENT("Documents"), OTHER("Other") }

    data class Entry(
        val atMillis: Long,
        val kind: Kind,
        /** "added", "updated", "completed" */
        val action: String,
        val title: String,
        val detail: String,
        val who: String,
        val whoUid: String,
        val route: String
    ) {
        /** "Asha added an expense: Groceries" */
        val sentence: String get() = "$who $action ${noun()}: $title"
        private fun noun() = when (kind) {
            Kind.MONEY -> "a money entry"; Kind.TASK -> "a task"; Kind.EVENT -> "an event"
            Kind.REMINDER -> "a reminder"; Kind.DOCUMENT -> "a document"; Kind.OTHER -> "an item"
        }
    }

    private const val PRIVATE_FLAG = "_private" // same as data.remote.PRIVATE_FLAG
    /** Types that stay out of the shared timeline (health, IDs, personal notes, chats…). */
    private val HIDDEN_TYPES = setOf(
        "medicine", "vital", "period", "habit", "health", "emergency", "vault", "note", "memory", "food", "workout",
        "category", "card", "account", "debit_card", "loan", "lab", "doctor", "allergy"
    )
    private const val EDIT_GAP_MS = 2 * 60_000L

    private fun Map<String, Any?>.s(k: String) = (this[k] as? String).orEmpty().trim()
    private fun Map<String, Any?>.l(k: String) = (this[k] as? Number)?.toLong()
    private fun Map<String, Any?>.b(k: String) = this[k] == true
    private fun money(v: Double) = "₹" + String.format(Locale("en", "IN"), "%,.0f", v)

    /** True if a record may appear in the shared timeline. */
    fun shareable(r: Map<String, Any?>): Boolean =
        r[PRIVATE_FLAG] != true && !r.s("visibility").equals("Only me", true) && r.s("type").lowercase(Locale.ROOT) !in HIDDEN_TYPES

    private fun kindOf(collection: String, r: Map<String, Any?>): Kind? = when (collection) {
        "expenses" -> Kind.MONEY
        "tasks" -> Kind.TASK
        "events" -> Kind.EVENT
        "items" -> when (r.s("type")) { "reminder" -> Kind.REMINDER; "document" -> Kind.DOCUMENT; "subscription", "bill", "policy" -> Kind.MONEY; else -> null }
        else -> null
    }

    private fun routeOf(kind: Kind, r: Map<String, Any?>) = when (kind) {
        Kind.MONEY -> if (r.s("type") == "subscription") "subscriptions" else "transactions"
        Kind.TASK -> if (r.s("type") == "SHOPPING") "shopping" else "tasks"
        Kind.EVENT -> "calendar"; Kind.REMINDER -> "reminders"; Kind.DOCUMENT -> "documents"; Kind.OTHER -> "more"
    }

    /**
     * All entries, newest first. [data] is keyed by collection (expenses, tasks, events, items); [names] maps uid to
     * name for records that only carry a uid. Entries without a known time are left out (never guessed).
     */
    fun build(data: Map<String, List<Map<String, Any?>>>, names: Map<String, String>, limit: Int = 300, now: Long = System.currentTimeMillis()): List<Entry> {
        val out = ArrayList<Entry>()
        for ((collection, recs) in data) {
            for (r in recs) {
                if (!shareable(r)) continue
                val kind = kindOf(collection, r) ?: continue
                val title = r.s("title").ifBlank { r.s("destination") }
                if (title.isBlank()) continue
                val detail = when (kind) {
                    Kind.MONEY -> listOfNotNull((r["amountInr"] as? Number)?.toDouble()?.let { money(it) }, r.s("category").takeIf { it.isNotBlank() }).joinToString(" · ")
                    Kind.DOCUMENT -> r.s("category")            // never the number or notes
                    else -> ""
                }
                val creatorUid = r.s("createdByUid").ifBlank { r.s("memberUid") }
                val creator = r.s("createdByName").ifBlank { r.s("memberName") }.ifBlank { names[creatorUid].orEmpty() }.ifBlank { "Someone" }
                val created = r.l("createdAt") ?: r.l("createdAtMillis")
                val route = routeOf(kind, r)
                val deleted = r.b("isDeleted")
                if (created != null && !deleted) out += Entry(created, kind, "added", title, detail, creator, creatorUid, route)
                val updated = r.l("updatedAt")
                val completed = r.l("completedAt")
                val doneNow = r.b("isCompleted") || r.b("done")
                if (completed != null && doneNow) {
                    val uid = r.s("completedByUid")
                    out += Entry(completed, kind, "completed", title, detail, r.s("completedByName").ifBlank { names[uid].orEmpty() }.ifBlank { "Someone" }, uid, route)
                }
                if (updated != null && !deleted && (created == null || updated - created > EDIT_GAP_MS) && (completed == null || kotlin.math.abs(updated - completed) > EDIT_GAP_MS)) {
                    val uid = r.s("updatedByUid")
                    out += Entry(updated, kind, "updated", title, detail, r.s("updatedByName").ifBlank { names[uid].orEmpty() }.ifBlank { "Someone" }, uid, route)
                }
            }
        }
        // A time in the future is a date typed into the record, not when someone did something: never shown.
        return out.filter { it.atMillis <= now + 5 * 60_000L }.sortedByDescending { it.atMillis }.take(limit)
    }

    /** Filters by kind (null = all) and member name (null = everyone). */
    fun filter(entries: List<Entry>, kind: Kind?, who: String?): List<Entry> =
        entries.filter { (kind == null || it.kind == kind) && (who == null || it.who.equals(who, true)) }
}
