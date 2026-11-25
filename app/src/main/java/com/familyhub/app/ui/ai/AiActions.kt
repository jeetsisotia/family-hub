package com.familyhub.app.ui.ai

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.familyhub.app.data.remote.PRIVATE_FLAG
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.health.StepTracker
import com.familyhub.app.money.TxnParser
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.atTime
import com.familyhub.app.ui.common.dayKey
import com.familyhub.app.ui.common.formatDate
import com.familyhub.app.ui.common.parseTimes
import com.familyhub.app.ui.modules.DAY_MS
import com.familyhub.app.ui.modules.PAYMENT_METHODS
import com.familyhub.app.ui.modules.financeCategories
import com.familyhub.app.ui.modules.localNoon
import com.familyhub.app.ui.modules.lng
import com.familyhub.app.ui.modules.num
import com.familyhub.app.ui.modules.startOfDay
import com.familyhub.app.ui.modules.str
import com.familyhub.app.ui.money.inr
import com.google.firebase.firestore.FieldValue
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Something the assistant offers to add; added only when the user taps Add (or Add all). */
data class AiAction(
    val kind: String,
    val title: String,
    val dateMillis: Long? = null,
    val time: String = "",
    val amount: Double? = null,
    val category: String = "",
    val method: String = "",
    val account: String = "",
    val payee: String = "",
    val member: String = "",
    val repeat: String = "",
    val private: Boolean = false,
    val flow: String = "",
    val symptoms: List<String> = emptyList(),
    val value: String = "",
    val vitalKind: String = "",
    val minutes: Int? = null,
    val count: Int? = null,
    /** For "open": the app or site (see SmartLinks.SERVICES) and its details. */
    val service: String = "",
    val query: String = "",
    val place: String = "",
    val origin: String = "",
    val phone: String = "",
    val upi: String = "",
    val dateText: String = "",
    /** Understood on the phone and never sent to the AI (health). */
    val local: Boolean = false,
    var state: String = "new", // new, added, dismissed, failed
    var error: String = ""
)

private val AI_KINDS = setOf("shopping", "task", "event", "reminder", "expense", "income", "bill", "note", "open")

fun parseAiDate(s: String): Long? {
    if (s.isBlank()) return null
    return try {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        f.parse(s.trim())?.time?.let { localNoon(it) }
    } catch (e: Exception) {
        null
    }
}

private fun JSONObject.text(vararg keys: String): String =
    keys.asSequence().map { optString(it).trim() }.firstOrNull { it.isNotEmpty() && it != "null" }.orEmpty()

fun parseAiReply(text: String): ChatMsg {
    val raw = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    return try {
        val o = JSONObject(raw)
        val acts = ArrayList<AiAction>()
        val arr = o.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val kind = a.optString("kind").lowercase(Locale.ROOT).trim()
            val service = a.text("service", "app").lowercase(Locale.ROOT)
            if (kind == "open" && service !in SmartLinks.SERVICES) continue
            val title = a.text("title", "payee", "category").ifBlank { if (kind == "open") SmartLinks.label(service) else "" }
            if (title.isBlank() || kind !in AI_KINDS) continue
            val vis = a.text("visibility").lowercase(Locale.ROOT)
            acts.add(
                AiAction(
                    kind = kind,
                    title = title.take(120),
                    dateMillis = parseAiDate(a.optString("date")),
                    time = a.optString("time").trim().take(5).takeIf { it != "null" }.orEmpty(),
                    amount = a.optDouble("amount").takeIf { !it.isNaN() && it > 0 },
                    category = a.text("category"),
                    method = a.text("method", "payment_method", "paymentMethod"),
                    account = a.text("account", "bank", "card"),
                    payee = if (kind == "open") a.text("payee") else a.text("payee", "to", "from"),
                    member = a.text("member", "for", "assignee"),
                    repeat = a.text("repeat"),
                    private = a.optBoolean("private", false) || vis == "private" || vis == "only me",
                    service = service,
                    query = a.text("query", "message", "keywords"),
                    place = a.text("to", "destination", "place", "location"),
                    origin = a.text("from_place", "origin", "pickup"),
                    phone = a.text("phone"),
                    upi = a.text("upi", "vpa"),
                    dateText = a.text("date")
                )
            )
        }
        ChatMsg(false, o.optString("reply").ifBlank { if (acts.isEmpty()) raw else "Here's what I can add:" }, acts.take(15))
    } catch (e: Exception) {
        ChatMsg(false, raw)
    }
}

