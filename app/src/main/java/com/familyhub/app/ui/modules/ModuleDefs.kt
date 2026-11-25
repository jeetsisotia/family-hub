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

/** For repeating items (birthdays, renewals, salaries) returns the next date on or after today. */
fun nextOccurrence(start: Long, repeat: String, now: Long = System.currentTimeMillis()): Long {
    val rep = repeat.uppercase()
    val today0 = startOfDay(now)
    if (rep.isBlank() || rep == "NONE" || start >= today0) return start
    val cal = Calendar.getInstance()
    cal.timeInMillis = start
    var guard = 0
    while (cal.timeInMillis < today0 && guard < 5000) {
        when (rep) {
            "YEARLY" -> cal.add(Calendar.YEAR, 1)
            "QUARTERLY" -> cal.add(Calendar.MONTH, 3)
            "MONTHLY" -> cal.add(Calendar.MONTH, 1)
            "WEEKLY" -> cal.add(Calendar.DAY_OF_YEAR, 7)
            else -> return start
        }
        guard++
    }
    return cal.timeInMillis
}

// ---------- module definitions ----------

enum class FieldType { TEXT, MULTILINE, NUMBER, DATE, CHOICE, MEMBER, BOOL }

data class FieldDef(
    val key: String,
    val label: String,
    val type: FieldType = FieldType.TEXT,
    val options: List<String> = emptyList(),
    val required: Boolean = false
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
    val kind: String = "generic"
)

private val financeCategories = listOf(
    "Groceries", "Rent", "Utilities", "Education", "Health", "Transport", "Fuel",
    "Dining", "Shopping", "Entertainment", "EMI", "Insurance", "Salary", "Other"
)

val CalendarModule = ModuleDef(
    route = "calendar", title = "Calendar", icon = Icons.Rounded.CalendarMonth,
    collection = "events",
    titleKey = "title", subtitleKeys = listOf("category", "notes"),
    fields = listOf(
        FieldDef("title", "Title", required = true),
        FieldDef("category", "Category", FieldType.CHOICE,
            listOf("Family", "Birthday", "Anniversary", "Doctor", "School", "Office", "Function", "Festival", "Other")),
        FieldDef("startTimeMillis", "Date", FieldType.DATE, required = true),
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

val FinanceModule = ModuleDef(
    route = "finance", title = "Finance", icon = Icons.Rounded.AccountBalanceWallet,
    collection = "expenses",
    titleKey = "title", subtitleKeys = listOf("type", "category", "memberName"),
    fields = listOf(
        FieldDef("type", "Type", FieldType.CHOICE, listOf("EXPENSE", "INCOME", "BILL", "EMI", "SAVING"), true),
        FieldDef("title", "Title", required = true),
        FieldDef("amountInr", "Amount (₹)", FieldType.NUMBER, required = true),
        FieldDef("category", "Category", FieldType.CHOICE, financeCategories),
        FieldDef("dateMillis", "Date or due date", FieldType.DATE, required = true),
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
        if (m["memberUid"] == null) {
            m["memberUid"] = uid
            m["memberName"] = name
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
        FieldDef("subtitle", "Quantity or note")
    ),
    doneKey = "isCompleted", doneLabel = "Bought", alerts = false,
    emptyText = "The shopping list is empty. Add milk, groceries and anything else the family needs.",
    constants = mapOf("isCompleted" to false)
)

val TravelModule = ModuleDef(
    route = "travel", title = "Travel", icon = Icons.Rounded.FlightTakeoff,
    collection = "trips",
    titleKey = "destination", subtitleKeys = listOf("transportDetails", "hotelDetails"),
    fields = listOf(
        FieldDef("destination", "Destination", required = true),
        FieldDef("startDateMillis", "Starts on", FieldType.DATE, required = true),
        FieldDef("endDateMillis", "Ends on", FieldType.DATE, required = true),
        FieldDef("transportDetails", "Flights, trains and tickets", FieldType.MULTILINE),
        FieldDef("hotelDetails", "Hotel bookings", FieldType.MULTILINE),
        FieldDef("estimatedBudgetInr", "Trip budget (₹)", FieldType.NUMBER),
        FieldDef("itineraryNotes", "Itinerary", FieldType.MULTILINE),
        FieldDef("packingNotes", "Packing list", FieldType.MULTILINE)
    ),
    amountKey = "estimatedBudgetInr",
    dateKeys = listOf(DateKey("startDateMillis", "Starts"), DateKey("endDateMillis", "Ends")),
    alertOverdue = false, alertDays = 14
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
                "Life insurance", "Vehicle insurance", "Property", "Bank", "Education", "Other")),
        FieldDef("holder", "Belongs to", FieldType.MEMBER),
        FieldDef("number", "Document or policy number"),
        FieldDef("expiryDateMillis", "Expires or renews on", FieldType.DATE),
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
    dateKeys = listOf(DateKey("dateMillis", "Due")), alertOverdue = false, alertDays = 7
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
        FieldDef("notes", "RC details, service history, challans", FieldType.MULTILINE)
    ),
    dateKeys = listOf(
        DateKey("insuranceExpiryMillis", "Insurance"),
        DateKey("pucExpiryMillis", "PUC"),
        DateKey("serviceDueMillis", "Service")
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
        FieldDef("category", "Type", FieldType.CHOICE,
            listOf("OTT", "Music", "Software", "Membership", "Gym", "Insurance", "Utility", "Other")),
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
    subtitleKeys = listOf("lender", "notes"),
    fields = listOf(
        FieldDef("title", "Loan name", required = true),
        FieldDef("lender", "Bank or lender"),
        FieldDef("amountInr", "Outstanding amount (₹)", FieldType.NUMBER, required = true),
        FieldDef("emiAmount", "Monthly EMI (₹)", FieldType.NUMBER),
        FieldDef("nextDueMillis", "Next EMI due", FieldType.DATE),
        FieldDef("interestRate", "Interest rate (%)", FieldType.NUMBER),
        FieldDef("notes", "Account number, tenure, notes", FieldType.MULTILINE)
    ),
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
        FieldDef("notes", "Notes", FieldType.MULTILINE)
    ),
    amountKey = "amountInr", doneKey = "settled", doneLabel = "Settled",
    newestFirst = true, autoCreatedAt = true, alerts = false,
    constants = mapOf("settled" to false),
    emptyText = "Add shared expenses. The app works out who owes whom, split equally.",
    kind = "settle"
)

