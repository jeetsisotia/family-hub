package com.familyhub.app.core.ai

import com.familyhub.app.data.remote.Rec
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.monthStart
import com.familyhub.app.ui.modules.DAY_MS
import com.familyhub.app.ui.modules.bool
import com.familyhub.app.ui.modules.buildAlerts
import com.familyhub.app.ui.modules.daysUntil
import com.familyhub.app.ui.modules.lng
import com.familyhub.app.ui.modules.nextOccurrence
import com.familyhub.app.ui.modules.num
import com.familyhub.app.ui.modules.str
import com.familyhub.app.ui.money.CardCycle
import com.familyhub.app.ui.money.amountOf
import com.familyhub.app.ui.money.cardSpends
import com.familyhub.app.ui.money.inRange
import com.familyhub.app.ui.money.isIncome
import com.familyhub.app.ui.money.isSpend
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the summary the AI assistant is allowed to see.
 * Never included: ID numbers, profile details, health records, medicines, vitals, periods, habits,
 * steps, emergency contacts, the vault, documents' contents and free-text notes.
 */
object AiContext {
    /** Record types and alert routes that stay out of the AI. */
    private val PRIVATE_TYPES = setOf(
        "medicine", "vital", "period", "habit", "health", "emergency", "vault", "document", "note", "memory", "food"
    )
    private val PRIVATE_ROUTES = setOf(
        "health", "medicines", "vitals", "periods", "habits", "emergency", "home_vault", "documents", "notes", "memories", "food"
    )

    private val day = SimpleDateFormat("EEE d MMM yyyy", Locale.ENGLISH)
    private fun d(t: Long?): String = if (t == null) "" else day.format(Date(t))
    private fun money(v: Double): String = "₹" + String.format(Locale("en", "IN"), "%,.0f", v)

