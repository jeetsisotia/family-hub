package com.familyhub.app.data.remote

import com.familyhub.app.data.model.*
import android.util.Log
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Converts each document on its own and skips any that do not fit the model, so one bad
 * entry can never crash the app (a type mismatch inside a listener would otherwise do that).
 */
private fun <T> QuerySnapshot?.safeList(cls: Class<T>): List<T> =
    this?.documents.orEmpty().mapNotNull { d ->
        try {
            d.toObject(cls)
        } catch (e: Exception) {
            Log.w("FamilyRepository", "Skipping ${d.reference.path}: ${e.message}")
            null
        }
    }

@Singleton
class FamilyRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private fun familyRef(familyId: String) = firestore.collection("families").document(familyId)

    private fun logListenerError(name: String, e: Exception) {
        Log.w("FamilyRepository", "$name listener failed: ${e.message}", e)
    }

    /** Creates users/{uid} if it does not exist yet (e.g. the app was closed right after sign-in). */
    suspend fun ensureUserProfile(user: FirebaseUser): Result<Unit> = try {
        val ref = firestore.collection("users").document(user.uid)
        if (!ref.get().await().exists()) {
            val profile = UserProfile(
                uid = user.uid,
                name = user.displayName ?: "Family Member",
                email = user.email.orEmpty(),
                photoUrl = user.photoUrl?.toString().orEmpty()
            )
            ref.set(profile, SetOptions.merge()).await()
        }
        Result.success(Unit)
    } catch (e: kotlinx.coroutines.CancellationException) {
        // The screen closed while this was running: stop here instead of carrying on.
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun observeFamily(familyId: String): Flow<FamilyGroup?> = callbackFlow {
        if (familyId.isBlank()) {
            // New user with no family yet: nothing to listen to (an empty ID makes Firestore throw).
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }
        val reg = familyRef(familyId).addSnapshotListener { snap, e ->
            if (e != null) { logListenerError("family", e); return@addSnapshotListener }
            trySend(runCatching { snap?.toObject(FamilyGroup::class.java) }.getOrNull())
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
            // The code points to the family, so someone with the code can ask to join without seeing other families.
            batch.set(inviteRef(inviteCode), inviteDoc(familyId, family.name, user.uid))
            batch.set(
                firestore.collection("users").document(user.uid),
                mapOf(
                    "activeFamilyId" to familyId,
                    "familyIds" to (user.familyIds + familyId).distinct()
                ),
                SetOptions.merge()
            )
        }.await()
        family
    }

    private fun inviteRef(code: String) = firestore.collection("invites").document(code.trim().uppercase())

    private fun inviteDoc(familyId: String, familyName: String, byUid: String) = mapOf(
        "familyId" to familyId, "familyName" to familyName, "createdByUid" to byUid, "updatedAt" to System.currentTimeMillis()
    )

    /**
     * Makes sure the family's invite code can be looked up (admins only; families made before invites existed
     * get theirs the first time an admin opens the app). Quietly does nothing for members or when offline.
     */
    suspend fun ensureInvite(family: FamilyGroup, uid: String) {
        if (family.id.isBlank() || family.inviteCode.isBlank()) return
        try {
            val ref = inviteRef(family.inviteCode)
            val snap = ref.get().await()
            if (!snap.exists() || snap.getString("familyId") != family.id || snap.getString("familyName") != family.name) {
                ref.set(inviteDoc(family.id, family.name, uid)).await()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not an admin, offline or the rules are not updated yet: the code can still be typed later.
        }
    }

    suspend fun joinFamilyByCode(user: UserProfile, inviteCode: String, relation: String): Result<String> = runCatching {
        val code = inviteCode.trim().uppercase()
        if (!Regex("[A-Z0-9-]{4,32}").matches(code)) throw IllegalArgumentException("That invite code doesn't look right.")
        // Look the code up directly; families cannot be listed or searched.
        val invite = try { inviteRef(code).get().await() } catch (e: Exception) { null }
        val familyId = invite?.getString("familyId")?.takeIf { invite.exists() && it.isNotBlank() }
            ?: throw IllegalArgumentException("Invite code not found. Check it, or ask your family admin to open Family Hub once (it activates the code).")

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
            batch.set(
                firestore.collection("users").document(user.uid),
                mapOf(
                    "activeFamilyId" to familyId,
                    "familyIds" to (user.familyIds + familyId).distinct()
                ),
                SetOptions.merge()
            )
        }.await()
        familyId
    }

    suspend fun switchActiveFamily(uid: String, familyId: String) {
        firestore.collection("users").document(uid).set(mapOf("activeFamilyId" to familyId), SetOptions.merge()).await()
    }

    fun observeMembers(familyId: String): Flow<List<FamilyMember>> = callbackFlow {
        if (familyId.isBlank()) {
            // New user with no family yet: nothing to listen to (an empty ID makes Firestore throw).
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val reg = familyRef(familyId).collection("members")
            .addSnapshotListener { snap, e ->
                if (e != null) { logListenerError("members", e); return@addSnapshotListener }
                trySend(snap.safeList(FamilyMember::class.java))
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
            .addSnapshotListener { snap, e ->
                if (e != null) { logListenerError("events", e); return@addSnapshotListener }
                trySend(snap.safeList(FamilyEvent::class.java).sortedBy { it.startTimeMillis })
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
            .addSnapshotListener { snap, e ->
                if (e != null) { logListenerError("expenses", e); return@addSnapshotListener }
                trySend(snap.safeList(FinanceTransaction::class.java).sortedByDescending { it.dateMillis })
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
            .addSnapshotListener { snap, e ->
                if (e != null) { logListenerError("documents", e); return@addSnapshotListener }
                val all = snap.safeList(FamilyDocument::class.java)
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
            .addSnapshotListener { snap, e ->
                if (e != null) { logListenerError("tasks", e); return@addSnapshotListener }
                trySend(snap.safeList(HouseholdTask::class.java))
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
            .addSnapshotListener { snap, e ->
                if (e != null) { logListenerError("trips", e); return@addSnapshotListener }
                trySend(snap.safeList(FamilyTrip::class.java))
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
