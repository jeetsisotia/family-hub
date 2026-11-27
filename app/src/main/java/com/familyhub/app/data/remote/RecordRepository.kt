package com.familyhub.app.data.remote

import android.util.Log
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** A raw Firestore document as a map. The document id is added under the key "id". */
typealias Rec = Map<String, Any?>

/** Marks a record that lives in the person's private space ("Only me"). Never stored. */
const val PRIVATE_FLAG = "_private"

/** A family the signed-in person belongs to. */
data class FamilyInfo(val id: String, val name: String, val ownerUid: String)

/**
 * Generic access to records. Family records live under families/{familyId}/{collection};
 * records marked "Only me" live under users/{uid}/{collection}, which only that person can read.
 * Everything here stays on Firestore, so it needs no paid Firebase features.
 */
@Singleton
class RecordRepository @Inject constructor(private val firestore: FirebaseFirestore) {

    private fun familyRef(familyId: String) = firestore.collection("families").document(familyId)
    private fun col(familyId: String, collection: String) = familyRef(familyId).collection(collection)
    private fun userRef(uid: String) = firestore.collection("users").document(uid)
    private fun privateCol(uid: String, collection: String) = userRef(uid).collection(collection)

    private fun listen(ref: CollectionReference, private: Boolean, tag: String): Flow<List<Rec>> = callbackFlow<List<Rec>> {
        var sent = false
        val reg = ref.addSnapshotListener { snap, e ->
            if (e != null) {
                Log.w("RecordRepository", "$tag listener failed", e)
                // Never leave screens waiting forever; an empty list is better than a spinner.
                if (!sent) trySend(emptyList())
                return@addSnapshotListener
            }
            val list = snap?.documents.orEmpty().map { d ->
                val m = HashMap<String, Any?>()
                d.data?.let { m.putAll(it) }
                m["id"] = d.id
                if (private) m[PRIVATE_FLAG] = true
                m as Rec
            }
            sent = true
            trySend(list)
        }
        awaitClose { reg.remove() }
    }

    private fun emptyFlow(): Flow<List<Rec>> = callbackFlow<List<Rec>> {
        trySend(emptyList())
        awaitClose { }
    }

    fun observeRecords(familyId: String, collection: String): Flow<List<Rec>> =
        if (familyId.isBlank()) emptyFlow() else listen(col(familyId, collection), false, collection)

    fun observePrivate(uid: String, collection: String): Flow<List<Rec>> =
        if (uid.isBlank()) emptyFlow() else listen(privateCol(uid, collection), true, "private $collection")

    /**
     * Every record of a collection (family and this person's "Only me"), read from the server, not from the phone's
     * cache. Throws if the server cannot be reached, so a backup is never silently incomplete.
     */
    suspend fun fetchFromServer(familyId: String, uid: String, collection: String): List<Rec> {
        fun rows(docs: List<com.google.firebase.firestore.DocumentSnapshot>, private: Boolean): List<Rec> = docs.map { d ->
            val m = HashMap<String, Any?>()
            d.data?.let { m.putAll(it) }
            m["id"] = d.id
            if (private) m[PRIVATE_FLAG] = true
            m as Rec
        }
        val server = com.google.firebase.firestore.Source.SERVER
        val fam = if (familyId.isBlank()) emptyList() else rows(col(familyId, collection).get(server).await().documents, false)
        val mine = if (uid.isBlank()) emptyList() else rows(privateCol(uid, collection).get(server).await().documents, true)
        return fam + mine
    }

    /** Family records plus this person's own "Only me" records of the same kind. */
    fun observeMerged(familyId: String, uid: String, collection: String): Flow<List<Rec>> =
        combine(observeRecords(familyId, collection), observePrivate(uid, collection)) { a, b -> a + b }

    private fun target(familyId: String, uid: String, collection: String, private: Boolean): CollectionReference =
        if (private) privateCol(uid, collection) else col(familyId, collection)

    private fun clean(data: Map<String, Any?>): HashMap<String, Any?> {
        val m = HashMap<String, Any?>(data)
        m.remove("id")
        m.remove(PRIVATE_FLAG)
        return m
    }

