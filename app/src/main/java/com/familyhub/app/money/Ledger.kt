package com.familyhub.app.money

import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The money maths for accounts, cards, loans and dues, kept free of Android so it can be tested on its own.
 *
 * Rules (each one is checked by LedgerTest):
 * - Spending counts when it happens: a card purchase is an expense on the day it is made.
 * - Paying a card bill (CARD_PAYMENT) lowers the card's outstanding and the bank balance, but is not an expense.
 * - Moving money between your own accounts (TRANSFER) is neither income nor expense.
 * - A bill or EMI counts as spending only once it is marked paid. A reminder is never a payment.
 * - Refunds merged into a purchase reduce what it counts for; balances use the real money that moved.
 */
object Ledger {
    const val DAY = 86_400_000L

    // ---------- record helpers ----------
    fun Map<String, Any?>.s(k: String): String = (this[k] as? String).orEmpty()
    fun Map<String, Any?>.n(k: String): Double? = when (val v = this[k]) {
        is Number -> v.toDouble()
        is String -> v.replace(",", "").trim().toDoubleOrNull()
        else -> null
    }
    fun Map<String, Any?>.l(k: String): Long? = when (val v = this[k]) {
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull()
        else -> null
    }
    fun Map<String, Any?>.b(k: String): Boolean = this[k] == true

    fun id(r: Map<String, Any?>): String = r.s("id")
    fun type(r: Map<String, Any?>): String = r.s("type").uppercase().ifBlank { "EXPENSE" }
    fun live(r: Map<String, Any?>): Boolean = !r.b("isDeleted")
    fun amount(r: Map<String, Any?>): Double = r.n("amountInr") ?: 0.0

    /**
     * Money is added up in whole paise (a Long), never by adding Doubles, so totals cannot drift
     * (0.10 + 0.20 − 0.30 is exactly zero). Rupee figures are made from the paise total at the end.
     */
    fun paise(v: Double): Long = Math.round(v * 100.0)
    fun rupees(p: Long): Double = p / 100.0
    fun paiseOf(r: Map<String, Any?>): Long = paise(amount(r))

    /** Money types that only move money around and never count as income or spending. */
    val MOVES = setOf("TRANSFER", "CARD_PAYMENT", "ADJUSTMENT")

    fun isPaidDue(r: Map<String, Any?>): Boolean = r["isPaid"] as? Boolean ?: true

    /** Counts as spending in reports and budgets. */
    fun isSpend(r: Map<String, Any?>): Boolean {
        if (!live(r) || r.b("notExpense")) return false
        return when (type(r)) {
            // Money put into savings is kept, not spent; it shows in the savings figure.
            "EXPENSE" -> true
            "BILL", "EMI" -> isPaidDue(r)
            else -> false
        }
    }

    fun isIncome(r: Map<String, Any?>): Boolean = live(r) && !r.b("notExpense") && !r.b("isRefund") && type(r) == "INCOME"

    /**
     * Money back for a purchase (a refund or reversal) that has not been merged into that purchase.
     * It lowers spending in the month it arrives and is never income. A merged refund ("refundOf" set)
     * is already taken off its purchase, so it is not counted again.
     */
    fun isRefund(r: Map<String, Any?>): Boolean = live(r) && r.b("isRefund") && type(r) == "INCOME" && r.s("refundOf").isBlank()

    /** What a purchase counts for after merged refunds. */
    fun spendAmount(r: Map<String, Any?>): Double = rupees(max(0L, paise(amount(r)) - paise(r.n("refundAmount") ?: 0.0)))

    // ---------- matching records to accounts and cards ----------

    /** A transaction belongs to an account by id, by title, or through a debit card linked to that account. */
    fun touchesAccount(t: Map<String, Any?>, account: Map<String, Any?>, debitCards: List<Map<String, Any?>>): Boolean {
        val aid = id(account)
        val title = account.s("title")
        if (aid.isNotBlank() && t.s("accountId") == aid) return true
        if (title.isNotBlank() && t.s("account") == title) return true
        val card = t.s("cardId").ifBlank { t.s("card") }
        if (card.isNotBlank() && card != "None") {
            debitCards.firstOrNull { id(it) == card || it.s("title") == card }?.let { dc ->
                return dc.s("accountId") == aid || (title.isNotBlank() && dc.s("account") == title)
            }
        }
        return false
    }

