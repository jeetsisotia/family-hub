import com.familyhub.app.money.*
import java.io.File
import java.util.Calendar

var failures = 0
fun check(name: String, cond: Boolean, detail: Any? = null) {
    if (cond) println("PASS $name") else { failures++; println("FAIL $name ${detail ?: ""}") }
}
fun ymd(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.let { "${it.get(Calendar.YEAR)}-${it.get(Calendar.MONTH) + 1}-${it.get(Calendar.DAY_OF_MONTH)}" }

fun main() {
    // A bank CSV with a preamble, quotes, separate debit and credit columns.
    val csv = """
HDFC BANK LTD
Account No : XXXXXXXX1234
Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
01/11/26,"UPI-SWIGGY-swiggy@icici-ICIC0001-432112345678-Food, order",0000432112345678,01/11/26,450.00,,"1,01,550.00"
02/11/26,NEFT CR-ACME PVT LTD-SALARY,N306261234,02/11/26,,"85,000.00","1,86,550.00"
15/11/26,CREDIT CARD PAYMENT HDFC XX4321,,15/11/26,"12,601.00",,"1,73,949.00"
""".trimIndent()
    val t = StatementParser.readCsv(csv)
    val h = StatementParser.headerRow(t)
    check("header found after preamble", h == 2, h)
    val m = StatementParser.guess(t[h])
    check("columns guessed", m.date == 0 && m.description == 1 && m.debit == 4 && m.credit == 5 && m.balance == 6 && m.ref == 2, m)
    val rows = StatementParser.rows(t, h, m, StatementParser.looksDayFirst(t.drop(h + 1).map { it[0] }))
    check("three rows", rows.size == 3, rows)
    check("debit row", rows[0].amount == 450.0 && !rows[0].isCredit && ymd(rows[0].dateMillis) == "2026-11-1", rows[0])
    check("credit row", rows[1].amount == 85000.0 && rows[1].isCredit, rows[1])
    check("balance read", rows[2].balance == 173949.0, rows[2].balance)
    check("quoted comma kept in narration", rows[0].description.contains("Food, order"), rows[0].description)
    check("merchant from narration", StatementParser.merchantOf(rows[0].description).startsWith("Swiggy"), StatementParser.merchantOf(rows[0].description))

    val acct = mapOf("id" to "a1", "type" to "account", "title" to "HDFC Savings", "last4" to "1234")
    val card = mapOf("id" to "c1", "type" to "card", "title" to "HDFC Millennia", "last4" to "4321", "bank" to "HDFC")
    val cash = mapOf("id" to "a2", "type" to "account", "title" to "Cash", "category" to "Cash")
    val items = listOf(acct, card, cash)
    val e0 = StatementImport.entryForRow(rows[0], acct, items, "Dining")
    check("bank debit is an expense from the account", e0["type"] == "EXPENSE" && e0["accountId"] == "a1" && e0["paymentMethod"] == "UPI", e0)
    val e2 = StatementImport.entryForRow(rows[2], acct, items, "Other")
    check("card bill from bank is a card payment to the right card", e2["type"] == "CARD_PAYMENT" && e2["cardId"] == "c1" && e2["accountId"] == "a1", e2)

    // Axio / Walnut style: one amount column with a DR/CR column.
    val axio = "DATE,TIME,PLACE,AMOUNT,DR/CR,ACCOUNT,EXPENSE,INCOME,CATEGORY,TAGS,NOTE\n05-11-2026,10:30,Zomato,349.00,DR,HDFC 1234,Yes,No,Food,,\n06-11-2026,09:00,Salary,85000,CR,HDFC 1234,No,Yes,Income,,"
    val at = StatementParser.readCsv(axio)
    val am = StatementParser.guess(at[0])
    check("axio columns", am.date == 0 && am.amount == 3 && am.drCr == 4 && am.description == 2 && am.category == 8, am)
    val ar = StatementParser.rows(at, 0, am, true)
    check("axio rows", ar.size == 2 && !ar[0].isCredit && ar[1].isCredit && ar[0].category == "Food", ar)

    // A card statement with signed amounts and "Cr" credits.
    val cc = "Transaction Date;Details;Amount (INR)\n03 Nov 2026;AMAZON PAY;2,199.00\n04-Nov-26;REFUND AMAZON;500.00 Cr\n05 Nov 2026;PAYMENT RECEIVED - THANK YOU;12,601.00 Cr"
    val ct = StatementParser.readCsv(cc)
    val cm = StatementParser.guess(ct[0])
    val cr = StatementParser.rows(ct, 0, cm, true)
    check("card statement rows", cr.size == 3 && !cr[0].isCredit && cr[1].isCredit && cr[2].isCredit, cr)
    check("named month dates", ymd(cr[0].dateMillis) == "2026-11-3" && ymd(cr[1].dateMillis) == "2026-11-4", cr.map { ymd(it.dateMillis) })
    val ce = StatementImport.entryForRow(cr[2], card, items, "Other")
    check("payment on card statement is a card payment", ce["type"] == "CARD_PAYMENT" && ce["cardId"] == "c1", ce)
    val rf = StatementImport.entryForRow(cr[1], card, items, "Shopping")
    check("refund on card is not income", rf["type"] == "INCOME" && rf["notExpense"] == true && !Ledger.isIncome(rf), rf)

    // Excel file with date numbers.
    val x = StatementParser.readTable(File("s.xlsx").readBytes(), "s.xlsx")
    val xh = StatementParser.headerRow(x)
    val xm = StatementParser.guess(x[xh])
    val xr = StatementParser.rows(x, xh, xm, true)
    check("xlsx rows", xr.size == 4 && xr[0].amount == 450.0 && xr[1].isCredit, xr)
    check("excel date number", ymd(xr[0].dateMillis) == "2026-11-14", ymd(xr[0].dateMillis))
    val atm = StatementImport.entryForRow(xr[3], acct, items, "Other")
    check("ATM cash moves to the Cash account", atm["type"] == "TRANSFER" && atm["toAccountId"] == "a2", atm)

    check("month-first dates detected", !StatementParser.looksDayFirst(listOf("11/23/2026", "11/05/2026")))
    check("amount forms", StatementParser.parseAmount("(1,234.50)") == -1234.5 && StatementParser.parseAmount("₹ 2,000") == 2000.0 && StatementParser.parseAmount("") == null)
    try { StatementParser.readTable(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte()), "a.xls"); check("old xls refused", false) } catch (e: StatementParser.UnsupportedFile) { check("old xls refused", true) }
    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
}