    /** Saves a record for the family or privately. Moving it between the two keeps the same id. */
    suspend fun saveScoped(
        familyId: String,
        uid: String,
        collection: String,
        existing: Rec?,
        data: Map<String, Any?>,
        private: Boolean
    ) {
        val id = existing?.get("id") as? String
        val wasPrivate = existing?.get(PRIVATE_FLAG) == true
        val dest = target(familyId, uid, collection, private)
        val ref = if (id.isNullOrBlank()) dest.document() else dest.document(id)
        // Remembered so the assistant can offer Undo on a record it has just added.
        if (existing == null) lastCreated = Created(collection, ref.id, private)
        if (existing != null && !id.isNullOrBlank() && wasPrivate != private) {
            val merged = HashMap<String, Any?>(clean(existing))
            merged.putAll(clean(data))
            ref.set(merged).await()
            val old = target(familyId, uid, collection, wasPrivate).document(id)
            try {
                old.delete().await()
            } catch (e: Exception) {
                // Only admins may delete shared records for good; hide the old shared copy instead.
                old.update(mapOf("isDeleted" to true, "deletedAt" to System.currentTimeMillis(), "deletedByUid" to uid, "movedToPrivate" to true)).await()
            }
        } else {
            ref.set(clean(data), SetOptions.merge()).await()
        }
    }

    suspend fun setFieldScoped(familyId: String, uid: String, collection: String, rec: Rec, key: String, value: Any?) {
        val id = rec["id"] as? String ?: return
        target(familyId, uid, collection, rec[PRIVATE_FLAG] == true).document(id).update(key, value).await()
    }

    /** Updates several fields of one record in a single write. */
    suspend fun setFieldsScoped(familyId: String, uid: String, collection: String, rec: Rec, fields: Map<String, Any?>) {
        val id = rec["id"] as? String ?: return
        if (fields.isEmpty()) return
        target(familyId, uid, collection, rec[PRIVATE_FLAG] == true).document(id).update(fields).await()
    }

    /**
     * Deletes a record. Your own "Only me" records are removed; shared family records go to Recently deleted
     * (marked deleted), where a family admin can restore them or delete them for good.
     */
    suspend fun deleteScoped(familyId: String, uid: String, collection: String, rec: Rec) {
        val id = rec["id"] as? String ?: return
        if (rec[PRIVATE_FLAG] == true) {
            privateCol(uid, collection).document(id).delete().await()
        } else {
            col(familyId, collection).document(id)
                .update(mapOf("isDeleted" to true, "deletedAt" to System.currentTimeMillis(), "deletedByUid" to uid)).await()
        }
    }

    /** Deletes a record for good (family admins only for shared records). */
    suspend fun purgeScoped(familyId: String, uid: String, collection: String, rec: Rec) {
        val id = rec["id"] as? String ?: return
        target(familyId, uid, collection, rec[PRIVATE_FLAG] == true).document(id).delete().await()
    }

    /** Brings a deleted record back. */
    suspend fun restoreScoped(familyId: String, uid: String, collection: String, rec: Rec) {
        val id = rec["id"] as? String ?: return
        target(familyId, uid, collection, rec[PRIVATE_FLAG] == true).document(id)
            .update(mapOf("isDeleted" to false, "deletedAt" to FieldValue.delete(), "deletedByUid" to FieldValue.delete(), "skipped" to FieldValue.delete())).await()
    }

    /** Writes a record with its original id (restoring from a backup). Existing fields are kept, backup values win. */
    suspend fun putScoped(familyId: String, uid: String, collection: String, id: String, data: Map<String, Any?>, private: Boolean) {
        target(familyId, uid, collection, private).document(id).set(clean(data), SetOptions.merge()).await()
    }

    /** The last record this phone created (collection, id, "Only me"), for Undo of AI actions. */
    data class Created(val collection: String, val id: String, val private: Boolean)
    @Volatile var lastCreated: Created? = null

    /**
     * Updates fields on many records in batches of 400 (for example moving transactions to another category).
     * Each record keeps its id, so nothing is copied or lost. "Only me" records are written to the person's own
     * copy. Returns how many records were updated. Firestore keeps the writes on the phone when offline and sends
     * them later, so this only returns once the server has them; callers should not wait on it to update the screen.
     */
    suspend fun setFieldsMany(familyId: String, uid: String, collection: String, changes: List<Pair<Rec, Map<String, Any?>>>): Int {
        var done = 0
        for (chunk in changes.filter { it.first["id"] is String && it.second.isNotEmpty() }.chunked(400)) {
            val batch = com.google.firebase.firestore.FirebaseFirestore.getInstance().batch()
            for ((r, fields) in chunk) {
                batch.update(target(familyId, uid, collection, r[PRIVATE_FLAG] == true).document(r["id"] as String), fields)
            }
            batch.commit().await()
            done += chunk.size
        }
        return done
    }

