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
fun isSpend(r: Rec): Boolean {
    val t = r.str("type").uppercase()
    return !r.bool("isDeleted") && (t == "EXPENSE" || t == "BILL" || t == "EMI" || t.isBlank())
}

fun isIncome(r: Rec): Boolean = !r.bool("isDeleted") && r.str("type").uppercase() == "INCOME"

fun amountOf(r: Rec): Double = r.num("amountInr") ?: 0.0

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
