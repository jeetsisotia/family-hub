package com.familyhub.app.ui.money

import com.familyhub.app.data.remote.PRIVATE_FLAG
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.money.Ledger
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.modules.num
import com.familyhub.app.ui.modules.str
import java.util.Calendar

/**
 * What the buttons on a due do. A card bill is settled with a card payment (never an expense);
 * an EMI, bill, subscription or premium becomes a paid entry in Finance. Moving, snoozing or skipping
 * a due that the app worked out (card bill, loan instalment, renewal) saves it as an entry first,
 * so the change sticks for that month only.
 */
object DueActions {

    private fun source(env: AppEnv, d: Ledger.Due): Rec? =
        env.data[d.sourceCollection].orEmpty().firstOrNull { it.str("id") == d.sourceId }

    private fun isPrivate(src: Rec?): Boolean = src?.get(PRIVATE_FLAG) == true

    private fun linkFields(d: Ledger.Due): Map<String, Any?> {
        val parts = d.key.split(':')
        return when (d.kind) {
            "card" -> mapOf("cardId" to d.sourceId)
            "emi" -> if (d.sourceCollection == "items") mapOf("loanId" to d.sourceId, "installmentNo" to parts.getOrNull(2)?.toDoubleOrNull()) else emptyMap()
            "subscription" -> mapOf("subscriptionId" to d.sourceId)
            "policy" -> mapOf("policyId" to d.sourceId)
            else -> emptyMap()
        }
    }

    /** Saves a generated due as an entry so it can be moved, snoozed or skipped. Returns the new fields. */
    private suspend fun materialise(env: AppEnv, d: Ledger.Due, extra: Map<String, Any?>) {
        val src = source(env, d)
        val data = HashMap<String, Any?>()
        data["type"] = if (d.kind == "emi") "EMI" else "BILL"
        data["title"] = d.title
        data["amountInr"] = d.amount
        data["category"] = d.category
        data["dateMillis"] = d.dueMillis
        data["dueDateMillis"] = d.dueMillis
        data["isPaid"] = false
        data["isDeleted"] = false
        data["dueKey"] = d.key
        data["dueKind"] = d.kind
        // A card bill repeats purchases already counted, so it never counts as spending itself.
        data["notExpense"] = d.kind == "card"
        if (d.minAmount != null) data["minDue"] = d.minAmount
        data["memberUid"] = env.uid
        data["memberName"] = env.profile.name
        data["createdAt"] = System.currentTimeMillis()
        data.putAll(linkFields(d))
        data.putAll(extra)
        env.repo.saveScoped(env.familyId, env.uid, "expenses", null, data, isPrivate(src))
    }

    suspend fun markPaid(env: AppEnv, d: Ledger.Due, amount: Double, account: String, proof: String?) {
        val now = System.currentTimeMillis()
        val src = source(env, d)
        if (d.kind == "card") {
            val cardId = if (d.sourceCollection == "items") d.sourceId else src?.str("cardId").orEmpty()
            val card = env.data["items"].orEmpty().firstOrNull { it.str("id") == cardId }
            env.repo.saveScoped(
                env.familyId, env.uid, "expenses", null,
                mapOf(
                    "type" to "CARD_PAYMENT", "title" to "${card?.str("title") ?: d.title.removeSuffix(" bill")} payment",
                    "amountInr" to amount, "dateMillis" to now, "cardId" to cardId, "card" to (card?.str("title") ?: ""),
                    "account" to account, "category" to "Credit card", "isDeleted" to false,
                    "proofPhoto" to proof, "memberUid" to env.uid, "memberName" to env.profile.name, "createdAt" to now
                ),
                isPrivate(card ?: src)
            )
            if (d.sourceCollection == "expenses" && src != null && amount + 0.5 >= d.amount) {
                env.repo.setFieldsScoped(env.familyId, env.uid, "expenses", src, mapOf("isPaid" to true, "paidOnMillis" to now))
            }
            return
        }
        if (d.sourceCollection == "expenses" && src != null) {
            env.repo.setFieldsScoped(
                env.familyId, env.uid, "expenses", src,
                mapOf(
                    "isPaid" to true, "paidOnMillis" to now, "dateMillis" to now, "amountInr" to amount,
                    "account" to account, "proofPhoto" to (proof ?: src["proofPhoto"]), "snoozedUntilMillis" to null
                )
            )
            return
        }
        // A generated EMI, renewal or premium: record the payment.
        val loan = if (d.kind == "emi") src else null
        val data = HashMap<String, Any?>()
        data["type"] = if (d.kind == "emi") "EMI" else "EXPENSE"
        data["title"] = d.title
        data["amountInr"] = amount
        data["category"] = d.category
        data["dateMillis"] = now
        data["dueDateMillis"] = d.dueMillis
        data["isPaid"] = true
        data["paidOnMillis"] = now
        data["isDeleted"] = false
        data["dueKey"] = d.key
        data["dueKind"] = d.kind
        data["account"] = account
        data["proofPhoto"] = proof
        data["memberUid"] = env.uid
        data["memberName"] = env.profile.name
        data["createdAt"] = now
        // Card EMIs are paid through the card bill, so they are not counted twice.
        if (loan?.str("loanType") == "Credit card EMI") data["notExpense"] = true
        data.putAll(linkFields(d))
        env.repo.saveScoped(env.familyId, env.uid, "expenses", null, data, isPrivate(src))
        // Move a yearly premium to next year.
        if (d.kind == "policy" && src != null) {
            val months = when (src.str("premiumCycle").lowercase()) { "monthly" -> 1; "quarterly" -> 3; "half-yearly" -> 6; else -> 12 }
            val next = Calendar.getInstance().apply { timeInMillis = d.dueMillis; add(Calendar.MONTH, months) }.timeInMillis
            env.repo.setFieldsScoped(env.familyId, env.uid, "items", src, mapOf("premiumDueMillis" to next))
        }
    }

