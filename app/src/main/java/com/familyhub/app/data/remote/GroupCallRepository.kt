package com.familyhub.app.data.remote

import com.familyhub.app.calling.GroupCallMath
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** A family group call (up to 4 people). */
data class GroupCall(
    val id: String,
    val hostUid: String,
    val hostName: String,
    val video: Boolean,
    val memberUids: List<String>,
    val names: Map<String, String>,
    val joined: List<String>,
    val state: String,          // open, ended
    val clientAt: Long
)

/**
 * Group call signalling on Firestore (free plan, no server):
 *
 * families/{fid}/groupcalls/{id}                               who is invited, who is in the call now
 * families/{fid}/groupcalls/{id}/signal/{from}__{to}__offer    one pair's connection offer
 * families/{fid}/groupcalls/{id}/signal/{from}__{to}__answer   and its answer
 *
 * Only invited people can read a call; each person adds or removes only themselves; only the two people of a
 * pair can read that pair's connection details. The voice and picture go directly between phones.
 */
class GroupCallRepository {
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private fun calls(fid: String) = db.collection("families").document(fid).collection("groupcalls")

    private fun DocumentSnapshot.toCall(): GroupCall? {
        val host = getString("hostUid") ?: return null
        @Suppress("UNCHECKED_CAST")
        val names = (get("names") as? Map<String, Any?>).orEmpty().mapValues { it.value as? String ?: "" }
        return GroupCall(
            id = id, hostUid = host, hostName = getString("hostName").orEmpty(),
            video = getString("type") == "video",
            memberUids = (get("memberUids") as? List<*>).orEmpty().mapNotNull { it as? String },
            names = names,
            joined = (get("joined") as? List<*>).orEmpty().mapNotNull { it as? String },
            state = getString("state") ?: "open",
            clientAt = getLong("clientAt") ?: 0L
        )
    }

    fun observeMine(fid: String, uid: String, limit: Long = 20): Flow<List<GroupCall>> = callbackFlow {
        val reg = calls(fid).whereArrayContains("memberUids", uid).limit(limit).addSnapshotListener { snap, e ->
            if (e == null && snap != null) trySend(snap.documents.mapNotNull { it.toCall() }.sortedByDescending { it.clientAt })
        }
        awaitClose { reg.remove() }
    }

    fun observe(fid: String, id: String): Flow<GroupCall?> = callbackFlow {
        val reg = calls(fid).document(id).addSnapshotListener { snap, _ -> trySend(snap?.toCall()) }
        awaitClose { reg.remove() }
    }

    suspend fun start(fid: String, id: String, hostUid: String, hostName: String, invited: Map<String, String>, video: Boolean) {
        val members = (listOf(hostUid) + invited.keys).distinct()
        require(members.size in 2..GroupCallMath.MAX_PEOPLE)
        calls(fid).document(id).set(
            mapOf(
                "hostUid" to hostUid, "hostName" to hostName.take(80),
                "type" to if (video) "video" else "audio",
                "memberUids" to members,
                "names" to (invited + (hostUid to hostName)).mapValues { it.value.take(80) },
                "joined" to listOf(hostUid),
                "state" to "open",
                "clientAt" to System.currentTimeMillis()
            )
        ).await()
    }

    suspend fun join(fid: String, id: String, uid: String) { calls(fid).document(id).update("joined", FieldValue.arrayUnion(uid)).await() }
    suspend fun leave(fid: String, id: String, uid: String) { calls(fid).document(id).update("joined", FieldValue.arrayRemove(uid)).await() }
    suspend fun end(fid: String, id: String) { calls(fid).document(id).update(mapOf("state" to "ended", "endedAt" to System.currentTimeMillis())).await() }

    suspend fun sendSignal(fid: String, id: String, from: String, to: String, kind: String, sdp: String, nonce: String) {
        calls(fid).document(id).collection("signal").document(GroupCallMath.signalId(from, to, kind))
            .set(mapOf("from" to from, "to" to to, "type" to kind, "sdp" to sdp, "nonce" to nonce, "at" to System.currentTimeMillis())).await()
    }

    data class Signal(val sdp: String, val nonce: String, val at: Long)

    fun observeSignal(fid: String, id: String, from: String, to: String, kind: String): Flow<Signal?> = callbackFlow {
        val reg = calls(fid).document(id).collection("signal").document(GroupCallMath.signalId(from, to, kind))
            .addSnapshotListener { snap, _ ->
                val sdp = snap?.getString("sdp")
                trySend(if (sdp.isNullOrBlank()) null else Signal(sdp, snap.getString("nonce").orEmpty(), snap.getLong("at") ?: 0L))
            }
        awaitClose { reg.remove() }
    }

    /** Removes the connection details I wrote (when I leave). */
    suspend fun cleanUp(fid: String, id: String, me: String, others: Collection<String>) {
        val s = calls(fid).document(id).collection("signal")
        others.forEach { o ->
            listOf("offer", "answer").forEach { k -> runCatching { s.document(GroupCallMath.signalId(me, o, k)).delete().await() } }
        }
    }
}