/** Maps what the person said ("travel", "petrol", "swiggy") to one of the Finance categories. */
fun normalizeCategory(raw: String, title: String = ""): String {
    val r = raw.trim()
    financeCategories.firstOrNull { it.equals(r, ignoreCase = true) }?.let { return it }
    val x = "$r $title".lowercase(Locale.ROOT)
    val map = listOf(
        "Travel" to listOf("travel", "trip", "flight", "hotel", "holiday", "tour"),
        "Transport" to listOf("cab", "taxi", "auto", "uber", "ola", "rapido", "bus", "metro", "train", "parking", "toll"),
        "Fuel" to listOf("petrol", "diesel", "fuel", "cng"),
        "Dining" to listOf("food", "lunch", "dinner", "breakfast", "restaurant", "cafe", "tea", "coffee", "swiggy", "zomato", "snack"),
        "Groceries" to listOf("grocery", "groceries", "vegetable", "fruit", "milk", "ration", "kirana"),
        "Health" to listOf("medicine", "doctor", "hospital", "pharmacy", "clinic", "test", "lab"),
        "Utilities" to listOf("electricity", "recharge", "mobile", "internet", "wifi", "broadband", "gas", "water bill", "dth"),
        "Education" to listOf("school", "fees", "tuition", "books", "course", "college"),
        "Entertainment" to listOf("movie", "netflix", "concert", "game", "outing"),
        "Shopping" to listOf("clothes", "shopping", "amazon", "flipkart", "shoes", "gift"),
        "Rent" to listOf("rent"),
        "EMI" to listOf("emi", "loan"),
        "Insurance" to listOf("insurance", "premium", "policy"),
        "Salary" to listOf("salary")
    )
    map.firstOrNull { (_, words) -> words.any { it in x } }?.let { return it.first }
    return TxnParser.guessCategory(x) ?: "Other"
}

fun normalizeMethod(raw: String, account: String): String {
    val x = "$raw $account".lowercase(Locale.ROOT)
    return when {
        Regex("upi|gpay|google pay|phonepe|paytm upi|bhim").containsMatchIn(x) -> "UPI"
        "credit" in x || Regex("\\bcc\\b").containsMatchIn(x) -> "Credit card"
        "debit" in x || "atm" in x -> "Debit card"
        "cash" in x -> "Cash"
        Regex("net ?banking|neft|imps|rtgs|bank transfer").containsMatchIn(x) -> "Net banking"
        "wallet" in x -> "Wallet"
        raw.isNotBlank() -> PAYMENT_METHODS.firstOrNull { it.equals(raw, true) } ?: "Other"
        else -> ""
    }
}

fun actionLabel(a: AiAction): String {
    val what = when (a.kind) {
        "shopping" -> "Shopping"
        "task" -> "Task"
        "event" -> "Calendar"
        "reminder" -> "Reminder"
        "expense" -> "Expense"
        "income" -> "Income"
        "bill" -> "Bill"
        "period" -> "Period started"
        "period_end" -> "Period ended"
        "period_log" -> "Period symptoms"
        "vital" -> a.vitalKind
        "water" -> "Water"
        "workout" -> "Workout"
        "medicine_taken" -> "Medicine taken"
        "open" -> "Open " + SmartLinks.label(a.service)
        else -> "Note"
    }
    val main = when (a.kind) {
        "period", "period_end", "period_log" -> ""
        "vital" -> a.value
        "water" -> "+${a.count ?: 1} glass(es)"
        "workout" -> "${a.title}, ${a.minutes ?: 0} min"
        "open" -> a.title.takeIf { !it.equals(SmartLinks.label(a.service), true) }.orEmpty()
        else -> a.title
    }
    val details = listOfNotNull(
        a.amount?.let { inr(it) },
        a.category.takeIf { it.isNotBlank() && a.kind in setOf("expense", "income", "bill") }?.let { normalizeCategory(it, a.title) },
        normalizeMethod(a.method, a.account).takeIf { it.isNotBlank() && a.kind in setOf("expense", "income", "bill") },
        a.account.takeIf { it.isNotBlank() }?.let { "from $it" },
        a.origin.takeIf { it.isNotBlank() && a.kind == "open" }?.let { "from $it" },
        a.place.takeIf { it.isNotBlank() && a.kind == "open" }?.let { "to $it" },
        a.query.takeIf { it.isNotBlank() && a.kind == "open" }?.let { "\"$it\"" },
        a.upi.takeIf { it.isNotBlank() },
        a.payee.takeIf { it.isNotBlank() && !a.title.contains(it, true) }?.let { (if (a.kind == "income") "from " else "to ") + it },
        a.flow.takeIf { it.isNotBlank() }?.let { "$it flow" },
        a.symptoms.takeIf { it.isNotEmpty() }?.joinToString(", "),
        a.member.takeIf { it.isNotBlank() }?.let { "for $it" },
        a.dateMillis?.takeIf { a.kind != "open" }?.let { if (startOfDay(it) == startOfDay(System.currentTimeMillis())) "today" else formatDate(it) },
        a.time.takeIf { it.isNotBlank() },
        a.repeat.takeIf { it.isNotBlank() && !it.equals("none", true) },
        if (a.private) "Only me" else null
    )
    return listOf(what + if (main.isNotBlank()) ": $main" else "", details.joinToString(" • ")).filter { it.isNotBlank() }.joinToString("\n")
}

