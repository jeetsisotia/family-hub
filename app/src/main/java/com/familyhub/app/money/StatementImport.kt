package com.familyhub.app.money

/**
 * Turns alerts and statement lines into Finance entries linked to the right account or card,
 * with card bill payments and own transfers saved as money moved (never as spending).
 */
object StatementImport {

    private fun Map<String, Any?>.s(k: String): String = when (val v = this[k]) { null -> ""; is String -> v; else -> v.toString() }
    private fun Map<String, Any?>.n(k: String): Long? = (this[k] as? Number)?.toLong()

    private val METHODS = setOf("UPI", "Cash", "Debit card", "Credit card", "Net banking", "Wallet", "Other")

    fun typeFor(t: ParsedTxn): String = when {
        t.isCardPayment -> "CARD_PAYMENT"
        t.isTransfer -> "TRANSFER"
        t.isCredit -> "INCOME"
        else -> "EXPENSE"
    }

    /** What an alert would be saved as, for the duplicate check. */
    fun candidate(t: ParsedTxn): Map<String, Any?> = mapOf(
        "type" to typeFor(t), "amountInr" to t.amount, "dateMillis" to t.dateMillis, "last4" to t.last4,
        "refNo" to t.refNo, "merchant" to t.merchant, "title" to t.merchant
    )

    private fun live(items: List<Map<String, Any?>>) = items.filter { it["isDeleted"] != true }

    private fun byLast4(items: List<Map<String, Any?>>, type: String, last4: String) =
        if (last4.isBlank()) null else items.firstOrNull { it.s("type") == type && it.s("last4") == last4 }

    private fun accountOfDebit(items: List<Map<String, Any?>>, debit: Map<String, Any?>) =
        items.firstOrNull { it.s("type") == "account" && ((debit.s("accountId").isNotBlank() && it.s("id") == debit.s("accountId")) || it.s("title") == debit.s("account")) }

    /** The only card from that bank, when the bank's own SMS does not name the card. */
    private fun cardOfBank(items: List<Map<String, Any?>>, bank: String): Map<String, Any?>? {
        if (bank.isBlank()) return null
        val word = bank.substringBefore(' ').lowercase()
        return items.filter { it.s("type") == "card" && (it.s("bank").lowercase().contains(word) || it.s("title").lowercase().contains(word)) }.singleOrNull()
    }

