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
    val isTransfer: Boolean
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
    private val vpaRx = Regex("""(?i)\b(?:vpa|upi id|to)\s*:?\s*([a-z0-9.\-_]{2,}@[a-z]{2,})""")
    private val merchantRx = listOf(
        Regex(
            """(?i)\b(?:at|to|towards|for|in favour of|@)\s+([A-Za-z0-9&.'*\- ]{3,40}?)(?=\s+(?:on|via|ref|upi|using|avl|avail|txn|by|from|at|is|was|for|dated|with)\b|[.,;:(]|$)"""
        ),
        Regex("""(?i)\binfo[:\s]+([A-Za-z0-9&.*\- ]{3,40})""")
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
        val isTransfer = transferRx.containsMatchIn(t)
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
        val merchant = findMerchant(t, amountMatch.range.last)
        val category = when {
            isTransfer -> "Other"
            else -> guessCategory("$merchant $lower") ?: if (isCredit) "Other" else "Other"
        }
        val key = "${source.lowercase(Locale.ROOT)}-${sender.filter { it.isLetterOrDigit() }.takeLast(8)}-" +
            "${dateMillis / 60_000L}-${"%.2f".format(Locale.ROOT, amount)}"
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
            isTransfer = isTransfer
        )
    }

    private fun findMerchant(t: String, afterIndex: Int): String {
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
        val bad = listOf("your", "a/c", "account", "card", "bank", "the ", "rs", "inr", "available", "limit", "balance", "xx")
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
