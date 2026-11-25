package com.familyhub.app.data.remote

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** A raw Firestore document as a map. The document id is added under the key "id". */
typealias Rec = Map<String, Any?>

/**
 * Generic access to the family sub-collections (events, expenses, tasks, trips and the
 * new "items" collection that holds vault, documents, health, vehicles, loans and so on).
 * Everything here stays on Firestore, so it needs no paid Firebase features.
 */
@Singleton
class RecordRepository @Inject constructor(private val firestore: FirebaseFirestore) {

    private fun familyRef(familyId: String) = firestore.collection("families").document(familyId)
    private fun col(familyId: String, collection: String) = familyRef(familyId).collection(collection)

    fun observeRecords(familyId: String, collection: String): Flow<List<Rec>> = callbackFlow<List<Rec>> {
        if (familyId.isBlank()) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val reg = col(familyId, collection).addSnapshotListener { snap, e ->
            if (e != null) {
                Log.w("RecordRepository", "${collection} listener failed", e)
                return@addSnapshotListener
            }
            val list = snap?.documents.orEmpty().map { d ->
                val m = HashMap<String, Any?>()
                d.data?.let { m.putAll(it) }
                m["id"] = d.id
                m as Rec
            }
            trySend(list)
        }
        awaitClose { reg.remove() }
    }

    suspend fun save(familyId: String, collection: String, id: String?, data: Map<String, Any?>) {
        val ref = if (id.isNullOrBlank()) col(familyId, collection).document()
        else col(familyId, collection).document(id)
        ref.set(data, SetOptions.merge()).await()
    }

    suspend fun setField(familyId: String, collection: String, id: String, key: String, value: Any?) {
        col(familyId, collection).document(id).update(key, value).await()
    }

    suspend fun delete(familyId: String, collection: String, id: String) {
        col(familyId, collection).document(id).delete().await()
    }

    /** Emits the signed-in user's membership status (ACTIVE, PENDING_APPROVAL, or NONE if no record). */
    fun observeMyStatus(familyId: String, uid: String): Flow<String> = callbackFlow<String> {
        if (familyId.isBlank()) {
            trySend("NONE")
            awaitClose { }
            return@callbackFlow
        }
        val reg = familyRef(familyId).collection("members").document(uid)
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    Log.w("RecordRepository", "member status listener failed", e)
                    trySend("ACTIVE")
                    return@addSnapshotListener
                }
                trySend(if (snap != null && snap.exists()) (snap.getString("status") ?: "ACTIVE") else "NONE")
            }
        awaitClose { reg.remove() }
    }

    suspend fun approveMember(familyId: String, uid: String) {
        familyRef(familyId).collection("members").document(uid)
            .update(mapOf("status" to "ACTIVE", "canEditFinance" to true))
            .await()
    }

    suspend fun removeMember(familyId: String, uid: String) {
        familyRef(familyId).collection("members").document(uid).delete().await()
    }

    suspend fun updateBudget(familyId: String, monthly: Double) {
        familyRef(familyId).update(mapOf("monthlyBudget" to monthly, "yearlyBudget" to monthly * 12)).await()
    }
}
