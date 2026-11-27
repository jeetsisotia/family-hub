// Phase 2 logic: shopping lists, calendar, subscriptions, maintenance (run by the Unit tests workflow).
import com.familyhub.app.family.CalendarMath
import com.familyhub.app.family.Maintenance
import com.familyhub.app.family.ShoppingLists
import com.familyhub.app.family.Subscriptions
import java.util.Calendar
import java.util.TimeZone

var failures = 0
fun check(name: String, cond: Boolean, detail: Any? = null) { if (cond) println("PASS $name") else { failures++; println("FAIL $name ${detail ?: ""}") } }

fun main() {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
    fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, min, 0) }.timeInMillis
    // ---------- shopping ----------
    val items = listOf(
        mapOf("title" to "Milk", "qty" to "2 L", "estPrice" to 60.0, "listName" to "Groceries", "isCompleted" to false),
        mapOf("title" to "Atta", "qty" to "1", "estPrice" to 300.0, "listName" to "Groceries", "isCompleted" to false, "assignedToName" to "Ravi"),
        mapOf("title" to "Bulb", "estPrice" to 120.0, "listName" to "Household", "status" to "Unavailable", "isCompleted" to false),
        mapOf("title" to "Soap", "subtitle" to "3 bars", "estPrice" to 40.0, "isCompleted" to false),
        mapOf("title" to "Eggs", "estPrice" to 90.0, "listName" to "Groceries", "isCompleted" to true)
    )
    check("shopping: quantity from text", ShoppingLists.quantity(items[0]) == 2.0 && ShoppingLists.quantity(items[3]) == 3.0 && ShoppingLists.quantity(items[2]) == 1.0)
    check("shopping: estimate only for items still to buy", ShoppingLists.estimate(items) == 2 * 60.0 + 300.0 + 3 * 40.0, ShoppingLists.estimate(items))
    val sums = ShoppingLists.summaries(items).associateBy { it.name }
    check("shopping: lists with pending, unavailable, bought", sums["Groceries"]?.let { it.pending == 2 && it.bought == 1 } == true && sums["Household"]?.unavailable == 1 && sums[ShoppingLists.DEFAULT_LIST]?.pending == 1)
    check("shopping: states", ShoppingLists.state(items[2]) == ShoppingLists.State.UNAVAILABLE && ShoppingLists.state(items[4]) == ShoppingLists.State.BOUGHT)
    check("shopping: search by person and list", ShoppingLists.search(items, "ravi").map { it["title"] } == listOf("Atta") && ShoppingLists.search(items, "house").size == 1)

    // ---------- calendar ----------
    val ev = listOf(
        mapOf("title" to "Dentist", "startTimeMillis" to at(2026, 12, 3), "time" to "10:00", "endTime" to "11:00", "ownerName" to "Asha"),
        mapOf("title" to "School PTM", "startTimeMillis" to at(2026, 12, 3), "time" to "10:30", "participants" to "Ravi, Asha"),
        mapOf("title" to "Lunch", "startTimeMillis" to at(2026, 12, 3), "time" to "11:00"),
        mapOf("title" to "Asha's birthday", "startTimeMillis" to at(1990, 12, 5), "recurrence" to "YEARLY"),
        mapOf("title" to "Yoga", "startTimeMillis" to at(2026, 11, 2), "time" to "07:00", "recurrence" to "WEEKLY"),
        mapOf("title" to "Old", "startTimeMillis" to at(2026, 12, 3), "time" to "10:15", "isDeleted" to true)
    )
    val occ = CalendarMath.occurrences(ev, at(2026, 12, 1), at(2026, 12, 31, 23, 59))
    check("calendar: yearly birthday shows in this December", occ.any { it.rec["title"] == "Asha's birthday" && it.allDay && it.start == at(2026, 12, 5) })
    check("calendar: weekly yoga repeats every week of the month", occ.count { it.rec["title"] == "Yoga" } == 4, occ.count { it.rec["title"] == "Yoga" })
    check("calendar: deleted events left out", occ.none { it.rec["title"] == "Old" })
    val cf = CalendarMath.conflicts(occ)
    check("calendar: overlapping times are conflicts (dentist 10-11 and PTM 10:30)", cf.any { setOf(it.first.rec["title"], it.second.rec["title"]) == setOf("Dentist", "School PTM") })
    check("calendar: back-to-back is not a conflict (dentist ends 11:00, lunch 11:00)", cf.none { setOf(it.first.rec["title"], it.second.rec["title"]) == setOf("Dentist", "Lunch") })
    check("calendar: all-day events never conflict", cf.none { it.first.allDay || it.second.allDay })
    check("calendar: people for the member filter", CalendarMath.people(ev[1]) == setOf("ravi", "asha"))
    check("calendar: time parsing", CalendarMath.minutes("18:30") == 1110 && CalendarMath.minutes("") == null && CalendarMath.minutes("25:00") == null)

    // ---------- subscriptions ----------
    val subs = listOf(
        mapOf("title" to "Netflix", "amountInr" to 649.0, "cycle" to "Monthly"),
        mapOf("title" to "Prime", "amountInr" to 1499.0, "cycle" to "Yearly", "status" to "Active"),
        mapOf("title" to "Gym", "amountInr" to 3000.0, "cycle" to "Quarterly", "status" to "Paused"),
        mapOf("title" to "Old app", "amountInr" to 99.0, "cycle" to "Monthly", "status" to "Cancelled")
    )
    val t = Subscriptions.totals(subs)
    check("subscriptions: monthly total of active ones only", kotlin.math.abs(t.monthly - (649.0 + 1499.0 / 12)) < 0.01, t)
    check("subscriptions: yearly = 12 x monthly", kotlin.math.abs(t.yearly - t.monthly * 12) < 0.01)
    check("subscriptions: status counts", t.active == 2 && t.paused == 1 && t.cancelled == 1)
    check("subscriptions: paused and cancelled are not live", !Subscriptions.isLive(subs[2]) && !Subscriptions.isLive(subs[3]) && Subscriptions.isLive(subs[0]))

    // ---------- maintenance ----------
    val ac = mapOf<String, Any?>("title" to "Bedroom AC", "serviceLog" to listOf(mapOf("at" to at(2026, 4, 1), "cost" to 600.0, "provider" to "CoolCare", "notes" to "gas top-up")))
    val log = Maintenance.Log(at(2026, 11, 20), 750.0, "CoolCare", "wet service")
    val newLog = Maintenance.withEntry(ac, log)
    check("maintenance: new service added first, old kept", newLog.size == 2 && newLog[0]["cost"] == 750.0)
    check("maintenance: total spent", Maintenance.totalSpent(ac + ("serviceLog" to newLog)) == 1350.0)
    check("maintenance: next due from the repeat", Maintenance.nextAfter(at(2026, 11, 20), "Half-yearly") == at(2026, 5 + 0, 20).let { at(2027, 5, 20) })
    check("maintenance: no repeat, no next date", Maintenance.nextAfter(at(2026, 11, 20), "None") == null)

    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
}