    fun touchesCard(t: Map<String, Any?>, card: Map<String, Any?>): Boolean {
        val cid = id(card)
        val title = card.s("title")
        val ref = t.s("cardId").ifBlank { t.s("card") }
        if (ref.isBlank() || ref == "None") return false
        return (cid.isNotBlank() && ref == cid) || (title.isNotBlank() && ref == title)
    }

    private fun isCardPurchase(t: Map<String, Any?>): Boolean =
        t.s("paymentMethod") == "Credit card" || (t.s("paymentMethod").isBlank() && t.s("cardKind") == "credit")

    // ---------- accounts ----------

    data class AccountBalance(
        val id: String, val title: String, val kind: String,
        val opening: Double, val moneyIn: Double, val moneyOut: Double, val balance: Double
    )

    /**
     * Opening balance plus money in, minus money out, counting only entries on or after the opening date.
     * Credit card purchases do not touch the bank until the card bill is paid.
     */
    fun accountBalance(
        account: Map<String, Any?>, txns: List<Map<String, Any?>>, debitCards: List<Map<String, Any?>>,
        upTo: Long = Long.MAX_VALUE
    ): AccountBalance {
        val opening = account.n("openingBalance") ?: 0.0
        // The opening balance is the balance at the start of that day.
        val from = account.l("openingDateMillis")?.let { startOfDay(it) } ?: 0L
        var inflow = 0L
        var outflow = 0L
        val aid = id(account)
        for (t in txns) {
            if (!live(t) || t.s("dueKind") == "card") continue
            val at = t.l("dateMillis") ?: 0L
            if (at < from || at > upTo) continue
            val ty = type(t)
            val a = paiseOf(t)
            // Money arriving from a transfer.
            if (ty == "TRANSFER" && (t.s("toAccountId") == aid || (t.s("toAccount").isNotBlank() && t.s("toAccount") == account.s("title")))) {
                inflow += a
                continue
            }
            if (!touchesAccount(t, account, debitCards)) continue
            when (ty) {
                "INCOME" -> inflow += a
                "ADJUSTMENT" -> if (a >= 0) inflow += a else outflow += -a
                "TRANSFER", "CARD_PAYMENT" -> outflow += a
                "BILL", "EMI" -> if (isPaidDue(t) && !isCardPurchase(t)) outflow += a
                else -> if (!isCardPurchase(t)) outflow += a
            }
        }
        return AccountBalance(
            aid, account.s("title"), account.s("category").ifBlank { "Savings" }, opening,
            rupees(inflow), rupees(outflow), rupees(paise(opening) + inflow - outflow)
        )
    }

    // ---------- credit cards ----------

    data class CardStatus(
        val id: String, val title: String, val limit: Double?,
        val outstanding: Double, val available: Double?,
        val lastStatementMillis: Long, val nextStatementMillis: Long, val dueMillis: Long,
        val statementAmount: Double, val paidSinceStatement: Double, val amountDue: Double, val minDue: Double,
        val unbilled: Double, val history: List<CardCycle>
    )

    data class CardCycle(val statementMillis: Long, val spent: Double, val paid: Double, val closing: Double)

    /** The day [day] of the month that holds [at], clamped to the month's length, at noon. */
    fun dayOfMonth(at: Long, day: Int, monthOffset: Int = 0): Long {
        val c = Calendar.getInstance().apply { timeInMillis = at; add(Calendar.MONTH, monthOffset) }
        val d = day.coerceIn(1, c.getActualMaximum(Calendar.DAY_OF_MONTH))
        c.set(Calendar.DAY_OF_MONTH, d)
        c.set(Calendar.HOUR_OF_DAY, 12); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun endOfDay(at: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59); c.set(Calendar.SECOND, 59); c.set(Calendar.MILLISECOND, 999)
        return c.timeInMillis
    }

