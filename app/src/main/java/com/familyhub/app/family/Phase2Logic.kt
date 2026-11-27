package com.familyhub.app.family

import java.util.Calendar
import java.util.Locale

/** Phase 2 logic in plain Kotlin (tested in tools/phase2-test): shopping lists, calendar, subscriptions, maintenance. */

private fun Map<String, Any?>.s(k: String) = (this[k] as? String).orEmpty().trim()
private fun Map<String, Any?>.n(k: String) = (this[k] as? Number)?.toDouble()
private fun Map<String, Any?>.l(k: String) = (this[k] as? Number)?.toLong()

object ShoppingLists {
    const val DEFAULT_LIST = "Main list"
    val CATEGORIES = listOf("Grocery", "Household", "Medicines", "Electronics", "Personal care", "Other")

    enum class State { PENDING, UNAVAILABLE, BOUGHT }

    fun listName(r: Map<String, Any?>) = r.s("listName").ifBlank { DEFAULT_LIST }
    fun state(r: Map<String, Any?>): State = when {
        r["isCompleted"] == true -> State.BOUGHT
        r.s("status").equals("Unavailable", true) -> State.UNAVAILABLE
        else -> State.PENDING
    }

    /** "2", "2 kg", "1.5 L" -> 2.0, 2.0, 1.5 (the count used for the estimate; units are not converted). */
    fun quantity(r: Map<String, Any?>): Double {
        (r["qty"] as? Number)?.let { return it.toDouble().coerceAtLeast(0.0) }
        val t = r.s("qty").ifBlank { r.s("subtitle") }
        return Regex("^\\s*(\\d+(?:\\.\\d+)?)").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
    }

    /** Estimated cost of what is still to buy: price × quantity, for items that have a price. */
    fun estimate(items: List<Map<String, Any?>>): Double =
        items.filter { state(it) == State.PENDING }.sumOf { (it.n("estPrice") ?: 0.0) * quantity(it) }

    data class ListSummary(val name: String, val pending: Int, val unavailable: Int, val bought: Int, val estimate: Double)

    fun summaries(items: List<Map<String, Any?>>): List<ListSummary> =
        items.groupBy { listName(it) }.map { (name, l) ->
            ListSummary(name, l.count { state(it) == State.PENDING }, l.count { state(it) == State.UNAVAILABLE }, l.count { state(it) == State.BOUGHT }, estimate(l))
        }.sortedWith(compareByDescending<ListSummary> { it.pending }.thenBy { it.name.lowercase(Locale.ROOT) })

    /** Search across item, list, category and who it is assigned to. */
    fun search(items: List<Map<String, Any?>>, q: String): List<Map<String, Any?>> {
        val w = q.trim().lowercase(Locale.ROOT)
        if (w.isEmpty()) return items
        return items.filter { r -> listOf("title", "listName", "category", "assignedToName", "qty", "subtitle").any { r.s(it).lowercase(Locale.ROOT).contains(w) } }
    }
}

object CalendarMath {
    private const val DAY = 86_400_000L
    private const val HOUR = 3_600_000L

    data class Occurrence(val rec: Map<String, Any?>, val start: Long, val end: Long, val allDay: Boolean)

    /** "18:30" -> minutes after midnight, or null for all day. */
    fun minutes(t: String): Int? = Regex("^(\\d{1,2}):(\\d{2})").find(t.trim())?.destructured?.let { (h, m) ->
        val hh = h.toInt(); val mm = m.toInt(); if (hh in 0..23 && mm in 0..59) hh * 60 + mm else null
    }

    private fun startOfDay(t: Long, cal: Calendar): Long { cal.timeInMillis = t; cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0); return cal.timeInMillis }

    /** Every occurrence of the events (repeating ones included) between [from] and [to], in the phone's calendar. */
    fun occurrences(events: List<Map<String, Any?>>, from: Long, to: Long, cal: Calendar = Calendar.getInstance()): List<Occurrence> {
        val out = ArrayList<Occurrence>()
        for (e in events) {
            if (e["isDeleted"] == true) continue
            val base = e.l("startTimeMillis") ?: continue
            val day0 = startOfDay(base, cal)
            val startMin = minutes(e.s("time"))
            val endMin = minutes(e.s("endTime"))
            val rec = e.s("recurrence").uppercase(Locale.ROOT)
            var k = 0
            while (k < 800) {
                cal.timeInMillis = day0
                when (rec) {
                    "YEARLY" -> cal.add(Calendar.YEAR, k)
                    "MONTHLY" -> cal.add(Calendar.MONTH, k)
                    "WEEKLY" -> cal.add(Calendar.DAY_OF_YEAR, 7 * k)
                    "DAILY" -> cal.add(Calendar.DAY_OF_YEAR, k)
                    else -> if (k > 0) break
                }
                val day = cal.timeInMillis
                if (day > to) break
                if (day + DAY > from) {
                    val s = if (startMin != null) day + startMin * 60_000L else day
                    val en = when {
                        startMin == null -> day + DAY
                        endMin != null && endMin > startMin -> day + endMin * 60_000L
                        else -> s + HOUR
                    }
                    out += Occurrence(e, s, en, startMin == null)
                }
                k++
            }
        }
        return out.sortedBy { it.start }
    }

    /** Pairs of timed events that overlap (all-day events never conflict). */
    fun conflicts(occ: List<Occurrence>): List<Pair<Occurrence, Occurrence>> {
        val timed = occ.filter { !it.allDay }.sortedBy { it.start }
        val out = ArrayList<Pair<Occurrence, Occurrence>>()
        for (i in timed.indices) for (j in i + 1 until timed.size) {
            if (timed[j].start >= timed[i].end) break
            out += timed[i] to timed[j]
        }
        return out
    }

    /** Owner and participants, for the member filter. */
    fun people(e: Map<String, Any?>): Set<String> =
        (listOf(e.s("ownerName"), e.s("memberName")) + e.s("participants").split(',', '&', ';').map { it.trim() })
            .filter { it.isNotBlank() }.map { it.lowercase(Locale.ROOT) }.toSet()
}

