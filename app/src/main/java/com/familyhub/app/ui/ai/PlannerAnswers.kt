package com.familyhub.app.ui.ai

import android.content.Context
import com.familyhub.app.family.CalendarMath
import com.familyhub.app.family.DocExpiry
import com.familyhub.app.family.Planner
import com.familyhub.app.family.Subscriptions
import com.familyhub.app.family.Timeline
import com.familyhub.app.money.BudgetPlan
import com.familyhub.app.money.Ledger
import com.familyhub.app.money.RecurringFinder
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.money.inr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The family planner and expense questions, answered on the phone from the data this person can already see (no AI
 * call, nothing sent). Anything that changes data comes back as cards to confirm, through the shared action runner.
 */
object PlannerAnswers {
    private val day = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    private val time = SimpleDateFormat("h:mm a", Locale.getDefault())
    private const val DAY = 86_400_000L

    fun handle(text: String, env: AppEnv, now: Long = System.currentTimeMillis()): AppCommands.Result? {
        val ask = Planner.classify(text) ?: return null
        return when (ask) {
            Planner.Ask.SCHEDULE -> schedule(text, env, now)
            Planner.Ask.BILLS -> AppCommands.Result(bills(env, now, 30), emptyList())
            Planner.Ask.WEEK_SUMMARY -> AppCommands.Result(weekSummary(env, now), emptyList())
            Planner.Ask.CATEGORY_UP -> AppCommands.Result(categoryUp(env, now), emptyList())
            Planner.Ask.RECURRING -> AppCommands.Result(recurring(env), emptyList())
            Planner.Ask.SUGGEST_REMINDERS -> reminders(env, now)
            Planner.Ask.SHOPPING_LIST -> shopping(text)
            Planner.Ask.WEEKLY_BRIEF -> AppCommands.Result(brief(env, null, now) + "\n\nChoose what the brief shows in More → Weekly brief.", emptyList())
        }
    }

    private fun schedule(text: String, env: AppEnv, now: Long): AppCommands.Result {
        val (from, to, label) = Planner.range(text, now)
        val birthdays = env.data["items"].orEmpty().filter { it["type"] == "birthday" }.mapNotNull { b ->
            val d = (b["dateMillis"] as? Number ?: b["dobMillis"] as? Number)?.toLong() ?: return@mapNotNull null
            mapOf("title" to "🎂 " + (b["title"] as? String).orEmpty(), "startTimeMillis" to d, "recurrence" to "YEARLY")
        }
        val occ = CalendarMath.occurrences(env.data["events"].orEmpty() + birthdays, from, to)
        val tasks = env.data["tasks"].orEmpty().filter { it["type"] == "TASK" && it["isCompleted"] != true && ((it["dueDateMillis"] as? Number)?.toLong() ?: -1L) in from..to }
        if (occ.isEmpty() && tasks.isEmpty()) return AppCommands.Result("Nothing is planned for $label in Family Hub.", emptyList())
        val clashes = CalendarMath.conflicts(occ)
        val sb = StringBuilder("Planned for $label:\n")
        occ.groupBy { day.format(Date(it.start)) }.forEach { (d, list) ->
            sb.append("\n$d\n")
            list.forEach { o -> sb.append("• ").append(if (o.allDay) "" else time.format(Date(o.start)) + " ").append((o.rec["title"] as? String).orEmpty())
                .append(listOf(o.rec["ownerName"], o.rec["participants"]).mapNotNull { (it as? String)?.takeIf { s -> s.isNotBlank() } }.joinToString(", ").let { if (it.isNotBlank()) " ($it)" else "" }).append('\n') }
        }
        if (tasks.isNotEmpty()) sb.append("\nTasks due: ").append(tasks.joinToString(", ") { (it["title"] as? String).orEmpty() }).append('\n')
        if (clashes.isNotEmpty()) sb.append("\n⚠️ Clash: ").append(clashes.joinToString("; ") { "${it.first.rec["title"]} and ${it.second.rec["title"]}" })
        return AppCommands.Result(sb.toString().trim(), emptyList())
    }

