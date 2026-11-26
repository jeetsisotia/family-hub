package com.familyhub.app.data.remote

import com.google.firebase.Timestamp
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Family chat on Firestore (free Spark plan).
 *
 * families/{familyId}/chats/{chatId}               one chat: the family group ("family") or two people ("dm_<uidA>_<uidB>")
 * families/{familyId}/chats/{chatId}/messages/{id}  its messages
 * families/{familyId}/chats/{chatId}/reads/{uid}    when each person last read it (for unread counts and "Seen")
 *
 * The rules let only the two people in a direct chat read it, admins included. Messages are not end-to-end
 * encrypted: they are protected by Google's encryption in transit and at rest and by the rules.
 * To keep within the free limits, a chat loads its latest [PAGE] messages and older ones on request.
 */
object ChatRepository {
    const val FAMILY_CHAT = "family"
    const val PAGE = 60L
    const val MAX_TEXT = 4000

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private fun chats(familyId: String) = db.collection("families").document(familyId).collection("chats")
    private fun chat(familyId: String, chatId: String) = chats(familyId).document(chatId)
    private fun messages(familyId: String, chatId: String) = chat(familyId, chatId).collection("messages")

    /** The id of the direct chat between two people; the same whoever starts it. */
    fun directId(a: String, b: String): String = "dm_" + listOf(a, b).sorted().joinToString("_")

    data class Chat(
        val id: String,
        val type: String,
        val memberUids: List<String>,
        val title: String,
        val lastText: String,
        val lastSenderUid: String,
        val lastAt: Long,
        val pinnedMessageId: String
    )

    data class Message(
        val id: String,
        val senderUid: String,
        val senderName: String,
        val text: String,
        val at: Long,
        /** Still waiting to reach the server (sending, or queued while offline). */
        val pending: Boolean,
        val editedAt: Long?,
        val deletedForAll: Boolean,
        val replyToId: String,
        val replyToText: String,
        val replyToName: String,
        val forwarded: Boolean,
        /** Who reacted with what: uid to emoji. */
        val reactions: Map<String, String>
    )

    private fun millis(v: Any?): Long = when (v) {
        is Timestamp -> v.toDate().time
        is Number -> v.toLong()
        else -> 0L
    }

    private fun toChat(d: DocumentSnapshot): Chat = Chat(
        id = d.id,
        type = d.getString("type").orEmpty().ifBlank { if (d.id == FAMILY_CHAT) "group" else "direct" },
        memberUids = (d.get("memberUids") as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
        title = d.getString("title").orEmpty(),
        lastText = d.getString("lastText").orEmpty(),
        lastSenderUid = d.getString("lastSenderUid").orEmpty(),
        lastAt = millis(d.get("lastAt")),
        pinnedMessageId = d.getString("pinnedMessageId").orEmpty()
    )

    private fun toMessage(d: DocumentSnapshot): Message {
        @Suppress("UNCHECKED_CAST")
        val reactions = (d.get("reactions") as? Map<String, Any?>).orEmpty().mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
        return Message(
            id = d.id,
            senderUid = d.getString("senderUid").orEmpty(),
            senderName = d.getString("senderName").orEmpty(),
            text = d.getString("text").orEmpty(),
            // While a message is still sending, the server time is not known yet: use the phone's time.
            at = millis(d.get("at")).takeIf { it > 0 } ?: millis(d.get("clientAt")),
            pending = d.metadata.hasPendingWrites(),
            editedAt = millis(d.get("editedAt")).takeIf { it > 0 },
            deletedForAll = d.getBoolean("deletedForAll") == true,
            replyToId = d.getString("replyToId").orEmpty(),
            replyToText = d.getString("replyToText").orEmpty(),
            replyToName = d.getString("replyToName").orEmpty(),
            forwarded = d.getBoolean("forwarded") == true,
            reactions = reactions
        )
    }

    /**
     * Chats this person is in. Direct chats are found with an array-contains query on memberUids, which the
     * rules allow only for the person's own uid; the family group chat is read directly.
     */
    fun observeChats(familyId: String, uid: String): Flow<List<Chat>> = callbackFlow {
        if (familyId.isBlank() || uid.isBlank()) { trySend(emptyList()); awaitClose { }; return@callbackFlow }
        var direct: List<Chat> = emptyList()
        var group: Chat? = null
        fun emit() = trySend((listOfNotNull(group) + direct).sortedByDescending { it.lastAt })
        // On an error (offline with nothing cached, or no access) the list shows what is known, never spins forever.
        val r1 = chats(familyId).whereArrayContains("memberUids", uid).addSnapshotListener { snap, e ->
            if (e == null && snap != null) direct = snap.documents.map { toChat(it) }.filter { it.type == "direct" }
            emit()
        }
        val r2 = chat(familyId, FAMILY_CHAT).addSnapshotListener { snap, e ->
            if (e == null) group = snap?.takeIf { it.exists() }?.let { toChat(it) }
            emit()
        }
        awaitClose { r1.remove(); r2.remove() }
    }

    /** The latest [limit] messages, oldest first, including ones still sending. */
    fun observeMessages(familyId: String, chatId: String, limit: Long = PAGE): Flow<List<Message>> = callbackFlow {
        val reg = messages(familyId, chatId).orderBy("clientAt", Query.Direction.DESCENDING).limit(limit)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, e ->
                if (e == null && snap != null) trySend(snap.documents.map { toMessage(it) }.sortedBy { it.at })
                else if (e != null) trySend(emptyList())
            }
        awaitClose { reg.remove() }
    }