    suspend fun snooze(env: AppEnv, d: Ledger.Due, until: Long) {
        val src = source(env, d)
        if (d.sourceCollection == "expenses" && src != null) {
            env.repo.setFieldsScoped(env.familyId, env.uid, "expenses", src, mapOf("snoozedUntilMillis" to until))
        } else materialise(env, d, mapOf("snoozedUntilMillis" to until))
    }

    suspend fun reschedule(env: AppEnv, d: Ledger.Due, newDue: Long) {
        val src = source(env, d)
        if (d.sourceCollection == "expenses" && src != null) {
            env.repo.setFieldsScoped(
                env.familyId, env.uid, "expenses", src,
                mapOf("dueDateMillis" to newDue, "dateMillis" to newDue, "snoozedUntilMillis" to null)
            )
        } else materialise(env, d, mapOf("dueDateMillis" to newDue, "dateMillis" to newDue))
    }

    /** Removes a saved bill, or skips a generated due for this month only. */
    suspend fun delete(env: AppEnv, d: Ledger.Due) {
        val src = source(env, d)
        if (d.sourceCollection == "expenses" && src != null) {
            env.repo.setFieldsScoped(env.familyId, env.uid, "expenses", src, mapOf("isDeleted" to true))
        } else materialise(env, d, mapOf("isDeleted" to true, "skipped" to true))
    }

    suspend fun attachProof(env: AppEnv, d: Ledger.Due, proof: String) {
        val id = d.paidTxnId ?: return
        val rec = env.data["expenses"].orEmpty().firstOrNull { it.str("id") == id } ?: return
        env.repo.setFieldsScoped(env.familyId, env.uid, "expenses", rec, mapOf("proofPhoto" to proof))
    }

    /** Money between your own accounts, or from an account to a credit card. Neither is income nor spending. */
    suspend fun transfer(env: AppEnv, fromAccount: String, to: String, toIsCard: Boolean, amount: Double, at: Long, note: String) {
        val items = env.data["items"].orEmpty()
        val from = items.firstOrNull { it.str("type") == "account" && it.str("title") == fromAccount }
        val now = System.currentTimeMillis()
        if (toIsCard) {
            val card = items.firstOrNull { it.str("type") == "card" && it.str("title") == to }
            env.repo.saveScoped(
                env.familyId, env.uid, "expenses", null,
                mapOf(
                    "type" to "CARD_PAYMENT", "title" to "$to payment", "amountInr" to amount, "dateMillis" to at,
                    "account" to fromAccount, "accountId" to from?.str("id"), "card" to to, "cardId" to card?.str("id"),
                    "category" to "Credit card", "notes" to note, "isDeleted" to false,
                    "memberUid" to env.uid, "memberName" to env.profile.name, "createdAt" to now
                ),
                isPrivate(card ?: from)
            )
        } else {
            val dest = items.firstOrNull { it.str("type") == "account" && it.str("title") == to }
            env.repo.saveScoped(
                env.familyId, env.uid, "expenses", null,
                mapOf(
                    "type" to "TRANSFER", "title" to "$fromAccount → $to", "amountInr" to amount, "dateMillis" to at,
                    "account" to fromAccount, "accountId" to from?.str("id"), "toAccount" to to, "toAccountId" to dest?.str("id"),
                    "category" to "Transfer", "notes" to note, "isDeleted" to false,
                    "memberUid" to env.uid, "memberName" to env.profile.name, "createdAt" to now
                ),
                isPrivate(from)
            )
        }
    }

    /** Brings an account in line with its statement by recording the difference (not income or spending). */
    suspend fun reconcile(env: AppEnv, account: Rec, appBalance: Double, statementBalance: Double) {
        val diff = Ledger.round2(statementBalance - appBalance)
        if (kotlin.math.abs(diff) < 0.01) return
        env.repo.saveScoped(
            env.familyId, env.uid, "expenses", null,
            mapOf(
                "type" to "ADJUSTMENT", "title" to "Balance matched to statement", "amountInr" to diff,
                "dateMillis" to System.currentTimeMillis(), "account" to account.str("title"), "accountId" to account.str("id"),
                "category" to "Adjustment", "isDeleted" to false, "memberUid" to env.uid, "createdAt" to System.currentTimeMillis()
            ),
            account[PRIVATE_FLAG] == true
        )
    }

    fun cardOutstandingNote(c: Ledger.CardStatus): String =
        if (c.amountDue <= 0.0) "Nothing due" else "Due ${inr(c.amountDue)} • minimum ${inr(c.minDue)}"

    fun num(r: Rec, k: String) = r.num(k)
}
