package com.familyhub.app.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName

enum class FamilyRole { OWNER, ADULT, CHILD, CUSTOM }

enum class AppModuleType {
    CALENDAR, FINANCE, HOME_VAULT, DOCUMENTS, HEALTH, HOUSEHOLD, TRAVEL, MEMORIES
}

data class UserProfile(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val photoUrl: String = "",
    val bloodGroup: String = "",
    val phone: String = "",
    val activeFamilyId: String = "",
    val familyIds: List<String> = emptyList(),
    val fcmToken: String = "",
    val createdAt: Timestamp = Timestamp.now()
)

data class FamilyGroup(
    val id: String = "",
    val name: String = "",
    val ownerUid: String = "",
    val inviteCode: String = "",
    val currency: String = "INR",
    val monthlyBudget: Double = 100000.0,
    val yearlyBudget: Double = 1200000.0,
    val createdAt: Timestamp = Timestamp.now()
)

data class FamilyMember(
    val uid: String = "",
    val familyId: String = "",
    val name: String = "",
    val email: String = "",
    val photoUrl: String = "",
    val relation: String = "Member",
    val role: FamilyRole = FamilyRole.ADULT,
    val status: String = "ACTIVE",
    val allowedModules: List<String> = AppModuleType.entries.map { it.name },
    val canEditFinance: Boolean = true,
    val canViewPrivateDocs: Boolean = false,
    val canViewHealth: Boolean = true,
    val joinedAt: Timestamp = Timestamp.now(),
    // Shared from the person's profile only if they turned sharing on.
    val photoData: String = "",
    val phone: String = "",
    val bloodGroup: String = "",
    val dobMillis: Long? = null,
    val anniversaryMillis: Long? = null,
    val emergencyName: String = "",
    val emergencyPhone: String = "",
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    // Shared activity numbers for family comparison.
    val stepsToday: Long? = null,
    val stepsDate: String = "",
    val stepsWeek: Long? = null
)

data class FamilyEvent(
    val id: String = "",
    val familyId: String = "",
    val title: String = "",
    val category: String = "Family",
    val startTimeMillis: Long = System.currentTimeMillis(),
    val endTimeMillis: Long = System.currentTimeMillis() + 3600000L,
    val recurrence: String = "NONE",
    val reminderMinutesBefore: Int = 30,
    val participantUids: List<String> = emptyList(),
    val createdByUid: String = "",
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

data class FinanceTransaction(
    val id: String = "",
    val familyId: String = "",
    val type: String = "EXPENSE",
    val title: String = "",
    val amountInr: Double = 0.0,
    val category: String = "Groceries",
    val memberUid: String = "",
    val memberName: String = "",
    val dateMillis: Long = System.currentTimeMillis(),
    val dueDateMillis: Long? = null,
    @get:PropertyName("isPaid") val isPaid: Boolean = true,
    val notes: String = "",
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
)

data class HomeVaultItem(
    val id: String = "",
    val familyId: String = "",
    val name: String = "",
    val category: String = "Electronics",
    val brand: String = "",
    val model: String = "",
    val serialNumber: String = "",
    val purchaseDateMillis: Long = System.currentTimeMillis(),
    val purchasePriceInr: Double = 0.0,
    val warrantyExpiryMillis: Long = System.currentTimeMillis() + 31536000000L,
    val serviceProvider: String = "",
    val servicePhone: String = "",
    val invoiceStoragePath: String = "",
    val photoStoragePath: String = "",
    val maintenanceLog: List<String> = emptyList()
)

data class FamilyDocument(
    val id: String = "",
    val familyId: String = "",
    val ownerUid: String = "",
    val title: String = "",
    val category: String = "Identity",
    val associatedMemberName: String = "",
    val referenceNote: String = "",
    val issueDateMillis: Long? = null,
    val expiryDateMillis: Long? = null,
    val storagePath: String = "",
    val mimeType: String = "application/pdf",
    @get:PropertyName("isPrivate") val isPrivate: Boolean = true,
    val authorizedUids: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class HealthRecord(
    val id: String = "",
    val familyId: String = "",
    val memberUid: String = "",
    val memberName: String = "",
    val bloodGroup: String = "",
    val allergies: String = "",
    val emergencyContact: String = "",
    val doctorName: String = "",
    val doctorPhone: String = "",
    val medicines: List<String> = emptyList(),
    val vaccinations: List<String> = emptyList(),
    val insurancePolicyNo: String = "",
    val nextAppointmentMillis: Long? = null,
    val authorizedUids: List<String> = emptyList()
)

data class HouseholdTask(
    val id: String = "",
    val familyId: String = "",
    val type: String = "TASK",
    val title: String = "",
    val subtitle: String = "",
    val assignedToName: String = "",
    val dueDateMillis: Long? = null,
    @get:PropertyName("isCompleted") val isCompleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

data class FamilyTrip(
    val id: String = "",
    val familyId: String = "",
    val destination: String = "",
    val startDateMillis: Long = System.currentTimeMillis(),
    val endDateMillis: Long = System.currentTimeMillis() + 259200000L,
    val transportDetails: String = "",
    val hotelDetails: String = "",
    val itineraryNotes: String = "",
    val packingChecklist: List<String> = emptyList(),
    val estimatedBudgetInr: Double = 0.0
)

data class FamilyMemory(
    val id: String = "",
    val familyId: String = "",
    val albumName: String = "Family Moments",
    val eventTag: String = "",
    val caption: String = "",
    val storagePath: String = "",
    val mediaType: String = "IMAGE",
    val uploadedByName: String = "",
    val comments: List<String> = emptyList(),
    val createdAtMillis: Long = System.currentTimeMillis()
)
