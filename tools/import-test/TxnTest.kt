import com.familyhub.app.money.TxnParser

var failures = 0
fun check(name: String, cond: Boolean, detail: Any? = null) {
    if (cond) println("PASS $name") else { failures++; println("FAIL $name ${detail ?: ""}") }
}

fun main() {
    val now = 1_764_000_000_000L
    val a = TxnParser.parse("Rs.450.00 debited from A/c XX1234 on 24-11-26 to VPA swiggy@icici (UPI Ref No 432112345678). Not you? Call 18002586161 - HDFC Bank", "VM-HDFCBK", now, "SMS")
    check("upi debit parsed", a != null && !a.isCredit && a.amount == 450.0, a)
    check("upi ref", a?.refNo == "432112345678", a?.refNo)
    check("upi last4", a?.last4 == "1234", a?.last4)
    check("upi high confidence", a?.confidence == "HIGH", a?.confidence)
    val b = TxnParser.parse("INR 2,199.00 spent on ICICI Bank Card XX4321 on 23-Nov-26 at AMAZON PAY IN. Avl Lmt: INR 97,801.00. If not you, call 1800 2662", "AD-ICICIB", now, "SMS")
    check("card spend", b != null && b.method == "Credit card" && b.amount == 2199.0, b)
    check("card available limit", b?.availableLimit == 97801.0, b?.availableLimit)
    val c = TxnParser.parse("Dear Customer, Rs 25,000.00 credited to your A/c XXXXXX5678 on 25-11-2026 by NEFT from ACME PVT LTD. Avl Bal Rs 1,02,345.67 -SBI", "BZ-SBIINB", now, "SMS")
    check("credit parsed", c != null && c.isCredit && c.amount == 25000.0, c)
    check("balance parsed", c?.balance == 102345.67, c?.balance)
    val d = TxnParser.parse("Payment of Rs 3,804.00 received towards your HDFC Bank Credit Card XX4321. Thank you.", "VM-HDFCBK", now, "SMS")
    check("card payment detected", d != null && d.isCardPayment && d.isTransfer, d)
    val e = TxnParser.parse("Your OTP for transaction of Rs 999 is 123456. Do not share it with anyone.", "VM-HDFCBK", now, "SMS")
    check("otp skipped", e == null, e)
    val f = TxnParser.parse("Rs 500 paid", "SOMEONE", now, "SMS")
    check("vague message is low confidence or skipped", f == null || f.confidence == "LOW", f)
    val g = TxnParser.parse("Rs.12,601.00 debited from A/c XX1234 towards your HDFC Credit Card payment on 05-11-26. Avl Bal Rs 45,210.00", "VM-HDFCBK", now, "SMS")
    check("bank-side card payment", g != null && g.isCardPayment && !g.isCredit && g.balance == 45210.0, g)
    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
}
