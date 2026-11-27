// Phase 1 logic: family activity timeline, document expiry, recurring payment suggestions (run by the Unit tests workflow).
import com.familyhub.app.family.DocExpiry
import com.familyhub.app.family.Timeline
import com.familyhub.app.money.RecurringFinder

var failures = 0
fun check(name: String, cond: Boolean, detail: Any? = null) { if (cond) println("PASS $name") else { failures++; println("FAIL $name ${detail ?: ""}") } }
const val DAY = 86_400_000L

fun main() {
    val t0 = 1_780_000_000_000L
    // ---------- timeline ----------
    val data = mapOf(
        "expenses" to listOf(
            mapOf("id" to "e1", "type" to "EXPENSE", "title" to "Groceries", "amountInr" to 1200.0, "category" to "Groceries", "createdAt" to t0, "memberName" to "Asha", "memberUid" to "a"),
            mapOf("id" to "e2", "type" to "EXPENSE", "title" to "Secret gift", "amountInr" to 5000.0, "createdAt" to t0 + 10, "_private" to true),
            mapOf("id" to "e3", "type" to "EXPENSE", "title" to "Fuel", "amountInr" to 900.0, "createdAt" to t0 - DAY, "updatedAt" to t0 + 3600_000, "createdByUid" to "r", "updatedByUid" to "a")
        ),
        "tasks" to listOf(
            mapOf("id" to "t1", "type" to "TASK", "title" to "Car service", "createdAt" to t0 - 2 * DAY, "createdByName" to "Ravi", "isCompleted" to true, "completedAt" to t0 + 7200_000, "completedByName" to "Asha"),
            mapOf("id" to "t2", "type" to "TASK", "title" to "Private task", "createdAt" to t0, "visibility" to "Only me")
        ),
        "items" to listOf(
            mapOf("id" to "d1", "type" to "document", "title" to "Passport", "category" to "Passport", "number" to "J1234567", "notes" to "locker", "createdAtMillis" to t0, "createdByName" to "Asha"),
            mapOf("id" to "m1", "type" to "medicine", "title" to "Thyroxine", "createdAt" to t0),
            mapOf("id" to "r1", "type" to "reminder", "title" to "Pay school fees", "createdAt" to t0 - DAY, "done" to true, "completedAt" to t0, "completedByUid" to "r")
        ),
        "events" to listOf(mapOf("id" to "ev1", "title" to "PTM", "startTimeMillis" to t0)) // no known time: left out
    )
    val names = mapOf("a" to "Asha", "r" to "Ravi")
    val tl = Timeline.build(data, names)
    check("timeline: newest first", tl.zipWithNext().all { (x, y) -> x.atMillis >= y.atMillis })
    check("timeline: Only me and private entries never shown", tl.none { it.title == "Secret gift" || it.title == "Private task" })
    check("timeline: health records never shown", tl.none { it.title == "Thyroxine" })
    check("timeline: documents show name and type only (no number or notes)", tl.filter { it.kind == Timeline.Kind.DOCUMENT }.all { "J1234567" !in it.detail && "locker" !in it.detail && it.detail == "Passport" })
    check("timeline: names from uid when only the uid is saved", tl.any { it.title == "Fuel" && it.action == "updated" && it.who == "Asha" } && tl.any { it.title == "Fuel" && it.action == "added" && it.who == "Ravi" })
    check("timeline: completed tasks and reminders with who did it", tl.any { it.title == "Car service" && it.action == "completed" && it.who == "Asha" } && tl.any { it.title == "Pay school fees" && it.action == "completed" && it.who == "Ravi" })
    check("timeline: entries without a time are left out (not guessed)", tl.none { it.title == "PTM" })
    check("timeline: money detail has amount and category", tl.first { it.title == "Groceries" }.detail == "₹1,200 · Groceries")
    check("timeline: filter by type", Timeline.filter(tl, Timeline.Kind.TASK, null).all { it.kind == Timeline.Kind.TASK } && Timeline.filter(tl, Timeline.Kind.TASK, null).isNotEmpty())
    check("timeline: filter by member", Timeline.filter(tl, null, "Ravi").all { it.who == "Ravi" } && Timeline.filter(tl, null, "Ravi").size == 3)
    check("timeline: sentence", tl.first { it.title == "Groceries" }.sentence == "Asha added a money entry: Groceries")

    // ---------- document expiry ----------
    val now = t0
    fun doc(days: Int, extra: Map<String, Any?> = emptyMap()) = mapOf<String, Any?>("type" to "document", "title" to "D$days", "expiryDateMillis" to now + days * DAY) + extra
    check("docs: past expiry is Expired", DocExpiry.status(doc(-1), now) == DocExpiry.Status.EXPIRED)
    check("docs: within 30 days is Expiring soon", DocExpiry.status(doc(20), now) == DocExpiry.Status.DUE_SOON)
    check("docs: 45 days is Active by default", DocExpiry.status(doc(45), now) == DocExpiry.Status.ACTIVE)
    check("docs: own window (60 days before) makes 45 days Expiring soon", DocExpiry.status(doc(45, mapOf("remindBefore" to "60 days before")), now) == DocExpiry.Status.DUE_SOON)
    check("docs: marked Renewed is not nagged", DocExpiry.status(doc(5, mapOf("renewalStatus" to "Renewed")), now) == DocExpiry.Status.RENEWED)
    check("docs: no date", DocExpiry.status(mapOf("type" to "document"), now) == DocExpiry.Status.NO_DATE)
    val sum = DocExpiry.summary(listOf(doc(-3), doc(10), doc(400), doc(25), mapOf("title" to "x"), doc(5, mapOf("isDeleted" to true))), now)
    check("docs: summary counts (deleted left out)", sum.expired == 1 && sum.dueSoon == 2 && sum.active == 1 && sum.noDate == 1, sum)
    check("docs: next to act on, expired first then soonest", sum.next.map { it.second } == listOf(-3, 10, 25))

    // ---------- recurring payments ----------
    fun pay(id: String, title: String, amount: Double, day: Int, extra: Map<String, Any?> = emptyMap()) =
        mapOf<String, Any?>("id" to id, "type" to "EXPENSE", "title" to title, "amountInr" to amount, "dateMillis" to t0 + day * DAY, "category" to "Utilities") + extra
    val txs = listOf(
        pay("n1", "NETFLIX.COM", 649.0, 0), pay("n2", "UPI/Netflix.com", 649.0, 31), pay("n3", "Netflix.com", 649.0, 61), pay("n4", "Netflix.com", 649.0, 92),
        pay("m1", "Milkman Ramesh", 300.0, 0), pay("m2", "Milkman Ramesh", 310.0, 7), pay("m3", "Milkman Ramesh", 295.0, 14), pay("m4", "Milkman Ramesh", 300.0, 21),
        pay("s1", "Swiggy", 450.0, 2), pay("s2", "Swiggy", 120.0, 5), pay("s3", "Swiggy", 890.0, 40),
        pay("i1", "LIC premium", 12000.0, 0), pay("i2", "LIC premium", 12000.0, 365),
        pay("d1", "Netflix.com", 649.0, 92, mapOf("id" to "dup")), // same day twice
        pay("x1", "Gym", 1500.0, 0), pay("x2", "Gym", 1500.0, 30, mapOf("isDeleted" to true)), pay("x3", "Gym", 1500.0, 60)
    )
    val sug = RecurringFinder.find(txs)
    val byPayee = sug.associateBy { it.key.substringBefore('|') }
    check("recurring: Netflix monthly with 4 payments (saved twice on one day counted once)", byPayee["netflix com"]?.let { it.frequency == RecurringFinder.Frequency.MONTHLY && it.count == 4 && it.amount == 649.0 } == true, sug)
    check("recurring: milkman weekly, small amount changes allowed", byPayee["milkman ramesh"]?.frequency == RecurringFinder.Frequency.WEEKLY)
    check("recurring: yearly premium with 2 payments", byPayee["lic premium"]?.frequency == RecurringFinder.Frequency.YEARLY)
    check("recurring: irregular food orders are not suggested", byPayee["swiggy"] == null)
    check("recurring: deleted entries do not count (Gym 0 and 60 days is not monthly)", byPayee["gym"] == null)
    check("recurring: next date follows the last one", byPayee["netflix com"]!!.nextMillis == t0 + 92 * DAY + 30 * DAY)
    check("recurring: already tracked as a subscription is not suggested again", RecurringFinder.find(txs, alreadyTracked = setOf("netflix")).none { it.key.startsWith("netflix") })
    check("recurring: dismissed stays dismissed", RecurringFinder.find(txs, dismissed = setOf("netflix com|MONTHLY")).none { it.key == "netflix com|MONTHLY" })
    check("recurring: payee key ignores UPI noise", RecurringFinder.payeeKey("UPI/NETFLIX.COM/ref 12345") == RecurringFinder.payeeKey("Netflix.com"))
    check("recurring: never creates anything (suggestions only)", sug.all { it.ids.isNotEmpty() })

    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
}
