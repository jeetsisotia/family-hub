// Shared-expense splitting (run by the Unit tests workflow).
import com.familyhub.app.money.Split
import com.familyhub.app.money.Split.Expense

var failures = 0
fun check(name: String, cond: Boolean, detail: Any? = null) { if (cond) println("PASS $name") else { failures++; println("FAIL $name ${detail ?: ""}") } }

fun main() {
    val ppl = listOf("Asha", "Ravi", "Dadi")
    val eq = Split.shares(Expense("1", 100.0, "Asha"), ppl)!!
    check("equal split adds up exactly (₹100 / 3)", eq.values.sum().let { kotlin.math.abs(it - 100.0) < 1e-9 } && eq.values.all { it in 33.33..33.34 }, eq)
    check("equal split among chosen people only", Split.shares(Expense("2", 90.0, "Asha", listOf("asha", "ravi")), ppl) == mapOf("Asha" to 45.0, "Ravi" to 45.0))
    check("exact amounts", Split.shares(Expense("3", 800.0, "Ravi", mode = Split.EXACT, detail = "Asha 500, Ravi: 300"), ppl) == mapOf("Asha" to 500.0, "Ravi" to 300.0))
    check("exact amounts that don't add up are refused", Split.shares(Expense("4", 800.0, "Ravi", mode = Split.EXACT, detail = "Asha 500, Ravi 200"), ppl) == null)
    check("percentages", Split.shares(Expense("5", 1000.0, "Asha", mode = Split.PERCENT, detail = "Asha 60%, Ravi 40%"), ppl) == mapOf("Asha" to 600.0, "Ravi" to 400.0))
    check("percentages must total 100", Split.shares(Expense("6", 1000.0, "Asha", mode = Split.PERCENT, detail = "Asha 60, Ravi 30"), ppl) == null)
    check("shares (2:1)", Split.shares(Expense("7", 300.0, "Asha", mode = Split.SHARES, detail = "Asha 2, Ravi 1"), ppl) == mapOf("Asha" to 200.0, "Ravi" to 100.0))
    check("amounts with ₹ and commas", Split.parseDetail("Asha ₹1,200, Ravi=300.50") == mapOf("Asha" to 1200.0, "Ravi" to 300.5))
    check("names: commas and 'and'", Split.names("asha, Ravi and dadi", ppl) == listOf("Asha", "Ravi", "Dadi"))

    val b = Split.balances(listOf(
        Expense("a", 300.0, "Asha"),                           // each owes 100
        Expense("b", 150.0, "Ravi", listOf("Ravi", "Dadi"))    // Dadi owes Ravi 75
    ), ppl)
    check("balances: payer is owed, others owe", b.net == mapOf("Asha" to 200.0, "Ravi" to -25.0, "Dadi" to -175.0), b.net)
    check("balances add to zero", kotlin.math.abs(b.net.values.sum()) < 1e-9 && b.total == 450.0)
    val t = Split.settlements(b.net)
    check("fewest payments settle everyone", t == listOf(Split.Transfer("Dadi", "Asha", 175.0), Split.Transfer("Ravi", "Asha", 25.0)), t)
    val after = Split.balances(listOf(Expense("a", 300.0, "Asha"), Expense("b", 150.0, "Ravi", listOf("Ravi", "Dadi"))) + t.mapIndexed { i, x -> Split.payment("p$i", x) }, ppl)
    check("recording the suggested payments settles everyone", after.net.isEmpty(), after.net)
    val bad = Split.balances(listOf(Expense("x", 100.0, "Asha", mode = Split.EXACT, detail = "Ravi 10")), ppl)
    check("an expense that cannot be split is skipped and reported", bad.skipped == listOf("x") && bad.net.isEmpty())
    check("no payer, skipped", Split.balances(listOf(Expense("y", 50.0, "")), ppl).skipped == listOf("y"))
    val many = Split.balances((1..7).map { Expense("m$it", 10.0 * it, ppl[it % 3]) }, ppl)
    check("settlements never exceed people − 1", Split.settlements(many.net).size <= 2)
    println(if (failures == 0) "ALL PASSED" else "FAILED: $failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
