package com.familyhub.app.data.remote

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** One call between two family members (light record; the connection details live in signal/offer and signal/answer). */
data class CallRecord(
    val id: String,
    val callerUid: String,
    val calleeUid: String,
    val callerName: String,
    val calleeName: String,
    val video: Boolean,
    val state: String,          // ringing, accepted, declined, busy, missed, cancelled, ended
    val clientAt: Long,
    val answeredAt: Long?,
    val endedAt: Long?
) {
    val isTerminal: Boolean get() = state !in setOf("ringing", "accepted")
    fun otherUid(me: String) = if (callerUid == me) calleeUid else callerUid
    fun otherName(me: String) = if (callerUid == me) calleeName else callerName
}

/**
 * Call signalling on Firestore (free Spark plan, no Cloud Functions).
 *
 * families/{fid}/calls/{callId}                 light record, readable only by the two people
 * families/{fid}/calls/{callId}/signal/offer    caller's connection description
 * families/{fid}/calls/{callId}/signal/answer   callee's connection description
 *
 * Call ids start with a time-inverted number so the newest calls come first without needing an index.
 */
class CallRepository {
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private fun calls(familyId: String) = db.collection("families").document(familyId).collection("calls")

    fun newCallId(now: Long = System.currentTimeMillis()): String {
        val inverted = 9_999_999_999_999L - now
        val rand = (1..4).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        return "%013d_%s".format(inverted, rand)
    }

    private fun DocumentSnapshot.toCall(): CallRecord? {
        val caller = getString("callerUid") ?: return null
        val callee = getString("calleeUid") ?: return null
        return CallRecord(
            id = id,
            callerUid = caller,
            calleeUid = callee,
            callerName = getString("callerName") ?: "",
            calleeName = getString("calleeName") ?: "",
            video = getString("type") == "video",
            state = getString("state") ?: "ringing",
            clientAt = getLong("clientAt") ?: 0L,
            answeredAt = getLong("answeredAt"),
            endedAt = getLong("endedAt")
        )
    }

    /** The newest calls this person is part of (history, an incoming ring, and state changes all come from here). */
    fun observeMyCalls(familyId: String, uid: String, limit: Long = 30): Flow<List<CallRecord>> = callbackFlow {
        val reg = calls(familyId)
            .whereArrayContains("memberUids", uid)
            .limit(limit)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                trySend(snap.documents.mapNotNull { it.toCall() }.sortedByDescending { it.clientAt })
            }
        awaitClose { reg.remove() }
    }

    /** Caller starts a call: the record and the offer are written together. */
    suspend fun start(
        familyId: String, callId: String, callerUid: String, callerName: String,
        calleeUid: String, calleeName: String, video: Boolean, offerSdp: String
    ) {
        val call = calls(familyId).document(callId)
        val batch = db.batch()
        batch.set(
            call, mapOf(
                "callerUid" to callerUid, "calleeUid" to calleeUid,
                "callerName" to callerName.take(80), "calleeName" to calleeName.take(80),
                "memberUids" to listOf(callerUid, calleeUid),
                "type" to if (video) "video" else "audio",
                "state" to "ringing",
                "clientAt" to System.currentTimeMillis()
            )
        )
        batch.set(call.collection("signal").document("offer"), mapOf("from" to callerUid, "type" to "offer", "sdp" to offerSdp))
        batch.commit().await()
    }

    suspend fun readSignal(familyId: String, callId: String, kind: String): String? =
        try {
            calls(familyId).document(callId).collection("signal").document(kind).get().await().getString("sdp")
        } catch (e: Exception) { null }

    fun observeAnswer(familyId: String, callId: String): Flow<String?> = callbackFlow {
        val reg = calls(familyId).document(callId).collection("signal").document("answer")
            .addSnapshotListener { snap, _ -> trySend(snap?.getString("sdp")) }
        awaitClose { reg.remove() }
    }

    /** Callee accepts: state change and answer are written together. */
    suspend fun accept(familyId: String, callId: String, calleeUid: String, answerSdp: String) {
        val call = calls(familyId).document(callId)
        val batch = db.batch()
        batch.update(call, mapOf("state" to "accepted", "answeredAt" to System.currentTimeMillis()))
        batch.set(call.collection("signal").document("answer"), mapOf("from" to calleeUid, "type" to "answer", "sdp" to answerSdp))
        batch.commit().await()
    }

    suspend fun setState(familyId: String, callId: String, state: String) {
        val data = mutableMapOf<String, Any>("state" to state)
        if (state == "ended") { data["endedAt"] = System.currentTimeMillis(); data["endReason"] = "hangup" }
        calls(familyId).document(callId).update(data).await()
    }

    /** Removes the connection details once the call is over (best effort). */
    suspend fun cleanUp(familyId: String, callId: String) {
        try {
            val s = calls(familyId).document(callId).collection("signal")
            s.document("offer").delete().await()
            s.document("answer").delete().await()
        } catch (_: Exception) { }
    }
}
