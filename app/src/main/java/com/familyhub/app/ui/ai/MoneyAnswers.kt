package com.familyhub.app.ui.ai

import com.familyhub.app.money.Ledger
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.hub.homeViewFilter
import com.familyhub.app.ui.modules.str
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Money questions answered on the phone from the person's own entries, with the same rules as the Finance
 * dashboard (transfers and card bill payments are not spending). Nothing here is sent to the AI.
 * Settings the assistant may change are limited to safe, confirmable ones (a new category, the family budget).
 */
object MoneyAnswers {

    private val rupees: NumberFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply { maximumFractionDigits = 0 }
    private fun inr(v: Double) = rupees.format(v)
    private fun day(t: Long) = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(t))

    private fun monthRange(offset: Int): Pair<Long, Long> {
        val c = Calendar.getInstance()
        c.add(Calendar.MONTH, offset)
        c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val from = c.timeInMillis
        c.add(Calendar.MONTH, 1)
        return from to c.timeInMillis - 1
    }

    private val Q_SPEND = Regex("\\b(how much|what).{0,30}\\b(spen[dt]|spending|expenses?)\\b|\\bspent this month\\b|\\btotal spend")
    private val Q_TOP = Regex("\\b(top|biggest|most|highest)\\b.{0,25}\\b(categor|spend|expense)|\\bwhere\\b.{0,20}\\b(money|spending) go")
    private val Q_EMI = Regex("\\bemis?\\b|\\bloans?\\b.{0,20}\\b(due|pending|left|outstanding)")
    private val Q_CARD = Regex("\\b(credit )?card\\b.{0,25}\\b(bill|due|outstanding|owe)|\\bnext card\\b")
    private val Q_SAVE = Regex("\\b(how much|what).{0,30}\\b(save|saved|saving|savings)\\b|\\bsavings? rate\\b|\\b(my|our) savings\\b")
    private val ADDING = Regex("^\\s*(please\\s+)?(add|create|new|make|remind|set up|schedule|log|record|save)\\b")
    private val Q_FAMILY = Regex("\\b(who|which member|each member|family)\\b.{0,25}\\b(spen[dt]|spending)")
    private val Q_BALANCE = Regex("\\b(bank )?balance\\b|\\bhow much (money )?(do i|do we) have\\b|\\bmoney in (the )?bank")
    private val Q_DUE = Regex("\\b(what'?s|what is|anything|bills?)\\b.{0,20}\\bdue\\b|\\bdue (this|next) week\\b|\\bupcoming (bills|payments)\\b")
    private val Q_UNCAT = Regex("\\b(categori[sz]e|uncategori[sz]ed|no category|without (a )?category)\\b")
    private val ADD_CAT = Regex("^\\s*(please\\s+)?(add|create|new)\\s+(a\\s+)?(spending |money )?(category|sub-?category)\\s+(?:called\\s+|named\\s+)?\"?([\\p{L}0-9 &'-]{2,30}?)\"?(?:\\s+(?:under|inside|in)\\s+\"?([\\p{L}0-9 &'-]{2,30})\"?)?\\s*$")
    private val THEME = Regex("\\b(?:switch|change|set|use|turn on|make)\\b.{0,25}?\\b(dark|light|night|day|system)\\s+(?:mode|theme)\\b|\\b(?:theme|appearance)\\b.{0,12}?\\b(dark|light|system)\\b")
    private val SET_BUDGET = Regex("\\b(set|change|make)\\b.{0,20}\\b(monthly |family )?budget\\b.{0,15}?(?:to\\s+)?(?:rs\\.?|₹|inr)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(k|thousand|lakh|l)?\\b")

    fun answer(text: String, env: AppEnv): AppCommands.Result? {
        val s = text.lowercase(Locale.ROOT).trim()
        THEME.find(s)?.let { m ->
            val want = (m.groupValues[1].ifBlank { m.groupValues[2] }).let { if (it == "night") "dark" else if (it == "day") "light" else it }
            val label = when (want) { "dark" -> "Dark"; "light" -> "Light"; else -> "the phone's setting" }
            return AppCommands.Result(
                "Tap Apply to switch the app to $label. You can change it back any time in Family → Settings → Appearance.",
                listOf(AiAction(kind = "theme", title = if (want == "system") "Phone setting" else label, value = want))
            )
        }
        ADD_CAT.find(s)?.let { m ->
            val name = m.groupValues[6].trim().replaceFirstChar { it.uppercase() }
            val parent = m.groupValues[7].trim().takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
            return AppCommands.Result(
                "Here is the new category. Tap Add to save it; you can rename or remove it later in Money categories (removed ones go to Recently deleted).",
                listOf(AiAction(kind = "category", title = name, value = parent.orEmpty()))
            )
        }
        SET_BUDGET.find(s)?.let { m ->
            var v = m.groupValues[3].replace(",", "").toDoubleOrNull() ?: return null
            when (m.groupValues[4]) { "k", "thousand" -> v *= 1000; "lakh", "l" -> v *= 100_000 }
            val now = env.family.monthlyBudget
            return AppCommands.Result(
                if (!env.isAdmin) "Only family admins can change the family budget. It is ${inr(now)} a month now."
                else "The family budget is ${inr(now)} a month now. Tap Set to make it ${inr(v)}; to undo, ask to set it back to ${inr(now)}.",
                if (env.isAdmin) listOf(AiAction(kind = "budget", title = "Family monthly budget", amount = v, value = now.toString())) else emptyList()
            )
        }
        // Requests to add or log something go to the assistant, not to these answers.
        if (ADDING.containsMatchIn(s)) return null
        val mine = Regex("\\b(i|my|me|mine)\\b").containsMatchIn(s) && !Regex("\\b(we|our|us|family)\\b").containsMatchIn(s)
        val view = homeViewFilter(!mine, env.uid, env.profile.name)
        val data = env.data.mapValues { (_, l) -> l.filter(view) }
        val all = env.data["expenses"].orEmpty()
        val last = Regex("\\blast month\\b").containsMatchIn(s)
        val (from, to) = monthRange(if (last) -1 else 0)
        val now = System.currentTimeMillis()
        val who = if (mine) "You" else "The family"
        val period = if (last) "last month" else "this month"
        val sum = Ledger.summary(data, from, to, now, all)
        return when {
            Q_UNCAT.containsMatchIn(s) -> {
                val open = data["expenses"].orEmpty().filter { Ledger.isSpend(it) && (it.str("category").isBlank() || it.str("category") == "Other") && ((it["dateMillis"] as? Number)?.toLong() ?: 0L) in from..to }
                reply(
                    if (open.isEmpty()) "Every spend $period has a category."
                    else "${open.size} spends $period have no category: " + open.take(6).joinToString(", ") { it.str("title") } +
                        (if (open.size > 6) "…" else "") + ". Open Finance and tap one to choose its category."
                )
            }
            Q_TOP.containsMatchIn(s) -> {
                val cats = data["expenses"].orEmpty().filter { Ledger.isSpend(it) && ((it["dateMillis"] as? Number)?.toLong() ?: 0L) in from..to }
                    .groupBy { it.str("category").ifBlank { "Other" } }.mapValues { (_, l) -> l.sumOf { Ledger.spendAmount(it) } }
                    .toList().sortedByDescending { it.second }
                reply(
                    if (cats.isEmpty()) "No spending recorded $period."
                    else "$who spent most on ${cats.first().first} $period (${inr(cats.first().second)}). Next: " +
                        cats.drop(1).take(3).joinToString(", ") { "${it.first} ${inr(it.second)}" } + "."
                )
            }
            Q_FAMILY.containsMatchIn(s) -> {
                val by = env.data["expenses"].orEmpty().filter { Ledger.isSpend(it) && it["_private"] != true && ((it["dateMillis"] as? Number)?.toLong() ?: 0L) in from..to }
                    .groupBy { it.str("memberName").ifBlank { "Someone" } }.mapValues { (_, l) -> l.sumOf { Ledger.spendAmount(it) } }
                    .toList().sortedByDescending { it.second }
                reply(if (by.isEmpty()) "No shared spending $period." else "Shared spending $period: " + by.joinToString(", ") { "${it.first} ${inr(it.second)}" } + ".")
            }
            Q_EMI.containsMatchIn(s) -> {
                val dues = Ledger.dues(mapOf("items" to data["items"].orEmpty(), "expenses" to all), now - 60 * Ledger.DAY, now + 31 * Ledger.DAY, now)
                    .filter { it.kind == "emi" && it.status != "PAID" }
                reply(
                    if (sum.loans.isEmpty() && dues.isEmpty()) "No loans or EMIs are saved. Add them in Loans & EMIs."
                    else listOfNotNull(
                        if (dues.isEmpty()) "No EMI is due in the next month." else "EMIs to pay: " + dues.joinToString("; ") { "${it.title} ${inr(it.amount)} on ${day(it.dueMillis)}" + if (it.status == "OVERDUE") " (overdue)" else "" } + ".",
                        sum.loans.takeIf { it.isNotEmpty() }?.let { l -> "Principal left: ${inr(l.sumOf { it.outstandingPrincipal })}; monthly EMIs ${inr(l.sumOf { it.emi })}." }
                    ).joinToString(" ")
                )
            }
            Q_CARD.containsMatchIn(s) -> reply(
                if (sum.cards.isEmpty()) "No credit cards are saved. Add them in Credit cards."
                else sum.cards.joinToString(" ") { c ->
                    "${c.title}: " + (if (c.amountDue > 0) "${inr(c.amountDue)} due on ${day(c.dueMillis)} (minimum ${inr(c.minDue)})" else "nothing due") +
                        ", ${inr(c.outstanding)} outstanding."
                }
            )
            Q_DUE.containsMatchIn(s) -> {
                val dues = Ledger.dues(mapOf("items" to data["items"].orEmpty(), "expenses" to all), now - 30 * Ledger.DAY, now + 7 * Ledger.DAY, now)
                    .filter { it.status != "PAID" }
                reply(if (dues.isEmpty()) "Nothing is due in the next 7 days." else "Due in the next 7 days: " + dues.joinToString("; ") { "${it.title} ${inr(it.amount)} ${day(it.dueMillis)}" + if (it.status == "OVERDUE") " (overdue)" else "" } + ".")
            }
            Q_BALANCE.containsMatchIn(s) -> reply(
                if (sum.accounts.isEmpty()) "No accounts are saved yet. Add them in Accounts with today's balance."
                else "In accounts: ${inr(sum.bank)}" + (if (sum.cash != 0.0) ", cash ${inr(sum.cash)}" else "") + ". " +
                    sum.accounts.joinToString(", ") { "${it.title} ${inr(it.balance)}" } + "."
            )
            Q_SAVE.containsMatchIn(s) && !Q_SPEND.containsMatchIn(s) -> reply(
                if (sum.income <= 0) "No income is recorded $period, so savings can't be worked out. Spent ${inr(sum.spent)}."
                else "$period: income ${inr(sum.income)}, spent ${inr(sum.spent)}, saved ${inr(sum.savings)} (${sum.savingsPct}%)."
                    .replaceFirstChar { it.uppercase() }
            )
            Q_SPEND.containsMatchIn(s) -> reply(
                "$who spent ${inr(sum.spent)} $period" + (if (sum.income > 0) ", against income of ${inr(sum.income)}" else "") +
                    (if (!mine && env.family.monthlyBudget > 0 && !last) " (budget ${inr(env.family.monthlyBudget)})" else "") +
                    ". Card bill payments and transfers are not counted again."
            )
            else -> null
        }
    }

    private fun reply(text: String) = AppCommands.Result(text, emptyList())
}