    /** A Finance entry for an SMS or email alert. */
    fun entryFor(t: ParsedTxn, allItems: List<Map<String, Any?>>, category: String): HashMap<String, Any?> {
        val items = live(allItems)
        val credit = byLast4(items, "card", t.last4)
        val debit = byLast4(items, "debit_card", t.last4)
        val acct = byLast4(items, "account", t.last4) ?: debit?.let { accountOfDebit(items, it) }
        val type = typeFor(t)
        val m = HashMap<String, Any?>()
        m["type"] = type
        m["amountInr"] = t.amount
        m["dateMillis"] = t.dateMillis
        m["merchant"] = t.merchant
        m["refNo"] = t.refNo
        m["last4"] = t.last4
        m["confidence"] = t.confidence
        // A refund or reversal lowers spending in the month it arrives and is never counted as income.
        if (t.isRefund) { m["isRefund"] = true; m["notExpense"] = true }
        m["isPaid"] = true
        m["isDeleted"] = false
        m["notes"] = "Added from bank ${if (t.source == "SMS") "SMS" else "email"}" + (if (t.last4.isNotBlank()) " (ending ${t.last4})" else "")
        when (type) {
            "CARD_PAYMENT" -> {
                val card = credit ?: cardOfBank(items, t.bank)
                m["title"] = (card?.s("title") ?: t.bank.ifBlank { "Credit card" }) + " payment"
                m["category"] = "Credit card"
                if (card != null) { m["cardId"] = card.s("id"); m["card"] = card.s("title") }
                if (acct != null) { m["accountId"] = acct.s("id"); m["account"] = acct.s("title") }
            }
            "TRANSFER" -> {
                m["title"] = t.merchant.ifBlank { if (t.isCredit) "Money in (own transfer)" else "Own transfer" }
                m["category"] = "Transfer"
                if (acct != null) {
                    if (t.isCredit) { m["toAccountId"] = acct.s("id"); m["toAccount"] = acct.s("title") }
                    else { m["accountId"] = acct.s("id"); m["account"] = acct.s("title") }
                }
            }
            else -> {
                m["title"] = t.merchant.ifBlank { if (t.isCredit) "Money received" else "Payment" }
                m["category"] = category
                when {
                    credit != null -> {
                        m["paymentMethod"] = "Credit card"; m["card"] = credit.s("title"); m["cardId"] = credit.s("id"); m["cardKind"] = "credit"
                        // Money back on a card is a refund that lowers what is owed, not income.
                        if (t.isCredit) m["notExpense"] = true
                    }
                    debit != null -> {
                        m["paymentMethod"] = "Debit card"; m["card"] = debit.s("title"); m["cardId"] = debit.s("id"); m["cardKind"] = "debit"
                        if (acct != null) { m["accountId"] = acct.s("id"); m["account"] = acct.s("title") }
                    }
                    else -> {
                        m["paymentMethod"] = if (t.method in METHODS) t.method else "Other"
                        if (t.method == "Credit card") m["cardKind"] = "credit"
                        m["card"] = "None"
                        if (acct != null) { m["accountId"] = acct.s("id"); m["account"] = acct.s("title") }
                    }
                }
            }
        }
        return m
    }

    /**
     * The newest balance each account's alerts reported, and the newest available limit for each card,
     * as field updates for those records (only when newer than what is saved).
     */
    fun balanceUpdates(txns: List<ParsedTxn>, allItems: List<Map<String, Any?>>): List<Pair<Map<String, Any?>, Map<String, Any?>>> {
        val items = live(allItems)
        val out = ArrayList<Pair<Map<String, Any?>, Map<String, Any?>>>()
        txns.filter { it.balance != null && it.last4.isNotBlank() }.groupBy { it.last4 }.forEach { (l4, list) ->
            val latest = list.maxByOrNull { it.dateMillis } ?: return@forEach
            val acct = byLast4(items, "account", l4) ?: byLast4(items, "debit_card", l4)?.let { accountOfDebit(items, it) } ?: return@forEach
            if ((acct.n("lastSeenBalanceAt") ?: 0L) < latest.dateMillis) {
                out.add(acct to mapOf("lastSeenBalance" to latest.balance, "lastSeenBalanceAt" to latest.dateMillis, "lastSeenBalanceFrom" to latest.source))
            }
        }
        txns.filter { it.availableLimit != null && it.last4.isNotBlank() }.groupBy { it.last4 }.forEach { (l4, list) ->
            val latest = list.maxByOrNull { it.dateMillis } ?: return@forEach
            val card = byLast4(items, "card", l4) ?: return@forEach
            if ((card.n("lastSeenAvailableAt") ?: 0L) < latest.dateMillis) {
                out.add(card to mapOf("lastSeenAvailable" to latest.availableLimit, "lastSeenAvailableAt" to latest.dateMillis))
            }
        }
        return out
    }

