package com.familyhub.app.ui.modules

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.familyhub.app.data.remote.Rec
import java.util.Calendar
import java.util.TimeZone

const val DAY_MS = 86_400_000L

// ---------- helpers for reading raw records ----------

fun Rec.str(key: String): String = when (val v = this[key]) {
    null -> ""
    is String -> v
    else -> v.toString()
}

fun Rec.num(key: String): Double? = (this[key] as? Number)?.toDouble()

fun Rec.lng(key: String): Long? = (this[key] as? Number)?.toLong()

fun Rec.bool(key: String): Boolean = (this[key] as? Boolean) ?: false

// ---------- date helpers ----------

fun startOfDay(t: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = t
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

/** Local noon of today. Dates are stored at noon so time zones never shift the day. */
fun noonToday(): Long = startOfDay(System.currentTimeMillis()) + 12 * 3_600_000L

/** Converts the UTC midnight returned by the date picker into local noon of the same date. */
fun localNoon(utcMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    utc.timeInMillis = utcMillis
    val c = Calendar.getInstance()
    c.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 12, 0, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

/**
 * For repeating items (birthdays, renewals, salaries) returns the next date on or after today. Counted from the
 * original date each time, so the 31st and 29 February no longer drift (see ReminderRules, tools/reminder-test).
 */
fun nextOccurrence(start: Long, repeat: String, now: Long = System.currentTimeMillis()): Long =
    com.familyhub.app.work.ReminderRules.nextOccurrence(start, repeat, now)

/** Same, for a record whose "Every few days" repeat keeps its number of days in "everyDays". */
fun nextOccurrenceOf(r: Rec, start: Long, repeat: String, now: Long = System.currentTimeMillis()): Long =
    com.familyhub.app.work.ReminderRules.nextOccurrence(start, repeat, now, (r.num("everyDays") ?: 0.0).toInt())

// ---------- module definitions ----------

/** TIME is "HH:mm" text picked on a clock; empty means all day. */
enum class FieldType { TEXT, MULTILINE, NUMBER, DATE, CHOICE, MEMBER, BOOL, PHOTO, TIME }

data class FieldDef(
    val key: String,
    val label: String,
    val type: FieldType = FieldType.TEXT,
    val options: List<String> = emptyList(),
    val required: Boolean = false,
    /**
     * When set, the choices come from the app at run time (for example "cards"). A {key} in it is replaced by
     * that field's current value, so "sub:{category}" lists the subcategories of the chosen category.
     */
    val dynamic: String? = null,
    /** Shown only when this returns true for the form's current values. */
    val showIf: ((Map<String, Any?>) -> Boolean)? = null
)

data class DateKey(val key: String, val label: String)

data class ModuleDef(
    val route: String,
    val title: String,
    val icon: ImageVector,
    val collection: String,
    val fields: List<FieldDef>,
    val typeValue: String? = null,
    val defaultType: Boolean = false,
    val titleKey: String = "title",
    val subtitleKeys: List<String> = emptyList(),
    val amountKey: String? = null,
    val dateKeys: List<DateKey> = emptyList(),
    val repeatKey: String? = null,
    val doneKey: String? = null,
    val doneLabel: String = "Done",
    val phoneKey: String? = null,
    val softDeleteKey: String? = null,
    val newestFirst: Boolean = false,
    val alerts: Boolean = true,
    val alertOverdue: Boolean = true,
    val alertDays: Int = 30,
    val emptyText: String = "Nothing here yet. Tap + to add the first one.",
    val constants: Map<String, Any> = emptyMap(),
    val autoCreatedAt: Boolean = false,
    val postProcess: ((MutableMap<String, Any?>, String, String) -> Unit)? = null,
    val kind: String = "generic",
    /** New entries start as "Only me" (health and other personal records). */
    val privateByDefault: Boolean = false
)

val PAYMENT_METHODS = listOf("UPI", "Cash", "Debit card", "Credit card", "Net banking", "Wallet", "Other")

val financeCategories = listOf(
    "Groceries", "Rent", "Utilities", "Education", "Health", "Transport", "Travel", "Fuel",
    "Dining", "Shopping", "Entertainment", "EMI", "Insurance", "Salary", "Other"
)

val CalendarModule = ModuleDef(
    route = "calendar", title = "Calendar", icon = Icons.Rounded.CalendarMonth,
    collection = "events",
    titleKey = "title", subtitleKeys = listOf("timeText", "category", "notes"),
    fields = listOf(
        FieldDef("title", "Title", required = true),
        FieldDef("category", "Category", FieldType.CHOICE,
            listOf("Family", "Birthday", "Anniversary", "Appointment", "Doctor", "School", "Office", "Function", "Festival", "Other")),
        FieldDef("startTimeMillis", "Date", FieldType.DATE, required = true),
        FieldDef("time", "Time (leave empty for all day)", FieldType.TIME),
        FieldDef("endTime", "Ends at (optional)", FieldType.TIME),
        FieldDef("ownerName", "Whose event", FieldType.MEMBER),
        FieldDef("participants", "Who else is going (names, comma separated)"),
        FieldDef("alert", "Alert", FieldType.CHOICE, com.familyhub.app.work.ReminderRules.EVENT_ALERTS.map { it.first }),
        FieldDef("recurrence", "Repeats", FieldType.CHOICE, listOf("NONE", "YEARLY", "MONTHLY", "WEEKLY")),
        FieldDef("notes", "Notes", FieldType.MULTILINE)
    ),
    dateKeys = listOf(DateKey("startTimeMillis", "Date")),
    repeatKey = "recurrence", softDeleteKey = "isDeleted", alertOverdue = false, alertDays = 14,
    constants = mapOf("isDeleted" to false, "reminderMinutesBefore" to 30L),
    postProcess = { m, uid, _ ->
        val s = (m["startTimeMillis"] as? Number)?.toLong() ?: System.currentTimeMillis()
        m["endTimeMillis"] = s + 3_600_000L
        if (m["createdByUid"] == null) m["createdByUid"] = uid
    },
    kind = "calendar"
)

/** Types that only move money (between accounts, or to pay a card) and never count as spending. */
val MONEY_MOVES = setOf("TRANSFER", "CARD_PAYMENT", "ADJUSTMENT")

private fun Map<String, Any?>.t(k: String) = (this[k] as? String).orEmpty()
private val notMove: (Map<String, Any?>) -> Boolean = { m -> m.t("type") !in MONEY_MOVES }

val FinanceModule = ModuleDef(
    route = "finance", title = "Finance", icon = Icons.Rounded.AccountBalanceWallet,
    collection = "expenses",
    titleKey = "title", subtitleKeys = listOf("type", "category", "memberName"),
    fields = listOf(
        FieldDef("type", "Type", FieldType.CHOICE, listOf("EXPENSE", "INCOME", "BILL", "EMI", "SAVING", "TRANSFER", "CARD_PAYMENT"), true),
        FieldDef("title", "Title", required = true),
        FieldDef("amountInr", "Amount (₹)", FieldType.NUMBER, required = true),
        FieldDef("category", "Category", FieldType.CHOICE, financeCategories, dynamic = "categories", showIf = notMove),
        FieldDef("subcategory", "Subcategory", FieldType.CHOICE, listOf("None"), dynamic = "sub:{category}", showIf = notMove),
        FieldDef("merchant", "Merchant or payee", showIf = notMove),
        FieldDef("dateMillis", "Date or due date", FieldType.DATE, required = true),
        FieldDef("paymentMethod", "Paid by", FieldType.CHOICE, PAYMENT_METHODS, showIf = notMove),
        FieldDef(
            "account", "Bank account or cash (money from)", FieldType.CHOICE, listOf("None"), dynamic = "accounts",
            showIf = { m -> m.t("type") in MONEY_MOVES || m.t("paymentMethod") != "Credit card" }
        ),
        FieldDef("toAccount", "To account", FieldType.CHOICE, listOf("None"), dynamic = "accounts", showIf = { m -> m.t("type") == "TRANSFER" }),
        FieldDef(
            "card", "Card", FieldType.CHOICE, listOf("None"), dynamic = "cards",
            showIf = { m -> m.t("type") == "CARD_PAYMENT" || m.t("paymentMethod") == "Credit card" || m.t("paymentMethod") == "Debit card" }
        ),
        FieldDef("memberName", "For", FieldType.MEMBER, showIf = notMove),
        FieldDef("proofPhoto", "Receipt or payment proof", FieldType.PHOTO),
        FieldDef("notes", "Notes", FieldType.MULTILINE)
    ),
    amountKey = "amountInr", dateKeys = listOf(DateKey("dateMillis", "Date")),
    doneKey = "isPaid", doneLabel = "Paid", softDeleteKey = "isDeleted", newestFirst = true,
    alertDays = 30,
    constants = mapOf("isDeleted" to false),
    postProcess = { m, uid, name ->
        val t = (m["type"] as? String) ?: "EXPENSE"
        val isDue = t == "BILL" || t == "EMI"
        if (m["isPaid"] == null) m["isPaid"] = !isDue
        if (isDue) m["dueDateMillis"] = m["dateMillis"]
        if (m["createdByUid"] == null) m["createdByUid"] = uid
        // "For" decides whose spending it is; it starts as the person adding it.
        val forName = (m["memberName"] as? String).orEmpty()
        if (forName.isBlank() || forName == name) {
            m["memberName"] = name
            if (m["memberUid"] == null) m["memberUid"] = uid
        } else if (m["memberUid"] == uid) {
            m["memberUid"] = null
        }
        // Moves are never spending (the type alone decides that), and are always settled.
        if (t in MONEY_MOVES) m["isPaid"] = true
        // Remember which kind of card paid, so card bills and bank balances stay apart.
        when ((m["paymentMethod"] as? String).orEmpty()) {
            "Credit card" -> m["cardKind"] = "credit"
            "Debit card" -> m["cardKind"] = "debit"
        }
    },
    kind = "finance"
)

val HouseholdModule = ModuleDef(
    route = "household", title = "Household", icon = Icons.Rounded.HomeWork,
    collection = "tasks", typeValue = "TASK", defaultType = true,
    titleKey = "title", subtitleKeys = listOf("subtitle", "assignedToName"),
    fields = listOf(
        FieldDef("title", "Task", required = true),
        FieldDef("subtitle", "Details", FieldType.MULTILINE),
        FieldDef("assignedToName", "Assigned to", FieldType.MEMBER),
        FieldDef("dueDateMillis", "Due date", FieldType.DATE)
    ),
    dateKeys = listOf(DateKey("dueDateMillis", "Due")),
    doneKey = "isCompleted", doneLabel = "Done",
    constants = mapOf("isCompleted" to false)
)

val ShoppingModule = ModuleDef(
    route = "shopping", title = "Shopping", icon = Icons.Rounded.ShoppingCart,
    collection = "tasks", typeValue = "SHOPPING",
    titleKey = "title", subtitleKeys = listOf("subtitle"),
    fields = listOf(
        FieldDef("title", "Item", required = true),
        FieldDef("qty", "Quantity (for example 2 kg)"),
        FieldDef("listName", "List (for example Groceries, Party)"),
        FieldDef("category", "Category", FieldType.CHOICE, com.familyhub.app.family.ShoppingLists.CATEGORIES),
        FieldDef("estPrice", "Estimated price each (₹)", FieldType.NUMBER),
        FieldDef("assignedToName", "Who buys it", FieldType.MEMBER),
        FieldDef("status", "Status", FieldType.CHOICE, listOf("Pending", "Unavailable")),
        FieldDef("subtitle", "Note")
    ),
    doneKey = "isCompleted", doneLabel = "Bought", alerts = false,
    emptyText = "The shopping list is empty. Add milk, groceries and anything else the family needs.",
    constants = mapOf("isCompleted" to false)
)

val TravelModule = ModuleDef(
    route = "travel", title = "Travel", icon = Icons.Rounded.FlightTakeoff,
    collection = "trips",
    titleKey = "destination", subtitleKeys = listOf("travellers", "transportDetails", "hotelDetails"),
    fields = listOf(
        FieldDef("destination", "Destination", required = true),
        FieldDef("startDateMillis", "Starts on", FieldType.DATE, required = true),
        FieldDef("endDateMillis", "Ends on", FieldType.DATE, required = true),
        FieldDef("transportDetails", "Flights, trains and tickets", FieldType.MULTILINE),
        FieldDef("hotelDetails", "Hotel bookings", FieldType.MULTILINE),
        FieldDef("estimatedBudgetInr", "Trip budget (₹)", FieldType.NUMBER),
        FieldDef("itineraryNotes", "Itinerary", FieldType.MULTILINE),
        FieldDef("travellers", "Who is going (names)"),
        FieldDef("documentsNeeded", "Documents to carry (passport, visa, tickets…)", FieldType.MULTILINE),
        FieldDef("packingNotes", "Packing list (one per line; start a line with [x] when packed)", FieldType.MULTILINE)
    ),
    amountKey = "estimatedBudgetInr",
    dateKeys = listOf(DateKey("startDateMillis", "Starts"), DateKey("endDateMillis", "Ends")),
    alertOverdue = false, alertDays = 14,
    kind = "travel"
)

val VaultModule = ModuleDef(
    route = "home_vault", title = "Home Vault", icon = Icons.Rounded.Inventory2,
    collection = "items", typeValue = "vault",
    subtitleKeys = listOf("category", "brand", "model"),
    fields = listOf(
        FieldDef("title", "Item name", required = true),
        FieldDef("category", "Category", FieldType.CHOICE,
            listOf("Electronics", "Appliance", "Furniture", "Jewellery", "Tools", "Other")),
        FieldDef("brand", "Brand"),
        FieldDef("model", "Model"),
        FieldDef("serialNumber", "Serial number"),
        FieldDef("purchaseDateMillis", "Purchase date", FieldType.DATE),
        FieldDef("amountInr", "Price (₹)", FieldType.NUMBER),
        FieldDef("warrantyExpiryMillis", "Warranty expires", FieldType.DATE),
        FieldDef("nextServiceMillis", "Next service due", FieldType.DATE),
        FieldDef("serviceProvider", "Service provider"),
        FieldDef("servicePhone", "Service phone"),
        FieldDef("notes", "Invoice details, repairs and maintenance history", FieldType.MULTILINE)
    ),
    amountKey = "amountInr", phoneKey = "servicePhone",
    dateKeys = listOf(DateKey("warrantyExpiryMillis", "Warranty ends"), DateKey("nextServiceMillis", "Service due")),
    emptyText = "Add everything you buy for the home: invoice details, warranty and service dates."
)

val DocumentsModule = ModuleDef(
    route = "documents", title = "Documents", icon = Icons.Rounded.FolderShared,
    collection = "items", typeValue = "document",
    subtitleKeys = listOf("category", "holder", "number"),
    fields = listOf(
        FieldDef("title", "Document name", required = true),
        FieldDef("category", "Type", FieldType.CHOICE,
            listOf("Aadhaar", "PAN", "Passport", "Driving licence", "Voter ID", "Vehicle RC", "Health insurance",
                "Life insurance", "Vehicle insurance", "Vehicle PUC", "Warranty", "Property", "Bank", "Education", "Other")),
        FieldDef("holder", "Belongs to", FieldType.MEMBER),
        FieldDef("number", "Document or policy number"),
        FieldDef("expiryDateMillis", "Expires or renews on", FieldType.DATE),
        // Expiry tracker: when to start reminding, and where the renewal stands.
        FieldDef("remindBefore", "Remind me", FieldType.CHOICE,
            listOf("7 days before", "15 days before", "30 days before", "60 days before", "90 days before")),
        FieldDef("renewalStatus", "Renewal", FieldType.CHOICE, listOf("Not started", "In progress", "Renewed", "Not renewing")),
        FieldDef("renewalNotes", "Renewal notes (agent, documents needed)", FieldType.MULTILINE),
        FieldDef("notes", "Where the original is kept, other details", FieldType.MULTILINE)
    ),
    dateKeys = listOf(DateKey("expiryDateMillis", "Expires")),
    emptyText = "Keep a record of your documents and get reminders before they expire. (Details only, no file uploads.)"
)

val HealthModule = ModuleDef(
    route = "health", title = "Health", icon = Icons.Rounded.MedicalServices,
    collection = "items", typeValue = "health",
    subtitleKeys = listOf("memberName", "category", "notes"),
    fields = listOf(
        FieldDef("memberName", "Family member", FieldType.MEMBER, required = true),
        FieldDef("category", "Type", FieldType.CHOICE,
            listOf("Health profile", "Medicine", "Appointment", "Vaccination", "Medical report", "Health insurance")),
        FieldDef("title", "Title (for example BP tablet, Dr Sharma visit)", required = true),
        FieldDef("dateMillis", "Date or next due", FieldType.DATE),
        FieldDef("notes", "Details: dose, doctor, blood group, allergies", FieldType.MULTILINE)
    ),
    dateKeys = listOf(DateKey("dateMillis", "Due")), alertOverdue = false, alertDays = 7,
    privateByDefault = true
)

val VehiclesModule = ModuleDef(
    route = "vehicles", title = "Vehicles", icon = Icons.Rounded.DirectionsCar,
    collection = "items", typeValue = "vehicle",
    subtitleKeys = listOf("category", "regNumber"),
    fields = listOf(
        FieldDef("title", "Vehicle (for example Honda City)", required = true),
        FieldDef("category", "Type", FieldType.CHOICE, listOf("Car", "Bike", "Scooter", "Other")),
        FieldDef("regNumber", "Registration number"),
        FieldDef("insuranceExpiryMillis", "Insurance expires", FieldType.DATE),
        FieldDef("pucExpiryMillis", "PUC expires", FieldType.DATE),
        FieldDef("serviceDueMillis", "Next service due", FieldType.DATE),
        FieldDef("lastServiceMillis", "Last service", FieldType.DATE),
        FieldDef("vendor", "Service centre or mechanic"),
        FieldDef("phone", "Their phone"),
        FieldDef("warrantyExpiryMillis", "Warranty or extended warranty ends", FieldType.DATE),
        FieldDef("receiptPhoto", "Service bill or insurance copy", FieldType.PHOTO),
        FieldDef("notes", "RC details, service history, challans", FieldType.MULTILINE)
    ),
    dateKeys = listOf(
        DateKey("insuranceExpiryMillis", "Insurance"),
        DateKey("pucExpiryMillis", "PUC"),
        DateKey("serviceDueMillis", "Service"),
        DateKey("warrantyExpiryMillis", "Warranty ends")
    )
)

val PropertyModule = ModuleDef(
    route = "property", title = "Property", icon = Icons.Rounded.Apartment,
    collection = "items", typeValue = "property",
    subtitleKeys = listOf("category", "location"),
    fields = listOf(
        FieldDef("title", "Property", required = true),
        FieldDef("category", "Type", FieldType.CHOICE, listOf("House", "Flat", "Plot", "Shop", "Rented out", "Rented in", "Other")),
        FieldDef("location", "Location"),
        FieldDef("amountInr", "Rent or value (₹)", FieldType.NUMBER),
        FieldDef("taxDueMillis", "Property tax due", FieldType.DATE),
        FieldDef("rentDueMillis", "Rent due", FieldType.DATE),
        FieldDef("notes", "Papers, maintenance, tax records", FieldType.MULTILINE)
    ),
    amountKey = "amountInr",
    dateKeys = listOf(DateKey("taxDueMillis", "Tax due"), DateKey("rentDueMillis", "Rent due"))
)

val SubscriptionsModule = ModuleDef(
    route = "subscriptions", title = "Subscriptions", icon = Icons.Rounded.Subscriptions,
    collection = "items", typeValue = "subscription",
    subtitleKeys = listOf("category", "cycle"),
    fields = listOf(
        FieldDef("title", "Name", required = true),
        FieldDef("provider", "Provider (for example Netflix, Jio)"),
        FieldDef("status", "Status", FieldType.CHOICE, listOf("Active", "Paused", "Cancelled")),
        FieldDef("category", "Type", FieldType.CHOICE,
            listOf("OTT", "Music", "Software", "Membership", "Gym", "Insurance", "Utility", "Other")),
        FieldDef("paymentMethod", "Paid with", FieldType.CHOICE, PAYMENT_METHODS),
        FieldDef("amountInr", "Amount (₹)", FieldType.NUMBER, required = true),
        FieldDef("cycle", "Billing cycle", FieldType.CHOICE, listOf("Monthly", "Quarterly", "Yearly")),
        FieldDef("renewalMillis", "Renews on", FieldType.DATE),
        FieldDef("notes", "Plan, account, notes", FieldType.MULTILINE)
    ),
    amountKey = "amountInr", repeatKey = "cycle", alertOverdue = false, alertDays = 7,
    dateKeys = listOf(DateKey("renewalMillis", "Renews")),
    kind = "subscription"
)

val LoansModule = ModuleDef(
    route = "loans", title = "Loans & EMIs", icon = Icons.Rounded.AccountBalance,
    collection = "items", typeValue = "loan",
    subtitleKeys = listOf("loanType", "lender"),
    fields = listOf(
        FieldDef("title", "Loan name", required = true),
        FieldDef("loanType", "Type", FieldType.CHOICE, listOf("Home", "Car", "Two-wheeler", "Personal", "Education", "Gold", "Credit card EMI", "Business", "Other")),
        FieldDef("lender", "Bank or lender"),
        FieldDef("principal", "Loan amount (₹)", FieldType.NUMBER),
        FieldDef("interestRate", "Interest rate (% a year)", FieldType.NUMBER),
        FieldDef("tenureMonths", "Tenure (months)", FieldType.NUMBER),
        FieldDef("startMillis", "First EMI date", FieldType.DATE),
        FieldDef("emiAmount", "Monthly EMI (₹, empty = work it out)", FieldType.NUMBER),
        FieldDef("paidInstallments", "EMIs already paid before adding here", FieldType.NUMBER),
        FieldDef("amountInr", "Outstanding today (₹, if known)", FieldType.NUMBER),
        FieldDef("nextDueMillis", "Next EMI due (if no first EMI date)", FieldType.DATE),
        FieldDef("account", "EMI paid from", FieldType.CHOICE, listOf("None"), dynamic = "accounts"),
        FieldDef("notes", "Loan account number, notes", FieldType.MULTILINE)
    ),
    postProcess = { m, _, _ ->
        val p = (m["principal"] as? Number)?.toDouble()
        val r = (m["interestRate"] as? Number)?.toDouble()
        val n = (m["tenureMonths"] as? Number)?.toInt()
        if (m["emiAmount"] == null && p != null && r != null && n != null && n > 0) {
            m["emiAmount"] = com.familyhub.app.money.Ledger.round2(com.familyhub.app.money.Ledger.emi(p, r, n))
        }
        if (m["amountInr"] == null && p != null) m["amountInr"] = p
    },
    amountKey = "amountInr",
    dateKeys = listOf(DateKey("nextDueMillis", "EMI due")),
    kind = "loan"
)

val StaffModule = ModuleDef(
    route = "staff", title = "Staff", icon = Icons.Rounded.Badge,
    collection = "items", typeValue = "staff",
    subtitleKeys = listOf("category", "phone"),
    fields = listOf(
        FieldDef("title", "Name", required = true),
        FieldDef("category", "Role", FieldType.CHOICE, listOf("Maid", "Cook", "Driver", "Gardener", "Security", "Other")),
        FieldDef("phone", "Phone"),
        FieldDef("amountInr", "Monthly salary (₹)", FieldType.NUMBER),
        FieldDef("salaryDueMillis", "Salary due on", FieldType.DATE),
        FieldDef("notes", "Timings, leave record, notes", FieldType.MULTILINE)
    ),
    amountKey = "amountInr", phoneKey = "phone", repeatKey = "repeat", alertOverdue = false, alertDays = 5,
    dateKeys = listOf(DateKey("salaryDueMillis", "Salary due")),
    constants = mapOf("repeat" to "MONTHLY")
)

val EmergencyModule = ModuleDef(
    route = "emergency", title = "Emergency", icon = Icons.Rounded.LocalHospital,
    collection = "items", typeValue = "emergency",
    subtitleKeys = listOf("category", "phone", "notes"),
    fields = listOf(
        FieldDef("title", "Name", required = true),
        FieldDef("category", "Type", FieldType.CHOICE,
            listOf("Contact", "Doctor", "Hospital", "Police or fire", "Insurance helpline", "Medical info", "Other")),
        FieldDef("phone", "Phone"),
        FieldDef("notes", "Details (blood group, address, allergies)", FieldType.MULTILINE)
    ),
    phoneKey = "phone", alerts = false,
    emptyText = "Add emergency contacts, doctors and critical medical information."
)

val NoticesModule = ModuleDef(
    route = "notices", title = "Notice Board", icon = Icons.Rounded.Campaign,
    collection = "items", typeValue = "notice",
    subtitleKeys = listOf("notes", "createdByName"),
    fields = listOf(
        FieldDef("title", "Headline", required = true),
        FieldDef("notes", "Message", FieldType.MULTILINE)
    ),
    newestFirst = true, autoCreatedAt = true, alerts = false,
    emptyText = "No announcements yet. Post something the whole family should see."
)

val SettleModule = ModuleDef(
    route = "settle", title = "Settle Up", icon = Icons.Rounded.SwapHoriz,
    collection = "items", typeValue = "settle",
    subtitleKeys = listOf("paidBy", "notes"),
    fields = listOf(
        FieldDef("title", "What was it for", required = true),
        FieldDef("amountInr", "Amount (₹)", FieldType.NUMBER, required = true),
        FieldDef("paidBy", "Paid by", FieldType.MEMBER, required = true),
        FieldDef("splitWith", "Split with (names, comma-separated; empty = everyone)"),
        FieldDef("splitMode", "How to split", FieldType.CHOICE, com.familyhub.app.money.Split.MODES),
        FieldDef("splitDetail", "Amount, % or shares per person (e.g. Asha 500, Ravi 300)",
            showIf = { (it["splitMode"] as? String).orEmpty().let { m -> m.isNotBlank() && m != com.familyhub.app.money.Split.EQUAL } }),
        FieldDef("trip", "Trip (optional, to split trip costs)"),
        FieldDef("notes", "Notes", FieldType.MULTILINE)
    ),
    amountKey = "amountInr", doneKey = "settled", doneLabel = "Settled",
    newestFirst = true, autoCreatedAt = true, alerts = false,
    constants = mapOf("settled" to false),
    emptyText = "Add shared expenses: split equally, by exact amounts, % or shares. The app works out the fewest payments.",
    kind = "settle"
)

val MemoriesModule = ModuleDef(
    route = "memories", title = "Memories", icon = Icons.Rounded.PhotoLibrary,
    collection = "items", typeValue = "memory",
    subtitleKeys = listOf("album", "category", "notes"),
    fields = listOf(
        FieldDef("title", "Title", required = true),
        FieldDef("category", "Type", FieldType.CHOICE,
            listOf("Milestone", "Birthday", "Anniversary", "Trip", "Festival", "Other")),
        FieldDef("album", "Album (e.g. Goa 2026, Baby's first year)"),
        FieldDef("dateMillis", "Date", FieldType.DATE, required = true),
        FieldDef("notes", "The story", FieldType.MULTILINE)
    ),
    dateKeys = listOf(DateKey("dateMillis", "On")), newestFirst = true, alerts = false,
    emptyText = "Save your family's special moments. Open one to add photos, reactions and comments."
)

val MaintenanceModule = ModuleDef(
    route = "maintenance", title = "Maintenance", icon = Icons.Rounded.Build,
    collection = "items", typeValue = "maintenance",
    subtitleKeys = listOf("category", "vendor"),
    fields = listOf(
        FieldDef("title", "What needs doing (for example AC service)", required = true),
        FieldDef("category", "Type", FieldType.CHOICE,
            listOf("AC service", "Water purifier", "Appliance", "Plumbing", "Electrical", "Painting", "Pest control", "Water tank", "Other")),
        FieldDef("vendor", "Vendor or technician"),
        FieldDef("purchaseMillis", "Bought on (appliances)", FieldType.DATE),
        FieldDef("warrantyStartMillis", "Warranty starts", FieldType.DATE),
        FieldDef("warrantyExpiryMillis", "Warranty ends", FieldType.DATE),
        FieldDef("lastServiceMillis", "Last service", FieldType.DATE),
        FieldDef("phone", "Phone"),
        FieldDef("costInr", "Last cost (₹)", FieldType.NUMBER),
        FieldDef("nextDueMillis", "Next due", FieldType.DATE),
        FieldDef("receiptPhoto", "Receipt, invoice or warranty card", FieldType.PHOTO),
        FieldDef("repeat", "Repeats", FieldType.CHOICE, listOf("None", "Monthly", "Quarterly", "Yearly")),
        FieldDef("notes", "Notes", FieldType.MULTILINE)
    ),
    amountKey = "costInr", phoneKey = "phone", repeatKey = "repeat", alertOverdue = true, alertDays = 14,
    dateKeys = listOf(DateKey("nextDueMillis", "Due"), DateKey("warrantyExpiryMillis", "Warranty ends"))
)

val NotesModule = ModuleDef(
    route = "notes", title = "Family Notes", icon = Icons.Rounded.StickyNote2,
    collection = "items", typeValue = "note",
    subtitleKeys = listOf("notes"),
    fields = listOf(
        FieldDef("title", "Title", required = true),
        FieldDef("category", "Type", FieldType.CHOICE, listOf("Idea", "Recipe", "Wifi or codes", "Gift idea", "Other")),
        FieldDef("notes", "Note", FieldType.MULTILINE)
    ),
    newestFirst = true, autoCreatedAt = true, alerts = false,
    emptyText = "Keep quick notes, recipes and ideas that everyone in the family can see."
)

val MealsModule = ModuleDef(
    route = "meals", title = "Meal Planner", icon = Icons.Rounded.Restaurant,
    collection = "items", typeValue = "meal",
    subtitleKeys = listOf("category", "notes"),
    fields = listOf(
        FieldDef("title", "Dish", required = true),
        FieldDef("category", "Meal", FieldType.CHOICE, listOf("Breakfast", "Lunch", "Snack", "Dinner")),
        FieldDef("dateMillis", "Day", FieldType.DATE, required = true),
        FieldDef("notes", "Ingredients or notes", FieldType.MULTILINE)
    ),
    dateKeys = listOf(DateKey("dateMillis", "Planned")), alerts = false,
    emptyText = "Plan what the family will eat. Add a dish and a day."
)

val ChoresModule = ModuleDef(
    route = "chores", title = "Chores", icon = Icons.Rounded.CleaningServices,
    collection = "items", typeValue = "chore",
    subtitleKeys = listOf("assignedTo", "repeat"),
    fields = listOf(
        FieldDef("title", "Chore", required = true),
        FieldDef("assignedTo", "Assigned to", FieldType.MEMBER),
        FieldDef("dueMillis", "Due", FieldType.DATE),
        FieldDef("repeat", "Repeats", FieldType.CHOICE, listOf("None", "Weekly", "Monthly")),
        FieldDef("notes", "Notes", FieldType.MULTILINE)
    ),
    dateKeys = listOf(DateKey("dueMillis", "Due")), repeatKey = "repeat",
    doneKey = "done", doneLabel = "Done", alertDays = 3,
    constants = mapOf("done" to false),
    emptyText = "Share out the chores and see who does what."
)

val BirthdaysModule = ModuleDef(
    route = "birthdays", title = "Birthdays", icon = Icons.Rounded.Cake,
    collection = "items", typeValue = "birthday",
    subtitleKeys = listOf("category", "notes"),
    fields = listOf(
        FieldDef("title", "Name", required = true),
        FieldDef("category", "Occasion", FieldType.CHOICE, listOf("Birthday", "Anniversary", "Other")),
        FieldDef("dateMillis", "Date", FieldType.DATE, required = true),
        FieldDef("repeat", "Repeats", FieldType.CHOICE, listOf("Yearly", "None")),
        FieldDef("notes", "Gift ideas, notes", FieldType.MULTILINE)
    ),
    dateKeys = listOf(DateKey("dateMillis", "On")), repeatKey = "repeat", alertDays = 14, alertOverdue = false,
    constants = mapOf("repeat" to "YEARLY"),
    emptyText = "Add birthdays and anniversaries so nobody is forgotten."
)

val PetsModule = ModuleDef(
    route = "pets", title = "Pets", icon = Icons.Rounded.Pets,
    collection = "items", typeValue = "pet",
    subtitleKeys = listOf("category", "vetName"),
    fields = listOf(
        FieldDef("title", "Pet name", required = true),
        FieldDef("category", "Kind", FieldType.CHOICE, listOf("Dog", "Cat", "Bird", "Fish", "Other")),
        FieldDef("vetName", "Vet"),
        FieldDef("phone", "Vet phone"),
        FieldDef("vaccineDueMillis", "Next vaccination", FieldType.DATE),
        FieldDef("notes", "Food, medicines, notes", FieldType.MULTILINE)
    ),
    phoneKey = "phone", alertOverdue = true, alertDays = 14,
    dateKeys = listOf(DateKey("vaccineDueMillis", "Vaccination"))
)

val SchoolModule = ModuleDef(
    route = "school", title = "School", icon = Icons.Rounded.School,
    collection = "items", typeValue = "school",
    subtitleKeys = listOf("category", "childName"),
    fields = listOf(
        FieldDef("title", "What (fee, exam, event)", required = true),
        FieldDef("category", "Type", FieldType.CHOICE, listOf("Fee", "Exam", "Event", "Homework", "Holiday", "Other")),
        FieldDef("childName", "Child", FieldType.MEMBER),
        FieldDef("amountInr", "Amount (₹)", FieldType.NUMBER),
        FieldDef("dueMillis", "Date", FieldType.DATE),
        FieldDef("notes", "Notes", FieldType.MULTILINE)
    ),
    amountKey = "amountInr", doneKey = "done", doneLabel = "Done", alertDays = 14,
    dateKeys = listOf(DateKey("dueMillis", "On")),
    constants = mapOf("done" to false)
)

val GoalsModule = ModuleDef(
    route = "goals", title = "Savings Goals", icon = Icons.Rounded.Savings,
    collection = "items", typeValue = "goal",
    subtitleKeys = listOf("savedInr", "notes"),
    fields = listOf(
        FieldDef("title", "Goal (for example new car)", required = true),
        FieldDef("amountInr", "Target (₹)", FieldType.NUMBER, required = true),
        FieldDef("savedInr", "Saved so far (₹)", FieldType.NUMBER),
        FieldDef("targetMillis", "Target date", FieldType.DATE),
        FieldDef("notes", "Notes", FieldType.MULTILINE),
        FieldDef("archived", "Archived (hide from the progress card)", FieldType.BOOL)
    ),
    amountKey = "amountInr", doneKey = "done", doneLabel = "Achieved", alertOverdue = false, alertDays = 30,
    dateKeys = listOf(DateKey("targetMillis", "Target")),
    constants = mapOf("done" to false)
)

/** Savings challenges: 52-week, a fixed amount every day or week, or no-spend days (worked out from Finance). */
val ChallengesModule = ModuleDef(
    route = "challenges", title = "Savings Challenges", icon = Icons.Rounded.EmojiEvents,
    collection = "items", typeValue = "challenge",
    subtitleKeys = listOf("kind", "savedInr"),
    fields = listOf(
        FieldDef("title", "Name (for example Holiday fund)", required = true),
        FieldDef("kind", "Challenge", FieldType.CHOICE, options = com.familyhub.app.money.Challenges.KINDS, required = true),
        FieldDef("stepInr", "Amount (₹): week 1 for the 52-week challenge, or each day or week", FieldType.NUMBER,
            showIf = { it["kind"] != com.familyhub.app.money.Challenges.NO_SPEND }),
        FieldDef("length", "How many days or weeks (not for 52-week)", FieldType.NUMBER,
            showIf = { it["kind"] != com.familyhub.app.money.Challenges.WEEK52 }),
        FieldDef("startMillis", "Start date", FieldType.DATE, required = true),
        FieldDef("savedInr", "Saved so far (₹)", FieldType.NUMBER, showIf = { it["kind"] != com.familyhub.app.money.Challenges.NO_SPEND }),
        FieldDef("notes", "Notes", FieldType.MULTILINE)
    ),
    amountKey = "savedInr", doneKey = "done", doneLabel = "Completed", alertOverdue = false,
    constants = mapOf("done" to false)
)

// ---------- daily life: medicines, reminders, vitals, habits, cards ----------

val MedicinesModule = ModuleDef(
    route = "medicines", title = "Medicines", icon = Icons.Rounded.Medication,
    collection = "items", typeValue = "medicine",
    subtitleKeys = listOf("memberName", "dose", "times", "food"),
    fields = listOf(
        FieldDef("title", "Medicine name", required = true),
        FieldDef("memberName", "For", FieldType.MEMBER),
        FieldDef("dose", "Dose (for example 1 tablet or 5 ml)"),
        FieldDef("times", "Times, 24-hour (for example 08:00, 20:00)", required = true),
        FieldDef("food", "When", FieldType.CHOICE, listOf("After food", "Before food", "With food", "Any time")),
        FieldDef("startMillis", "Start date", FieldType.DATE),
        FieldDef("endMillis", "Last day (leave empty if ongoing)", FieldType.DATE),
        FieldDef("stock", "Tablets or doses left", FieldType.NUMBER),
        FieldDef("notes", "Notes (doctor, purpose)", FieldType.MULTILINE)
    ),
    dateKeys = listOf(DateKey("endMillis", "Course ends")), alertOverdue = false, alertDays = 3,
    privateByDefault = true, kind = "medicine",
    emptyText = "Add medicines with their times. Family Hub reminds you at each time and you can mark them as taken."
)

val RemindersModule = ModuleDef(
    route = "reminders", title = "Reminders", icon = Icons.Rounded.Alarm,
    collection = "items", typeValue = "reminder",
    subtitleKeys = listOf("timeText", "repeat", "memberName", "notes"),
    fields = listOf(
        FieldDef("title", "Remind me to", required = true),
        FieldDef("dateMillis", "Date", FieldType.DATE, required = true),
        FieldDef("time", "Time (leave empty for all day)", FieldType.TIME),
        FieldDef("repeat", "Repeats", FieldType.CHOICE, com.familyhub.app.work.ReminderRules.REPEATS),
        FieldDef("everyDays", "Repeat every how many days", FieldType.NUMBER,
            showIf = { it["repeat"] == com.familyhub.app.work.ReminderRules.EVERY_FEW_DAYS }),
        FieldDef("earlyAlert", "Early alert", FieldType.CHOICE, com.familyhub.app.work.ReminderRules.EARLY_ALERTS.map { it.first }),
        FieldDef("memberName", "Whose phone rings", FieldType.MEMBER),
        FieldDef("notes", "Notes", FieldType.MULTILINE)
    ),
    dateKeys = listOf(DateKey("dateMillis", "Remind")), repeatKey = "repeat",
    doneKey = "done", doneLabel = "Done", alertDays = 2,
    constants = mapOf("done" to false), privateByDefault = true,
    emptyText = "Set a reminder with a date and time and you'll get a notification right on time."
)

val FoodModule = ModuleDef(
    route = "food", title = "Food diary", icon = Icons.Rounded.Restaurant,
    collection = "items", typeValue = "food",
    subtitleKeys = listOf("meal", "kcalText", "memberName"),
    fields = listOf(
        FieldDef("title", "What you ate (for example 2 rotis and dal)", required = true),
        FieldDef("meal", "Meal", FieldType.CHOICE, listOf("Breakfast", "Lunch", "Dinner", "Snack")),
        FieldDef("kcal", "Calories (kcal, optional)", FieldType.NUMBER),
        FieldDef("dateMillis", "Date", FieldType.DATE, required = true),
        FieldDef("memberName", "Whose", FieldType.MEMBER),
        FieldDef("notes", "Notes", FieldType.MULTILINE)
    ),
    dateKeys = listOf(DateKey("dateMillis", "Eaten")), newestFirst = true, alerts = false,
    privateByDefault = true, kind = "food",
    postProcess = { m, _, _ ->
        val k = (m["kcal"] as? Number)?.toInt()
        m["kcalText"] = if (k != null && k > 0) "$k kcal" else ""
    },
    emptyText = "Note what you eat, with calories if you know them. Only you see it unless you share it. Family Hub does not estimate calories or give diet advice."
)

val VITAL_KINDS = listOf(
    "Weight (kg)", "Blood pressure", "Sugar fasting", "Sugar after food",
    "Heart rate", "SpO2 (%)", "Temperature", "Sleep (hours)", "Other"
)

val VitalsModule = ModuleDef(
    route = "vitals", title = "Vitals", icon = Icons.Rounded.MonitorHeart,
    collection = "items", typeValue = "vital", titleKey = "kind",
    subtitleKeys = listOf("value", "memberName", "notes"),
    fields = listOf(
        FieldDef("kind", "Reading", FieldType.CHOICE, VITAL_KINDS, required = true),
        FieldDef("value", "Value (for example 72.5 or 120/80)", required = true),
        FieldDef("dateMillis", "Date", FieldType.DATE, required = true),
        FieldDef("memberName", "Whose", FieldType.MEMBER),
        FieldDef("notes", "Notes", FieldType.MULTILINE)
    ),
    dateKeys = listOf(DateKey("dateMillis", "Measured")), newestFirst = true, alerts = false,
    privateByDefault = true,
    emptyText = "Log weight, blood pressure, sugar and more to see your trends in Health."
)

val HabitsModule = ModuleDef(
    route = "habits", title = "Daily Habits", icon = Icons.Rounded.SelfImprovement,
    collection = "items", typeValue = "habit",
    subtitleKeys = listOf("notes"),
    fields = listOf(
        FieldDef("title", "Habit (for example Walk 30 minutes)", required = true),
        FieldDef("notes", "Details", FieldType.MULTILINE)
    ),
    alerts = false, privateByDefault = true,
    emptyText = "Add habits you want to keep, then tick them off each day in Health."
)

val WORKOUT_KINDS = listOf("Walk", "Run", "Cycling", "Yoga", "Gym", "Swimming", "Sports", "Dance", "Other")

val WorkoutsModule = ModuleDef(
    route = "workouts", title = "Workouts", icon = Icons.Rounded.FitnessCenter,
    collection = "items", typeValue = "workout", titleKey = "activity",
    subtitleKeys = listOf("minutesText", "memberName", "notes"),
    fields = listOf(
        FieldDef("activity", "Activity", FieldType.CHOICE, WORKOUT_KINDS, required = true),
        FieldDef("minutes", "Minutes", FieldType.NUMBER, required = true),
        FieldDef("dateMillis", "Date", FieldType.DATE, required = true),
        FieldDef("caloriesKcal", "Calories burned (optional)", FieldType.NUMBER),
        FieldDef("memberName", "Who", FieldType.MEMBER),
        FieldDef("notes", "Notes", FieldType.MULTILINE)
    ),
    dateKeys = listOf(DateKey("dateMillis", "Done on")), newestFirst = true, alerts = false,
    privateByDefault = true,
    postProcess = { m, _, _ ->
        val mins = (m["minutes"] as? Number)?.toInt()
        m["minutesText"] = if (mins != null) "$mins min" else ""
    },
    emptyText = "Log walks, yoga, gym and sports to see your active minutes each week in Health & Fitness."
)

val CardsModule = ModuleDef(
    route = "card_list", title = "Credit Cards", icon = Icons.Rounded.CreditCard,
    collection = "items", typeValue = "card",
    subtitleKeys = listOf("bank", "last4"),
    fields = listOf(
        FieldDef("title", "Card name (for example HDFC Millennia)", required = true),
        FieldDef("bank", "Bank"),
        FieldDef("last4", "Last 4 digits"),
        FieldDef("statementDay", "Statement date (day of month, 1 to 31)", FieldType.NUMBER, required = true),
        FieldDef("dueDay", "Payment due date (day of month)", FieldType.NUMBER, required = true),
        FieldDef("limitInr", "Credit limit (₹)", FieldType.NUMBER),
        FieldDef("openingOutstanding", "Amount owed today (₹, before entries added here)", FieldType.NUMBER),
        FieldDef("openingDateMillis", "Owed as on", FieldType.DATE),
        FieldDef("rewardPoints", "Reward points", FieldType.NUMBER),
        FieldDef("notes", "Notes", FieldType.MULTILINE)
    ),
    postProcess = { m, _, _ ->
        if (m["openingOutstanding"] != null && m["openingDateMillis"] == null) m["openingDateMillis"] = startOfDay(System.currentTimeMillis())
    },
    alerts = false, privateByDefault = true, kind = "card",
    emptyText = "Add your credit cards with statement and due dates to follow each billing cycle."
)

val PoliciesModule = ModuleDef(
    route = "policies", title = "Insurance policies", icon = Icons.Rounded.Shield,
    collection = "items", typeValue = "policy",
    subtitleKeys = listOf("category", "insurer"),
    fields = listOf(
        FieldDef("title", "Policy name", required = true),
        FieldDef("insurer", "Insurer"),
        FieldDef("category", "Type", FieldType.CHOICE, listOf("Health", "Life", "Motor", "Home", "Travel", "Other")),
        FieldDef("amountInr", "Premium (₹)", FieldType.NUMBER),
        FieldDef("premiumCycle", "Premium paid", FieldType.CHOICE, listOf("Yearly", "Half-yearly", "Quarterly", "Monthly")),
        FieldDef("premiumDueMillis", "Next premium due", FieldType.DATE),
        FieldDef("expiryMillis", "Policy expires on", FieldType.DATE),
        FieldDef("notes", "Who is covered, cover amount, notes", FieldType.MULTILINE)
    ),
    amountKey = "amountInr", alertDays = 30,
    dateKeys = listOf(DateKey("premiumDueMillis", "Premium due"), DateKey("expiryMillis", "Expires")),
    kind = "policy",
    emptyText = "Add your insurance policies, or find them in SMS and Gmail from Import, to be reminded before premiums and renewals."
)

val AccountsModule = ModuleDef(
    route = "accounts", title = "Bank accounts", icon = Icons.Rounded.AccountBalance,
    collection = "items", typeValue = "account",
    subtitleKeys = listOf("bank", "last4"),
    fields = listOf(
        FieldDef("title", "Account name (for example HDFC savings, Cash)", required = true),
        FieldDef("category", "Type", FieldType.CHOICE, listOf("Savings", "Salary", "Current", "Joint", "Cash", "Wallet", "Other")),
        FieldDef("bank", "Bank", showIf = { m -> m.t("category") != "Cash" && m.t("category") != "Wallet" }),
        FieldDef("last4", "Last 4 digits", showIf = { m -> m.t("category") != "Cash" && m.t("category") != "Wallet" }),
        FieldDef("openingBalance", "Balance today (₹)", FieldType.NUMBER),
        FieldDef("openingDateMillis", "Balance as on", FieldType.DATE),
        FieldDef("notes", "Notes", FieldType.MULTILINE)
    ),
    postProcess = { m, _, _ ->
        if (m["openingBalance"] != null && m["openingDateMillis"] == null) m["openingDateMillis"] = startOfDay(System.currentTimeMillis())
    },
    alerts = false, privateByDefault = true, kind = "account",
    emptyText = "Add bank accounts, cash and wallets with today's balance. Import also finds accounts in bank SMS by the last four digits."
)

val DebitCardsModule = ModuleDef(
    route = "debit_cards", title = "Debit cards", icon = Icons.Rounded.CreditCard,
    collection = "items", typeValue = "debit_card",
    subtitleKeys = listOf("account", "last4"),
    fields = listOf(
        FieldDef("title", "Card name (for example HDFC debit)", required = true),
        FieldDef("account", "Linked bank account", FieldType.CHOICE, listOf("None"), required = true, dynamic = "accounts"),
        FieldDef("last4", "Last 4 digits"),
        FieldDef("notes", "Notes", FieldType.MULTILINE)
    ),
    alerts = false, privateByDefault = true, kind = "debit_card",
    emptyText = "Link each debit card to its bank account, so card spends come out of the right balance. Only the last four digits are kept."
)

val CategoriesModule = ModuleDef(
    route = "categories", title = "Money categories", icon = Icons.Rounded.Category,
    collection = "items", typeValue = "category",
    subtitleKeys = listOf("parent", "kind"),
    fields = listOf(
        FieldDef("title", "Name", required = true),
        FieldDef("parent", "Inside category (None = a main category)", FieldType.CHOICE, listOf("None"), dynamic = "categories"),
        FieldDef("kind", "Used for", FieldType.CHOICE, listOf("Spending", "Income", "Both")),
        FieldDef("budgetInr", "Monthly budget (₹, optional)", FieldType.NUMBER)
    ),
    alerts = false, kind = "category",
    emptyText = "The built-in categories are always there. Add your own, or subcategories inside one (for example Dining › Swiggy)."
)

val AllModules: List<ModuleDef> = listOf(
    CalendarModule, FinanceModule, HouseholdModule, ShoppingModule, TravelModule,
    VaultModule, DocumentsModule, HealthModule, VehiclesModule, PropertyModule,
    SubscriptionsModule, LoansModule, StaffModule, EmergencyModule, NoticesModule,
    SettleModule, MemoriesModule,
    MaintenanceModule, NotesModule, MealsModule, ChoresModule, BirthdaysModule,
    PetsModule, SchoolModule, GoalsModule, ChallengesModule,
    MedicinesModule, RemindersModule, VitalsModule, HabitsModule, CardsModule, WorkoutsModule, FoodModule,
    PoliciesModule, AccountsModule, DebitCardsModule, CategoriesModule
)

/** Modules reached from inside other screens rather than from the More grid. */
val HiddenFromMore: Set<String> = setOf("card_list", "habits", "workouts", "debit_cards", "categories", "food")

val ModuleByRoute: Map<String, ModuleDef> = AllModules.associateBy { it.route }

/** Shortcut chips shown under the Finance summary (route to label). Filled in as screens are added. */
object FinanceShortcutRegistry {
    val items: List<Pair<String, String>> = listOf(
        "due_calendar" to "Due calendar",
        "insights" to "Insights",
        "import" to "Import SMS / email"
    )

    fun icon(route: String): ImageVector = when (route) {
        "insights" -> Icons.Rounded.PieChart
        "due_calendar" -> Icons.Rounded.EventNote
        "cards" -> Icons.Rounded.CreditCard
        "import" -> Icons.Rounded.MarkEmailRead
        else -> Icons.Rounded.ChevronRight
    }
}

// ---------- filtering, sorting, alerts ----------

fun recordsFor(def: ModuleDef, all: List<Rec>): List<Rec> {
    val softKey = def.softDeleteKey
    val wanted = def.typeValue
    return all.filter { r ->
        val typeOk = if (wanted == null) true else {
            val t = r.str("type")
            (if (t.isBlank() && def.defaultType) wanted else t) == wanted
        }
        // Anything in Recently deleted is hidden everywhere.
        val notDeleted = (softKey == null || !r.bool(softKey)) && !r.bool("isDeleted")
        typeOk && notDeleted
    }
}

fun sortKey(def: ModuleDef, r: Rec): Long {
    val dk = def.dateKeys.firstOrNull()
    val rep = def.repeatKey?.let { r.str(it) }.orEmpty()
    val d = if (dk != null) r.lng(dk.key) else null
    return if (d != null) nextOccurrenceOf(r, d, rep) else (r.lng("createdAtMillis") ?: r.lng("updatedAt") ?: 0L)
}

fun sortRecords(def: ModuleDef, recs: List<Rec>): List<Rec> {
    val today0 = startOfDay(System.currentTimeMillis())
    val base: List<Rec> = if (def.newestFirst || def.dateKeys.isEmpty()) {
        if (def.newestFirst) recs.sortedByDescending { sortKey(def, it) } else recs.sortedBy { sortKey(def, it) }
    } else {
        val upcoming = recs.filter { sortKey(def, it) >= today0 }.sortedBy { sortKey(def, it) }
        val past = recs.filter { sortKey(def, it) < today0 }.sortedByDescending { sortKey(def, it) }
        upcoming + past
    }
    val done = def.doneKey
    return if (done != null) base.sortedBy { it.bool(done) } else base
}

data class AlertItem(
    val title: String,
    val label: String,
    val dateMillis: Long,
    val route: String,
    val overdue: Boolean
)

fun daysUntil(dateMillis: Long, now: Long = System.currentTimeMillis()): Int =
    ((startOfDay(dateMillis) - startOfDay(now)) / DAY_MS).toInt()

private val MONEY_DUE_KINDS = setOf("finance", "loan", "subscription")

/** data is keyed by Firestore collection name (items, events, expenses, tasks, trips). */
fun buildAlerts(data: Map<String, List<Rec>>): List<AlertItem> {
    val out = mutableListOf<AlertItem>()
    val now = System.currentTimeMillis()
    for (def in AllModules) {
        if (!def.alerts || def.dateKeys.isEmpty()) continue
        // Money dues (bills, EMIs, renewals, premiums) come from moneyAlerts, which knows what is paid.
        if (def.kind in MONEY_DUE_KINDS) continue
        val recs = recordsFor(def, data[def.collection].orEmpty())
        val doneKey = def.doneKey
        for (r in recs) {
            if (doneKey != null && r.bool(doneKey)) continue
            if ((r.lng("snoozedUntilMillis") ?: 0L) > now) continue
            val rep = def.repeatKey?.let { r.str(it) }.orEmpty()
            for (dk in def.dateKeys) {
                if (def.kind == "policy" && dk.key == "premiumDueMillis") continue
                val raw = r.lng(dk.key) ?: continue
                val d = nextOccurrenceOf(r, raw, rep)
                val days = daysUntil(d)
                // A document can choose its own reminder window ("Remind me: 60 days before").
                val window = r.str("remindBefore").filter(Char::isDigit).toIntOrNull() ?: def.alertDays
                if (days > window) continue
                if (r.str("renewalStatus").equals("Renewed", true) || r.str("renewalStatus").equals("Not renewing", true)) continue
                if (days < 0 && (!def.alertOverdue || days < -30)) continue
                out.add(AlertItem(r.str(def.titleKey), dk.label, d, def.route, days < 0))
            }
        }
    }
    return out.sortedBy { it.dateMillis }
}