fun actionIcon(kind: String): ImageVector = when (kind) {
    "shopping" -> Icons.Rounded.ShoppingCart
    "task" -> Icons.Rounded.TaskAlt
    "event" -> Icons.Rounded.CalendarMonth
    "reminder" -> Icons.Rounded.Alarm
    "expense", "bill" -> Icons.Rounded.CurrencyRupee
    "income" -> Icons.Rounded.Savings
    "period", "period_end", "period_log" -> Icons.Rounded.WaterDrop
    "vital" -> Icons.Rounded.MonitorHeart
    "water" -> Icons.Rounded.LocalDrink
    "workout" -> Icons.Rounded.FitnessCenter
    "medicine_taken" -> Icons.Rounded.Medication
    "open" -> Icons.Rounded.OpenInNew
    else -> Icons.Rounded.StickyNote2
}

/** The period in progress (started within the last 12 days and not ended before today), if any. */
fun currentPeriod(items: List<Rec>, now: Long = System.currentTimeMillis()): Rec? {
    val today = startOfDay(now)
    return items.filter { it.str("type") == "period" && it[PRIVATE_FLAG] == true }
        .sortedByDescending { it.lng("startMillis") ?: 0L }
        .firstOrNull { r ->
            val s = r.lng("startMillis") ?: return@firstOrNull false
            val e = r.lng("endMillis")
            startOfDay(s) <= today && today - startOfDay(s) < 12 * DAY_MS && (e == null || startOfDay(e) >= today)
        }
}

