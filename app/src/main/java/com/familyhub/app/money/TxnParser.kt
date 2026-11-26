package com.familyhub.app.money

import java.util.Locale

/** One bank transaction found in an SMS or email. */
data class ParsedTxn(
    /** Stable id used to avoid importing the same transaction twice. */
    val key: String,
    /** "SMS" or "Email". */
    val source: String,
    val sender: String,
    val dateMillis: Long,
    val amount: Double,
    val isCredit: Boolean,
    val merchant: String,
    val last4: String,
    val method: String,
    val category: String,
    /** Credit-card bill payments and own-account transfers are not real spending. */
    val isTransfer: Boolean,
    /** A payment towards a credit card bill (from the bank side or the card side). */
    val isCardPayment: Boolean = false,
    /** Account balance the alert reports ("Avl Bal"), if any. */
    val balance: Double? = null,
    /** Card limit still available ("Avl Lmt"), if any. */
    val availableLimit: Double? = null,
    /** UPI / NEFT / IMPS reference number, used to spot the same payment from SMS and email. */
    val refNo: String = "",
    val bank: String = "",
    /** HIGH, MEDIUM or LOW: how sure the reading is. Low ones start unticked. */
    val confidence: String = "HIGH",
    /** Money back for a purchase (refund or reversal): it lowers spending and is never income. */
    val isRefund: Boolean = false
)

/**
 * Reads Indian bank and card alerts such as
 * "Rs.450.00 debited from A/c XX1234 on 20-11-26 to VPA swiggy@icici" or
 * "INR 2,199 spent on ICICI Bank Card XX4321 at AMAZON on 2026-11-20".
 * Promotions, OTPs, due-date reminders and statements are skipped.
 */
