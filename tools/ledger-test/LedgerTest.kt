import com.familyhub.app.money.Ledger
import com.familyhub.app.money.MerchantRules
import java.util.Calendar

var failures = 0
fun check(name: String, cond: Boolean, detail: Any? = null) {
    if (cond) println("PASS $name") else { failures++; println("FAIL $name ${detail ?: ""}") }
}
fun near(a: Double, b: Double, tol: Double = 0.6) = kotlin.math.abs(a - b) <= tol
fun at(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, 12, 0, 0) }.timeInMillis

fun main() {
    val now = at(2026, 11, 26)
    val acct = mapOf("id" to "a1", "type" to "account", "title" to "HDFC Savings", "openingBalance" to 50000.0, "openingDateMillis" to at(2026, 11, 1))
    val cash = mapOf("id" to "a2", "type" to "account", "title" to "Cash", "category" to "Cash", "openingBalance" to 2000.0)
    val card = mapOf("id" to "c1", "type" to "card", "title" to "HDFC Millennia", "statementDay" to 5.0, "dueDay" to 23.0, "limitInr" to 100000.0)
    val debit = mapOf("id" to "d1", "type" to "debit_card", "title" to "HDFC Debit", "accountId" to "a1")
    val loan = mapOf("id" to "l1", "type" to "loan", "title" to "Car loan", "principal" to 500000.0, "interestRate" to 9.0, "tenureMonths" to 60.0, "startMillis" to at(2026, 6, 10))
    val tx = listOf(
        mapOf("id" to "t1", "type" to "INCOME", "title" to "Salary", "amountInr" to 100000.0, "dateMillis" to at(2026, 11, 1), "accountId" to "a1"),
        mapOf("id" to "t2", "type" to "EXPENSE", "title" to "Groceries", "amountInr" to 3000.0, "dateMillis" to at(2026, 11, 3), "paymentMethod" to "UPI", "accountId" to "a1"),
        // Card purchases: one in the closed cycle (before 5 Nov), one unbilled.
        mapOf("id" to "t3", "type" to "EXPENSE", "title" to "Amazon", "amountInr" to 8000.0, "dateMillis" to at(2026, 11, 2), "paymentMethod" to "Credit card", "card" to "HDFC Millennia"),
        mapOf("id" to "t4", "type" to "EXPENSE", "title" to "Swiggy", "amountInr" to 1500.0, "dateMillis" to at(2026, 11, 20), "paymentMethod" to "Credit card", "cardId" to "c1"),
        // Partial card payment from the bank.
        mapOf("id" to "t5", "type" to "CARD_PAYMENT", "title" to "Card bill", "amountInr" to 5000.0, "dateMillis" to at(2026, 11, 15), "accountId" to "a1", "cardId" to "c1"),
        // Transfer to cash wallet.
        mapOf("id" to "t6", "type" to "TRANSFER", "title" to "ATM", "amountInr" to 4000.0, "dateMillis" to at(2026, 11, 10), "accountId" to "a1", "toAccountId" to "a2"),
        // Debit card spend maps to the linked account.
        mapOf("id" to "t7", "type" to "EXPENSE", "title" to "Fuel", "amountInr" to 2500.0, "dateMillis" to at(2026, 11, 12), "paymentMethod" to "Debit card", "card" to "HDFC Debit"),
        // A reminder (unpaid bill) is not spending and not money out.
        mapOf("id" to "t8", "type" to "BILL", "title" to "Electricity", "amountInr" to 1840.0, "dateMillis" to at(2026, 11, 30), "dueDateMillis" to at(2026, 11, 30), "isPaid" to false, "accountId" to "a1"),
        // Paid EMI for instalments 1..5 of the car loan.
        *(1..5).map { i -> mapOf("id" to "e$i", "type" to "EMI", "title" to "Car EMI", "amountInr" to 10379.0, "dateMillis" to at(2026, 5 + i, 10), "isPaid" to true, "loanId" to "l1", "installmentNo" to i.toDouble(), "accountId" to "a9") }.toTypedArray(),
        // Before the opening date: ignored for the balance.
        mapOf("id" to "t9", "type" to "EXPENSE", "title" to "Old", "amountInr" to 999.0, "dateMillis" to at(2026, 10, 20), "accountId" to "a1"),
        // Refund to the card.
        mapOf("id" to "t10", "type" to "INCOME", "title" to "Amazon refund", "amountInr" to 1000.0, "dateMillis" to at(2026, 11, 21), "cardId" to "c1", "notExpense" to true),
        // Deleted entries never count.
        mapOf("id" to "t11", "type" to "EXPENSE", "title" to "Deleted", "amountInr" to 7777.0, "dateMillis" to at(2026, 11, 4), "accountId" to "a1", "isDeleted" to true)
    )

    val b = Ledger.accountBalance(acct, tx, listOf(debit))
    // 50000 + 100000 - 3000 - 5000 (card payment) - 4000 (transfer) - 2500 (debit card) = 135500
    check("bank balance", near(b.balance, 135500.0), b)
    val c = Ledger.accountBalance(cash, tx, listOf(debit))
    check("cash gets the transfer", near(c.balance, 6000.0), c)

    val cs = Ledger.cardStatus(card, tx, now)
    // Statement on 5 Nov: 8000. Since then: +1500 -1000 refund, paid 5000. Outstanding 3500, due 3000.
    check("card statement amount", near(cs.statementAmount, 8000.0), cs)
    check("card outstanding", near(cs.outstanding, 3500.0), cs.outstanding)
    check("card amount due after partial payment", near(cs.amountDue, 3000.0), cs.amountDue)
    check("card min due", near(cs.minDue, 200.0), cs.minDue)
    check("card available", near(cs.available ?: -1.0, 96500.0), cs.available)
    check("card due date is 23 Nov", Ledger.startOfDay(cs.dueMillis) == Ledger.startOfDay(at(2026, 11, 23)), cs.dueMillis)

    val emi = Ledger.emi(500000.0, 9.0, 60)
    check("EMI formula", near(emi, 10379.18, 0.05), emi)
    val ls = Ledger.loanStatus(loan, tx, now)
    check("loan paid count", ls.paidCount == 5, ls.paidCount)
    check("loan remaining", ls.remaining == 55, ls.remaining)
    check("loan next due is instalment 6 on 10 Nov (overdue)", ls.nextDue?.no == 6 && ls.nextDue?.status == "OVERDUE", ls.nextDue)
    check("loan outstanding principal sensible", ls.outstandingPrincipal in 460000.0..470000.0, ls.outstandingPrincipal)
    check("schedule ends at zero", near(ls.schedule.last().balanceAfter, 0.0, 1.0), ls.schedule.last())
    check("total interest", near(ls.totalInterest, 122750.0, 60.0), ls.totalInterest)

    // Old-style loan with only outstanding + EMI.
    val old = mapOf("id" to "l2", "type" to "loan", "title" to "Phone EMI", "amountInr" to 12000.0, "emiAmount" to 2000.0, "nextDueMillis" to at(2026, 12, 5))
    val os = Ledger.loanStatus(old, emptyList(), now)
    check("old loan months", os.tenure == 6, os.tenure)

    val data = mapOf("expenses" to tx, "items" to listOf(acct, cash, card, debit, loan))
    val from = at(2026, 11, 1) - 12 * 3600_000L
    val to = Ledger.endOfDay(at(2026, 11, 30))
    val s = Ledger.summary(data, from, to, now)
    // Income 100000 (refund to card is notExpense). Spend: 3000 + 8000 + 1500 + 2500 + EMI(10 Nov? none paid in Nov) = 15000.
    check("month income", near(s.income, 100000.0), s.income)
    check("month spend excludes transfers, card payments, unpaid bills", near(s.spent, 15000.0), s.spent)
    check("cash total", near(s.cash, 6000.0), s.cash)
    check("bank total", near(s.bank, 135500.0), s.bank)
    check("card outstanding total", near(s.cardOutstanding, 3500.0), s.cardOutstanding)

    val dues = Ledger.dues(data, from, to, now)
    val names = dues.map { it.kind + ":" + it.title + ":" + it.status }
    check("dues include electricity unpaid", names.any { it.startsWith("bill:Electricity:UNPAID") }, names)
    check("dues include card bill", dues.any { it.kind == "card" }, names)
    check("dues include overdue EMI 6", dues.any { it.kind == "emi" && it.title.contains("6/60") && it.status == "OVERDUE" }, names)

    // Duplicates across sources.
    val sms = mapOf("id" to "x1", "type" to "EXPENSE", "title" to "SWIGGY", "amountInr" to 645.0, "dateMillis" to at(2026, 11, 24), "last4" to "4321")
    val mail = mapOf("id" to "x2", "type" to "EXPENSE", "title" to "Swiggy order", "amountInr" to 645.0, "dateMillis" to at(2026, 11, 24) + 3_600_000L, "last4" to "4321")
    val other = mapOf("id" to "x3", "type" to "EXPENSE", "title" to "Zomato", "amountInr" to 645.0, "dateMillis" to at(2026, 11, 24), "last4" to "9999")
    check("same payment from SMS and email is a duplicate", Ledger.sameTransaction(sms, mail))
    check("different card is not a duplicate", !Ledger.sameTransaction(sms, other))

    // A card bill saved and marked paid must not be charged to the card again or taken from the bank.
    val savedBill = mapOf("id" to "sb", "type" to "BILL", "title" to "HDFC Millennia bill", "amountInr" to 8000.0, "dateMillis" to at(2026, 11, 23),
        "dueDateMillis" to at(2026, 11, 23), "isPaid" to true, "dueKey" to "card:c1:2026-11", "dueKind" to "card", "cardId" to "c1", "notExpense" to true, "accountId" to "a1")
    val tx2 = tx + savedBill
    check("saved card bill not charged again", near(Ledger.cardStatus(card, tx2, now).outstanding, 3500.0), Ledger.cardStatus(card, tx2, now).outstanding)
    check("saved card bill not taken from bank", near(Ledger.accountBalance(acct, tx2, listOf(debit)).balance, 135500.0))
    check("saved card bill is not spending", !Ledger.isSpend(savedBill))
    val d2 = Ledger.dues(mapOf("expenses" to tx2, "items" to listOf(acct, cash, card, debit, loan)), at(2026, 11, 1), at(2026, 11, 30), now)
    check("saved card bill replaces generated one", d2.count { it.kind == "card" && it.dueMillis in at(2026, 11, 1)..at(2026, 11, 30) } == 1 && d2.first { it.key == "card:c1:2026-11" }.status == "PAID", d2.filter { it.kind == "card" })
    // No due day saved: 18 days after the statement.
    val noDue = mapOf("id" to "c2", "type" to "card", "title" to "SBI", "statementDay" to 5.0)
    check("default due day", Ledger.cardDueDay(noDue) == 23, Ledger.cardDueDay(noDue))
    check("default due day wraps", Ledger.cardDueDay(mapOf("statementDay" to 20.0)) == 10)

    check("saving is not spending", !Ledger.isSpend(mapOf("type" to "SAVING", "amountInr" to 5000.0)))
    check("transfer is not spending or income", !Ledger.isSpend(tx[5]) && !Ledger.isIncome(tx[5]))
    check("card payment is not spending", !Ledger.isSpend(tx[4]))

    val bankSide = mapOf("type" to "CARD_PAYMENT", "amountInr" to 12601.0, "dateMillis" to at(2026, 11, 5), "last4" to "1234", "refNo" to "111222333")
    val cardSide = mapOf("type" to "CARD_PAYMENT", "amountInr" to 12601.0, "dateMillis" to at(2026, 11, 6), "last4" to "4321", "cardId" to "c1")
    check("card payment seen from bank and card is one payment", Ledger.sameTransaction(bankSide, cardSide))
    check("payments to two different cards are not duplicates", !Ledger.sameTransaction(cardSide + ("id" to "p1"), cardSide + mapOf("id" to "p2", "cardId" to "c2")))

    // ---------- money is added up in paise: no floating-point drift ----------
    val zeroAcct = mapOf("id" to "z1", "type" to "account", "title" to "Wallet", "openingBalance" to 0.0)
    val tiny = listOf(
        mapOf("id" to "p1", "type" to "INCOME", "amountInr" to 0.1, "dateMillis" to at(2026, 11, 2), "accountId" to "z1"),
        mapOf("id" to "p2", "type" to "INCOME", "amountInr" to 0.2, "dateMillis" to at(2026, 11, 2), "accountId" to "z1"),
        mapOf("id" to "p3", "type" to "EXPENSE", "amountInr" to 0.3, "dateMillis" to at(2026, 11, 3), "paymentMethod" to "UPI", "accountId" to "z1")
    )
    val zb = Ledger.accountBalance(zeroAcct, tiny, emptyList())
    check("0.10 + 0.20 - 0.30 leaves exactly zero", zb.balance == 0.0, zb.balance)
    val card3 = mapOf("id" to "c3", "type" to "card", "title" to "ICICI Amazon", "statementDay" to 5.0, "dueDay" to 23.0)
    val split = listOf(
        mapOf("id" to "q1", "type" to "EXPENSE", "amountInr" to 300.3, "dateMillis" to at(2026, 11, 2), "paymentMethod" to "Credit card", "cardId" to "c3"),
        mapOf("id" to "q2", "type" to "CARD_PAYMENT", "amountInr" to 100.1, "dateMillis" to at(2026, 11, 10), "cardId" to "c3"),
        mapOf("id" to "q3", "type" to "CARD_PAYMENT", "amountInr" to 200.2, "dateMillis" to at(2026, 11, 12), "cardId" to "c3")
    )
    val cs3 = Ledger.cardStatus(card3, split, now)
    check("card bill paid in two parts leaves nothing due", cs3.amountDue == 0.0 && cs3.minDue == 0.0 && cs3.outstanding == 0.0, cs3)
    val month3 = Ledger.summary(mapOf("expenses" to List(3) { i -> mapOf("id" to "m$i", "type" to "EXPENSE", "amountInr" to 0.1, "dateMillis" to at(2026, 11, 4)) }, "items" to emptyList()),
        at(2026, 11, 1), at(2026, 11, 30), now)
    check("three spends of 0.10 add up to exactly 0.30", month3.spent == 0.3, month3.spent)

    // ---------- refunds are not income ----------
    val refundTx = listOf(
        mapOf("id" to "r1", "type" to "INCOME", "title" to "Salary", "amountInr" to 50000.0, "dateMillis" to at(2026, 11, 1)),
        mapOf("id" to "r2", "type" to "EXPENSE", "title" to "Myntra", "amountInr" to 2000.0, "dateMillis" to at(2026, 11, 5), "paymentMethod" to "UPI"),
        mapOf("id" to "r3", "type" to "INCOME", "title" to "Refund", "amountInr" to 2000.0, "dateMillis" to at(2026, 11, 9), "isRefund" to true, "notExpense" to true)
    )
    val rs = Ledger.summary(mapOf("expenses" to refundTx, "items" to emptyList()), at(2026, 11, 1), at(2026, 11, 30), now)
    check("a refund is not income", rs.income == 50000.0, rs.income)
    check("a refund lowers spending", rs.spent == 0.0, rs.spent)
    val merged = refundTx.toMutableList().also { it[1] = it[1] + ("refundAmount" to 2000.0); it[2] = it[2] + ("refundOf" to "r2") }
    val ms = Ledger.summary(mapOf("expenses" to merged, "items" to emptyList()), at(2026, 11, 1), at(2026, 11, 30), now)
    check("a merged refund is not counted twice", ms.spent == 0.0 && ms.income == 50000.0, ms.spent)

    // ---------- one category for the same merchant (like Axio) ----------
    check("SWIGGY and 'Swiggy order from Meghana Foods' are the same merchant", MerchantRules.same("SWIGGY", "Swiggy order from Meghana Foods"))
    check("a UPI handle names its merchant", MerchantRules.same("swiggy@icici", "Swiggy"))
    check("'Payment to Zepto' and 'ZEPTO MARKETPLACE' are the same merchant", MerchantRules.same("Payment to Zepto", "ZEPTO MARKETPLACE PVT LTD"))
    check("Indian Oil and Indian Bank are different", !MerchantRules.same("Indian Oil", "Indian Bank"))
    check("Amazon Pay and Amazon Prime are different", !MerchantRules.same("Amazon Prime Video", "Amazon Fresh"))
    check("a two-letter name never matches", !MerchantRules.same("AB", "AB Stores"))
    check("a name that is only payment words has no merchant", MerchantRules.key("UPI payment to") == "")
    val shop = listOf(
        mapOf("id" to "s1", "type" to "EXPENSE", "title" to "Swiggy order from Meghana Foods", "category" to "Other", "amountInr" to 645.0),
        mapOf("id" to "s2", "type" to "EXPENSE", "title" to "SWIGGY", "merchant" to "swiggy@icici", "category" to "Food"),
        mapOf("id" to "s3", "type" to "EXPENSE", "title" to "Swiggy Instamart", "category" to "Dining"),
        mapOf("id" to "s4", "type" to "INCOME", "title" to "Swiggy refund", "category" to "Other"),
        mapOf("id" to "s5", "type" to "TRANSFER", "title" to "Swiggy wallet top-up", "category" to "Transfer"),
        mapOf("id" to "s6", "type" to "EXPENSE", "title" to "Swiggy", "category" to "Other", "isDeleted" to true),
        mapOf("id" to "s7", "type" to "EXPENSE", "title" to "Zomato", "category" to "Other")
    )
    val same = MerchantRules.matches(shop[0], shop, "Dining").map { it["id"] }
    check("same-merchant spends with another category are offered", same == listOf("s2"), same)
    check("refunds, transfers, deleted entries and other shops are left alone", "s4" !in same && "s5" !in same && "s6" !in same && "s7" !in same)
    val rules = listOf(
        MerchantRules.ruleData("Swiggy", "Dining", 1L),
        MerchantRules.ruleData("Swiggy Instamart", "Groceries", 2L),
        MerchantRules.ruleData("Old shop", "Other", 3L) + ("isDeleted" to true)
    )
    check("a remembered merchant gets its category", MerchantRules.categoryFor("SWIGGY*ORDER", rules) == "Dining", MerchantRules.categoryFor("SWIGGY*ORDER", rules))
    check("the more exact rule wins", MerchantRules.categoryFor("Swiggy Instamart Bangalore", rules) == "Groceries", MerchantRules.categoryFor("Swiggy Instamart Bangalore", rules))
    check("an unknown merchant keeps its own category", MerchantRules.categoryFor("Zomato", rules) == null)
    check("a deleted rule is ignored", MerchantRules.categoryFor("Old shop", rules) == null)
    val wide = listOf(MerchantRules.ruleData("Swiggy order from Meghana Foods", "Dining", 1L))
    check("a plain SMS name uses the only matching longer rule", MerchantRules.categoryFor("SWIGGY", wide) == "Dining")
    val clash = wide + MerchantRules.ruleData("Swiggy Instamart", "Groceries", 2L)
    check("longer rules that disagree are not guessed", MerchantRules.categoryFor("SWIGGY", clash) == null, MerchantRules.categoryFor("SWIGGY", clash))
    check("the rule id is stable for the same merchant", MerchantRules.ruleId("SWIGGY") == MerchantRules.ruleId("swiggy@ybl"), MerchantRules.ruleId("SWIGGY"))
    check("the label is tidy", MerchantRules.label("SWIGGY*ORDER") == "Swiggy", MerchantRules.label("SWIGGY*ORDER"))

    // ---------- shared totals (Finance, Insights, assistant and budget alerts use these) ----------
    val nov = listOf(
        mapOf("id" to "m1", "type" to "EXPENSE", "title" to "Swiggy", "category" to "Dining", "amountInr" to 100.10, "dateMillis" to at(2026, 11, 3)),
        mapOf("id" to "m2", "type" to "EXPENSE", "title" to "Zomato", "category" to "Dining", "amountInr" to 200.20, "dateMillis" to at(2026, 11, 4)),
        mapOf("id" to "m3", "type" to "EXPENSE", "title" to "Big Bazaar", "category" to "Groceries", "amountInr" to 600.0, "dateMillis" to at(2026, 11, 5), "refundAmount" to 100.0),
        mapOf("id" to "m4", "type" to "INCOME", "title" to "Salary", "amountInr" to 50000.0, "dateMillis" to at(2026, 11, 1)),
        mapOf("id" to "m5", "type" to "INCOME", "title" to "Refund Amazon", "amountInr" to 250.0, "dateMillis" to at(2026, 11, 6), "isRefund" to true),
        mapOf("id" to "m6", "type" to "TRANSFER", "title" to "To cash", "amountInr" to 4000.0, "dateMillis" to at(2026, 11, 7)),
        mapOf("id" to "m7", "type" to "CARD_PAYMENT", "title" to "Card bill", "amountInr" to 5000.0, "dateMillis" to at(2026, 11, 8)),
        mapOf("id" to "m8", "type" to "EXPENSE", "title" to "Gone", "category" to "Dining", "amountInr" to 999.0, "dateMillis" to at(2026, 11, 9), "isDeleted" to true),
        mapOf("id" to "m9", "type" to "BILL", "title" to "Electricity", "category" to "Bills", "amountInr" to 1840.0, "dateMillis" to at(2026, 11, 10), "isPaid" to false),
        mapOf("id" to "m10", "type" to "EXPENSE", "title" to "Old month", "category" to "Dining", "amountInr" to 77.0, "dateMillis" to at(2026, 10, 31)),
        mapOf("id" to "m11", "type" to "SAVING", "title" to "FD", "amountInr" to 3000.0, "dateMillis" to at(2026, 11, 12))
    )
    val tt = Ledger.totals(nov, at(2026, 11, 1), at(2026, 11, 30))
    check("totals: spending is added in paise, merged refunds and deletions handled", tt.gross == 100_10L + 200_20L + 500_00L, tt.gross)
    check("totals: a refund that arrives lowers spending, and is not income", tt.refunds == 250_00L && tt.spent == tt.gross - 250_00L && tt.income == 50_000_00L, "${tt.spent} ${tt.refunds} ${tt.income}")
    check("totals: transfers, card payments, unpaid bills and other months are left out", tt.spends.map { it["id"] }.toSet() == setOf("m1", "m2", "m3"), tt.spends.map { it["id"] })
    check("totals: categories are biggest first and add up to the gross spend", tt.byCategory == listOf("Groceries" to 500_00L, "Dining" to 300_30L) && tt.byCategory.sumOf { it.second } == tt.gross, tt.byCategory)
    check("totals: money put into savings is counted separately", tt.saved == 3000_00L, tt.saved)
    check("totals: 0.10 + 0.20 - 0.30 is exactly zero", Ledger.totals(listOf(
        mapOf("id" to "p1", "type" to "EXPENSE", "amountInr" to 0.10, "dateMillis" to at(2026, 11, 3)),
        mapOf("id" to "p2", "type" to "EXPENSE", "amountInr" to 0.20, "dateMillis" to at(2026, 11, 3)),
        mapOf("id" to "p3", "type" to "INCOME", "amountInr" to 0.30, "dateMillis" to at(2026, 11, 3), "isRefund" to true)
    ), at(2026, 11, 1), at(2026, 11, 30)).spent == 0L)
    check("totals: a big refund never makes spending negative", Ledger.totals(listOf(
        mapOf("id" to "q1", "type" to "EXPENSE", "amountInr" to 100.0, "dateMillis" to at(2026, 11, 3)),
        mapOf("id" to "q2", "type" to "INCOME", "amountInr" to 500.0, "dateMillis" to at(2026, 11, 4), "isRefund" to true)
    ), at(2026, 11, 1), at(2026, 11, 30)).spent == 0L)
    val me = Ledger.totals(nov, at(2026, 11, 1), at(2026, 11, 30)) { it["id"] == "m1" || it["id"] == "m5" }
    check("totals: a filter (for example one person) applies to spending and refunds", me.gross == 100_10L && me.refunds == 250_00L)
    check("totals: an entry saved without a type counts as an expense", Ledger.totals(listOf(mapOf("id" to "z", "amountInr" to 50.0, "dateMillis" to at(2026, 11, 3))), at(2026, 11, 1), at(2026, 11, 30)).spent == 50_00L)

    // ---------- finding entries saved twice ----------
    val dupe = listOf(
        mapOf("id" to "d1", "type" to "EXPENSE", "title" to "Swiggy", "amountInr" to 450.0, "dateMillis" to at(2026, 11, 10), "refNo" to "R123"),
        mapOf("id" to "d2", "type" to "EXPENSE", "title" to "SWIGGY*ORDER", "amountInr" to 450.0, "dateMillis" to at(2026, 11, 10) + 3_600_000L, "refNo" to "R123"),
        mapOf("id" to "d3", "type" to "EXPENSE", "title" to "Swiggy", "amountInr" to 450.0, "dateMillis" to at(2026, 11, 10) + 7_200_000L, "refNo" to "R123"),
        // Same amount and day at another shop is a different payment.
        mapOf("id" to "d4", "type" to "EXPENSE", "title" to "Cinema tickets", "amountInr" to 450.0, "dateMillis" to at(2026, 11, 10)),
        // Same shop and amount a week later is a different payment.
        mapOf("id" to "d5", "type" to "EXPENSE", "title" to "Swiggy", "amountInr" to 450.0, "dateMillis" to at(2026, 11, 17)),
        // Same shop, same day, different amount.
        mapOf("id" to "d6", "type" to "EXPENSE", "title" to "Swiggy", "amountInr" to 120.0, "dateMillis" to at(2026, 11, 10)),
        // A deleted copy is not a duplicate.
        mapOf("id" to "d7", "type" to "EXPENSE", "title" to "Swiggy", "amountInr" to 450.0, "dateMillis" to at(2026, 11, 10), "isDeleted" to true),
        // The salary and an expense of the same amount are different directions.
        mapOf("id" to "d8", "type" to "INCOME", "title" to "Swiggy refund", "amountInr" to 450.0, "dateMillis" to at(2026, 11, 10)),
        mapOf("id" to "d9", "type" to "EXPENSE", "title" to "Uber", "amountInr" to 210.0, "dateMillis" to at(2026, 11, 12)),
        mapOf("id" to "d10", "type" to "EXPENSE", "title" to "Uber trip", "amountInr" to 210.0, "dateMillis" to at(2026, 11, 12) + 1_800_000L)
    )
    val groups = Ledger.duplicateGroups(dupe)
    check("duplicates: the same payment saved three times is one group of three", groups.firstOrNull()?.map { it["id"] } == listOf("d1", "d2", "d3"), groups.map { g -> g.map { it["id"] } })
    check("duplicates: a similar name on the same day also groups", groups.any { g -> g.map { it["id"] } == listOf("d9", "d10") }, groups.map { g -> g.map { it["id"] } })
    check("duplicates: other shops, other days, other amounts, deleted copies and refunds stay apart", groups.size == 2 && groups.flatten().none { it["id"] in setOf("d4", "d5", "d6", "d7", "d8") }, groups.map { g -> g.map { it["id"] } })
    check("duplicates: nothing found when nothing repeats", Ledger.duplicateGroups(dupe.filter { it["id"] in setOf("d1", "d4", "d5", "d6") }).isEmpty())
    check("duplicates: a long list is handled quickly", run {
        val big = (0 until 5000).map { i -> mapOf("id" to "b$i", "type" to "EXPENSE", "title" to "Shop ${i % 40}", "amountInr" to (10.0 + i), "dateMillis" to at(2026, 1, 1) + i * 3_600_000L) }
        val t0 = System.currentTimeMillis()
        Ledger.duplicateGroups(big)
        System.currentTimeMillis() - t0 < 2000
    })

    // Repeating renewals on the 31st must not drift to the 28th after February (Phase 7 fix).
    run {
        val sub = mapOf("id" to "s31", "type" to "subscription", "title" to "Cloud plan", "amountInr" to 99.0, "cycle" to "Monthly", "renewalMillis" to at(2026, 1, 31))
        val dues = Ledger.dues(mapOf("items" to listOf(sub), "expenses" to emptyList()), at(2026, 3, 1), at(2026, 5, 31), at(2026, 3, 1))
        val days = dues.filter { it.key.startsWith("sub:") }.map { Calendar.getInstance().apply { timeInMillis = it.dueMillis }.get(Calendar.DAY_OF_MONTH) }
        check("renewal on the 31st: 31 Mar, 30 Apr, 31 May (no drift to the 28th)", days == listOf(31, 30, 31), days)
        val day = { t: Long -> Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.DAY_OF_MONTH) }
        var p = at(2026, 1, 31)
        val seen = ArrayList<Int>()
        repeat(4) { p = Ledger.addMonthsKeepingDay(p, 1, 31); seen.add(day(p)) }
        check("monthly premium on the 31st paid 4 times: 28 Feb, 31 Mar, 30 Apr, 31 May", seen == listOf(28, 31, 30, 31), seen)
        check("yearly premium on 29 Feb 2028 moves to 28 Feb 2029", day(Ledger.addMonthsKeepingDay(at(2028, 2, 29), 12, 29)) == 28)
        check("renewal on the 31st: next one after February is 31 March", Calendar.getInstance().apply {
            timeInMillis = Ledger.nextOccurrence(at(2026, 1, 31), 1, at(2026, 3, 2))
        }.get(Calendar.DAY_OF_MONTH) == 31)
    }

    // ---------- Finance dashboard and Transactions (FinanceMonth) ----------
    run {
        val F = com.familyhub.app.money.FinanceMonth

        val rows = listOf(
            mapOf("id" to "m1", "type" to "INCOME", "title" to "Salary", "amountInr" to 80000.0, "dateMillis" to at(2026, 11, 1), "category" to "Salary"),
            mapOf("id" to "m2", "type" to "EXPENSE", "title" to "Big Basket", "amountInr" to 2500.25, "dateMillis" to at(2026, 11, 3), "category" to "Groceries"),
            mapOf("id" to "m3", "type" to "EXPENSE", "title" to "Swiggy", "amountInr" to 450.0, "dateMillis" to at(2026, 11, 3), "category" to "Dining"),
            mapOf("id" to "m4", "type" to "EXPENSE", "title" to "Petrol", "amountInr" to 3000.0, "dateMillis" to at(2026, 11, 10), "category" to "Fuel"),
            mapOf("id" to "m5", "type" to "EXPENSE", "title" to "Rent", "amountInr" to 20000.0, "dateMillis" to at(2026, 11, 5), "category" to "Rent"),
            mapOf("id" to "m6", "type" to "EXPENSE", "title" to "Movie", "amountInr" to 600.0, "dateMillis" to at(2026, 11, 12), "category" to "Entertainment"),
            mapOf("id" to "m7", "type" to "EXPENSE", "title" to "Medicines", "amountInr" to 900.0, "dateMillis" to at(2026, 11, 14), "category" to "Health"),
            mapOf("id" to "m8", "type" to "TRANSFER", "title" to "ATM", "amountInr" to 5000.0, "dateMillis" to at(2026, 11, 15)),
            mapOf("id" to "m9", "type" to "EXPENSE", "title" to "Deleted", "amountInr" to 999.0, "dateMillis" to at(2026, 11, 16), "isDeleted" to true),
            mapOf("id" to "m10", "type" to "INCOME", "title" to "Refund", "amountInr" to 100.0, "dateMillis" to at(2026, 11, 17), "isRefund" to true, "category" to "Dining"),
            mapOf("id" to "m11", "type" to "BILL", "title" to "Electricity", "amountInr" to 1840.0, "dateMillis" to at(2026, 11, 30), "isPaid" to false),
            // Last month
            mapOf("id" to "o1", "type" to "INCOME", "title" to "Salary", "amountInr" to 80000.0, "dateMillis" to at(2026, 10, 1), "category" to "Salary"),
            mapOf("id" to "o2", "type" to "EXPENSE", "title" to "Rent", "amountInr" to 20000.0, "dateMillis" to at(2026, 10, 5), "category" to "Rent"),
            mapOf("id" to "o3", "type" to "EXPENSE", "title" to "Groceries", "amountInr" to 5000.0, "dateMillis" to at(2026, 10, 8), "category" to "Groceries")
        )
        val v = F.view(rows, at(2026, 11, 20))
        // Spent: 2500.25 + 450 + 3000 + 20000 + 600 + 900 = 27450.25, less the 100 refund = 27350.25
        check("month: income", v.income == 8_000_000L, v.income)
        check("month: spent after refund (paise exact)", v.spent == 2_735_025L, v.spent)
        check("month: balance = income - spent", v.balance == 8_000_000L - 2_735_025L)
        check("month: transfers, deleted and unpaid bills are not spending", v.top.none { it.first == "Other" } && v.top.size == 5, v.top)
        check("month: top 5 in order", v.top.map { it.first } == listOf("Rent", "Fuel", "Groceries", "Health", "Entertainment"), v.top.map { it.first })
        check("month: vs last month (25000 -> 27350.25 = +9%)", v.spentChangePct == 9 && v.incomeChangePct == 0, v.spentChangePct)
        check("month: latest 5 newest first, no deleted entries or unpaid bills", v.latest.map { it["id"] } == listOf("m10", "m8", "m7", "m6", "m4"), v.latest.map { it["id"] })
        val empty = F.view(rows, at(2026, 9, 20))
        check("month: an empty month has no comparison against an empty one", empty.spent == 0L && empty.spentChangePct == null && empty.latest.isEmpty())
        val nov = F.month(at(2026, 11, 20))
        val exp = F.list(rows, com.familyhub.app.money.FinanceMonth.Kind.EXPENSE, nov.from, nov.to)
        check("transactions: expenses only", exp.map { it["id"] } == listOf("m7", "m6", "m4", "m5", "m2", "m3"), exp.map { it["id"] })
        check("transactions: expense total", F.total(exp, com.familyhub.app.money.FinanceMonth.Kind.EXPENSE) == 2_745_025L)
        check("transactions: income excludes refunds", F.list(rows, com.familyhub.app.money.FinanceMonth.Kind.INCOME, nov.from, nov.to).map { it["id"] } == listOf("m1"))
        check("transactions: search by name", F.list(rows, com.familyhub.app.money.FinanceMonth.Kind.EXPENSE, nov.from, nov.to, query = "swig").map { it["id"] } == listOf("m3"))
        check("transactions: search by amount", F.list(rows, com.familyhub.app.money.FinanceMonth.Kind.EXPENSE, nov.from, nov.to, query = "2500").map { it["id"] } == listOf("m2"))
        check("transactions: category filter", F.list(rows, com.familyhub.app.money.FinanceMonth.Kind.EXPENSE, nov.from, nov.to, category = "rent").map { it["id"] } == listOf("m5"))
        val b = F.breakdown(exp, com.familyhub.app.money.FinanceMonth.Kind.EXPENSE)
        check("breakdown: rent first, shares add to about 100", b.first().first == "Rent" && b.sumOf { it.third } in 99..101, b)
        val days = F.byDay(exp)
        check("by day: 3 Nov groups two entries", days.first { it.second.size == 2 }.second.map { it["id"] }.toSet() == setOf("m2", "m3"))
        check("month range: November", F.month(at(2026, 11, 20)).from == Ledger.startOfDay(at(2026, 11, 1)) && F.month(at(2026, 11, 20)).to == Ledger.startOfDay(at(2026, 12, 1)) - 1)
        check("month range: back from January", F.month(at(2026, 1, 15), -1).from == Ledger.startOfDay(at(2025, 12, 1)))
        // Categories
        val existing = listOf("Pet care" to "Spending", "Side work" to "Income")
        val sp = listOf("Groceries", "Dining"); val inc = listOf("Salary")
        check("category: built-in duplicate refused", F.isDuplicateCategory(" groceries ", "Spending", existing, sp, inc))
        check("category: custom duplicate refused (case, spaces)", F.isDuplicateCategory("PET   care", "Spending", existing, sp, inc))
        check("category: same name for the other type is allowed", !F.isDuplicateCategory("Pet care", "Income", existing, sp, inc))
        check("category: Both clashes with either", F.isDuplicateCategory("Side work", "Both", existing, sp, inc))
        check("category: renaming to its own name is fine", !F.isDuplicateCategory("Pet Care", "Spending", existing, sp, inc, ignoreName = "Pet care"))
        check("category: new name allowed", !F.isDuplicateCategory("Gym", "Spending", existing, sp, inc))
    }

    // ---------- Moving existing transactions to another category (Recategorize) ----------
    run {
        val R = com.familyhub.app.money.Recategorize
        val KE = com.familyhub.app.money.FinanceMonth.Kind.EXPENSE
        val d = at(2026, 11, 10)
        val rows = listOf(
            mapOf("id" to "r1", "type" to "EXPENSE", "title" to "Swiggy", "merchant" to "SWIGGY", "category" to "Other", "amountInr" to 450.0, "dateMillis" to d, "isPaid" to true, "isDeleted" to false, "account" to "HDFC", "notes" to "keep me", "proofPhoto" to "r1.jpg"),
            mapOf("id" to "r2", "type" to "EXPENSE", "title" to "Zomato", "category" to "", "amountInr" to 300.0, "dateMillis" to d, "isPaid" to true, "isDeleted" to false, "card" to "Amex 1005"),
            mapOf("id" to "r3", "type" to "EXPENSE", "title" to "BigBasket", "category" to "Groceries", "subcategory" to "Veg", "amountInr" to 1200.0, "dateMillis" to d, "isPaid" to true, "isDeleted" to false, "account" to "HDFC"),
            mapOf("id" to "r4", "type" to "TRANSFER", "title" to "To savings", "category" to "", "amountInr" to 5000.0, "dateMillis" to d, "isPaid" to true, "isDeleted" to false),
            mapOf("id" to "r5", "type" to "EXPENSE", "title" to "Old", "category" to "Other", "amountInr" to 10.0, "dateMillis" to d, "isPaid" to true, "isDeleted" to true),
            mapOf("id" to "r6", "type" to "INCOME", "title" to "Salary", "category" to "Salary", "amountInr" to 90000.0, "dateMillis" to d, "isPaid" to true, "isDeleted" to false),
            mapOf("id" to "r7", "type" to "EXPENSE", "title" to "Pet shop", "category" to "pet  care", "amountInr" to 800.0, "dateMillis" to d, "isPaid" to true, "isDeleted" to false)
        )
        val p = R.forIds(rows, setOf("r1", "r2", "r3", "r4", "r5", "gone"), "Dining", now = 1L)
        check("recat: changes only live, categorisable entries", p.changes.map { it.rec["id"] } == listOf("r1", "r2", "r3"), p.changes.map { it.rec["id"] })
        check("recat: transfer, deleted and missing ids are skipped and counted", p.skipped == 3, p.skipped)
        check("recat: writes only category, subcategory and updatedAt", p.changes.all { it.fields.keys == setOf("category", "subcategory", "updatedAt") })
        check("recat: subcategory of the old category is cleared", p.changes.first { it.rec["id"] == "r3" }.fields["subcategory"] == "None")
        check("recat: undo holds the old category and subcategory", p.changes.first { it.rec["id"] == "r3" }.undo.let { it["category"] == "Groceries" && it["subcategory"] == "Veg" })
        // Apply the plan the way the app does (update fields on the same record) and check nothing else changed.
        val after = rows.map { r -> p.changes.firstOrNull { it.rec["id"] == r["id"] }?.let { r + it.fields } ?: r }
        check("recat: same number of records afterwards (no copies, nothing lost)", after.size == rows.size && after.map { it["id"] }.toSet() == rows.map { it["id"] }.toSet())
        val r1 = after.first { it["id"] == "r1" }
        check("recat: amount, date, account, notes and receipt kept", r1["amountInr"] == 450.0 && r1["dateMillis"] == d && r1["account"] == "HDFC" && r1["notes"] == "keep me" && r1["proofPhoto"] == "r1.jpg")
        val nov = com.familyhub.app.money.FinanceMonth.month(d)
        val before = com.familyhub.app.money.FinanceMonth.breakdown(com.familyhub.app.money.FinanceMonth.list(rows, KE, nov.from, nov.to), KE)
        val after2 = com.familyhub.app.money.FinanceMonth.breakdown(com.familyhub.app.money.FinanceMonth.list(after, KE, nov.from, nov.to), KE)
        check("recat: category totals follow the change", after2.first { it.first == "Dining" }.second == 195_000L, after2)
        check("recat: total spending unchanged by a category change", before.sumOf { it.second } == after2.sumOf { it.second })
        check("recat: already in that category is skipped", R.forIds(rows, setOf("r3"), "groceries").let { it.count == 0 && it.skipped == 1 })
        check("recat: blank target changes nothing", R.forIds(rows, setOf("r1"), "  ").count == 0)
        val ren = R.rename(rows, "Pet care", "Pets")
        check("rename: finds the old name ignoring case and spaces", ren.changes.map { it.rec["id"] } == listOf("r7"))
        check("rename: deleted entries are not touched", R.rename(rows, "Other", "Misc").changes.map { it.rec["id"] } == listOf("r1"))
        check("rename: to the same name changes nothing", R.rename(rows, "Groceries", "Groceries").count == 0)
        check("delete: entries move to the chosen category", R.moveAll(rows, "Groceries", "Other").changes.single().fields["category"] == "Other")
        check("usage: counts live entries only", R.usage(rows, "other") == 1 && R.usage(rows, "Salary") == 1)
        val el = R.eligible(rows, KE, nov.from, nov.to, onlyUncategorised = true)
        check("eligible: only uncategorised spending (no transfers or deleted)", el.map { it["id"] }.toSet() == setOf("r1", "r2"), el.map { it["id"] })
        check("eligible: search narrows it", R.eligible(rows, KE, nov.from, nov.to, query = "swig").map { it["id"] } == listOf("r1"))
        check("eligible: leaves out the target category", R.eligible(rows, KE, nov.from, nov.to, exclude = "Groceries").none { it["id"] == "r3" })
        check("source: card wins over account, None ignored", R.sourceOf(rows[1]) == "Amex 1005" && R.sourceOf(rows[0]) == "HDFC" && R.sourceOf(rows[3]) == "")
        check("sources: most used first", R.sources(rows).first() == "HDFC", R.sources(rows))
    }

    // ---------- Suspected duplicates with reasons (Duplicates) ----------
    run {
        val D = com.familyhub.app.money.Duplicates
        val t0 = at(2026, 11, 12) + 10 * 3_600_000L
        fun e(id: String, extra: Map<String, Any?>) = mapOf<String, Any?>("id" to id, "type" to "EXPENSE", "amountInr" to 645.0, "dateMillis" to t0, "isPaid" to true, "isDeleted" to false) + extra
        val sms = e("s1", mapOf("title" to "SWIGGY", "merchant" to "SWIGGY", "sourceKey" to "sms-abc", "last4" to "1005", "category" to "Other"))
        val mail = e("g1", mapOf("title" to "Swiggy order", "merchant" to "Swiggy", "sourceKey" to "email-xyz", "last4" to "1005", "dateMillis" to t0 + 20 * 60_000L))
        val typed = e("t1", mapOf("title" to "Swiggy dinner", "merchant" to "Swiggy", "category" to "Dining", "notes" to "Friday", "createdByUid" to "u1"))
        val otherShop = e("o1", mapOf("title" to "Zomato", "merchant" to "Zomato"))
        val refA = e("r1", mapOf("title" to "UPI", "refNo" to "412345678901"))
        val refB = e("r2", mapOf("title" to "UPI payment", "refNo" to "412345678901", "dateMillis" to t0 + 5 * 3_600_000L))
        val g = D.find(listOf(sms, mail, typed, otherShop))
        check("dups: SMS, email and typed entry of one payment form one group", g.size == 1 && g[0].records.map { it["id"] }.toSet() == setOf("s1", "g1", "t1"), g.map { it.records.map { r -> r["id"] } })
        check("dups: a different shop with the same amount and day is not included", g.none { grp -> grp.records.any { it["id"] == "o1" } })
        check("dups: keeps the entry with the most detail (category and notes)", g[0].keep["id"] == "t1", g[0].keep["id"])
        check("dups: SMS plus email with the same card ending is high confidence", g[0].confidence == com.familyhub.app.money.Duplicates.Confidence.HIGH)
        check("dups: reasons name the amount, the card ending, the shop and the sources",
            g[0].reasons.any { it.startsWith("Same amount") } && g[0].reasons.any { it.contains("1005") } &&
                g[0].reasons.any { it.startsWith("Same shop") } && g[0].reasons.any { it.startsWith("Saved from") }, g[0].reasons)
        val r = D.find(listOf(refA, refB))
        check("dups: the same reference number is high confidence", r.size == 1 && r[0].confidence == com.familyhub.app.money.Duplicates.Confidence.HIGH && r[0].reasons.any { it.contains("reference") })
        val two = D.find(listOf(
            e("a", mapOf("title" to "Coffee Day", "merchant" to "Coffee Day", "createdByUid" to "u1")),
            e("b", mapOf("title" to "Coffee Day", "merchant" to "Coffee Day", "createdByUid" to "u2", "dateMillis" to t0 + 3 * 3_600_000L))
        ))
        check("dups: two typed entries by different people are only medium, with a caution",
            two.size == 1 && two[0].confidence == com.familyhub.app.money.Duplicates.Confidence.MEDIUM && two[0].cautions.any { it.contains("different people") }, two.map { it.cautions })
        val monthly = listOf(
            e("m1", mapOf("title" to "Netflix", "merchant" to "Netflix", "dateMillis" to at(2026, 9, 12))),
            e("m2", mapOf("title" to "Netflix", "merchant" to "Netflix", "dateMillis" to at(2026, 10, 12))),
            e("m3", mapOf("title" to "Netflix", "merchant" to "Netflix", "dateMillis" to t0, "sourceKey" to "sms-1")),
            e("m4", mapOf("title" to "Netflix", "merchant" to "Netflix", "dateMillis" to t0 + 60_000L, "sourceKey" to "email-1"))
        )
        val mg = D.find(monthly)
        check("dups: monthly payments in other months are not grouped", mg.size == 1 && mg[0].records.map { it["id"] }.toSet() == setOf("m3", "m4"))
        check("dups: a regular payment gets a caution and is not high confidence", mg[0].cautions.any { it.contains("regular") } && mg[0].confidence == com.familyhub.app.money.Duplicates.Confidence.MEDIUM, mg[0].cautions)
        val transfer = listOf(
            mapOf("id" to "x1", "type" to "TRANSFER", "amountInr" to 5000.0, "dateMillis" to t0, "title" to "To savings", "isDeleted" to false),
            mapOf("id" to "x2", "type" to "EXPENSE", "amountInr" to 5000.0, "dateMillis" to t0, "title" to "Rent", "isDeleted" to false)
        )
        check("dups: a transfer and a rent payment of the same amount are not grouped", D.find(transfer).isEmpty())
        check("dups: 'Not duplicates' hides the group", D.find(listOf(sms, mail, typed), excluded = setOf("g1|s1|t1")).isEmpty())
        check("dups: deleted entries are never offered", D.find(listOf(sms, mail + mapOf("isDeleted" to true))).isEmpty())
        val rf = D.removeFields(typed, 5L)
        check("dups: removing goes to Recently deleted and remembers the kept entry", rf["isDeleted"] == true && rf["duplicateOf"] == "t1")
        check("dups: restore clears it", D.restoreFields(6L).let { it["isDeleted"] == false && it["duplicateOf"] == null })
        check("dups: origin from the source key", D.origin(sms) == "SMS" && D.origin(mail) == "Email" && D.origin(typed) == "Typed in")
    }

    // ---------- assistant: finding the transaction for "change category" ----------
    run {
        val F = com.familyhub.app.money.TxnFinder
        val cats = listOf("Groceries", "Dining", "Shopping", "Transport", "Other", "Pet care")
        val recs = listOf(
            mapOf("id" to "s1", "type" to "EXPENSE", "title" to "Swiggy order from Meghana Foods", "amountInr" to 645.0, "category" to "Dining", "dateMillis" to at(2026, 11, 25)),
            mapOf("id" to "s2", "type" to "EXPENSE", "title" to "SWIGGY", "amountInr" to 645.0, "category" to "Other", "dateMillis" to at(2026, 11, 25) + 3600_000L),
            mapOf("id" to "s3", "type" to "EXPENSE", "title" to "Swiggy Instamart", "amountInr" to 1210.0, "category" to "Groceries", "dateMillis" to at(2026, 11, 20)),
            mapOf("id" to "s4", "type" to "EXPENSE", "title" to "Swiggy old", "amountInr" to 300.0, "category" to "Dining", "dateMillis" to at(2026, 11, 1), "isDeleted" to true),
            mapOf("id" to "s5", "type" to "TRANSFER", "title" to "Swiggy wallet top-up", "amountInr" to 500.0, "dateMillis" to at(2026, 11, 2)),
            mapOf("id" to "t9", "type" to "EXPENSE", "title" to "Test transaction", "amountInr" to 99.0, "category" to "Other", "dateMillis" to at(2026, 11, 26))
        )
        val r1 = F.parse("Change the category of Swiggy order from Meghana Foods to Shopping", cats)
        check("recat: reads 'change the category of X to Y'", r1?.category == "shopping" && r1.query == "swiggy meghana foods", r1)
        check("recat: one exact match", F.match(recs, r1!!).map { it["id"] } == listOf("s1"))
        check("recat: category resolved to the person's name", F.resolveCategory(r1.category, cats) == "Shopping")
        val r2 = F.parse("please change category of swiggy to shopping", cats)!!
        check("recat: several matches, newest first; deleted and transfers left out", F.match(recs, r2).map { it["id"] } == listOf("s2", "s1", "s3"))
        val r3 = F.parse("move swiggy ₹645 to Shopping", cats)!!
        check("recat: amount narrows it", r3.amount == 645.0 && F.match(recs, r3).map { it["id"] } == listOf("s2", "s1"), r3)
        val r4 = F.parse("change my last swiggy transaction's category to shopping", cats)!!
        check("recat: 'last' picks only the newest", r4.latest && F.match(recs, r4).map { it["id"] } == listOf("s2"), r4)
        check("recat: Hinglish 'X ki category Y kar do'", F.parse("test transaction ki category shopping kar do", cats)?.let { F.match(recs, it).map { r -> r["id"] } } == listOf("t9"))
        check("recat: Hinglish 'X ko Y mein daal do'", F.parse("swiggy instamart ko groceries mein daal do", cats)?.let { F.match(recs, it).map { r -> r["id"] } } == listOf("s3"))
        check("recat: 'move the meeting to Friday' is not a category change", F.parse("move the meeting to Friday", cats) == null)
        check("recat: 'mark the task as done' is not one", F.parse("mark the car service task as done", cats) == null)
        check("recat: 'put milk in shopping' (shopping list) is not one", F.parse("put milk in shopping", cats) == null)
        check("recat: 'put X under shopping category' is one", F.parse("put swiggy instamart under shopping category", cats)?.category == "shopping")
        check("recat: unknown category is kept as typed (the app says it does not exist)", F.parse("change category of swiggy to Gadgets", cats)?.let { F.resolveCategory(it.category, cats) } == null)
        check("recat: nothing found for an unknown shop", F.match(recs, F.parse("change category of Zomato to Shopping", cats)!!).isEmpty())
        check("recat: intent seen even when unreadable", F.isIntent("can you fix the category of my expense"))
        check("recat: a question is not a change", !F.isIntent("how much did I spend on shopping"))
        check("recat: plural forms resolve", F.resolveCategory("grocery", cats) == "Groceries")
        val plan = com.familyhub.app.money.Recategorize.forIds(recs, setOf("s1"), "Shopping", 7L)
        check("recat: the shared plan changes only category and subcategory, Undo keeps the old one",
            plan.count == 1 && plan.changes[0].fields.keys == setOf("category", "subcategory", "updatedAt") && plan.changes[0].undo["category"] == "Dining")
    }

    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
}