    /** When each person last read the chat (uid to millis). */
    fun observeReads(familyId: String, chatId: String): Flow<Map<String, Long>> = callbackFlow {
        val reg = chat(familyId, chatId).collection("reads").addSnapshotListener { snap, e ->
            if (e == null && snap != null) trySend(snap.documents.associate { it.id to millis(it.get("at")) })
        }
        awaitClose { reg.remove() }
    }

    /** Creates the family group chat or a direct chat if it does not exist yet. */
    suspend fun ensureChat(familyId: String, chatId: String, members: List<String>, byUid: String) {
        val ref = chat(familyId, chatId)
        if (ref.get().await().exists()) return
        val data = if (chatId == FAMILY_CHAT) {
            mapOf("type" to "group", "title" to "Family", "createdByUid" to byUid, "lastAt" to FieldValue.serverTimestamp())
        } else {
            mapOf("type" to "direct", "memberUids" to members.distinct().sorted(), "createdByUid" to byUid, "lastAt" to FieldValue.serverTimestamp())
        }
        ref.set(data).await()
    }

    /**
     * Sends a message. Firestore keeps it on the phone and sends it when there is a connection, so it works
     * offline; it shows as sending until the server has it. Returns the message id.
     */
    fun send(
        familyId: String, chatId: String, uid: String, name: String, text: String,
        replyTo: Message? = null, forwarded: Boolean = false,
        /** Called with the message id if the server refuses it (it is then shown as not sent, with Retry). */
        onFailed: ((String) -> Unit)? = null
    ): String {
        val body = text.trim().take(MAX_TEXT)
        require(body.isNotEmpty())
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val msg = HashMap<String, Any?>()
        msg["senderUid"] = uid
        msg["senderName"] = name
        msg["text"] = body
        msg["at"] = FieldValue.serverTimestamp()
        msg["clientAt"] = now
        if (replyTo != null) {
            msg["replyToId"] = replyTo.id
            msg["replyToText"] = replyTo.text.take(160)
            msg["replyToName"] = replyTo.senderName
        }
        if (forwarded) msg["forwarded"] = true
        val batch = db.batch()
        batch.set(messages(familyId, chatId).document(id), msg)
        batch.set(
            chat(familyId, chatId),
            mapOf("lastText" to body.take(160), "lastSenderUid" to uid, "lastAt" to FieldValue.serverTimestamp()),
            SetOptions.merge()
        )
        batch.set(chat(familyId, chatId).collection("reads").document(uid), mapOf("at" to FieldValue.serverTimestamp()))
        batch.commit().addOnFailureListener { onFailed?.invoke(id) }
        return id
    }

    /** Sends again a message the server refused; the old copy is left as it was. */
    fun resend(familyId: String, chatId: String, uid: String, name: String, m: Message) =
        send(familyId, chatId, uid, name, m.text)

    suspend fun edit(familyId: String, chatId: String, id: String, text: String) {
        val body = text.trim().take(MAX_TEXT)
        require(body.isNotEmpty())
        messages(familyId, chatId).document(id).update(mapOf("text" to body, "editedAt" to FieldValue.serverTimestamp())).await()
    }

    /** Delete for everyone: the text is removed and the message shows as deleted. */
    suspend fun deleteForEveryone(familyId: String, chatId: String, id: String) {
        messages(familyId, chatId).document(id).update(mapOf("text" to "", "deletedForAll" to true, "editedAt" to FieldValue.serverTimestamp())).await()
    }

    /** Adds, changes or (with null) removes this person's reaction. */
    suspend fun react(familyId: String, chatId: String, id: String, uid: String, emoji: String?) {
        messages(familyId, chatId).document(id).update("reactions.$uid", emoji ?: FieldValue.delete()).await()
    }

    suspend fun markRead(familyId: String, chatId: String, uid: String) {
        try {
            chat(familyId, chatId).collection("reads").document(uid).set(mapOf("at" to FieldValue.serverTimestamp())).await()
        } catch (e: Exception) { }
    }

    suspend fun pin(familyId: String, chatId: String, messageId: String?) {
        chat(familyId, chatId).update("pinnedMessageId", messageId ?: FieldValue.delete()).await()
    }

    /**
     * Messages from others since this person last read the chat. Uses a count query, which Firestore bills as
     * one read per 1,000 messages counted, so it stays cheap.
     */
    suspend fun unreadCount(familyId: String, chatId: String, uid: String, lastAt: Long, lastSenderUid: String): Int {
        if (lastAt <= 0L || lastSenderUid == uid) return 0
        return try {
            val readAt = millis(chat(familyId, chatId).collection("reads").document(uid).get().await().get("at"))
            if (readAt >= lastAt) 0 else messages(familyId, chatId).whereGreaterThan("clientAt", readAt)
                .count().get(AggregateSource.SERVER).await().count.toInt()
        } catch (e: Exception) {
            0
        }
    }
}
