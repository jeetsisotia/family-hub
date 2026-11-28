// Phase 3 logic: savings goals, budget plan, spending insights, report files (run by the Unit tests workflow).
import com.familyhub.app.money.Challenges
import com.familyhub.app.money.BudgetPlan
import com.familyhub.app.money.Goals
import com.familyhub.app.money.Intel
import com.familyhub.app.money.Reports
import java.util.Calendar
import java.util.TimeZone

var failures = 0
fun check(name: String, cond: Boolean, detail: Any? = null) { if (cond) println("PASS $name") else { failures++; println("FAIL $name ${detail ?: ""}") } }

fun main() {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
    fun at(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, 12, 0, 0) }.timeInMillis
    val now = at(2026, 11, 27)
    // ---------- goals ----------
    val g = mapOf<String, Any?>("title" to "Holiday", "amountInr" to 50000.0, "savedInr" to 40000.0, "targetMillis" to at(2027, 3, 27),
        "contributions" to listOf(mapOf("at" to at(2026, 10, 1), "by" to "Asha", "amount" to 25000.0), mapOf("at" to at(2026, 11, 1), "by" to "Ravi", "amount" to 15000.0)))
    check("goals: progress and remaining", Goals.progress(g) == 0.8 && Goals.remaining(g) == 10000.0)
    check("goals: by member", Goals.byMember(g) == listOf("Asha" to 25000.0, "Ravi" to 15000.0))
    val f = Goals.contributionFields(g, Goals.Contribution(now, "Asha", 10000.0, ""))
    check("goals: reaching the target marks it done once", f["savedInr"] == 50000.0 && f["done"] == true && f["completedAt"] == now)
    check("goals: contribution history keeps the new one first", (f["contributions"] as List<*>).size == 3)
    check("goals: withdrawal never goes below zero", Goals.contributionFields(g, Goals.Contribution(now, "Ravi", -90000.0, ""))["savedInr"] == 0.0)
    check("goals: monthly needed by the target date", Goals.monthlyNeeded(g, now)!!.let { it > 2400 && it < 2600 }, Goals.monthlyNeeded(g, now))

    // ---------- budget ----------
    fun sp(id: String, cat: String, amt: Double, day: Long, uid: String = "u1", extra: Map<String, Any?> = emptyMap()) =
        mapOf<String, Any?>("id" to id, "type" to "EXPENSE", "title" to cat, "category" to cat, "amountInr" to amt, "dateMillis" to day, "memberUid" to uid, "memberName" to if (uid == "u1") "Asha" else "Ravi") + extra
    val ex = listOf(
        sp("a", "Dining", 2500.0, at(2026, 11, 3)), sp("b", "Dining", 1500.0, at(2026, 11, 20), "u2"),
        sp("c", "Groceries", 6000.0, at(2026, 11, 5)), sp("d", "Shopping", 900.0, at(2026, 11, 9), extra = mapOf("refundAmount" to 400.0)),
        sp("e", "Dining", 1000.0, at(2026, 10, 10)), sp("f", "Groceries", 7000.0, at(2026, 10, 12)),
        mapOf("id" to "t", "type" to "TRANSFER", "amountInr" to 9000.0, "dateMillis" to at(2026, 11, 2))
    )
    val items = listOf(
        mapOf("type" to "budget_limit", "category" to "Dining", "amountInr" to 3000.0, "period" to "Monthly"),
        mapOf("type" to "budget_limit", "category" to "Groceries", "amountInr" to 90000.0, "period" to "Yearly"),
        mapOf("type" to "budget_limit", "category" to "Shopping", "amountInr" to 2000.0, "period" to "Monthly", "_private" to true)
    )
    val lim = BudgetPlan.limits(items)
    val lines = BudgetPlan.lines(lim, ex, BudgetPlan.monthStart(now, 0), now, "u1").associateBy { it.category }
    check("budget: family Dining over its limit (both members count)", lines["Dining"]!!.actual == 4000.0 && lines["Dining"]!!.state == BudgetPlan.State.OVER)
    check("budget: yearly limit counts one twelfth a month", lines["Groceries"]!!.planned == 7500.0 && lines["Groceries"]!!.state == BudgetPlan.State.NEAR)
    check("budget: Only me limit counts only my spending, refunds taken off", lines["Shopping"]!!.mine && lines["Shopping"]!!.actual == 500.0 && lines["Shopping"]!!.state == BudgetPlan.State.OK)
    val mom = BudgetPlan.monthOnMonth(ex, now)
    check("budget: month on month, Dining up 3,000", mom.first().category == "Dining" && mom.first().diff == 3000.0)
    check("budget: transfers never count", BudgetPlan.trend(ex, now, 2).last().second == 10500.0, BudgetPlan.trend(ex, now, 2))

    // ---------- insights ----------
    val hist = (1..6).map { sp("h$it", "Fuel", 800.0, at(2026, 9, it * 5)) } + sp("big", "Fuel", 6000.0, at(2026, 11, 15)) + sp("ok", "Fuel", 900.0, at(2026, 11, 16))
    val un = Intel.unusual(hist, now)
    check("insights: an unusually large spend is flagged", un.map { it.rec["id"] } == listOf("big"))
    check("insights: small categories without history are not flagged", Intel.unusual(ex, now).isEmpty())
    val sug = Intel.suggestions(lines.values.toList(), mom, 1200.0, un)
    check("insights: suggestions mention the over-limit category, the increase, repeating payments and the unusual spend", sug.size == 4 && sug[0].startsWith("Dining is ₹1,000 over"), sug)

    // ---------- reports ----------
    val csv = Reports.monthlyCsv(ex, BudgetPlan.monthStart(now, 0), now)
    check("reports: monthly CSV has every spend and the total, not transfers", csv.lines().size == 6 && csv.lines().last() == "Total,,,10500.00,," && "TRANSFER" !in csv, csv)
    check("reports: category CSV shares add to 100", Reports.categoryCsv(ex, BudgetPlan.monthStart(now, 0), now).lines().drop(1).sumOf { it.split(",")[2].toDouble() }.let { it > 99.9 && it < 100.1 })
    check("reports: member CSV", Reports.memberCsv(ex, BudgetPlan.monthStart(now, 0), now).lines()[1].startsWith("Asha,9000.00"))
    check("reports: commas are quoted", Reports.goalsCsv(listOf(g + ("title" to "Car, new"))).contains("\"Car, new\""))
    check("reports: budget CSV", Reports.budgetCsv(lines.values.toList()).lines().size == 4)

    // ---------- savings challenges (28 Nov) ----------
    val start = at(2026, 11, 2)   // a Monday
    val w52 = mapOf<String, Any?>("kind" to Challenges.WEEK52, "stepInr" to 10.0, "startMillis" to start, "savedInr" to 0.0)
    check("challenge: 52-week total is 1,378 × the base", Challenges.target(w52) == 13_780.0, Challenges.target(w52))
    check("challenge: week 1 saves the base, week 3 saves 3 ×", Challenges.stepAmount(w52, 1) == 10.0 && Challenges.stepAmount(w52, 3) == 30.0)
    check("challenge: week number from the start date", Challenges.step(w52, at(2026, 11, 2)) == 1 && Challenges.step(w52, at(2026, 11, 9)) == 2 && Challenges.step(w52, at(2026, 11, 27)) == 4, Challenges.step(w52, at(2026, 11, 27)))
    check("challenge: due so far after 4 weeks is 10+20+30+40", Challenges.dueSoFar(w52, at(2026, 11, 27)) == 100.0)
    check("challenge: behind plan when nothing saved", Challenges.versusPlan(w52, at(2026, 11, 27)) == -100.0)
    check("challenge: not started yet → step 0", Challenges.step(w52, at(2026, 10, 30)) == 0)
    val saved = Challenges.saveFields(w52, at(2026, 11, 27), "Jeet", 100.0, "Weeks 1-4")
    check("challenge: saving adds to the total and keeps the history", saved["savedInr"] == 100.0 && (saved["contributions"] as List<*>).size == 1 && saved["amountInr"] == 13_780.0)
    val daily = mapOf<String, Any?>("kind" to Challenges.DAILY, "stepInr" to 50.0, "length" to 30.0, "startMillis" to start, "savedInr" to 300.0)
    check("challenge: daily total and day number", Challenges.target(daily) == 1500.0 && Challenges.step(daily, at(2026, 11, 7)) == 6)
    check("challenge: daily on track", Challenges.versusPlan(daily, at(2026, 11, 7)) == 0.0)
    check("challenge: finished after its length", Challenges.finished(daily, at(2026, 12, 2)) && !Challenges.finished(daily, at(2026, 11, 30)))
    val ns = mapOf<String, Any?>("kind" to Challenges.NO_SPEND, "length" to 10.0, "startMillis" to start)
    val spent = setOf(at(2026, 11, 3), at(2026, 11, 6)).map(Challenges::startOfDay).toSet()
    val r = Challenges.noSpend(ns, spent, at(2026, 11, 8))
    check("no-spend: counts clean days, the current run and spent days", r.days == 5 && r.streak == 2 && r.spentDays == 2 && r.goal == 10, r)
    check("no-spend: stops at its last day", Challenges.noSpend(ns, emptySet(), at(2026, 12, 25)).days == 10)
    check("no-spend: before the start nothing counts", Challenges.noSpend(ns, emptySet(), at(2026, 10, 1)).days == 0)
    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
}