    fun build(env: AppEnv, now: Long = System.currentTimeMillis()): String {
        val items = env.data["items"].orEmpty().filter { it.str("type") !in PRIVATE_TYPES }
        val events = env.data["events"].orEmpty().filter { !it.bool("isDeleted") }
        val tasks = env.data["tasks"].orEmpty()
        val expenses = env.data["expenses"].orEmpty()
        val trips = env.data["trips"].orEmpty()
        val sb = StringBuilder()

        sb.appendLine("Today: ${d(now)}.")
        sb.appendLine("User: ${env.profile.name.ifBlank { "the user" }}. Family: ${env.family.name.ifBlank { "their family" }}.")
        if (env.memberNames.isNotEmpty()) sb.appendLine("Family members: ${env.memberNames.joinToString(", ")}.")

        val upcoming = events.mapNotNull { e ->
            val start = e.lng("startTimeMillis") ?: return@mapNotNull null
            val next = nextOccurrence(start, e.str("recurrence"), now)
            if (next in (now - DAY_MS)..(now + 30 * DAY_MS)) Triple(next, e.str("title"), e.str("category")) else null
        }.sortedBy { it.first }.take(15)
        if (upcoming.isNotEmpty()) {
            sb.appendLine("\nCalendar, next 30 days:")
            upcoming.forEach { (t, title, cat) -> sb.appendLine("- ${d(t)}: $title${if (cat.isNotBlank()) " ($cat)" else ""}") }
        }

        val openTasks = tasks.filter { !it.bool("isCompleted") }
        val shopping = openTasks.filter { it.str("type") == "SHOPPING" }
        val chores = openTasks.filter { it.str("type") != "SHOPPING" }
        if (shopping.isNotEmpty()) {
            sb.appendLine("\nShopping list: " + shopping.take(30).joinToString(", ") { it.str("title") } + ".")
        }
        if (chores.isNotEmpty()) {
            sb.appendLine("\nOpen tasks:")
            chores.take(15).forEach { t ->
                val due = t.lng("dueDateMillis")
                val who = t.str("assignedToName")
                sb.appendLine("- ${t.str("title")}${if (who.isNotBlank()) " (for $who)" else ""}${if (due != null) ", due ${d(due)}" else ""}")
            }
        }

        // "Only me" reminders stay out: their titles can be personal (for example a medicine or a test).
        val reminders = items.filter { it.str("type") == "reminder" && !it.bool("done") && it[com.familyhub.app.data.remote.PRIVATE_FLAG] != true }
            .mapNotNull { r ->
                val t = r.lng("dateMillis") ?: return@mapNotNull null
                val next = nextOccurrence(t, r.str("repeat"), now)
                if (next <= now + 7 * DAY_MS) Triple(next, r.str("title"), r.str("time")) else null
            }.sortedBy { it.first }.take(10)
        if (reminders.isNotEmpty()) {
            sb.appendLine("\nReminders this week:")
            reminders.forEach { (t, title, time) -> sb.appendLine("- ${d(t)}${if (time.isNotBlank()) " $time" else ""}: $title") }
        }

        val safeData = mapOf("items" to items, "events" to events, "expenses" to expenses, "tasks" to tasks, "trips" to trips)
        val due = (buildAlerts(safeData) + com.familyhub.app.ui.modules.moneyAlerts(safeData, now)).sortedBy { it.dateMillis }.filter { it.route !in PRIVATE_ROUTES && daysUntil(it.dateMillis, now) in -7..30 }.take(15)
        if (due.isNotEmpty()) {
            sb.appendLine("\nDue soon or overdue (bills, EMIs, renewals, expiries):")
            due.forEach { sb.appendLine("- ${it.title}: ${it.label}") }
        }

        val upcomingTrips = trips.filter { (it.lng("endDateMillis") ?: it.lng("startDateMillis") ?: 0L) >= now - DAY_MS }
            .sortedBy { it.lng("startDateMillis") ?: 0L }.take(5)
        if (upcomingTrips.isNotEmpty()) {
            sb.appendLine("\nTrips: " + upcomingTrips.joinToString("; ") {
                it.str("destination") + " from " + d(it.lng("startDateMillis")) + " to " + d(it.lng("endDateMillis"))
            })
        }

        // Money summary
        val mStart = monthStart(now, 0)
        val lastStart = monthStart(now, -1)
        // Same rules as the Finance dashboard: totals in paise, refunds lower spending and are never income.
        val L = com.familyhub.app.money.Ledger
        fun rs(list: List<Rec>) = L.rupees(list.sumOf { L.paise(amountOf(it)) })
        val dayOfMonth = ((now - mStart) / DAY_MS).toInt() + 1
        val tThis = L.totals(expenses, mStart, now)
        val tLast = L.totals(expenses, lastStart, mStart - 1)
        val tSame = L.totals(expenses, lastStart, lastStart + dayOfMonth * DAY_MS)
        val thisMonth = tThis.spends
        val lastMonth = tLast.spends
        val income = L.rupees(tThis.income)
        sb.appendLine("\nMoney (INR):")
        sb.appendLine("- Spent this month so far: ${money(tThis.spentRupees)} (same days last month: ${money(tSame.spentRupees)}).")
        sb.appendLine("- Spent last month in total: ${money(tLast.spentRupees)}.")
        if (tThis.refunds > 0) sb.appendLine("- Refunds received this month: ${money(L.rupees(tThis.refunds))} (already taken off the spending).")
        if (income > 0) sb.appendLine("- Income recorded this month: ${money(income)}.")
        if (env.family.monthlyBudget > 0) sb.appendLine("- Family monthly budget: ${money(env.family.monthlyBudget)}.")
        val cats = thisMonth.groupBy { it.str("category").ifBlank { "Other" } }.mapValues { (_, l) -> rs(l) }
            .toList().sortedByDescending { it.second }.take(8)
        if (cats.isNotEmpty()) sb.appendLine("- This month by category: " + cats.joinToString(", ") { "${it.first} ${money(it.second)}" } + ".")
        val lastCats = lastMonth.groupBy { it.str("category").ifBlank { "Other" } }.mapValues { (_, l) -> rs(l) }
            .toList().sortedByDescending { it.second }.take(8)
        if (lastCats.isNotEmpty()) sb.appendLine("- Last month by category: " + lastCats.joinToString(", ") { "${it.first} ${money(it.second)}" } + ".")
        val who = thisMonth.groupBy { it.str("memberName").ifBlank { "Someone" } }.mapValues { (_, l) -> rs(l) }
        if (who.size > 1) sb.appendLine("- This month by member: " + who.entries.joinToString(", ") { "${it.key} ${money(it.value)}" } + ".")
        val places = thisMonth.groupBy { it.str("title").trim().lowercase() }
            .map { (_, l) -> l.first().str("title").trim() to rs(l) }
            .filter { it.first.isNotBlank() }.sortedByDescending { it.second }.take(10)
        if (places.isNotEmpty()) sb.appendLine("- Top places this month: " + places.joinToString(", ") { "${it.first} ${money(it.second)}" } + ".")
        val recent = expenses.filter { isSpend(it) && inRange(it, now - 10 * DAY_MS, now) }
            .sortedByDescending { it.lng("dateMillis") ?: 0L }.take(15)
        if (recent.isNotEmpty()) {
            sb.appendLine("- Recent spends: " + recent.joinToString("; ") {
                "${d(it.lng("dateMillis"))} ${it.str("title")} ${money(amountOf(it))} (${it.str("category").ifBlank { "Other" }})"
            } + ".")
        }

        val cards = env.data["items"].orEmpty().filter { it.str("type") == "card" }
        cards.forEach { c ->
            val cy = CardCycle.of(c, now) ?: return@forEach
            val billed = rs(cardSpends(expenses, c, cy.billedStart, cy.billedEnd))
            val current = rs(cardSpends(expenses, c, cy.currentStart, cy.currentEnd))
            val limit = c.num("limitInr")
            sb.appendLine(
                "- Card ${c.str("title")}: last bill ${money(billed)} due ${d(cy.billDue)}; this cycle so far ${money(current)}" +
                    (if (limit != null && limit > 0) " of ${money(limit)} limit" else "") + "."
            )
        }
        return sb.toString().take(12_000)
    }