    /**
     * A Finance entry for one statement line. [target] is the account or credit card the statement belongs to
     * (null when the person chose not to link it). ATM cash goes to a Cash account when there is one.
     */
    fun entryForRow(r: StatementRow, target: Map<String, Any?>?, allItems: List<Map<String, Any?>>, category: String): HashMap<String, Any?> {
        val items = live(allItems)
        val isCard = target?.s("type") == "card"
        val cash = items.firstOrNull { it.s("type") == "account" && (it.s("category") == "Cash" || it.s("category") == "Wallet") }
        val merchant = StatementParser.merchantOf(r.description)
        val m = HashMap<String, Any?>()
        m["amountInr"] = r.amount
        m["dateMillis"] = r.dateMillis
        m["merchant"] = merchant
        m["refNo"] = r.refNo
        m["isPaid"] = true
        m["isDeleted"] = false
        m["notes"] = "From statement: " + r.description.take(120)
        fun linkAccount() { if (target != null && !isCard) { m["accountId"] = target.s("id"); m["account"] = target.s("title") } }
        when {
            isCard && r.isCredit && StatementParser.isCardPayment(r.description) -> {
                m["type"] = "CARD_PAYMENT"; m["title"] = target!!.s("title") + " payment"; m["category"] = "Credit card"
                m["cardId"] = target.s("id"); m["card"] = target.s("title")
            }
            isCard -> {
                m["type"] = if (r.isCredit) "INCOME" else "EXPENSE"
                m["title"] = merchant.ifBlank { if (r.isCredit) "Refund" else "Card purchase" }
                m["category"] = category
                m["paymentMethod"] = "Credit card"; m["cardKind"] = "credit"
                m["cardId"] = target!!.s("id"); m["card"] = target.s("title")
                // A refund or cashback lowers what is owed; it is not income.
                if (r.isCredit) m["notExpense"] = true
                if (r.isCredit && TxnParser.refundRx.containsMatchIn(r.description)) m["isRefund"] = true
            }
            !r.isCredit && StatementParser.isCardPayment(r.description) -> {
                m["type"] = "CARD_PAYMENT"; m["category"] = "Credit card"; linkAccount()
                val card = items.filter { it.s("type") == "card" && it.s("last4").length == 4 }.firstOrNull { c -> r.description.contains(c.s("last4")) }
                m["title"] = (card?.s("title") ?: "Credit card") + " payment"
                if (card != null) { m["cardId"] = card.s("id"); m["card"] = card.s("title") }
            }
            StatementParser.isSelfTransfer(r.description) -> {
                m["type"] = "TRANSFER"; m["title"] = merchant.ifBlank { "Own transfer" }; m["category"] = "Transfer"
                if (target != null) {
                    if (r.isCredit) { m["toAccountId"] = target.s("id"); m["toAccount"] = target.s("title") } else linkAccount()
                }
            }
            !r.isCredit && StatementParser.isAtm(r.description) && cash != null && cash.s("id") != target?.s("id") -> {
                m["type"] = "TRANSFER"; m["title"] = "ATM cash"; m["category"] = "Transfer"; linkAccount()
                m["toAccountId"] = cash.s("id"); m["toAccount"] = cash.s("title")
            }
            else -> {
                m["type"] = if (r.isCredit) "INCOME" else "EXPENSE"
                m["title"] = merchant.ifBlank { if (r.isCredit) "Money received" else "Payment" }
                if (r.isCredit && TxnParser.refundRx.containsMatchIn(r.description)) { m["isRefund"] = true; m["notExpense"] = true }
                m["category"] = category
                val d = r.description.lowercase()
                m["paymentMethod"] = when {
                    "upi" in d -> "UPI"
                    "neft" in d || "imps" in d || "rtgs" in d || "netbanking" in d || "net banking" in d -> "Net banking"
                    "atm" in d -> "Cash"
                    "pos" in d || "debit card" in d -> "Debit card"
                    else -> "Other"
                }
                m["card"] = "None"
                linkAccount()
            }
        }
        return m
    }

    /** Stable key so the same statement line is never imported twice. */
    fun rowKey(r: StatementRow, target: Map<String, Any?>?): String =
        "stmt-" + (target?.s("id") ?: "none") + "-" + (r.dateMillis / 86_400_000L) + "-" + "%.2f".format(java.util.Locale.ROOT, r.amount) + "-" +
            (if (r.isCredit) "c" else "d") + "-" + r.description.lowercase().filter { it.isLetterOrDigit() }.take(24)
}