/** Saves one confirmed action. Throws with a short message if it cannot. */
suspend fun applyAction(env: AppEnv, context: Context, a: AiAction) {
    val now = System.currentTimeMillis()
    val day = a.dateMillis ?: localNoon(now)
    val items = env.data["items"].orEmpty()
    val repo = env.repo
    fun at(): Long = parseTimes(a.time).firstOrNull()?.let { (h, m) -> atTime(startOfDay(day), h, m) } ?: day
    when (a.kind) {
        "shopping" -> repo.saveScoped(env.familyId, env.uid, "tasks", null,
            mapOf("type" to "SHOPPING", "title" to a.title, "isCompleted" to false, "createdAt" to now), a.private)
        "task" -> repo.saveScoped(env.familyId, env.uid, "tasks", null,
            mapOf("type" to "TASK", "title" to a.title, "isCompleted" to false, "assignedToName" to a.member,
                "dueDateMillis" to a.dateMillis, "createdAt" to now), a.private)
        "event" -> {
            val start = at()
            repo.saveScoped(env.familyId, env.uid, "events", null,
                mapOf("title" to a.title, "category" to a.category.ifBlank { "Family" }, "startTimeMillis" to start,
                    "endTimeMillis" to start + 3_600_000L, "recurrence" to (a.repeat.uppercase().takeIf { it in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY") } ?: "NONE"),
                    "isDeleted" to false, "reminderMinutesBefore" to 30L, "createdByUid" to env.uid, "notes" to ""), a.private)
        }
        "reminder" -> repo.saveScoped(env.familyId, env.uid, "items", null,
            mapOf("type" to "reminder", "title" to a.title, "dateMillis" to day, "time" to a.time,
                "repeat" to (listOf("None", "Daily", "Weekly", "Monthly", "Yearly").firstOrNull { it.equals(a.repeat, true) } ?: "None"),
                "done" to false, "notes" to ""), true)
        "expense", "income", "bill" -> {
            val cards = items.filter { it.str("type") == "card" }
            var method = normalizeMethod(a.method, a.account)
            val acct = a.account.lowercase(Locale.ROOT)
            val card = if (acct.isBlank()) null else cards.firstOrNull { c ->
                val t = c.str("title").lowercase(Locale.ROOT)
                val b = c.str("bank").lowercase(Locale.ROOT)
                (t.isNotBlank() && (t in acct || acct in t)) || (b.isNotBlank() && b in acct && method == "Credit card")
            }
            if (card != null && method.isBlank()) method = "Credit card"
            val notes = listOfNotNull(
                a.account.takeIf { it.isNotBlank() }?.let { "From $it" },
                a.payee.takeIf { it.isNotBlank() }?.let { (if (a.kind == "income") "From " else "To ") + it }
            ).joinToString(", ")
            val data = hashMapOf<String, Any?>(
                "type" to when (a.kind) { "income" -> "INCOME"; "bill" -> "BILL"; else -> "EXPENSE" },
                "title" to a.title,
                "amountInr" to (a.amount ?: 0.0),
                "category" to normalizeCategory(a.category, a.title + " " + a.payee),
                "dateMillis" to day,
                "paymentMethod" to method,
                "card" to (if (method == "Credit card") card?.str("title") ?: "None" else "None"),
                "notes" to notes,
                "isPaid" to (a.kind != "bill"),
                "isDeleted" to false,
                "memberUid" to env.uid,
                "memberName" to env.profile.name,
                "createdAt" to now
            )
            if (a.kind == "bill") data["dueDateMillis"] = day
            repo.saveScoped(env.familyId, env.uid, "expenses", null, data, a.private)
        }
        "note" -> repo.saveScoped(env.familyId, env.uid, "items", null,
            mapOf("type" to "note", "title" to a.title, "category" to a.category.ifBlank { "General" },
                "notes" to "", "createdAt" to now), true)

        // Health: kept "Only me".
        "period" -> {
            val cur = currentPeriod(items, now)
            if (cur != null) {
                val upd = HashMap<String, Any?>()
                if (a.flow.isNotBlank()) upd["flow"] = a.flow
                if (a.symptoms.isNotEmpty()) upd["symptoms"] = (cur["symptoms"] as? List<*>).orEmpty().map { it.toString() }.plus(a.symptoms).distinct()
                if (upd.isNotEmpty()) repo.saveScoped(env.familyId, env.uid, "items", cur, upd, true)
            } else {
                repo.saveScoped(env.familyId, env.uid, "items", null,
                    mapOf("type" to "period", "title" to "Period", "startMillis" to day, "endMillis" to null,
                        "flow" to a.flow, "symptoms" to a.symptoms), true)
            }
        }
        "period_end" -> {
            val cur = currentPeriod(items, now) ?: throw IllegalStateException("No period in progress to end.")
            repo.saveScoped(env.familyId, env.uid, "items", cur, mapOf("endMillis" to day), true)
        }
        "period_log" -> {
            val cur = currentPeriod(items, now) ?: throw IllegalStateException("No period in progress.")
            val upd = HashMap<String, Any?>()
            if (a.flow.isNotBlank()) upd["flow"] = a.flow
            upd["symptoms"] = (cur["symptoms"] as? List<*>).orEmpty().map { it.toString() }.plus(a.symptoms).distinct()
            repo.saveScoped(env.familyId, env.uid, "items", cur, upd, true)
        }
        "vital" -> repo.saveScoped(env.familyId, env.uid, "items", null,
            mapOf("type" to "vital", "kind" to a.vitalKind, "value" to a.value, "dateMillis" to day,
                "memberName" to env.profile.name, "notes" to ""), true)
        "workout" -> repo.saveScoped(env.familyId, env.uid, "items", null,
            mapOf("type" to "workout", "activity" to a.title, "minutes" to (a.minutes ?: 0).toDouble(),
                "minutesText" to "${a.minutes ?: 0} min", "dateMillis" to day, "memberName" to env.profile.name, "notes" to ""), true)
        "water" -> {
            val glasses = StepTracker.addWater(context, a.count ?: 1)
            env.repo.putPrivate(env.uid, "activity", dayKey(), mapOf("day" to dayKey(), "water" to glasses))
        }
        "medicine_taken" -> {
            val med = items.firstOrNull { it.str("type") == "medicine" && it.str("title").equals(a.title, true) }
                ?: throw IllegalStateException("Medicine not found.")
            val times = parseTimes(med.str("times"))
            val nowMin = ((now - startOfDay(now)) / 60_000L).toInt()
            val (h, m) = times.minByOrNull { (hh, mm) -> kotlin.math.abs(hh * 60 + mm - nowMin) } ?: (nowMin / 60 to nowMin % 60)
            val slot = dayKey(now) + "_" + "%02d%02d".format(h, m)
            repo.setFieldScoped(env.familyId, env.uid, "items", med, "taken.$slot", true)
            if (med.num("stock") != null) repo.setFieldScoped(env.familyId, env.uid, "items", med, "stock", FieldValue.increment(-1))
        }
        else -> throw IllegalStateException("Not supported.")
    }
}