    fun bills(env: AppEnv, now: Long, days: Int): String {
        val dues = Ledger.dues(env.data, now - 60 * DAY, now + days * DAY, now).filter { it.status != "PAID" && (it.snoozedUntil ?: 0L) <= now }.sortedBy { it.dueMillis }
        if (dues.isEmpty()) return "No bills, EMIs or renewals are due in the next $days days."
        val total = dues.sumOf { it.amount }
        val overdue = dues.filter { it.status == "OVERDUE" }
        return "Due in the next $days days: ${dues.size}, about ${inr(total)} in total" + (if (overdue.isNotEmpty()) " (${overdue.size} overdue)" else "") + ".\n" +
            dues.take(12).joinToString("\n") { "• ${day.format(Date(it.dueMillis))}: ${it.title}, ${inr(it.amount)}" + if (it.status == "OVERDUE") " (overdue)" else "" }
    }

    private fun weekSummary(env: AppEnv, now: Long): String {
        val names = env.members.associate { it.uid to it.name }
        val tl = Timeline.build(mapOf("expenses" to env.data["expenses"].orEmpty(), "tasks" to env.data["tasks"].orEmpty(),
            "events" to env.data["events"].orEmpty(), "items" to env.data["items"].orEmpty()), names, now = now).filter { it.atMillis >= now - 7 * DAY }
        if (tl.isEmpty()) return "No shared family activity in the last 7 days."
        val byKind = tl.groupBy { it.kind.label }.map { "${it.value.size} ${it.key.lowercase(Locale.ROOT)}" }
        val byWho = tl.groupBy { it.who }.map { "${it.key} ${it.value.size}" }
        return "This week: ${tl.size} updates (${byKind.joinToString(", ")}). Most active: ${byWho.joinToString(", ")}.\n" +
            tl.take(6).joinToString("\n") { "• ${it.who} ${it.action} ${it.title}" } + "\n\nSee More → Family activity for everything."
    }

    private fun categoryUp(env: AppEnv, now: Long): String {
        val up = BudgetPlan.monthOnMonth(env.data["expenses"].orEmpty(), now).filter { it.diff > 0 }
        if (up.isEmpty()) return "No category is higher than last month so far."
        return "Higher than last month (this month so far against all of last month):\n" +
            up.take(5).joinToString("\n") { "• ${it.category}: ${inr(it.thisMonth)}, up ${inr(it.diff)}" + (it.pct?.let { p -> " (${p.toInt()}%)" } ?: " (new)") }
    }

    private fun recurring(env: AppEnv): String {
        val items = env.data["items"].orEmpty()
        val subs = items.filter { it["type"] == "subscription" && Subscriptions.isLive(it) }
        val t = Subscriptions.totals(items.filter { it["type"] == "subscription" })
        val found = RecurringFinder.find(env.data["expenses"].orEmpty(), subs.map { RecurringFinder.payeeKey((it["title"] as? String).orEmpty()) }.toSet())
        val sb = StringBuilder()
        if (subs.isNotEmpty()) sb.append("Subscriptions: about ${inr(t.monthly)} a month (${inr(t.yearly)} a year).\n")
            .append(subs.joinToString("\n") { "• ${it["title"]}: ${inr((it["amountInr"] as? Number)?.toDouble() ?: 0.0)} ${(it["cycle"] as? String).orEmpty().ifBlank { "Monthly" }.lowercase(Locale.ROOT)}" })
        if (found.isNotEmpty()) sb.append(if (sb.isNotEmpty()) "\n\n" else "").append("These also look recurring in your transactions:\n")
            .append(found.take(5).joinToString("\n") { "• ${it.payee}: about ${inr(it.amount)} ${it.frequency.label.lowercase(Locale.ROOT)}" })
            .append("\nTrack them in Finance → Recurring payments.")
        return sb.toString().ifBlank { "No subscriptions or repeating payments found." }
    }

    private fun reminders(env: AppEnv, now: Long): AppCommands.Result {
        val s = Planner.suggestReminders(env.data["items"].orEmpty(), env.data["events"].orEmpty(), env.data["tasks"].orEmpty(), now)
        if (s.isEmpty()) return AppCommands.Result("Nothing needs a new reminder right now: upcoming documents, services, renewals, events and tasks already have one or are far off.", emptyList())
        return AppCommands.Result("These are coming up and have no reminder yet. Tap Add on the ones you want (saved as \"Only me\" reminders):",
            s.map { AiAction(kind = "reminder", title = it.title, dateMillis = it.dateMillis, time = "09:00", dateText = it.why) })
    }

