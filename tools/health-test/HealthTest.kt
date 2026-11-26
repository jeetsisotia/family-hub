import com.familyhub.app.health.HealthMath
import com.familyhub.app.health.HealthMath.FoodEntry
import com.familyhub.app.health.HealthMath.MedPlan
import java.util.Calendar
import java.util.TimeZone

// Checks for HealthMath (medicine adherence, food diary totals). Made-up data only.

private var failures = 0
private var checks = 0
private fun check(name: String, ok: Boolean, detail: Any? = null) {
    checks++
    if (!ok) { failures++; println("FAIL: $name" + (if (detail != null) " -> $detail" else "")) }
}
private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long = Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi, 0) }.timeInMillis

fun main() {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
    val now = at(2026, 11, 27, 22, 30)
    val twice = listOf(8 to 0, 21 to 0)

    // ---- adherence ----
    run {
        // Started 3 days ago (25 Nov), twice a day: 25, 26, 27 Nov at 08:00 and 21:00 = 6 doses, all due by 22:30.
        val taken = setOf("20261125_0800", "20261125_2100", "20261126_0800", "20261127_0800", "20261127_2100")
        val a = HealthMath.adherence(listOf(MedPlan(twice, at(2026, 11, 25, 12, 0), null, taken)), now)
        check("6 due, 5 taken, 83%", a.scheduled == 6 && a.taken == 5 && a.missed == 1 && a.percent == 83, a)
    }
    run {
        // At 21:30 the 21:00 dose is less than an hour old: not counted yet.
        val a = HealthMath.adherence(listOf(MedPlan(twice, at(2026, 11, 27, 12, 0), null, setOf("20261127_0800"))), at(2026, 11, 27, 21, 30))
        check("a dose under an hour old is not counted as missed", a.scheduled == 1 && a.taken == 1 && a.percent == 100, a)
    }
    run {
        val a = HealthMath.adherence(listOf(MedPlan(twice, at(2026, 1, 1, 12, 0), at(2026, 11, 19, 12, 0), emptySet())), now)
        check("course ended before the last 7 days: nothing due", a.scheduled == 0 && a.percent == null, a)
    }
    run {
        val a = HealthMath.adherence(listOf(MedPlan(listOf(9 to 0), null, null, emptySet())), now)
        check("no start date: 7 days counted, none taken", a.scheduled == 7 && a.percent == 0, a)
    }
    run {
        val a = HealthMath.adherence(listOf(MedPlan(listOf(9 to 0, 9 to 0), at(2026, 11, 27, 12, 0), null, setOf("20261127_0900"))), now)
        check("the same time listed twice counts once", a.scheduled == 1 && a.taken == 1, a)
    }
    run {
        val a = HealthMath.adherence(listOf(
            MedPlan(listOf(9 to 0), at(2026, 11, 27, 12, 0), null, setOf("20261127_0900")),
            MedPlan(listOf(7 to 0), at(2026, 11, 27, 12, 0), null, emptySet())
        ), now)
        check("two medicines add up", a.scheduled == 2 && a.taken == 1 && a.percent == 50, a)
    }
    check("slot key matches the alarm planner", HealthMath.slot(at(2026, 1, 5, 12, 0), 7, 5) == "20260105_0705")

    // ---- food diary ----
    run {
        val e = listOf(
            FoodEntry(at(2026, 11, 27, 12, 0), 450.0), FoodEntry(at(2026, 11, 27, 12, 0), 320.5),
            FoodEntry(at(2026, 11, 27, 12, 0), null),
            FoodEntry(at(2026, 11, 26, 12, 0), 1800.0),
            FoodEntry(at(2026, 11, 19, 12, 0), 5000.0) // 8 days ago: outside the week
        )
        val s = HealthMath.foodSummary(e, now)
        check("today's calories", s.todayKcal == 771, s)
        check("entries without calories today", s.todayWithoutKcal == 1, s)
        check("7-day average over days with calories", s.weekAverageKcal == 1285, s) // (770.5 + 1800) / 2 = 1285.25
    }
    run {
        val s = HealthMath.foodSummary(emptyList(), now)
        check("empty diary", s.todayKcal == 0 && s.weekAverageKcal == null && s.todayWithoutKcal == 0, s)
    }
    run {
        val s = HealthMath.foodSummary(listOf(FoodEntry(at(2026, 11, 27, 12, 0), -50.0)), now)
        check("negative calories ignored", s.todayKcal == 0 && s.weekAverageKcal == null, s)
    }

    // ---- AI request limit ----
    val t = now
    check("ai: first request allowed", com.familyhub.app.core.ai.AiLimiter.check(emptyList(), t) == null)
    check("ai: 8 in a minute blocks", com.familyhub.app.core.ai.AiLimiter.check(List(8) { t - it * 1000L }, t) != null)
    check("ai: 7 in a minute allowed", com.familyhub.app.core.ai.AiLimiter.check(List(7) { t - it * 1000L }, t) == null)
    check("ai: 150 in a day blocks", com.familyhub.app.core.ai.AiLimiter.check(List(150) { t - 2 * 60_000L - it * 60_000L }, t)?.contains("today") == true)
    check("ai: yesterday's do not count", com.familyhub.app.core.ai.AiLimiter.check(List(300) { t - 25 * 3_600_000L }, t) == null)
    run {
        var blocked = 0
        repeat(10) { if (com.familyhub.app.core.ai.AiLimiter.tryAcquire(t + it) != null) blocked++ }
        check("ai: 10 quick requests, the last 2 refused", blocked == 2, blocked)
    }

    // ---- personal AI usage limits (AI settings) ----
    val L = com.familyhub.app.core.ai.AiLimiter
    check("limits: under both", L.usageLimit(5, 50, 100, 1500) == null)
    check("limits: daily reached", L.usageLimit(100, 120, 100, 1500)?.contains("today") == true)
    check("limits: monthly reached first", L.usageLimit(10, 1500, 100, 1500)?.contains("monthly") == true)

    println(if (failures == 0) "ALL PASSED ($checks checks)" else "$failures of $checks FAILED")
    if (failures > 0) kotlin.system.exitProcess(1)
}
