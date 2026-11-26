package com.familyhub.app.ui.money

import com.familyhub.app.data.remote.Rec
import com.familyhub.app.ui.common.dayInMonth
import com.familyhub.app.ui.common.endOfDay
import com.familyhub.app.ui.common.monthStart
import com.familyhub.app.ui.modules.DAY_MS
import com.familyhub.app.ui.modules.bool
import com.familyhub.app.ui.modules.lng
import com.familyhub.app.ui.modules.num
import com.familyhub.app.ui.modules.startOfDay
import com.familyhub.app.ui.modules.str
import java.util.Calendar

/**
 * Billing cycle of a credit card. The statement is generated on [statementDay] every month;
 * spends after it count toward the next bill. The bill is due on [dueDay] (next month if the
 * due day is on or before the statement day).
 */
data class CardCycle(
    val currentStart: Long,
    val currentEnd: Long,
    val billedStart: Long,
    val billedEnd: Long,
    val billDue: Long,
    val nextDue: Long
) {
    companion object {
        fun of(card: Rec, now: Long = System.currentTimeMillis()): CardCycle? {
            val s = card.num("statementDay")?.toInt() ?: return null
            val d = card.num("dueDay")?.toInt() ?: return null
            if (s !in 1..31 || d !in 1..31) return null
            val today = startOfDay(now)
            val thisMonth = dayInMonth(today, s)
            val lastStatement = if (startOfDay(thisMonth) <= today) thisMonth else dayInMonth(monthStart(today, -1), s)
            val nextStatement = dayInMonth(monthStart(lastStatement, 1), s)
            val prevStatement = dayInMonth(monthStart(lastStatement, -1), s)
            return CardCycle(
                currentStart = startOfDay(lastStatement) + DAY_MS,
                currentEnd = endOfDay(nextStatement),
                billedStart = startOfDay(prevStatement) + DAY_MS,
                billedEnd = endOfDay(lastStatement),
                billDue = dueAfter(lastStatement, s, d),
                nextDue = dueAfter(nextStatement, s, d)
            )
        }

        private fun dueAfter(statement: Long, s: Int, d: Int): Long =
            if (d > s) dayInMonth(statement, d) else dayInMonth(monthStart(statement, 1), d)
    }
}

/** Money spent (expenses, bills, EMIs) between two times. Income and savings are not counted. */
/** Marked "Not an expense" (self transfers, card bill payments, refunds merged into a purchase). */
fun isExcluded(r: Rec): Boolean = r.bool("notExpense")

/** Real spending: expenses, plus bills and EMIs once paid. */
fun isSpend(r: Rec): Boolean {
    val t = r.str("type").uppercase()
    if (r.bool("isDeleted") || isExcluded(r)) return false
    return t == "EXPENSE" || t.isBlank() || ((t == "BILL" || t == "EMI") && (r["isPaid"] as? Boolean ?: true))
}

fun isIncome(r: Rec): Boolean = !r.bool("isDeleted") && !isExcluded(r) && r.str("type").uppercase() == "INCOME"

/** What an entry counts for: its amount less any refunds merged into it. */
fun amountOf(r: Rec): Double = ((r.num("amountInr") ?: 0.0) - (r.num("refundAmount") ?: 0.0)).coerceAtLeast(0.0)

fun inRange(r: Rec, from: Long, to: Long): Boolean {
    val t = r.lng("dateMillis") ?: return false
    return t in from..to
}

/** Card spends: entries paid by this card, matched by card name. */
fun cardSpends(expenses: List<Rec>, card: Rec, from: Long, to: Long): List<Rec> {
    val name = card.str("title")
    return expenses.filter { isSpend(it) && inRange(it, from, to) && it.str("card") == name }
}

/** Calendar month index helper: the first day of each of the last [count] months, oldest first. */
fun lastMonths(count: Int, now: Long = System.currentTimeMillis()): List<Long> =
    (count - 1 downTo 0).map { monthStart(now, -it) }

fun daysBetween(from: Long, to: Long): Int = ((startOfDay(to) - startOfDay(from)) / DAY_MS).toInt()

fun calendarYear(millis: Long): Int = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR)

/**
 * Start of the "budget month" that is [add] months away from the one containing [now].
 * With [startDay] 1 this is the calendar month; with 25 a month runs from the 25th to the 24th.
 */
fun budgetMonthStart(now: Long, startDay: Int, add: Int = 0): Long {
    if (startDay <= 1) return monthStart(now, add)
    val thisStart = startOfDay(dayInMonth(monthStart(now, 0), startDay))
    val base = if (startOfDay(now) >= thisStart) monthStart(now, 0) else monthStart(now, -1)
    return startOfDay(dayInMonth(monthStart(base, add), startDay))
}

/** The person's budget month start day (1 to 28) saved in their profile. */
fun monthStartDay(userDoc: Rec): Int = ((userDoc["monthStartDay"] as? Number)?.toInt() ?: 1).coerceIn(1, 28)

/** Per-category monthly budgets saved in the person's profile. */
fun categoryBudgets(userDoc: Rec): Map<String, Double> =
    (userDoc["categoryBudgets"] as? Map<*, *>)?.mapNotNull { (k, v) ->
        val key = k as? String ?: return@mapNotNull null
        val amount = (v as? Number)?.toDouble() ?: return@mapNotNull null
        if (amount > 0) key to amount else null
    }?.toMap().orEmpty()
