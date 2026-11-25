package com.familyhub.app.core.ai

import com.familyhub.app.data.model.*
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class FamilyAiAssistant {
    private val inr = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    fun answerQuery(
        query: String,
        member: FamilyMember,
        events: List<FamilyEvent>,
        transactions: List<FinanceTransaction>,
        documents: List<FamilyDocument>,
        tasks: List<HouseholdTask>,
        trips: List<FamilyTrip>
    ): String {
        val q = query.lowercase(Locale.getDefault())
        val now = System.currentTimeMillis()
        val weekAhead = now + 7L * 24 * 60 * 60 * 1000

        return when {
            "event" in q || "schedule" in q || "week" in q -> {
                if (AppModuleType.CALENDAR.name !in member.allowedModules) {
                    return "You do not have permission to view the Family Calendar."
                }
                val upcoming = events.filter { it.startTimeMillis in now..weekAhead }
                if (upcoming.isEmpty()) "No family events are scheduled for the next 7 days."
                else "Upcoming events this week:\n" + upcoming.joinToString("\n") {
                    "• ${it.title} (${it.category}) — ${dateFmt.format(Date(it.startTimeMillis))}"
                }
            }
            "birthday" in q || "anniversary" in q -> {
                val bdays = events.filter {
                    it.category in listOf("Birthday", "Anniversary") && it.startTimeMillis >= now
                }
                if (bdays.isEmpty()) "No upcoming birthdays or anniversaries found."
                else "Next celebration: ${bdays.first().title} on ${dateFmt.format(Date(bdays.first().startTimeMillis))}."
            }
            "expense" in q || "spend" in q || "budget" in q -> {
                if (AppModuleType.FINANCE.name !in member.allowedModules) {
                    return "Finance module access is restricted for your role."
                }
                val total = transactions.filter { it.type == "EXPENSE" }.sumOf { it.amountInr }
                "Total recorded family expenses this month: ${inr.format(total)} across ${transactions.count { it.type == "EXPENSE" }} entries."
            }
            "bill" in q || "due" in q || "emi" in q -> {
                if (AppModuleType.FINANCE.name !in member.allowedModules) {
                    return "Finance module access is restricted for your role."
                }
                val unpaid = transactions.filter { !it.isPaid && it.type in listOf("BILL", "EMI") }
                if (unpaid.isEmpty()) "All bills and EMIs are currently paid."
                else "Upcoming unpaid bills/EMIs:\n" + unpaid.joinToString("\n") {
                    "• ${it.title}: ${inr.format(it.amountInr)} (Due ${it.dueDateMillis?.let { d -> dateFmt.format(Date(d)) } ?: "Soon"})"
                }
            }
            "insurance" in q || "expire" in q || "document" in q -> {
                if (AppModuleType.DOCUMENTS.name !in member.allowedModules) {
                    return "Document Vault access is restricted for your role."
                }
                val expiring = documents.filter { it.expiryDateMillis != null }.sortedBy { it.expiryDateMillis }
                if (expiring.isEmpty()) "No authorized documents have upcoming expiry dates."
                else "Authorized document renewals:\n" + expiring.take(3).joinToString("\n") {
                    "• ${it.title} (${it.category}) expires on ${dateFmt.format(Date(it.expiryDateMillis!!))}"
                }
            }
            "task" in q || "pending" in q || "shopping" in q -> {
                val pending = tasks.filter { !it.isCompleted }
                if (pending.isEmpty()) "All household tasks and shopping items are completed!"
                else "You have ${pending.size} pending items:\n" + pending.take(5).joinToString("\n") {
                    "• [${it.type}] ${it.title} (Assigned: ${it.assignedToName.ifBlank { "Anyone" }})"
                }
            }
            "trip" in q || "travel" in q -> {
                if (AppModuleType.TRAVEL.name !in member.allowedModules) {
                    return "Travel Planner access is restricted for your role."
                }
                val nextTrip = trips.firstOrNull { it.endDateMillis >= now }
                nextTrip?.let {
                    "Upcoming trip to ${it.destination} (${dateFmt.format(Date(it.startDateMillis))} – ${dateFmt.format(Date(it.endDateMillis))}). Hotel: ${it.hotelDetails}."
                } ?: "No upcoming family trips planned."
            }
            else -> "I can help with this week's events, monthly expenses in ₹, unpaid bills, authorized document expiries, pending household tasks, birthdays, and travel plans."
        }
    }
}