    fun startOfDay(at: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** Charges, credits and payments on a card, and the bill that follows from them. */
    fun statementDay(card: Map<String, Any?>): Int = (card.n("statementDay") ?: 1.0).toInt().coerceIn(1, 31)

    /** The bill is due on the card's due day, or about 18 days after the statement when none is saved. */
    fun cardDueDay(card: Map<String, Any?>): Int =
        (card.n("dueDay")?.toInt() ?: (statementDay(card) + 18).let { if (it > 28) it - 28 else it }).coerceIn(1, 31)

    fun cardStatus(card: Map<String, Any?>, txns: List<Map<String, Any?>>, now: Long): CardStatus {
        val stDay = statementDay(card)
        val dueDay = cardDueDay(card)
        var lastSt = endOfDay(dayOfMonth(now, stDay))
        if (lastSt > now) lastSt = endOfDay(dayOfMonth(now, stDay, -1))
        val nextSt = endOfDay(dayOfMonth(lastSt, stDay, 1))
        // Due date: the first due day after the statement.
        var due = dayOfMonth(lastSt, dueDay)
        if (due <= lastSt) due = dayOfMonth(lastSt, dueDay, 1)
        val opening = card.n("openingOutstanding") ?: 0.0
        val openingAt = card.l("openingDateMillis")?.let { startOfDay(it) } ?: 0L

        // In paise.
        fun charge(t: Map<String, Any?>): Long {
            if (!live(t) || !touchesCard(t, card)) return 0L
            // A saved card bill only repeats purchases already charged.
            if (t.s("dueKind") == "card") return 0L
            val ty = type(t)
            if (ty == "CARD_PAYMENT" || ty == "TRANSFER" || ty == "ADJUSTMENT") return 0L
            if (ty == "INCOME") return -paiseOf(t) // refund or cashback to the card
            if ((ty == "BILL" || ty == "EMI") && !isPaidDue(t)) return 0L
            return if (isCardPurchase(t) || t.s("cardKind") == "credit" || t.s("paymentMethod").isBlank()) paiseOf(t) else 0L
        }
        fun payment(t: Map<String, Any?>): Long =
            if (live(t) && type(t) == "CARD_PAYMENT" && touchesCard(t, card)) paiseOf(t) else 0L

        val openingP = paise(opening)
        var chargesToStatement = 0L
        var paidToStatement = 0L
        var unbilled = 0L
        var paidSince = 0L
        for (t in txns) {
            val at = t.l("dateMillis") ?: continue
            if (at < openingAt) continue
            val c = charge(t)
            val p = payment(t)
            if (at <= lastSt) { chargesToStatement += c; paidToStatement += p } else { unbilled += c; paidSince += p }
        }
        val statementP = max(0L, openingP + chargesToStatement - paidToStatement)
        val outstandingP = max(0L, statementP + unbilled - paidSince)
        val dueP = max(0L, statementP - paidSince)
        // Card statements usually ask for 5% (at least ₹200, in whole rupees) unless a bill alert said otherwise.
        val minFromAlert = card.n("lastMinDue")?.takeIf { (card.l("lastMinDueFor") ?: 0L) >= lastSt - 5 * DAY }?.let { paise(it) }
        val minP = if (dueP <= 0L) 0L else min(dueP, minFromAlert ?: max(20_000L, ((dueP * 5 + 50) / 100 + 50) / 100 * 100))
        val statementAmount = rupees(statementP)
        val outstanding = rupees(outstandingP)
        val amountDue = rupees(dueP)
        val minDue = rupees(minP)
        val limit = card.n("limitInr")
        val history = (0 until 6).map { k ->
            val end = endOfDay(dayOfMonth(lastSt, stDay, -k))
            val start = endOfDay(dayOfMonth(lastSt, stDay, -k - 1))
            var spent = 0L
            var paid = 0L
            var closing = openingP
            for (t in txns) {
                val at = t.l("dateMillis") ?: continue
                if (at < openingAt) continue
                if (at in (start + 1)..end) { spent += charge(t); paid += payment(t) }
                if (at <= end) closing += charge(t) - payment(t)
            }
            CardCycle(end, rupees(spent), rupees(paid), rupees(max(0L, closing)))
        }
        return CardStatus(
            id(card), card.s("title"), limit, outstanding, limit?.let { rupees(max(0L, paise(it) - outstandingP)) },
            lastSt, nextSt, due, statementAmount, rupees(paidSince), amountDue, minDue, rupees(unbilled), history
        )
    }

    // ---------- loans ----------

    data class Installment(val no: Int, val dueMillis: Long, val emi: Double, val principal: Double, val interest: Double, val balanceAfter: Double, val status: String)

    data class LoanStatus(
        val id: String, val title: String, val principal: Double, val rate: Double, val tenure: Int, val emi: Double,
        val paidCount: Int, val remaining: Int, val outstandingPrincipal: Double, val interestPayable: Double,
        val totalInterest: Double, val nextDue: Installment?, val overdue: Int, val schedule: List<Installment>
    )

    /** EMI for a loan: P·r·(1+r)^n / ((1+r)^n − 1) with r the monthly rate. */
    fun emi(principal: Double, annualRatePct: Double, months: Int): Double {
        if (months <= 0) return 0.0
        val r = annualRatePct / 1200.0
        if (r == 0.0) return principal / months
        val f = (1 + r).pow(months)
        return principal * r * f / (f - 1)
    }

    fun round2(v: Double) = (v * 100).roundToLong() / 100.0

    /**
     * The full EMI schedule with each instalment's status. Paid instalments come from EMI entries linked to the loan
     * (by loanId, or loan title) that are marked paid; they are matched by instalment number, else in order.
     */
    fun loanStatus(loan: Map<String, Any?>, txns: List<Map<String, Any?>>, now: Long): LoanStatus {
        val p = loan.n("principal") ?: loan.n("amountInr") ?: 0.0
        val rate = loan.n("interestRate") ?: 0.0
        var n = (loan.n("tenureMonths") ?: 0.0).toInt()
        var e = loan.n("emiAmount") ?: 0.0
        if (n <= 0 && e > 0 && p > 0) {
            // Older entries only have the outstanding amount and EMI: work out how many months are left.
            val r = rate / 1200.0
            n = if (r == 0.0) ceil(p / e).toInt() else {
                val x = 1 - p * r / e
                if (x <= 0) 360 else ceil(-kotlin.math.ln(x) / kotlin.math.ln(1 + r)).toInt()
            }
        }
        if (e <= 0.0 && n > 0) e = emi(p, rate, n)
        n = n.coerceIn(0, 600)
        val first = loan.l("startMillis") ?: loan.l("nextDueMillis") ?: now
        val payments = txns.filter {
            live(it) && type(it) == "EMI" && isPaidDue(it) &&
                (it.s("loanId") == id(loan) || (it.s("loanId").isBlank() && it.s("loan") == loan.s("title")))
        }
        val byNo = payments.mapNotNull { pm -> pm.n("installmentNo")?.toInt() }.toMutableSet()
        var unnumbered = payments.count { it.n("installmentNo") == null } + (loan.n("paidInstallments") ?: 0.0).toInt()
        val r = rate / 1200.0
        var bal = p
        val today = startOfDay(now)
        val list = ArrayList<Installment>(n)
        for (i in 1..n) {
            val due = dayOfMonth(first, Calendar.getInstance().apply { timeInMillis = first }.get(Calendar.DAY_OF_MONTH), i - 1)
            val interest = bal * r
            var principalPart = e - interest
            if (i == n || principalPart > bal) principalPart = bal
            bal = max(0.0, bal - principalPart)
            val paid = if (i in byNo) true else if (unnumbered > 0) { unnumbered--; true } else false
            val status = when {
                paid -> "PAID"
                due < today -> "OVERDUE"
                due <= today + 7 * DAY -> "DUE"
                else -> "UPCOMING"
            }
            list.add(Installment(i, due, round2(principalPart + interest), round2(principalPart), round2(interest), round2(bal), status))
        }
        val paidCount = list.count { it.status == "PAID" }
        val lastPaid = list.lastOrNull { it.status == "PAID" }
        val outstanding = if (list.isEmpty()) p else (list.filter { it.status != "PAID" }.sumOf { it.principal })
        return LoanStatus(
            id(loan), loan.s("title"), p, rate, n, round2(e), paidCount, n - paidCount, round2(outstanding),
            round2(list.filter { it.status != "PAID" }.sumOf { it.interest }), round2(list.sumOf { it.interest }),
            list.firstOrNull { it.status != "PAID" }, list.count { it.status == "OVERDUE" }, list
        ).also { if (lastPaid == null && list.isEmpty()) Unit }
    }

    // ---------- dues for the month calendar ----------

    data class Due(
        /** Stable key: the same due keeps its key from month to month view. */
        val key: String,
        /** card, emi, bill, subscription, policy */
        val kind: String,
        val title: String,
        val amount: Double,
        val minAmount: Double?,
        val dueMillis: Long,
        /** PAID, UNPAID, OVERDUE */
        val status: String,
        val snoozedUntil: Long?,
        /** Where it comes from: collection and record id, or the card/loan id for generated dues. */
        val sourceCollection: String,
        val sourceId: String,
        val paidTxnId: String?,
        val category: String
    )

    private fun monthKey(at: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        return "%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    /**
     * [at] moved by [months], on [anchorDay] of that month (or its last day if shorter). Used when a premium is
     * paid and moves to the next period, so a due date on the 31st does not stay on the 28th after February.
     */
    fun addMonthsKeepingDay(at: Long, months: Int, anchorDay: Int): Long {
        val c = Calendar.getInstance().apply { timeInMillis = at; set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, months) }
        c.set(Calendar.DAY_OF_MONTH, anchorDay.coerceIn(1, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
        return c.timeInMillis
    }

    private fun addMonths(at: Long, months: Int): Long =
        Calendar.getInstance().apply { timeInMillis = at; add(Calendar.MONTH, months) }.timeInMillis

    private fun cycleMonths(cycle: String): Int = when (cycle.lowercase()) {
        "yearly", "annual", "annually" -> 12
        "half-yearly", "half yearly" -> 6
        "quarterly" -> 3
        else -> 1
    }

    /**
     * First occurrence of a repeating date on or after [from]. Each repeat is counted from [start] itself, so a
     * date on the 31st falls on the last day of shorter months and returns to the 31st (it used to drift to the
     * 28th for good after February).
     */
    fun nextOccurrence(start: Long, everyMonths: Int, from: Long): Long = occurrenceAtOrAfter(start, everyMonths, from).second

    /** (k, date): the first repeat number k (0 = [start]) whose date is on or after [from]. */
    private fun occurrenceAtOrAfter(start: Long, everyMonths: Int, from: Long): Pair<Int, Long> {
        val step = everyMonths.coerceAtLeast(1)
        var k = 0
        var d = start
        while (d < from && k < 1200) { k++; d = addMonths(start, step * k) }
        return k to d
    }

    /**
     * Everything due between [from] and [to]: unpaid and paid bills and EMIs saved in Finance,
     * card bills, loan instalments, subscription renewals and insurance premiums.
     * A generated due that was already paid, moved or skipped is represented by its saved entry instead.
     */
    fun dues(data: Map<String, List<Map<String, Any?>>>, from: Long, to: Long, now: Long): List<Due> {
        val expenses = data["expenses"].orEmpty()
        val items = data["items"].orEmpty()
        val out = ArrayList<Due>()
        val today = startOfDay(now)
        fun status(paid: Boolean, due: Long) = if (paid) "PAID" else if (due < today) "OVERDUE" else "UNPAID"

        // Saved bills and EMIs (including ones created by marking a generated due paid, moving or skipping it).
        val materialised = HashSet<String>()
        for (t in expenses) {
            val ty = type(t)
            val dueKey = t.s("dueKey")
            if (dueKey.isNotBlank()) materialised.add(dueKey)
            if (!live(t)) continue
            if (ty != "BILL" && ty != "EMI" && dueKey.isBlank()) continue
            val due = t.l("dueDateMillis") ?: t.l("dateMillis") ?: continue
            if (due < from || due > to) continue
            val paid = if (ty == "BILL" || ty == "EMI") isPaidDue(t) else true
            out.add(
                Due(
                    dueKey.ifBlank { "exp:" + id(t) }, t.s("dueKind").ifBlank { if (ty == "EMI") "emi" else "bill" },
                    t.s("title"), amount(t), t.n("minDue"), due, status(paid, due), t.l("snoozedUntilMillis"),
                    "expenses", id(t), if (paid) id(t) else null, t.s("category")
                )
            )
        }
        // Card payments also settle the matching card due.
        val payments = expenses.filter { live(it) && type(it) == "CARD_PAYMENT" }

        for (card in items.filter { it.s("type") == "card" && live(it) }) {
            val st = cardStatus(card, expenses, now)
            // The bill for each statement whose due date falls in the range.
            for (k in -1..2) {
                val stAt = endOfDay(dayOfMonth(st.lastStatementMillis, statementDay(card), k))
                var due = dayOfMonth(stAt, cardDueDay(card))
                if (due <= stAt) due = dayOfMonth(stAt, cardDueDay(card), 1)
                if (due < from || due > to) continue
                val key = "card:${id(card)}:${monthKey(due)}"
                if (key in materialised) continue
                val isCurrent = stAt == st.lastStatementMillis
                val amountDue = if (isCurrent) st.statementAmount else if (stAt > now) st.unbilled else 0.0
                if (amountDue <= 0.0 && stAt <= now && !isCurrent) continue
                val paidAmt = payments.filter { touchesCard(it, card) && (it.l("dateMillis") ?: 0L) > stAt && (it.l("dateMillis") ?: 0L) <= due + 20 * DAY }.sumOf { paiseOf(it) }
                val paid = amountDue > 0 && paidAmt + 50 >= paise(amountDue)
                if (amountDue <= 0.0 && stAt <= now) continue
                out.add(
                    Due(key, "card", st.title + " bill", if (isCurrent) st.statementAmount else amountDue,
                        if (isCurrent) st.minDue else null, due, status(paid, due), card.l("snoozedUntilMillis"),
                        "items", id(card), null, "Credit card")
                )
            }
        }

        for (loan in items.filter { it.s("type") == "loan" && live(it) }) {
            val ls = loanStatus(loan, expenses, now)
            for (inst in ls.schedule) {
                if (inst.dueMillis < from || inst.dueMillis > to) continue
                val key = "loan:${id(loan)}:${inst.no}"
                if (key in materialised) continue
                val st = when (inst.status) { "PAID" -> "PAID"; "OVERDUE" -> "OVERDUE"; else -> "UNPAID" }
                out.add(Due(key, "emi", "${ls.title} EMI ${inst.no}/${ls.tenure}", inst.emi, null, inst.dueMillis, st, null, "items", id(loan), null, "Loan & EMI"))
            }
        }

        for (sub in items.filter { it.s("type") == "subscription" && live(it) }) {
            val start = sub.l("renewalMillis") ?: continue
            val every = cycleMonths(sub.s("cycle"))
            var (k, d) = occurrenceAtOrAfter(start, every, from)
            while (d <= to && k < 1200) {
                val key = "sub:${id(sub)}:${monthKey(d)}"
                if (key !in materialised) {
                    out.add(Due(key, "subscription", sub.s("title"), sub.n("amountInr") ?: 0.0, null, d, status(false, d), sub.l("snoozedUntilMillis"), "items", id(sub), null, "Subscriptions"))
                }
                k++
                d = addMonths(start, every.coerceAtLeast(1) * k)
            }
        }

        for (pol in items.filter { it.s("type") == "policy" && live(it) }) {
            val d = pol.l("premiumDueMillis") ?: continue
            if (d < from || d > to) continue
            val key = "policy:${id(pol)}:${monthKey(d)}"
            if (key in materialised) continue
            out.add(Due(key, "policy", pol.s("title") + " premium", pol.n("amountInr") ?: 0.0, null, d, status(false, d), pol.l("snoozedUntilMillis"), "items", id(pol), null, "Insurance"))
        }
        return out.sortedWith(compareBy<Due> { it.dueMillis }.thenBy { it.title })
    }

    // ---------- the dashboard ----------

    data class Summary(
        val bank: Double, val cash: Double, val income: Double, val spent: Double, val savings: Double, val savingsPct: Int,
        val cardOutstanding: Double, val loanOutstanding: Double, val emisDue: Double, val cardBillsDue: Double,
        val liabilities: Double, val accounts: List<AccountBalance>, val cards: List<CardStatus>, val loans: List<LoanStatus>
    )

    /**
     * The dashboard for the accounts, cards and loans in [data]. Income and spending count the entries in [data];
     * balances, card bills and EMIs count every entry in [ledgerExpenses] (all entries on those accounts and cards,
     * whoever added them), which defaults to the same entries.
     */
    fun summary(
        data: Map<String, List<Map<String, Any?>>>, monthFrom: Long, monthTo: Long, now: Long,
        ledgerExpenses: List<Map<String, Any?>>? = null
    ): Summary {
        val expenses = data["expenses"].orEmpty()
        val all = ledgerExpenses ?: expenses
        val items = data["items"].orEmpty().filter { live(it) }
        val debit = items.filter { it.s("type") == "debit_card" }
        val accounts = items.filter { it.s("type") == "account" }.map { accountBalance(it, all, debit) }
        val cards = items.filter { it.s("type") == "card" }.map { cardStatus(it, all, now) }
        val loans = items.filter { it.s("type") == "loan" }.map { loanStatus(it, all, now) }
        val month = expenses.filter { (it.l("dateMillis") ?: 0L) in monthFrom..monthTo }
        // All totals in paise.
        val income = month.filter { isIncome(it) }.sumOf { paiseOf(it) }
        // Refunds that arrive this month (and were not merged into their purchase) lower spending.
        val refunds = month.filter { isRefund(it) }.sumOf { paiseOf(it) }
        val spent = max(0L, month.filter { isSpend(it) }.sumOf { paise(spendAmount(it)) } - refunds)
        val dues = dues(mapOf("items" to items, "expenses" to all), monthFrom, monthTo, now).filter { it.status != "PAID" }
        val emis = dues.filter { it.kind == "emi" }.sumOf { paise(it.amount) }
        val cardBills = dues.filter { it.kind == "card" }.sumOf { paise(it.amount) }
        val otherBills = dues.filter { it.kind != "emi" && it.kind != "card" }.sumOf { paise(it.amount) }
        val cash = accounts.filter { it.kind.equals("Cash", true) || it.kind.equals("Wallet", true) }.sumOf { paise(it.balance) }
        val bank = accounts.sumOf { paise(it.balance) } - cash
        val savings = income - spent
        return Summary(
            rupees(bank), rupees(cash), rupees(income), rupees(spent), rupees(savings),
            if (income > 0) Math.round(savings * 100.0 / income).toInt() else 0,
            rupees(cards.sumOf { paise(it.outstanding) }), rupees(loans.sumOf { paise(it.outstandingPrincipal) }),
            rupees(emis), rupees(cardBills), rupees(emis + cardBills + otherBills), accounts, cards, loans
        )
    }

    // ---------- duplicates across SMS, email, statements and hand entries ----------

    /**
     * True when two entries are very likely the same payment: same direction and amount, within a day,
     * and either the same reference number, the same account or card ending, or a similar merchant name.
     */
    fun sameTransaction(a: Map<String, Any?>, b: Map<String, Any?>): Boolean {
        if (id(a).isNotBlank() && id(a) == id(b)) return false
        if (type(a) == "INCOME" != (type(b) == "INCOME")) return false
        if (kotlin.math.abs(paiseOf(a) - paiseOf(b)) > 50) return false
        val ta = a.l("dateMillis") ?: return false
        val tb = b.l("dateMillis") ?: return false
        if (kotlin.math.abs(ta - tb) > DAY + DAY / 2) return false
        // A card bill payment shows up twice: debited by the bank and received by the card, with different
        // endings and references. Same amount and day is enough, unless they name different cards.
        if (type(a) == "CARD_PAYMENT" && type(b) == "CARD_PAYMENT") {
            val ca = a.s("cardId").ifBlank { a.s("card") }.takeIf { it != "None" }.orEmpty()
            val cb = b.s("cardId").ifBlank { b.s("card") }.takeIf { it != "None" }.orEmpty()
            return ca.isBlank() || cb.isBlank() || ca == cb
        }
        val ra = a.s("refNo")
        val rb = b.s("refNo")
        if (ra.isNotBlank() && rb.isNotBlank()) return ra == rb
        val la = a.s("last4")
        val lb = b.s("last4")
        if (la.isNotBlank() && lb.isNotBlank() && la != lb) return false
        val ma = merchantKey(a.s("merchant").ifBlank { a.s("title") })
        val mb = merchantKey(b.s("merchant").ifBlank { b.s("title") })
        if (ma.isEmpty() || mb.isEmpty()) return la.isNotBlank() && la == lb
        return ma.startsWith(mb.take(5)) || mb.startsWith(ma.take(5)) || (la.isNotBlank() && la == lb)
    }

    fun merchantKey(s: String): String =
        s.lowercase().replace(Regex("(upi|pos|ecom|neft|imps|vpa|ref|txn|payment|paid|to|at|via)"), " ")
            .filter { it.isLetter() }.take(12)

    /**
     * Groups of saved entries that are very likely one payment saved more than once (for example the SMS
     * and the email for the same purchase). Uses [sameTransaction], so two real payments of the same amount
     * on one day at different shops stay apart. Each group is sorted oldest first; groups are biggest first.
     */
    fun duplicateGroups(records: List<Map<String, Any?>>): List<List<Map<String, Any?>>> {
        val rs = records.filter { live(it) && it.l("dateMillis") != null && paiseOf(it) > 0 }
            .sortedBy { it.l("dateMillis") }
        val parent = IntArray(rs.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (i in rs.indices) {
            val ti = rs[i].l("dateMillis") ?: continue
            var j = i + 1
            // sameTransaction only matches within a day and a half, so the sorted list needs a short look ahead.
            while (j < rs.size && (rs[j].l("dateMillis") ?: Long.MAX_VALUE) - ti <= DAY + DAY / 2) {
                if (sameTransaction(rs[i], rs[j])) parent[find(j)] = find(i)
                j++
            }
        }
        return rs.indices.groupBy { find(it) }.values.filter { it.size > 1 }
            .map { g -> g.map { rs[it] } }
            .sortedWith(compareByDescending<List<Map<String, Any?>>> { it.size }.thenByDescending { it.first().l("dateMillis") })
    }

    // ---------- totals for reports, insights and the assistant ----------

    /**
     * Spending and income for a period, worked out the same way everywhere (Finance, Insights, the summary
     * card, budget alerts and the assistant). Amounts are in paise.
     * - [spent] is spending less refunds that arrived in the period (never below zero).
     * - [income] never includes refunds.
     * - [byCategory] is the spending before refunds, biggest first; [refunds] is shown beside it.
     */
    data class Totals(
        val spent: Long, val gross: Long, val refunds: Long, val income: Long, val saved: Long,
        val byCategory: List<Pair<String, Long>>, val spends: List<Map<String, Any?>>
    ) {
        val spentRupees get() = rupees(spent)
        val incomeRupees get() = rupees(income)
    }

    fun totals(
        records: List<Map<String, Any?>>, from: Long, to: Long,
        include: (Map<String, Any?>) -> Boolean = { true }
    ): Totals {
        val inPeriod = records.filter { r -> (r.l("dateMillis") ?: return@filter false) in from..to && include(r) }
        val spends = inPeriod.filter { isSpend(it) }
        val gross = spends.sumOf { paise(spendAmount(it)) }
        val refunds = inPeriod.filter { isRefund(it) }.sumOf { paiseOf(it) }
        val income = inPeriod.filter { isIncome(it) }.sumOf { paiseOf(it) }
        val saved = inPeriod.filter { live(it) && !it.b("notExpense") && type(it) == "SAVING" }.sumOf { paiseOf(it) }
        val byCategory = spends.groupBy { it.s("category").ifBlank { "Other" } }
            .map { (c, l) -> c to l.sumOf { paise(spendAmount(it)) } }
            .sortedByDescending { it.second }
        return Totals(max(0L, gross - refunds), gross, refunds, income, saved, byCategory, spends)
    }
}
