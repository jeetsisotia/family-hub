// Phase 4 logic: planner questions, reminder suggestions, shopping lists by voice or text (run by the Unit tests workflow).
import com.familyhub.app.family.Planner
import com.familyhub.app.family.Planner.Ask
import java.util.Calendar
import java.util.TimeZone

var failures = 0
fun check(name: String, cond: Boolean, detail: Any? = null) { if (cond) println("PASS $name") else { failures++; println("FAIL $name ${detail ?: ""}") } }

fun main() {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
    fun at(y: Int, m: Int, d: Int, h: Int = 12) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0, 0) }.timeInMillis
    val qs = mapOf(
        "What do we have scheduled this weekend?" to Ask.SCHEDULE, "What's planned for tomorrow" to Ask.SCHEDULE, "kal ka schedule kya hai" to Ask.SCHEDULE,
        "Show our upcoming bills" to Ask.BILLS, "How much is pending in upcoming bills?" to Ask.BILLS,
        "Summarise this week's family activities" to Ask.WEEK_SUMMARY, "Which category increased compared to last month?" to Ask.CATEGORY_UP,
        "Show recurring expenses" to Ask.RECURRING, "Suggest reminders" to Ask.SUGGEST_REMINDERS,
        "Create a shopping list for tomorrow: milk, eggs and bread" to Ask.SHOPPING_LIST, "Weekly family brief" to Ask.WEEKLY_BRIEF
    )
    qs.forEach { (q, a) -> check("planner: '$q'", Planner.classify(q) == a, Planner.classify(q)) }
    check("planner: other questions are left to the assistant", Planner.classify("How much did we spend on groceries this month?") == null && Planner.classify("Write an email to my landlord") == null)
    check("planner: 'Remind me about the car service next month' is an add, not a planner question", Planner.classify("Remind me about the car service next month") == null)

    val fri = at(2026, 11, 27) // a Friday
    val wk = Planner.range("this weekend", fri)
    check("planner: weekend is Saturday and Sunday", wk.first == at(2026, 11, 28, 0) && wk.second == at(2026, 11, 30, 0) - 1, wk)
    check("planner: tomorrow", Planner.range("tomorrow", fri).first == at(2026, 11, 28, 0))
    check("planner: on a Sunday, 'this weekend' is just today", Planner.range("this weekend", at(2026, 11, 29)).let { it.first == at(2026, 11, 29, 0) && it.second == at(2026, 11, 30, 0) - 1 })

    val day = 86_400_000L
    val items = listOf(
        mapOf("type" to "document", "title" to "Passport", "expiryDateMillis" to fri + 20 * day),
        mapOf("type" to "document", "title" to "Licence", "expiryDateMillis" to fri + 20 * day, "renewalStatus" to "Renewed"),
        mapOf("type" to "vehicle", "title" to "Honda City", "serviceDueMillis" to fri + 10 * day, "insuranceExpiryMillis" to fri + 300 * day),
        mapOf("type" to "subscription", "title" to "Netflix", "renewalMillis" to fri + 4 * day),
        mapOf("type" to "subscription", "title" to "Gym", "renewalMillis" to fri + 4 * day, "status" to "Paused"),
        mapOf("type" to "reminder", "title" to "Honda City service", "done" to false)
    )
    val ev = listOf(mapOf("title" to "PTM", "startTimeMillis" to fri + 2 * day))
    val tasks = listOf(mapOf("type" to "TASK", "title" to "Pay school fees", "dueDateMillis" to fri + 5 * day, "isCompleted" to false))
    val sug = Planner.suggestReminders(items, ev, tasks, fri)
    val titles = sug.map { it.title }
    check("reminders: passport renewal suggested ahead of time", sug.any { it.title == "Renew Passport" && it.dateMillis < fri + 20 * day })
    check("reminders: renewed documents are not suggested", "Renew Licence" !in titles)
    check("reminders: already has a reminder (car service) is skipped", "Honda City service" !in titles)
    check("reminders: far-away dates (insurance in 300 days) wait", "Honda City insurance" !in titles)
    check("reminders: paused subscriptions skipped, active one suggested", "Gym renews" !in titles && "Netflix renews" in titles)
    check("reminders: events and tasks due soon", "PTM" in titles && "Pay school fees" in titles)
    check("reminders: soonest first, never in the past", sug.zipWithNext().all { (a, b) -> a.dateMillis <= b.dateMillis } && sug.all { it.dateMillis >= fri })

    val sl = Planner.shoppingList("Create a shopping list for tomorrow: milk, eggs and bread")
    check("shopping: list name and items", sl?.listName == "Tomorrow" && sl.items == listOf("milk", "eggs", "bread"), sl)
    check("shopping: without a name", Planner.shoppingList("make a shopping list with rice, dal")?.let { it.listName == "Shopping" && it.items.size == 2 } == true)
    check("shopping: no items means the assistant asks the AI", Planner.shoppingList("create a shopping list for the party") == null)

    // ---------- spending on one category ----------
    check("spend: topic from 'How much did we spend on groceries this month?'", Planner.spendTopic("How much did we spend on groceries this month?") == "groceries", Planner.spendTopic("How much did we spend on groceries this month?"))
    check("spend: topic without a month", Planner.spendTopic("how much have I spent on fuel?") == "fuel")
    check("spend: no topic for a plain total", Planner.spendTopic("How much did we spend this month?") == null)
    check("spend: no topic for 'on it'", Planner.spendTopic("how much did we spend on it") == null)
    val cats = listOf("Groceries", "Dining", "Fuel", "Health & Medical", "Shopping")
    check("spend: grocery → Groceries", Planner.matchCategory("grocery", cats) == "Groceries")
    check("spend: groceries → Groceries", Planner.matchCategory("groceries", cats) == "Groceries")
    check("spend: medical → Health & Medical", Planner.matchCategory("medical", cats) == "Health & Medical")
    check("spend: unknown → null (then searched as a payee)", Planner.matchCategory("mukul", cats) == null)
    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
}