val MemoriesModule = ModuleDef(
    route = "memories", title = "Memories", icon = Icons.Rounded.PhotoLibrary,
    collection = "items", typeValue = "memory",
    subtitleKeys = listOf("category", "notes"),
    fields = listOf(
        FieldDef("title", "Title", required = true),
        FieldDef("category", "Type", FieldType.CHOICE,
            listOf("Milestone", "Birthday", "Anniversary", "Trip", "Festival", "Other")),
        FieldDef("dateMillis", "Date", FieldType.DATE, required = true),
        FieldDef("notes", "The story", FieldType.MULTILINE)
    ),
    dateKeys = listOf(DateKey("dateMillis", "On")), newestFirst = true, alerts = false,
    emptyText = "Write down your family's special moments. (Text only, photo uploads need a paid Firebase plan.)"
)

val MaintenanceModule = ModuleDef(
    route = "maintenance", title = "Maintenance", icon = Icons.Rounded.Build,
    collection = "items", typeValue = "maintenance",
    subtitleKeys = listOf("category", "vendor"),
    fields = listOf(
        FieldDef("title", "What needs doing (for example AC service)", required = true),
        FieldDef("category", "Type", FieldType.CHOICE,
            listOf("Plumbing", "Electrical", "AC or appliance", "Painting", "Pest control", "Water tank", "Other")),
        FieldDef("vendor", "Vendor or technician"),
        FieldDef("phone", "Phone"),
        FieldDef("costInr", "Last cost (₹)", FieldType.NUMBER),
        FieldDef("nextDueMillis", "Next due", FieldType.DATE),
        FieldDef("repeat", "Repeats", FieldType.CHOICE, listOf("None", "Monthly", "Quarterly", "Yearly")),
        FieldDef("notes", "Notes", FieldType.MULTILINE)
    ),
    amountKey = "costInr", phoneKey = "phone", repeatKey = "repeat", alertOverdue = true, alertDays = 14,
    dateKeys = listOf(DateKey("nextDueMillis", "Due"))
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
        FieldDef("notes", "Notes", FieldType.MULTILINE)
    ),
    amountKey = "amountInr", doneKey = "done", doneLabel = "Achieved", alertOverdue = false, alertDays = 30,
    dateKeys = listOf(DateKey("targetMillis", "Target")),
    constants = mapOf("done" to false)
)

val AllModules: List<ModuleDef> = listOf(
    CalendarModule, FinanceModule, HouseholdModule, ShoppingModule, TravelModule,
    VaultModule, DocumentsModule, HealthModule, VehiclesModule, PropertyModule,
    SubscriptionsModule, LoansModule, StaffModule, EmergencyModule, NoticesModule,
    SettleModule, MemoriesModule,
    MaintenanceModule, NotesModule, MealsModule, ChoresModule, BirthdaysModule,
    PetsModule, SchoolModule, GoalsModule
)

val ModuleByRoute: Map<String, ModuleDef> = AllModules.associateBy { it.route }

// ---------- filtering, sorting, alerts ----------

fun recordsFor(def: ModuleDef, all: List<Rec>): List<Rec> {
    val softKey = def.softDeleteKey
    val wanted = def.typeValue
    return all.filter { r ->
        val typeOk = if (wanted == null) true else {
            val t = r.str("type")
            (if (t.isBlank() && def.defaultType) wanted else t) == wanted
        }
        val notDeleted = softKey == null || !r.bool(softKey)
        typeOk && notDeleted
    }
}

fun sortKey(def: ModuleDef, r: Rec): Long {
    val dk = def.dateKeys.firstOrNull()
    val rep = def.repeatKey?.let { r.str(it) }.orEmpty()
    val d = if (dk != null) r.lng(dk.key) else null
    return if (d != null) nextOccurrence(d, rep) else (r.lng("createdAtMillis") ?: r.lng("updatedAt") ?: 0L)
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

/** data is keyed by Firestore collection name (items, events, expenses, tasks, trips). */
fun buildAlerts(data: Map<String, List<Rec>>): List<AlertItem> {
    val out = mutableListOf<AlertItem>()
    for (def in AllModules) {
        if (!def.alerts || def.dateKeys.isEmpty()) continue
        val recs = recordsFor(def, data[def.collection].orEmpty())
        val doneKey = def.doneKey
        for (r in recs) {
            if (doneKey != null && r.bool(doneKey)) continue
            val rep = def.repeatKey?.let { r.str(it) }.orEmpty()
            for (dk in def.dateKeys) {
                val raw = r.lng(dk.key) ?: continue
                val d = nextOccurrence(raw, rep)
                val days = daysUntil(d)
                if (days > def.alertDays) continue
                if (days < 0 && (!def.alertOverdue || days < -30)) continue
                out.add(AlertItem(r.str(def.titleKey), dk.label, d, def.route, days < 0))
            }
        }
    }
    return out.sortedBy { it.dateMillis }
}
