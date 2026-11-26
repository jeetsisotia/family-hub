var fails = 0; var passes = 0
fun check(name: String, ok: Boolean) { if (ok) { passes++; println("PASS $name") } else { fails++; println("FAIL $name") } }
/** Same order as MoneyAnswers.answer: which answer a question gets. */
fun route(t: String): String {
    val s = t.lowercase().trim()
    R.THEME.find(s)?.let { return "theme:" + it.groupValues[1].ifBlank { it.groupValues[2] } }
    R.ADD_CAT.find(s)?.let { return "addcat:" + it.groupValues[6].trim() + "|" + it.groupValues[7].trim() }
    R.SET_BUDGET.find(s)?.let { return "budget:" + it.groupValues[3] + it.groupValues[4] }
    if (R.ADDING.containsMatchIn(s)) return "assistant"
    return when {
        R.Q_UNCAT.containsMatchIn(s) -> "uncat"
        R.Q_TOP.containsMatchIn(s) -> "top"
        R.Q_FAMILY.containsMatchIn(s) -> "family"
        R.Q_EMI.containsMatchIn(s) -> "emi"
        R.Q_CARD.containsMatchIn(s) -> "card"
        R.Q_DUE.containsMatchIn(s) -> "due"
        R.Q_BALANCE.containsMatchIn(s) -> "balance"
        R.Q_SAVE.containsMatchIn(s) && !R.Q_SPEND.containsMatchIn(s) -> "save"
        R.Q_SPEND.containsMatchIn(s) -> "spend"
        else -> "assistant"
    }
}
fun main() {
    val cases = listOf(
        "How much did I spend this month?" to "spend",
        "What is my highest spending category?" to "top",
        "How much EMI is pending?" to "emi",
        "Which credit card bill is due next?" to "card",
        "What is my monthly savings?" to "save",
        "Show family spending" to "family",
        "Categorize pending transactions" to "uncat",
        "What's due this week?" to "due",
        "What is my bank balance?" to "balance",
        "How much did we spend last month?" to "spend",
        "Add a category called Pets" to "addcat:pets|",
        "add subcategory Vet under Pets" to "addcat:vet|pets",
        "Set the family budget to 80k" to "budget:80k",
        "change monthly budget to rs 1,20,000" to "budget:1,20,000",
        "Add expense 500 for groceries" to "assistant",
        "Remind me to pay rent on 5th" to "assistant",
        "log a card payment of 12000" to "assistant",
        "What's the weather?" to "assistant",
        "Switch to dark mode" to "theme:dark",
        "please change the app to light theme" to "theme:light",
        "set theme to system" to "theme:system",
        "Add expense 300 for dark chocolate" to "assistant"
    )
    for ((q, want) in cases) { val got = route(q); check("\"$q\" -> $want" + if (got != want) " (got $got)" else "", got == want) }
    println(if (fails == 0) "ALL PASSED ($passes)" else "$fails FAILED")
}
