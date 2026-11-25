package com.familyhub.app.data.remote

import com.familyhub.app.data.model.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FamilyRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private fun familyRef(familyId: String) = firestore.collection("families").document(familyId)

    fun observeFamily(familyId: String): Flow<FamilyGroup?> = callbackFlow {
        if (familyId.isBlank()) {
            // New user with no family yet: nothing to listen to (an empty ID makes Firestore throw).
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }
        val reg = familyRef(familyId).addSnapshotListener { snap, _ ->
            trySend(snap?.toObject(FamilyGroup::class.java))
        }
        awaitClose { reg.remove() }
    }

    suspend fun createFamilyGroup(user: UserProfile, familyName: String): Result<FamilyGroup> = runCatching {
        val familyId = firestore.collection("families").document().id
        val inviteCode = "FH-" + UUID.randomUUID().toString().take(6).uppercase()
        val family = FamilyGroup(
            id = familyId,
            name = familyName.trim(),
            ownerUid = user.uid,
            inviteCode = inviteCode
        )
        val ownerMember = FamilyMember(
            uid = user.uid,
            familyId = familyId,
            name = user.name,
            email = user.email,
            photoUrl = user.photoUrl,
            relation = "Owner",
            role = FamilyRole.OWNER,
            status = "ACTIVE",
            canEditFinance = true,
            canViewPrivateDocs = true,
            canViewHealth = true
        )

        firestore.runBatch { batch ->
            batch.set(familyRef(familyId), family)
            batch.set(familyRef(familyId).collection("members").document(user.uid), ownerMember)
            batch.update(
                firestore.collection("users").document(user.uid),
                mapOf(
                    "activeFamilyId" to familyId,
                    "familyIds" to (user.familyIds + familyId).distinct()
                )
            )
        }.await()
        family
    }

    suspend fun joinFamilyByCode(user: UserProfile, inviteCode: String, relation: String): Result<String> = runCatching {
        val query = firestore.collection("families")
            .whereEqualTo("inviteCode", inviteCode.trim().uppercase())
            .limit(1)
            .get()
            .await()

        val familyDoc = query.documents.firstOrNull()
            ?: throw IllegalArgumentException("Invalid invitation code.")
        val familyId = familyDoc.id

        val pendingMember = FamilyMember(
            uid = user.uid,
            familyId = familyId,
            name = user.name,
            email = user.email,
            photoUrl = user.photoUrl,
            relation = relation.ifBlank { "Member" },
            role = FamilyRole.ADULT,
            status = "PENDING_APPROVAL",
            canEditFinance = false,
            canViewPrivateDocs = false
        )

        firestore.runBatch { batch ->
            batch.set(familyRef(familyId).collection("members").document(user.uid), pendingMember)
            batch.update(
                firestore.collection("users").document(user.uid),
                mapOf(
                    "activeFamilyId" to familyId,
                    "familyIds" to (user.familyIds + familyId).distinct()
                )
            )
        }.await()
        familyId
    }

    suspend fun switchActiveFamily(uid: String, familyId: String) {
        firestore.collection("users").document(uid).update("activeFamilyId", familyId).await()
    }

    fun observeMembers(familyId: String): Flow<List<FamilyMember>> = callbackFlow {
        if (familyId.isBlank()) {
            // New user with no family yet: nothing to listen to (an empty ID makes Firestore throw).
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val reg = familyRef(familyId).collection("members")
            .addSnapshotListener { snap, _ ->
                trySend(snap?.toObjects(FamilyMember::class.java).orEmpty())
            }
        awaitClose { reg.remove() }
    }

    fun observeEvents(familyId: String): Flow<List<FamilyEvent>> = callbackFlow {
        if (familyId.isBlank()) {
            // New user with no family yet: nothing to listen to (an empty ID makes Firestore throw).
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val reg = familyRef(familyId).collection("events")
            .whereEqualTo("isDeleted", false)
            .orderBy("startTimeMillis", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, _ ->
                trySend(snap?.toObjects(FamilyEvent::class.java).orEmpty())
            }
        awaitClose { reg.remove() }
    }

    fun observeTransactions(familyId: String): Flow<List<FinanceTransaction>> = callbackFlow {
        if (familyId.isBlank()) {
            // New user with no family yet: nothing to listen to (an empty ID makes Firestore throw).
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val reg = familyRef(familyId).collection("expenses")
            .whereEqualTo("isDeleted", false)
            .orderBy("dateMillis", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, _ ->
                trySend(snap?.toObjects(FinanceTransaction::class.java).orEmpty())
            }
        awaitClose { reg.remove() }
    }

    fun observeDocuments(familyId: String, currentUid: String, isAdmin: Boolean): Flow<List<FamilyDocument>> = callbackFlow {
        if (familyId.isBlank()) {
            // New user with no family yet: nothing to listen to (an empty ID makes Firestore throw).
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val reg = familyRef(familyId).collection("documents")
            .addSnapshotListener { snap, _ ->
                val all = snap?.toObjects(FamilyDocument::class.java).orEmpty()
                val authorized = all.filter { doc ->
                    isAdmin || !doc.isPrivate || doc.ownerUid == currentUid || currentUid in doc.authorizedUids
                }
                trySend(authorized)
            }
        awaitClose { reg.remove() }
    }

    fun observeTasks(familyId: String): Flow<List<HouseholdTask>> = callbackFlow {
        if (familyId.isBlank()) {
            // New user with no family yet: nothing to listen to (an empty ID makes Firestore throw).
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val reg = familyRef(familyId).collection("tasks")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, _ ->
                trySend(snap?.toObjects(HouseholdTask::class.java).orEmpty())
            }
        awaitClose { reg.remove() }
    }

    fun observeTrips(familyId: String): Flow<List<FamilyTrip>> = callbackFlow {
        if (familyId.isBlank()) {
            // New user with no family yet: nothing to listen to (an empty ID makes Firestore throw).
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val reg = familyRef(familyId).collection("trips")
            .orderBy("startDateMillis", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, _ ->
                trySend(snap?.toObjects(FamilyTrip::class.java).orEmpty())
            }
        awaitClose { reg.remove() }
    }

    suspend fun upsertEvent(familyId: String, event: FamilyEvent) {
        val docRef = if (event.id.isBlank()) familyRef(familyId).collection("events").document()
        else familyRef(familyId).collection("events").document(event.id)
        docRef.set(event.copy(id = docRef.id, familyId = familyId, updatedAt = System.currentTimeMillis())).await()
    }

    suspend fun upsertTransaction(familyId: String, tx: FinanceTransaction) {
        val docRef = if (tx.id.isBlank()) familyRef(familyId).collection("expenses").document()
        else familyRef(familyId).collection("expenses").document(tx.id)
        docRef.set(tx.copy(id = docRef.id, familyId = familyId)).await()
    }

    suspend fun upsertDocument(familyId: String, doc: FamilyDocument) {
        val docRef = if (doc.id.isBlank()) familyRef(familyId).collection("documents").document()
        else familyRef(familyId).collection("documents").document(doc.id)
        docRef.set(doc.copy(id = docRef.id, familyId = familyId, updatedAt = System.currentTimeMillis())).await()
    }

    suspend fun upsertTask(familyId: String, task: HouseholdTask) {
        val docRef = if (task.id.isBlank()) familyRef(familyId).collection("tasks").document()
        else familyRef(familyId).collection("tasks").document(task.id)
        docRef.set(task.copy(id = docRef.id, familyId = familyId, updatedAt = System.currentTimeMillis())).await()
    }

    suspend fun upsertTrip(familyId: String, trip: FamilyTrip) {
        val docRef = if (trip.id.isBlank()) familyRef(familyId).collection("trips").document()
        else familyRef(familyId).collection("trips").document(trip.id)
        docRef.set(trip.copy(id = docRef.id, familyId = familyId)).await()
    }

    suspend fun updateMemberPermissions(familyId: String, member: FamilyMember) {
        familyRef(familyId).collection("members").document(member.uid).set(member).await()
    }

    suspend fun transferOwnership(familyId: String, currentOwnerUid: String, newOwnerUid: String) {
        firestore.runBatch { batch ->
            batch.update(familyRef(familyId), "ownerUid", newOwnerUid)
            batch.update(familyRef(familyId).collection("members").document(currentOwnerUid), "role", FamilyRole.ADULT.name)
            batch.update(
                familyRef(familyId).collection("members").document(newOwnerUid),
                mapOf(
                    "role" to FamilyRole.OWNER.name,
                    "status" to "ACTIVE",
                    "canViewPrivateDocs" to true,
                    "canEditFinance" to true
                )
            )
        }.await()
    }
}