    private fun shopping(text: String): AppCommands.Result? {
        val ask = Planner.shoppingList(text) ?: return null
        return AppCommands.Result("A \"${ask.listName}\" shopping list with ${ask.items.size} item(s). Tap Add all to save it:",
            ask.items.map { AiAction(kind = "shopping", title = it.replaceFirstChar { c -> c.uppercase() }, value = ask.listName) })
    }

    // ---------- weekly family brief ----------

    fun briefPrefs(ctx: Context) = ctx.getSharedPreferences("weekly_brief", Context.MODE_PRIVATE)
    fun briefSections(ctx: Context?): Set<String> = ctx?.let { briefPrefs(it).getStringSet("sections", null) } ?: Planner.BRIEF_SECTIONS.toSet()

    /** The weekly brief as text, from the person's own data; [sections] null means all. */
    fun brief(env: AppEnv, sections: Set<String>?, now: Long = System.currentTimeMillis()): String {
        val on = sections ?: Planner.BRIEF_SECTIONS.toSet()
        val out = ArrayList<String>()
        val week = now + 7 * DAY
        if ("Events" in on) {
            val occ = CalendarMath.occurrences(env.data["events"].orEmpty(), now, week)
            out += "📅 Next 7 days: " + if (occ.isEmpty()) "nothing planned." else occ.take(6).joinToString("; ") { "${day.format(Date(it.start))} ${it.rec["title"]}" } +
                CalendarMath.conflicts(occ).let { if (it.isNotEmpty()) " (⚠️ ${it.size} clash)" else "" }
        }
        if ("Tasks" in on) {
            val open = env.data["tasks"].orEmpty().filter { it["type"] == "TASK" && it["isCompleted"] != true }
            val shop = env.data["tasks"].orEmpty().count { it["type"] == "SHOPPING" && it["isCompleted"] != true }
            out += "✅ Tasks: ${open.size} open" + open.filter { ((it["dueDateMillis"] as? Number)?.toLong() ?: Long.MAX_VALUE) <= week }.let { if (it.isNotEmpty()) ", due this week: " + it.take(4).joinToString(", ") { t -> t["title"].toString() } else "" } +
                if (shop > 0) " · $shop on the shopping lists" else ""
        }
        if ("Bills" in on) out += "💳 " + bills(env, now, 7).substringBefore('\n')
        if ("Documents" in on) {
            val s = DocExpiry.summary(env.data["items"].orEmpty().filter { it["type"] == "document" }, now)
            out += "📄 Documents: " + if (s.expired + s.dueSoon == 0) "nothing to renew soon." else "${s.expired} expired, ${s.dueSoon} expiring soon" + s.next.firstOrNull()?.let { " (next: ${it.first["title"]})" }.orEmpty()
        }
        if ("Maintenance" in on) {
            val due = env.data["items"].orEmpty().filter { it["type"] == "maintenance" || it["type"] == "vehicle" }.flatMap { a ->
                listOf("nextDueMillis", "serviceDueMillis", "insuranceExpiryMillis", "pucExpiryMillis").mapNotNull { k -> (a[k] as? Number)?.toLong()?.takeIf { it <= now + 30 * DAY }?.let { a["title"] to it } }
            }.sortedBy { it.second }
            out += "🔧 Maintenance: " + if (due.isEmpty()) "nothing due in 30 days." else due.take(3).joinToString("; ") { "${it.first} ${day.format(Date(it.second))}" }
        }
        if ("Activity" in on) out += "👪 " + weekSummary(env, now).substringBefore('\n')
        if ("Money" in on) {
            val t = Ledger.totals(env.data["expenses"].orEmpty(), BudgetPlan.monthStart(now, 0), now)
            val top = t.byCategory.firstOrNull()?.let { " · most on ${it.first} (${inr(Ledger.rupees(it.second))})" }.orEmpty()
            val budget = env.family.monthlyBudget.takeIf { it > 0 }?.let { " of ${inr(it)} budget" }.orEmpty()
            out += "💰 This month: ${inr(t.spentRupees)} spent$budget$top"
        }
        return "Your weekly family brief\n\n" + out.joinToString("\n\n")
    }
}
