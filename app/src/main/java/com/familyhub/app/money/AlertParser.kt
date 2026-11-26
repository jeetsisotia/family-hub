package com.familyhub.app.money

import java.util.Calendar
import java.util.Locale

/**
 * Something with a date to act on, found in an SMS or email: a card bill, a phone or utility bill,
 * a subscription renewal, an insurance premium or expiry, a bank account or card, or a pending action.
 * Read on the phone; nothing is saved until the person adds it.
 */
data class ParsedAlert(
    /** Stable id so the same alert is not offered twice. */
    val key: String,
    /** card_due, bill_due, subscription, policy, action, card, account */
    val kind: String,
    val source: String,
    val sender: String,
    val title: String,
    val amount: Double? = null,
    val minDue: Double? = null,
    val dueMillis: Long? = null,
    val expiryMillis: Long? = null,
    val last4: String = "",
    val bank: String = "",
    val category: String = "",
    val receivedMillis: Long = 0L
)

object AlertParser {

    private val amountRx = Regex("""(?i)(?:rs\.?|inr|₹)\s*[:.]?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val last4Rx = Regex(
        """(?i)(?:a/c|ac|acct|account|card|cc)\s*(?:no\.?|number)?\s*(?:ending\s*(?:with|in)?)?\s*[:\-]?\s*[xX*•.]*\s*([0-9]{4})\b"""
    )
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** Bank names from SMS sender ids (VM-HDFCBK) or the message itself. */
    private val banks = listOf(
        "HDFC" to "HDFC Bank", "ICICI" to "ICICI Bank", "SBI" to "SBI", "AXIS" to "Axis Bank", "KOTAK" to "Kotak Bank",
        "PNB" to "PNB", "BOB" to "Bank of Baroda", "BARODA" to "Bank of Baroda", "YES" to "Yes Bank", "IDFC" to "IDFC First Bank",
        "INDUS" to "IndusInd Bank", "CANARA" to "Canara Bank", "CANBNK" to "Canara Bank", "UNION" to "Union Bank",
        "AMEX" to "American Express", "SCB" to "Standard Chartered", "STANCHART" to "Standard Chartered", "RBL" to "RBL Bank",
        "AUBANK" to "AU Bank", "FEDERAL" to "Federal Bank", "FEDBNK" to "Federal Bank", "ONECARD" to "OneCard", "HSBC" to "HSBC"
    )

    private val subscriptionNames = listOf(
        "netflix", "prime video", "amazon prime", "hotstar", "disney", "spotify", "youtube premium", "youtube", "apple",
        "icloud", "google one", "google play", "zee5", "sonyliv", "jiocinema", "jiohotstar", "swiggy one", "zomato gold",
        "linkedin premium", "microsoft 365", "office 365", "adobe", "canva", "chatgpt", "openai", "claude", "gaana", "wynk",
        "audible", "kindle unlimited", "cult.fit", "cultfit", "times prime", "tata play", "airtel xstream"
    )

    private val insurers = listOf(
        "lic", "hdfc life", "icici prudential", "icici lombard", "sbi life", "sbi general", "max life", "tata aia", "bajaj allianz",
        "star health", "care health", "niva bupa", "hdfc ergo", "new india", "united india", "oriental insurance", "national insurance",
        "digit", "acko", "policybazaar", "aditya birla", "kotak life", "reliance general", "manipal cigna", "future generali"
    )

    fun bankName(sender: String, text: String = ""): String {
        val s = sender.uppercase(Locale.ROOT).substringAfter('-')
        banks.firstOrNull { (code, _) -> s.contains(code) }?.let { return it.second }
        val t = text.uppercase(Locale.ROOT)
        return banks.firstOrNull { (code, name) -> t.contains(name.uppercase(Locale.ROOT)) || Regex("\\b$code\\b").containsMatchIn(t) }?.second.orEmpty()
    }

    private fun amountAfter(t: String, label: Regex): Double? {
        val m = label.find(t) ?: return null
        val tail = t.substring(m.range.last + 1).take(40)
        return amountRx.find(tail)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
    }

    private fun firstAmount(t: String): Double? = amountRx.find(t)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

    /** A date written the Indian way (08-12-2026, 08 Dec 26, Dec 8, 2026, 2026-12-08, 8th December). */
    fun parseDate(text: String, received: Long): Long? {
        val t = text.lowercase(Locale.ROOT)
        fun at(y: Int, m: Int, d: Int): Long? {
            if (m !in 1..12 || d !in 1..31) return null
            val yy = if (y < 100) 2000 + y else y
            if (yy !in 2000..2100) return null
            return Calendar.getInstance().apply {
                clear()
                set(yy, m - 1, d, 12, 0, 0)
            }.timeInMillis
        }
        fun nextYearly(m: Int, d: Int): Long? {
            val c = Calendar.getInstance().apply { timeInMillis = received }
            var v = at(c.get(Calendar.YEAR), m, d) ?: return null
            if (v < received - 20L * 86_400_000L) v = at(c.get(Calendar.YEAR) + 1, m, d) ?: return null
            return v
        }
        Regex("""\b(20\d{2})-(\d{1,2})-(\d{1,2})\b""").find(t)?.let { m ->
            return at(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        Regex("""\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})\b""").find(t)?.let { m ->
            return at(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())
        }
        val mon = months.joinToString("|")
        Regex("""\b(\d{1,2})(?:st|nd|rd|th)?[\s\-/,]*($mon)[a-z]*[\s\-/,']*(\d{2,4})?\b""").find(t)?.let { m ->
            val mo = months.indexOf(m.groupValues[2]) + 1
            val d = m.groupValues[1].toInt()
            val y = m.groupValues[3]
            return if (y.isNotBlank()) at(y.toInt(), mo, d) else nextYearly(mo, d)
        }
        Regex("""\b($mon)[a-z]*\s+(\d{1,2})(?:st|nd|rd|th)?,?\s*(\d{4})?\b""").find(t)?.let { m ->
            val mo = months.indexOf(m.groupValues[1]) + 1
            val d = m.groupValues[2].toInt()
            val y = m.groupValues[3]
            return if (y.isNotBlank()) at(y.toInt(), mo, d) else nextYearly(mo, d)
        }
        return null
    }

    /** The date that follows a phrase such as "due on" or "expires on", else the first date in the text. */
    private fun dateAfter(t: String, label: Regex, received: Long): Long? {
        label.findAll(t).forEach { m ->
            parseDate(t.substring(m.range.first).take(60), received)?.let { return it }
        }
        return null
    }

    private val dueLabel = Regex("""(?i)(due (?:date|on|by)|payment due|pay by|last date|before|on or before|due\b)""")
    private val renewLabel = Regex("""(?i)(renew(?:al|s|ed)?(?: date)?(?: on)?|auto.?debit(?:ed)?(?: on)?|will be (?:debited|charged)(?: on)?|next (?:billing|payment)(?: date)?|valid (?:till|until|upto))""")
    private val expiryLabel = Regex("""(?i)(expir(?:es|y|ing|ed)?(?: date)?(?: on)?|valid (?:till|until|upto)|lapse[sd]?(?: on)?|grace period (?:ends|till))""")

    private fun clean(s: String): String =
        s.trim().trim('.', ',', '-', ':', ' ').split(Regex("\\s+")).take(6).joinToString(" ").take(48)

    fun parse(raw: String, sender: String, received: Long, source: String, id: String = ""): ParsedAlert? {
        val t = raw.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (t.length < 15) return null
        val lower = t.lowercase(Locale.ROOT)
        if (Regex("""\botp\b|one.time.password|verification code""").containsMatchIn(lower)) return null
        // Offers and ads are not bills.
        if (Regex("""\b(offer|cashback up ?to|discount|sale|win|congratulations|pre.?approved|apply now|get up to)\b""").containsMatchIn(lower) &&
            !Regex("""\b(due|renew|expir|premium)\b""").containsMatchIn(lower)
        ) return null
        val keyBase = (if (id.isNotBlank()) id else "${sender.filter { it.isLetterOrDigit() }.takeLast(8)}-${received / 60_000L}")
        val last4 = last4Rx.find(t)?.groupValues?.get(1).orEmpty()
        val bank = bankName(sender, t)

        // Credit card statement or due reminder.
        val isCard = Regex("""credit card|\bcard\b.*\b(xx|ending|\*\*)|\bcc\b""").containsMatchIn(lower)
        val cardDueWords = Regex("""total (amount )?due|tad\b|minimum (amount )?due|min\.? (amt )?due|mad\b|statement|payment (is )?due|due date""")
        if (isCard && cardDueWords.containsMatchIn(lower) && !Regex("""payment (of .{0,20})?(has been )?received|thank you for (your )?payment""").containsMatchIn(lower)) {
            val total = amountAfter(t, Regex("""(?i)total (?:amount )?due|tad|total outstanding|statement (?:amount|balance)""")) ?: firstAmount(t)
            val min = amountAfter(t, Regex("""(?i)min(?:imum)?\.? (?:amount |amt )?due|mad"""))
            val due = dateAfter(t, dueLabel, received) ?: parseDate(t, received)
            return ParsedAlert(
                key = "${source.lowercase(Locale.ROOT)}-due-$keyBase", kind = "card_due", source = source, sender = sender,
                title = listOf(bank.ifBlank { "Credit" }, "card", if (last4.isNotBlank()) "••$last4" else "").filter { it.isNotBlank() }.joinToString(" "),
                amount = total, minDue = min, dueMillis = due, last4 = last4, bank = bank, category = "EMI", receivedMillis = received
            )
        }

        // Insurance premium due or policy expiry.
        val insurer = insurers.firstOrNull { lower.contains(it) }
        if ((Regex("""\b(policy|premium|insurance)\b""").containsMatchIn(lower) || insurer != null) &&
            Regex("""\b(due|renew|expir|lapse|grace|valid till|valid upto)""").containsMatchIn(lower)
        ) {
            val premium = amountAfter(t, Regex("""(?i)premium(?: amount)?(?: of)?""")) ?: firstAmount(t)
            val due = dateAfter(t, dueLabel, received) ?: dateAfter(t, renewLabel, received)
            val expiry = dateAfter(t, expiryLabel, received)
            val name = Regex("""(?i)(?:your|the)\s+([A-Za-z0-9 &'-]{3,40}?)\s+(?:policy|plan|insurance)""").find(t)?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() && it.lowercase(Locale.ROOT) !in setOf("insurance", "health", "life", "motor") }
            val type = when {
                Regex("""\b(car|bike|motor|vehicle|two.wheeler|4.wheeler)\b""").containsMatchIn(lower) -> "Motor"
                Regex("""\b(health|mediclaim|medical)\b""").containsMatchIn(lower) -> "Health"
                Regex("""\b(life|term|endowment|ulip)\b""").containsMatchIn(lower) -> "Life"
                Regex("""\b(home|property|fire)\b""").containsMatchIn(lower) -> "Home"
                Regex("""\btravel\b""").containsMatchIn(lower) -> "Travel"
                else -> "Other"
            }
            val title = clean(
                name?.let { "$it policy" } ?: listOfNotNull(insurer?.split(' ')?.joinToString(" ") { w -> if (w.length <= 4) w.uppercase(Locale.ROOT) else w.replaceFirstChar { it.uppercaseChar() } }, "$type insurance")
                    .joinToString(" ")
            )
            return ParsedAlert(
                key = "${source.lowercase(Locale.ROOT)}-policy-$keyBase", kind = "policy", source = source, sender = sender,
                title = title, amount = premium, dueMillis = due, expiryMillis = expiry ?: if (due == null) parseDate(t, received) else null,
                category = type, receivedMillis = received
            )
        }

        // Subscriptions and memberships.
        val sub = subscriptionNames.firstOrNull { lower.contains(it) }
        if ((sub != null && Regex("""\b(subscription|membership|plan|renew|auto.?pay|auto.?debit|mandate|billed|charged|trial)""").containsMatchIn(lower)) ||
            Regex("""\bsubscription\b.*\b(renew|due|charged|debited|expir)""").containsMatchIn(lower)
        ) {
            val name = sub?.split(' ')?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
                ?: Regex("""(?i)(?:your|the)\s+([A-Za-z0-9 &'.-]{3,30}?)\s+subscription""").find(t)?.groupValues?.get(1)?.let { clean(it) }
                ?: "Subscription"
            val renews = dateAfter(t, renewLabel, received) ?: dateAfter(t, dueLabel, received) ?: dateAfter(t, expiryLabel, received)
            val cat = when {
                sub == null -> "Other"
                Regex("spotify|gaana|wynk|audible|youtube music").containsMatchIn(sub) -> "Music"
                Regex("microsoft|office|adobe|canva|icloud|google one|chatgpt|openai|claude|linkedin").containsMatchIn(sub) -> "Software"
                Regex("swiggy one|zomato gold|times prime|cult").containsMatchIn(sub) -> "Membership"
                else -> "OTT"
            }
            return ParsedAlert(
                key = "${source.lowercase(Locale.ROOT)}-sub-$keyBase", kind = "subscription", source = source, sender = sender,
                title = name, amount = firstAmount(t), dueMillis = renews, category = cat, receivedMillis = received
            )
        }

        // Phone, electricity, gas, broadband, DTH and other bills that are due.
        val billWords = Regex("""\b(bill|postpaid|recharge|electricity|broadband|wifi|fibre|fiber|piped gas|gas bill|water bill|dth|fastag|maintenance|society|rent)\b""")
        if (billWords.containsMatchIn(lower) &&
            Regex("""\b(due|overdue|pay by|last date|expir|validity ends|will expire|unpaid|pending)""").containsMatchIn(lower) &&
            !Regex("""\b(paid successfully|payment successful|received your payment|thank you for (the )?payment)""").containsMatchIn(lower)
        ) {
            val biller = listOf(
                "airtel", "jio", "vodafone", "vi ", "bsnl", "act fibernet", "hathway", "tata play", "dish tv", "bescom", "bses",
                "tata power", "adani", "msedcl", "tneb", "torrent", "mahanagar gas", "igl", "indraprastha gas", "fastag"
            ).firstOrNull { lower.contains(it) }?.trim()?.replaceFirstChar { it.uppercaseChar() }
            val what = Regex("""\b(postpaid|electricity|broadband|gas|water|dth|recharge|fastag|maintenance|rent)\b""").find(lower)?.value
            val title = clean(listOfNotNull(biller, what?.replaceFirstChar { it.uppercaseChar() } ?: "bill").joinToString(" ") + if (what != null && what !in setOf("recharge", "rent", "maintenance")) " bill" else "")
            return ParsedAlert(
                key = "${source.lowercase(Locale.ROOT)}-bill-$keyBase", kind = "bill_due", source = source, sender = sender,
                title = title, amount = amountAfter(t, Regex("""(?i)(?:bill|amount|due)(?: amount)?(?: of)?""")) ?: firstAmount(t),
                dueMillis = dateAfter(t, dueLabel, received) ?: dateAfter(t, expiryLabel, received) ?: parseDate(t, received),
                category = "Utilities", receivedMillis = received
            )
        }

        // Things waiting on the person: KYC, documents, verification, deadlines.
        if (Regex("""\b(action required|complete (your )?(e-?)?kyc|kyc (is )?(pending|due|expir)|re-?kyc|pending (document|verification)|please (submit|update|verify|complete)|deadline|last date to)\b""")
                .containsMatchIn(lower)
        ) {
            val what = Regex("""(?i)(complete (?:your )?e?-?kyc|re-?kyc|kyc[^.]{0,30}|submit [^.]{3,40}|update [^.]{3,40}|verify [^.]{3,40}|action required[^.]{0,40})""")
                .find(t)?.value
            val who = bank.ifBlank { sender.substringAfter('-').take(12) }
            return ParsedAlert(
                key = "${source.lowercase(Locale.ROOT)}-act-$keyBase", kind = "action", source = source, sender = sender,
                title = clean(listOf(who, what ?: "action needed").filter { it.isNotBlank() }.joinToString(": ")),
                dueMillis = dateAfter(t, dueLabel, received) ?: parseDate(t, received), receivedMillis = received
            )
        }
        return null
    }

    /** Bank accounts and cards seen in transaction alerts, one per bank and last four digits. */
    fun accounts(txns: List<ParsedTxn>): List<ParsedAlert> =
        txns.filter { it.last4.isNotBlank() }
            .groupBy { bankName(it.sender) + "|" + it.last4 }
            .map { (_, list) ->
                val t = list.maxByOrNull { it.dateMillis } ?: list.first()
                val bank = bankName(t.sender)
                val isCard = list.any { it.method == "Credit card" }
                ParsedAlert(
                    key = "acct-${bank.ifBlank { "bank" }.lowercase(Locale.ROOT).filter { it.isLetter() }}-${t.last4}",
                    kind = if (isCard) "card" else "account",
                    source = t.source, sender = t.sender,
                    title = listOf(bank.ifBlank { if (isCard) "Credit" else "Bank" }, if (isCard) "card" else "account", "••${t.last4}").joinToString(" "),
                    last4 = t.last4, bank = bank,
                    amount = list.size.toDouble(),
                    receivedMillis = t.dateMillis
                )
            }
            .sortedByDescending { it.amount ?: 0.0 }
}