    /** Deletes many records for good in batches of 400 (resets; admins only). Returns how many were deleted. */
    suspend fun purgeMany(familyId: String, uid: String, collection: String, recs: List<Rec>): Int {
        var n = 0
        for (chunk in recs.chunked(400)) {
            val batch = com.google.firebase.firestore.FirebaseFirestore.getInstance().batch()
            for (r in chunk) {
                val id = r["id"] as? String ?: continue
                batch.delete(target(familyId, uid, collection, r[PRIVATE_FLAG] == true).document(id))
                n++
            }
            batch.commit().await()
        }
        return n
    }

    /** Makes a member a Family Admin, or takes it away (the Super Admin only). */
    suspend fun setMemberAdmin(familyId: String, memberUid: String, admin: Boolean) {
        familyRef(familyId).collection("members").document(memberUid).update("isAdmin", admin).await()
    }

    /** Writes a private record with a fixed id (for example one step-count record per day). */
    fun putPrivate(uid: String, collection: String, id: String, data: Map<String, Any?>) {
        if (uid.isBlank()) return
        privateCol(uid, collection).document(id).set(data, SetOptions.merge())
            .addOnFailureListener { Log.w("RecordRepository", "private write failed", it) }
    }

    /** Adds a private record without waiting for the server (works offline). */
    fun addPrivate(uid: String, collection: String, data: Map<String, Any?>) {
        if (uid.isBlank()) return
        privateCol(uid, collection).document().set(data)
            .addOnFailureListener { Log.w("RecordRepository", "private add failed", it) }
    }

    // ---------- older family-only helpers, still used by a few screens ----------

    suspend fun save(familyId: String, collection: String, id: String?, data: Map<String, Any?>) {
        val ref = if (id.isNullOrBlank()) col(familyId, collection).document()
        else col(familyId, collection).document(id)
        ref.set(clean(data), SetOptions.merge()).await()
    }

    suspend fun setField(familyId: String, collection: String, id: String, key: String, value: Any?) {
        col(familyId, collection).document(id).update(key, value).await()
    }

    suspend fun delete(familyId: String, collection: String, id: String) {
        col(familyId, collection).document(id).delete().await()
    }

    // ---------- the person's own profile ----------

    /** The signed-in person's own document (profile, ID numbers, layout). Only they can read it. */
    fun observeUserDoc(uid: String): Flow<Rec> = callbackFlow<Rec> {
        if (uid.isBlank()) {
            trySend(emptyMap())
            awaitClose { }
            return@callbackFlow
        }
        val reg = userRef(uid).addSnapshotListener { snap, e ->
            if (e != null) {
                Log.w("RecordRepository", "user doc listener failed", e)
                return@addSnapshotListener
            }
            trySend(snap?.data ?: emptyMap())
        }
        awaitClose { reg.remove() }
    }

    /** Saves profile fields. Returns at once; the change syncs in the background (also offline). */
    fun updateUserDoc(uid: String, data: Map<String, Any?>, onDone: ((Boolean) -> Unit)? = null) {
        if (uid.isBlank()) return
        userRef(uid).set(data, SetOptions.merge())
            .addOnSuccessListener { onDone?.invoke(true) }
            .addOnFailureListener {
                Log.w("RecordRepository", "user doc update failed", it)
                onDone?.invoke(false)
            }
    }

    /** Copies what the person chose to share into their member card in every family they belong to. */
    fun updateMemberCards(uid: String, familyIds: List<String>, data: Map<String, Any?>) {
        for (f in familyIds.distinct()) {
            if (f.isBlank()) continue
            familyRef(f).collection("members").document(uid).set(data, SetOptions.merge())
                .addOnFailureListener { Log.w("RecordRepository", "member card update failed for $f", it) }
        }
    }

    suspend fun familyInfos(ids: List<String>): List<FamilyInfo> {
        val out = ArrayList<FamilyInfo>()
        for (id in ids.distinct()) {
            if (id.isBlank()) continue
            try {
                val d = familyRef(id).get().await()
                out.add(FamilyInfo(id, d.getString("name") ?: "Family", d.getString("ownerUid").orEmpty()))
            } catch (e: Exception) {
                out.add(FamilyInfo(id, "Family (waiting for approval)", ""))
            }
        }
        return out
    }

    suspend fun leaveFamily(uid: String, familyId: String, remaining: List<String>, newActive: String) {
        try {
            familyRef(familyId).collection("members").document(uid).delete().await()
        } catch (e: Exception) {
            Log.w("RecordRepository", "could not remove member card", e)
        }
        userRef(uid).set(mapOf("familyIds" to remaining, "activeFamilyId" to newActive), SetOptions.merge()).await()
    }

    // ---------- membership ----------

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