    fun systemPrompt(env: AppEnv, context: String): String = """
You are Family Hub's assistant, a friendly and practical helper for an Indian family's daily life: calendar, tasks, shopping, bills, budgets, spending and planning.
Answer in the language the user writes in (English, Hindi or Hinglish). Be short and clear: a few sentences or a short list. Use ₹ and Indian number style.
Only use the family data below; if something is not in it, say you don't see it in Family Hub. Never invent amounts or dates.
You do not have the family's health records, medicines, period data, ID numbers (Aadhaar, PAN, passport and so on) or private notes. If asked about these, say they are kept private and are not shared with the assistant, and point to the Health or Profile screen. Do not give medical diagnoses or investment advice; suggest a doctor or a SEBI-registered adviser for those.

When the user asks you to add, log, remind, plan or note something (typed or spoken, often informal Hinglish), turn it into actions so the app can add them after the user confirms. Today is the first line of the data; resolve "today", "yesterday", "kal", "tomorrow", weekdays and "the 1st" to real dates.
Always reply with a JSON object only, in this form:
{"reply": "one short sentence", "actions": [{"kind": "expense|income|bill|shopping|task|event|reminder|note", "title": "short title", "date": "YYYY-MM-DD or empty", "time": "HH:MM 24-hour or empty", "amount": 0, "category": "", "method": "UPI|Cash|Debit card|Credit card|Net banking|Wallet or empty", "account": "bank or card used, e.g. HDFC Bank, or empty", "payee": "person or shop paid (or who paid, for income), or empty", "member": "family member it is for or assigned to, or empty", "repeat": "None|Daily|Weekly|Monthly|Yearly", "private": false}]}
Rules: one action per item (three shopping items = three actions). Money spent is "expense", money received is "income", an unpaid bill with a due date is "bill". Put the amount in rupees as a number. For expense titles use what it was for and who was paid, e.g. "Travel – Rajesh". Category is one of: Groceries, Rent, Utilities, Education, Health, Transport, Travel, Fuel, Dining, Shopping, Entertainment, EMI, Insurance, Salary, Other. Set "private": true only if the user says only me, private or personal.
Example: "add the Rs. 40 in expense of travel today from HDFC bank via UPI to Rajesh" -> {"kind":"expense","title":"Travel – Rajesh","amount":40,"category":"Travel","date":"<today>","method":"UPI","account":"HDFC Bank","payee":"Rajesh"}.
You can also help the user do things in other apps with kind "open". The app shows a button that opens the official app or site with the details filled in; the user books, pays or sends there (you never book, pay, apply or send by yourself). Fields for "open": "service" (one of uber, ola, rapido, maps, call, whatsapp, sms, upi, linkedin, naukri, indeed, amazon, flipkart, blinkit, bigbasket, zepto, swiggy, zomato, flights, trains, hotels, google, youtube), "title", "query" (search words or message text), "to" (destination, city or job location), "pickup" (start place, empty means current location), "payee" (person to call, message or pay), "phone", "upi" (UPI ID like name@okhdfcbank, only if the user gave it), "amount", "date" (YYYY-MM-DD).
Examples: "book a cab to the airport" -> {"kind":"open","service":"uber","title":"Cab to airport","to":"Airport"}; "find Java developer jobs in Gurugram" -> {"kind":"open","service":"linkedin","query":"Java developer","to":"Gurugram"}; "call mom" -> {"kind":"open","service":"call","payee":"Mom"}; "pay 500 to rahul@okaxis for dinner" -> {"kind":"open","service":"upi","upi":"rahul@okaxis","payee":"Rahul","amount":500,"query":"Dinner"}; "order milk from Blinkit" -> {"kind":"open","service":"blinkit","query":"milk"}. For a job application, you may also write a short cover note in "reply", but say the user applies on the site.
Use "actions": [] for questions. Never ask follow-up questions for small missing details; leave them empty.
Nothing is added, booked, searched or sent until the user taps a button, so the "reply" must never say it is done (no "I have added", "I have booked", "I have searched"). Say what the buttons will do instead, e.g. "Tap Add to save the expense" or "Tap Open to book your Uber and see the jobs on LinkedIn."

Family Hub data:
$context
""".trimIndent()
}
