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

    // ---------- AI spending by category: real matching rules (27 Nov phone report: "grocery" was not found) ----------
    val topics = mapOf(
        "How much did we spend on grocery this month?" to "grocery",
        "How much i spend on transport this month?" to "transport",
        "How much did we spend on food shopping last month?" to "food shopping",
        "grocery pe kitna kharcha hua is mahine" to "grocery",
        "is mahine grocery pe kitna kharcha hua" to "grocery",
        "maine petrol par kitna kharch kiya" to "petrol",
        "grocery spending this month" to "grocery",
        "how much did we spend on supermarket in november" to "supermarket"
    )
    topics.forEach { (q, t) -> check("topic: '$q'", Planner.spendTopic(q) == t, Planner.spendTopic(q)) }
    listOf("How much did we spend this month?", "what are our total expenses this month", "how much have I spent this month", "kitna kharcha hua is mahine")
        .forEach { q -> check("topic: none in '$q' (overall total is right)", Planner.spendTopic(q) == null, Planner.spendTopic(q)) }

    fun C(n: String) = Planner.CatRef("category", n)
    fun S(n: String, p: String) = Planner.CatRef("subcategory", n, p)
    val stored = listOf(C("Groceries"), C("Transport"), C("Shopping"), C("Dining"), C("Travel"), C("Fuel"))
    fun one(t: String, refs: List<Planner.CatRef>) = Planner.findCategories(t, refs).map { it.label }
    listOf("grocery", "groceries", "Grocery", "food shopping", "supermarket", "kirana", "sabzi").forEach { t ->
        check("category: '$t' → Groceries", one(t, stored) == listOf("Groceries"), one(t, stored))
    }
    check("category: transport unchanged", one("transport", stored) == listOf("Transport"))
    check("category: 'cab' → Transport", one("cab", stored) == listOf("Transport"))
    check("category: 'shopping' is Shopping, not Groceries", one("shopping", stored) == listOf("Shopping"))
    check("category: 'travel' is Travel, not Transport", one("travel", stored) == listOf("Travel"))
    check("category: 'petrol' → Fuel", one("petrol", stored) == listOf("Fuel"))
    check("category: 'food' joins nothing (Dining and Groceries stay apart)", one("food", stored).isEmpty(), one("food", stored))
    check("category: synonyms never invent a category the family does not use", one("supermarket", listOf(C("Transport"), C("Dining"))).isEmpty())
    val withSub = listOf(C("Food"), S("Grocery", "Food"), C("Transport"))
    check("category: a subcategory is found (Food › Grocery)", one("groceries", withSub) == listOf("Food › Grocery"), one("groceries", withSub))
    check("category: subcategory inside a matching category is not counted twice", one("groceries", listOf(C("Groceries"), S("Grocery", "Groceries"))) == listOf("Groceries"))
    check("category: two real matches → ask which one", one("grocery", listOf(C("Groceries"), S("Grocery", "Food"))).size == 2)
    check("category: 'cab' with Transport and Cab both used → ask", one("cab", listOf(C("Transport"), C("Cab"))).size == 2)
    check("category: 'Grocery & Household' found from either word", one("household", listOf(C("Grocery & Household"))) == listOf("Grocery & Household"))

    // ---------- P0 (27 Nov 20:33): typing slips and periods; money questions must never need the AI ----------
    check("typo: 'apend' → spend", Planner.fixTypos("how much did we apend on grocery this month") == "how much did we spend on grocery this month", Planner.fixTypos("how much did we apend on grocery this month"))
    check("typo: topic found despite 'apend'", Planner.spendTopic("How much did we apend on grocery this month?") == "grocery")
    listOf("spnd", "sepnd", "spemd", "soend").forEach { w -> check("typo: '$w' → spend", Planner.fixTypos("how much did i $w on transport") == "how much did i spend on transport", Planner.fixTypos("how much did i $w on transport")) }
    check("typo: 'expence' → expense", Planner.fixTypos("grocery expence this month").startsWith("grocery expense"))
    check("typo: 'grocey' → grocery, 'mnth' → month", Planner.spendTopic("how much did we spend on grocey this mnth") == "grocery", Planner.spendTopic("how much did we spend on grocey this mnth"))
    check("typo: real words are left alone (send, speed, attend, append)", Planner.fixTypos("send speed attend append") == "send speed attend append")
    check("topic: 'How much did I spend on transport this month?'", Planner.spendTopic("How much did I spend on transport this month?") == "transport")
    check("topic: 'grocery pe kitna kharcha hua is mahine?'", Planner.spendTopic("grocery pe kitna kharcha hua is mahine?") == "grocery")
    check("topic: weekly question", Planner.spendTopic("how much did we spend on grocery this week") == "grocery")
    check("topic: Hinglish weekly question", Planner.spendTopic("grocery pe kitna kharcha hua is hafte") == "grocery", Planner.spendTopic("grocery pe kitna kharcha hua is hafte"))
    check("period: this month by default", Planner.spendPeriod("how much did we spend on grocery") == Planner.Period.THIS_MONTH)
    check("period: last month / pichle mahine", Planner.spendPeriod("grocery last month") == Planner.Period.LAST_MONTH && Planner.spendPeriod("pichle mahine grocery") == Planner.Period.LAST_MONTH)
    check("period: this week / is hafte", Planner.spendPeriod("spend this week") == Planner.Period.THIS_WEEK && Planner.spendPeriod("is hafte kitna") == Planner.Period.THIS_WEEK)
    check("period: last week", Planner.spendPeriod("how much last week") == Planner.Period.LAST_WEEK)
    val friNov = at(2026, 11, 27)
    val wkR = Planner.periodRange(Planner.Period.THIS_WEEK, friNov)
    check("range: this week is Monday 23 Nov to Sunday 29 Nov", wkR.first == at(2026, 11, 23, 0) && wkR.second == at(2026, 11, 30, 0) - 1, wkR)
    check("range: last week", Planner.periodRange(Planner.Period.LAST_WEEK, friNov).first == at(2026, 11, 16, 0))
    check("range: this month and the month before", Planner.periodRange(Planner.Period.THIS_MONTH, friNov).first == at(2026, 11, 1, 0) &&
        Planner.periodRange(Planner.Period.THIS_MONTH, friNov, back = 1).first == at(2026, 10, 1, 0) && Planner.periodRange(Planner.Period.LAST_MONTH, friNov, back = 1).first == at(2026, 9, 1, 0))
    check("range: a Sunday belongs to the week that started on Monday", Planner.periodRange(Planner.Period.THIS_WEEK, at(2026, 11, 29)).first == at(2026, 11, 23, 0))
    check("planner: spend questions are not schedule questions", Planner.classify("how much did we spend this week") == null && Planner.classify("How much did we apend on grocery this month") == null)
    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
}