object Subscriptions {
    enum class Status(val label: String) { ACTIVE("Active"), PAUSED("Paused"), CANCELLED("Cancelled") }

    fun status(r: Map<String, Any?>): Status = Status.values().firstOrNull { it.label.equals(r.s("status"), true) } ?: Status.ACTIVE
    fun isLive(r: Map<String, Any?>) = r["isDeleted"] != true && status(r) == Status.ACTIVE

    /** Months per billing cycle (Weekly counts as 12/52 of a month). */
    fun monthsPerCycle(cycle: String): Double = when (cycle.lowercase(Locale.ROOT)) {
        "weekly" -> 12.0 / 52.0
        "quarterly" -> 3.0
        "half-yearly", "half yearly" -> 6.0
        "yearly", "annual", "annually" -> 12.0
        else -> 1.0
    }

    data class Totals(val monthly: Double, val yearly: Double, val active: Int, val paused: Int, val cancelled: Int)

    fun totals(subs: List<Map<String, Any?>>): Totals {
        val live = subs.filter { it["isDeleted"] != true }
        val monthly = live.filter { status(it) == Status.ACTIVE }.sumOf { (it.n("amountInr") ?: 0.0) / monthsPerCycle(it.s("cycle")) }
        return Totals(monthly, monthly * 12, live.count { status(it) == Status.ACTIVE }, live.count { status(it) == Status.PAUSED }, live.count { status(it) == Status.CANCELLED })
    }

    private fun key(t: String) = t.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** Payments in Finance that look like this subscription (its name or provider in the entry's title), newest first. */
    fun payments(sub: Map<String, Any?>, expenses: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val keys = listOf(sub.s("provider"), sub.s("title")).map(::key).filter { it.length >= 4 }.distinct()
        if (keys.isEmpty()) return emptyList()
        return expenses.filter { e ->
            e["isDeleted"] != true && (e.n("amountInr") ?: 0.0) > 0 &&
                key(e.s("title") + " " + e.s("merchant")).let { t -> keys.any { it in t } }
        }.sortedByDescending { it.n("dateMillis") ?: 0.0 }
    }
}

object Maintenance {
    data class Log(val atMillis: Long, val cost: Double, val provider: String, val notes: String)

    /** The service history saved on the record (newest first). */
    @Suppress("UNCHECKED_CAST")
    fun history(r: Map<String, Any?>): List<Log> =
        ((r["serviceLog"] as? List<*>).orEmpty()).mapNotNull { e ->
            val m = e as? Map<String, Any?> ?: return@mapNotNull null
            val at = (m["at"] as? Number)?.toLong() ?: return@mapNotNull null
            Log(at, (m["cost"] as? Number)?.toDouble() ?: 0.0, (m["provider"] as? String).orEmpty(), (m["notes"] as? String).orEmpty())
        }.sortedByDescending { it.atMillis }

    fun totalSpent(r: Map<String, Any?>) = history(r).sumOf { it.cost }

    /** Months between services from "Repeats" (Monthly, Quarterly, Half-yearly, Yearly), or null. */
    fun everyMonths(repeat: String): Int? = when (repeat.lowercase(Locale.ROOT)) {
        "monthly" -> 1; "quarterly" -> 3; "half-yearly", "half yearly", "every 6 months" -> 6; "yearly" -> 12; else -> null
    }

    /** Next due after a service on [at]: [at] plus the repeat interval, or null if it does not repeat. */
    fun nextAfter(at: Long, repeat: String, cal: Calendar = Calendar.getInstance()): Long? {
        val m = everyMonths(repeat) ?: return null
        cal.timeInMillis = at; cal.add(Calendar.MONTH, m); return cal.timeInMillis
    }

    /** The new log list with one entry added (kept to the newest 50). */
    fun withEntry(r: Map<String, Any?>, log: Log): List<Map<String, Any?>> =
        (listOf(log) + history(r)).take(50).map { mapOf("at" to it.atMillis, "cost" to it.cost, "provider" to it.provider, "notes" to it.notes) }
}