object TxnParser {
    private val amountRx = Regex("""(?i)(?:rs\.?|inr|₹)\s*[:.]?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val debitRx = Regex(
        """(?i)\b(debited|spent|paid|purchase|withdrawn|withdrawal|sent|debit|charged|deducted|used at|used for|txn of|transaction of)\b"""
    )
    /** Words that mark money coming back for a purchase rather than new income. */
    val refundRx = Regex("""(?i)\b(refund(?:ed)?|revers(?:ed|al)|chargeback)\b""")
    private val creditRx = Regex("""(?i)\b(credited|received|deposited|refund(?:ed)?|reversed|credit of)\b""")
    private val skipRx = Regex(
        """(?i)(\botp\b|one.time.password|verification code|\boffer\b|cashback up ?to|you have won|pre.?approved|apply now|""" +
            """\bis due\b|due (?:on|by|date)|minimum (?:amount )?due|total (?:amount )?due|bill (?:is )?generated|statement (?:is|for|generated)|""" +
            """requested money|collect request|will be debited|is scheduled|reminder|e-?mandate|autopay (?:set|registered)|has requested|""" +
            """failed|declined|unsuccessful|not processed)"""
    )
    private val transferRx = Regex(
        """(?i)(payment (?:of .{0,25})?(?:has been )?received (?:towards|on|for) your .{0,25}card|towards your credit card|""" +
            """credit card (?:bill )?payment|card bill paid|to your own|self transfer|own account)"""
    )
    private val last4Rx = Regex(
        """(?i)(?:a/c|ac|acct|account|card|cc)\s*(?:no\.?|number)?\s*(?:ending\s*(?:with|in)?)?\s*[:\-]?\s*[xX*•.]*\s*([0-9]{4})\b"""
    )
    private val cardPaymentRx = Regex(
        """(?i)(payment (?:of .{0,25})?(?:has been )?received (?:towards|on|for) your .{0,25}card|towards your (?:.{0,20})?credit card|""" +
            """credit card (?:bill )?payment|card bill paid|paid towards (?:your )?.{0,20}card|cc bill|bill ?desk.{0,20}credit card)"""
    )
    private val balanceRx = Regex("""(?i)(?:avl\.?|avail(?:able)?)\s*(?:bal(?:ance)?|bal\.?)\s*(?:is|:)?\s*(?:rs\.?|inr|₹)?\s*[:.]?\s*(-?[0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val limitRx = Regex("""(?i)(?:avl\.?|avail(?:able)?)\s*(?:lmt|limit|credit limit|cr\.? limit)\s*(?:is|:)?\s*(?:rs\.?|inr|₹)?\s*[:.]?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val refRx = Regex("""(?i)\b(?:upi\s*ref(?:erence)?(?:\s*no)?|ref(?:erence)?\s*(?:no|number|id)?|rrn|utr|txn\s*(?:id|no))\s*[:.#-]?\s*([0-9]{6,18}|[A-Z0-9]{10,22})\b""")
    private val vpaRx = Regex("""(?i)\b(?:vpa|upi id|to)\s*:?\s*([a-z0-9.\-_]{2,}@[a-z]{2,})""")
    private val merchantRx = listOf(
        Regex(
            """(?i)\b(?:at|to|towards|for|in favour of|@)\s+([A-Za-z0-9&.'*\- ]{3,40}?)(?=\s+(?:on|via|ref|upi|using|avl|avail|txn|by|from|at|is|was|for|dated|with)\b|[.,;:(]|$)"""
        ),
        Regex("""(?i)\binfo[:\s]+([A-Za-z0-9&.*\- ]{3,40})""")
    )
    /** "...; RELIANCE RETAIL credited" style debits name the payee before "credited". */
    private val payeeCreditedRx = Regex("""(?i)[;,]\s*([A-Za-z0-9&.'\- ]{3,40}?)\s+credited""")
    /** Credits name the payer after "from", e.g. "by NEFT from ACME PVT LTD". */
    private val payerRx = Regex(
        """(?i)\bfrom\s+([A-Za-z0-9&.'*\- ]{3,40}?)(?=\s+(?:on|via|ref|upi|using|avl|avail|txn|to|is|was|dated|with)\b|[.,;:(]|$)"""
    )

    private val categoryWords: List<Pair<String, List<String>>> = listOf(
        "Dining" to listOf("swiggy", "zomato", "restaurant", "cafe", "domino", "pizza", "mcdonald", "kfc", "starbucks", "burger", "eatsure", "food"),
        "Groceries" to listOf("bigbasket", "blinkit", "zepto", "grofers", "dmart", "instamart", "grocery", "jiomart", "supermarket", "mart", "kirana", "milk", "dairy"),
        "Fuel" to listOf("petrol", "fuel", "hpcl", "iocl", "bpcl", "indian oil", "shell", "filling station", "hp pay"),
        "Transport" to listOf("uber", "ola", "rapido", "metro", "irctc", "fastag", "redbus", "parking", "toll"),
        "Shopping" to listOf("amazon", "flipkart", "myntra", "ajio", "nykaa", "meesho", "tatacliq", "croma", "reliance digital", "decathlon", "lifestyle"),
        "Utilities" to listOf("electricity", "bescom", "bses", "tata power", "airtel", "jio", "vodafone", "broadband", "recharge", "dth", "tata play", "gas", "water bill", "postpaid", "bbps"),
        "Health" to listOf("pharmacy", "apollo", "medplus", "1mg", "pharmeasy", "hospital", "clinic", "diagnostic", "lab", "netmeds", "health"),
        "Entertainment" to listOf("netflix", "hotstar", "prime video", "spotify", "bookmyshow", "pvr", "inox", "youtube", "sonyliv", "zee5"),
        "Education" to listOf("school", "college", "university", "fees", "udemy", "byju", "coursera", "tuition"),
        "Rent" to listOf("rent", "nobroker", "nestaway"),
        "Insurance" to listOf("insurance", "lic ", "policy", "premium", "policybazaar"),
        "EMI" to listOf("emi", "loan", "bajaj finserv", "home credit"),
        "Salary" to listOf("salary", "sal cr", "payroll")
    )

    fun parse(text: String, sender: String, dateMillis: Long, source: String): ParsedTxn? {
        val t = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (t.length < 15 || skipRx.containsMatchIn(t)) return null
        val amountMatch = amountRx.find(t) ?: return null
        val amount = amountMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        if (amount <= 0.0 || amount > 50_000_000.0) return null

        val d = debitRx.find(t)
        val c = creditRx.find(t)
        val isCredit = when {
            d != null && c != null -> c.range.first < d.range.first
            c != null -> true
            d != null -> false
            else -> return null
        }
        val lower = t.lowercase(Locale.ROOT)
        val isCardPayment = cardPaymentRx.containsMatchIn(t)
        val isTransfer = isCardPayment || transferRx.containsMatchIn(t)
        val last4 = last4Rx.find(t)?.groupValues?.get(1).orEmpty()
        val method = when {
            "upi" in lower || "vpa" in lower || vpaRx.containsMatchIn(t) -> "UPI"
            "credit card" in lower || Regex("""(?i)\bcc\b""").containsMatchIn(t) -> "Credit card"
            "debit card" in lower || "atm" in lower -> if ("withdraw" in lower) "Cash" else "Debit card"
            "card" in lower && !isCredit -> "Credit card"
            "neft" in lower || "imps" in lower || "rtgs" in lower || "net banking" in lower || "netbanking" in lower -> "Net banking"
            "wallet" in lower -> "Wallet"
            else -> "Other"
        }
        val merchant = findMerchant(t, amountMatch.range.last, isCredit)
        val category = when {
            isTransfer -> "Other"
            else -> guessCategory("$merchant $lower") ?: if (isCredit) "Other" else "Other"
        }
        val key = "${source.lowercase(Locale.ROOT)}-${sender.filter { it.isLetterOrDigit() }.takeLast(8)}-" +
            "${dateMillis / 60_000L}-${"%.2f".format(Locale.ROOT, amount)}"
        val balance = balanceRx.find(t)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        val limit = limitRx.find(t)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        val refNo = refRx.find(t)?.groupValues?.get(1).orEmpty()
        val bank = AlertParser.bankName(sender, t)
        // How sure the reading is: a clear debit/credit word, an account or card ending, a reference, a known bank.
        val strongWord = Regex("""(?i)\b(debited|credited|spent|withdrawn|deposited|received)\b""").containsMatchIn(t)
        var score = 0
        if (strongWord) score += 2 else score += 1
        if (last4.isNotBlank()) score += 2
        if (refNo.isNotBlank()) score += 1
        if (bank.isNotBlank()) score += 1
        if (merchant.isNotBlank()) score += 1
        val confidence = when {
            score >= 5 -> "HIGH"
            score >= 3 -> "MEDIUM"
            else -> "LOW"
        }
        return ParsedTxn(
            key = key,
            source = source,
            sender = sender,
            dateMillis = dateMillis,
            amount = amount,
            isCredit = isCredit,
            merchant = merchant,
            last4 = last4,
            method = method,
            category = category,
            isTransfer = isTransfer,
            isCardPayment = isCardPayment,
            balance = balance,
            availableLimit = limit,
            refNo = refNo,
            bank = bank,
            confidence = confidence,
            isRefund = isCredit && !isTransfer && refundRx.containsMatchIn(t)
        )
    }

    private fun findMerchant(t: String, afterIndex: Int, isCredit: Boolean): String {
        if (isCredit) {
            payerRx.findAll(t).firstOrNull { looksLikeName(it.groupValues[1]) }?.let { return tidy(it.groupValues[1]) }
        } else {
            payeeCreditedRx.find(t)?.let { m -> if (looksLikeName(m.groupValues[1])) return tidy(m.groupValues[1]) }
        }
        vpaRx.find(t)?.let { m ->
            val handle = m.groupValues[1].substringBefore('@')
            if (handle.any { it.isLetter() }) return tidy(handle.replace('.', ' ').replace('-', ' ').replace('_', ' '))
        }
        for (rx in merchantRx) {
            val m = rx.findAll(t).firstOrNull { it.range.first >= afterIndex - 40 && looksLikeName(it.groupValues[1]) }
                ?: rx.findAll(t).firstOrNull { looksLikeName(it.groupValues[1]) }
            if (m != null) return tidy(m.groupValues[1])
        }
        return ""
    }

    private fun looksLikeName(s: String): Boolean {
        val x = s.trim().lowercase(Locale.ROOT)
        if (x.length < 3 || x.count { it.isLetter() } < 3) return false
        val bad = listOf(
            "your", "a/c", "account", "card", "bank", "the ", "rs", "inr", "available", "limit", "balance", "xx",
            "dispute", "call", "customer", "not you", "help", "ref", "neft", "imps", "rtgs", "upi", "transaction"
        )
        return bad.none { x.startsWith(it) }
    }

    private fun tidy(s: String): String =
        s.trim().trim('.', '-', '*', ' ').split(' ').filter { it.isNotBlank() }.take(5).joinToString(" ") { w ->
            if (w.length <= 3 && w.all { it.isUpperCase() }) w else w.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }.take(40)

    fun guessCategory(text: String): String? {
        val x = text.lowercase(Locale.ROOT)
        return categoryWords.firstOrNull { (_, words) -> words.any { it in x } }?.first
    }
}
