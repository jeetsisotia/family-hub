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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
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
    const val POLL_MAX = 6
    const val POLL_OPTION_MAX = 80

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
        val reactions: Map<String, String>,
        /** A poll's answers (2 to 6); the text is its question. Empty for ordinary messages. */
        val pollOptions: List<String> = emptyList(),
        /** Poll votes: uid to the chosen answer's position. */
        val votes: Map<String, Int> = emptyMap(),
        /** A photo, voice message or file sent with this message (its bytes are in parts/), or null. */
        val attachment: Attachment? = null
    )

    /** What was attached: [kind] is photo, voice or file; [parts] pieces hold the bytes (see [ChatMedia]). */
    data class Attachment(
        val id: String,
        val kind: String,
        val name: String,
        val mime: String,
        val size: Long,
        val parts: Int,
        val durationMs: Long = 0,
        val wave: List<Int> = emptyList(),
        val w: Int = 0,
        val h: Int = 0
    )

    private fun toAttachment(v: Any?): Attachment? {
        val a = v as? Map<*, *> ?: return null
        val kind = a["kind"] as? String ?: return null
        val id = a["id"] as? String ?: return null
        return Attachment(
            id = id, kind = kind, name = a["name"] as? String ?: "", mime = a["mime"] as? String ?: "",
            size = (a["size"] as? Number)?.toLong() ?: 0, parts = (a["parts"] as? Number)?.toInt() ?: 1,
            durationMs = (a["durationMs"] as? Number)?.toLong() ?: 0,
            wave = (a["wave"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() },
            w = (a["w"] as? Number)?.toInt() ?: 0, h = (a["h"] as? Number)?.toInt() ?: 0
        )
    }

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
        val pollOptions = (d.get("pollOptions") as? List<*>).orEmpty().mapNotNull { it as? String }
        @Suppress("UNCHECKED_CAST")
        val votes = (d.get("votes") as? Map<String, Any?>).orEmpty().mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toInt() } }.toMap()
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
            reactions = reactions,
            pollOptions = pollOptions,
            votes = votes,
            attachment = toAttachment(d.get("attachment"))
        )
    }

    /**
     * Chats this person is in. Direct chats are found with an array-contains query on memberUids, which the
     * rules allow only for the person's own uid; the family group chat is read directly.
     */
    fun observeChats(familyId: String, uid: String): Flow<List<Chat>> =
        if (DemoChats.active(familyId)) DemoChats.chats.map { list -> list.sortedByDescending { it.lastAt } } else callbackFlow {
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
    fun observeMessages(familyId: String, chatId: String, limit: Long = PAGE): Flow<List<Message>> =
        if (DemoChats.active(familyId)) DemoChats.messages.map { it[chatId].orEmpty().sortedBy { m -> m.at }.takeLast(limit.toInt()) } else callbackFlow {
        val reg = messages(familyId, chatId).orderBy("clientAt", Query.Direction.DESCENDING).limit(limit)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, e ->
                if (e == null && snap != null) trySend(snap.documents.map { toMessage(it) }.sortedBy { it.at })
                else if (e != null) trySend(emptyList())
            }
        awaitClose { reg.remove() }
    }

    /** When each person last read the chat (uid to millis). */
    fun observeReads(familyId: String, chatId: String): Flow<Map<String, Long>> =
        if (DemoChats.active(familyId)) DemoChats.reads.map { it[chatId].orEmpty() } else callbackFlow {
        val reg = chat(familyId, chatId).collection("reads").addSnapshotListener { snap, e ->
            if (e == null && snap != null) trySend(snap.documents.associate { it.id to millis(it.get("at")) })
        }
        awaitClose { reg.remove() }
    }

    /** Creates the family group chat or a direct chat if it does not exist yet. */
    suspend fun ensureChat(familyId: String, chatId: String, members: List<String>, byUid: String) {
        if (DemoChats.active(familyId)) return
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
        /** Answers for a poll (2 to 6); [text] is then the question. */
        pollOptions: List<String> = emptyList(),
        /** Called with the message id if the server refuses it (it is then shown as not sent, with Retry). */
        onFailed: ((String) -> Unit)? = null
    ): String {
        val body = text.trim().take(MAX_TEXT)
        require(body.isNotEmpty())
        val options = pollOptions.map { it.trim().take(POLL_OPTION_MAX) }.filter { it.isNotEmpty() }.distinct().take(POLL_MAX)
        require(pollOptions.isEmpty() || options.size >= 2)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        if (DemoChats.active(familyId)) {
            DemoChats.add(chatId, Message(id, uid, name, body, now, false, null, false, replyTo?.id.orEmpty(), replyTo?.text?.take(160).orEmpty(), replyTo?.senderName.orEmpty(), forwarded, emptyMap(), options))
            return id
        }
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
        if (options.isNotEmpty()) msg["pollOptions"] = options
        val batch = db.batch()
        batch.set(messages(familyId, chatId).document(id), msg)
        batch.set(
            chat(familyId, chatId),
            mapOf("lastText" to (if (options.isNotEmpty()) "📊 " else "") + body.take(160), "lastSenderUid" to uid, "lastAt" to FieldValue.serverTimestamp()),
            SetOptions.merge()
        )
        batch.set(chat(familyId, chatId).collection("reads").document(uid), mapOf("at" to FieldValue.serverTimestamp()))
        batch.commit().addOnFailureListener { onFailed?.invoke(id) }
        return id
    }

    /**
     * Sends a photo, voice message or file. The bytes go into parts/ and the message (with a short description)
     * in one batch, so the others never see a message whose file is missing. Works offline like [send].
     */
    fun sendAttachment(
        familyId: String, chatId: String, uid: String, name: String, bytes: ByteArray,
        kind: String, fileName: String, mime: String, durationMs: Long = 0, wave: List<Int> = emptyList(), w: Int = 0, h: Int = 0,
        replyTo: Message? = null, onFailed: ((String) -> Unit)? = null
    ): String {
        require(kind in ChatMedia.KINDS)
        val pieces = ChatMedia.split(bytes)
        val id = UUID.randomUUID().toString()
        val fileId = id.replace("-", "").take(24)
        val att = Attachment(fileId, kind, fileName.take(120), mime.take(80), bytes.size.toLong(), pieces.size, durationMs, wave.take(64), w, h)
        val label = ChatMedia.label(kind, fileName, durationMs)
        val now = System.currentTimeMillis()
        if (DemoChats.active(familyId)) {
            DemoChats.files[fileId] = bytes
            DemoChats.add(chatId, Message(id, uid, name, label, now, false, null, false, replyTo?.id.orEmpty(), replyTo?.text?.take(160).orEmpty(), replyTo?.senderName.orEmpty(), false, emptyMap(), attachment = att))
            return id
        }
        val attMap = HashMap<String, Any>()
        attMap["id"] = att.id; attMap["kind"] = att.kind; attMap["name"] = att.name; attMap["mime"] = att.mime
        attMap["size"] = att.size; attMap["parts"] = att.parts
        if (durationMs > 0) attMap["durationMs"] = durationMs
        if (att.wave.isNotEmpty()) attMap["wave"] = att.wave
        if (w > 0 && h > 0) { attMap["w"] = w; attMap["h"] = h }
        val msg = HashMap<String, Any?>()
        msg["senderUid"] = uid; msg["senderName"] = name; msg["text"] = label
        msg["at"] = FieldValue.serverTimestamp(); msg["clientAt"] = now; msg["attachment"] = attMap
        if (replyTo != null) { msg["replyToId"] = replyTo.id; msg["replyToText"] = replyTo.text.take(160); msg["replyToName"] = replyTo.senderName }
        val batch = db.batch()
        pieces.forEachIndexed { n, data ->
            batch.set(chat(familyId, chatId).collection("parts").document(ChatMedia.partId(fileId, n)), mapOf("senderUid" to uid, "data" to data, "at" to FieldValue.serverTimestamp()))
        }
        batch.set(messages(familyId, chatId).document(id), msg)
        batch.set(chat(familyId, chatId), mapOf("lastText" to label, "lastSenderUid" to uid, "lastAt" to FieldValue.serverTimestamp()), SetOptions.merge())
        batch.set(chat(familyId, chatId).collection("reads").document(uid), mapOf("at" to FieldValue.serverTimestamp()))
        batch.commit().addOnFailureListener { onFailed?.invoke(id) }
        return id
    }

    /** The bytes of an attachment, put back together from its parts (Firestore keeps them for offline use). */
    suspend fun loadAttachment(familyId: String, chatId: String, a: Attachment): ByteArray {
        if (DemoChats.active(familyId)) return DemoChats.files[a.id] ?: error("Not on this phone")
        val col = chat(familyId, chatId).collection("parts")
        val parts = (0 until a.parts).map { n -> col.document(ChatMedia.partId(a.id, n)).get().await().getString("data") }
        return ChatMedia.join(parts)
    }

    /** "Typing…": sets this person's typing time (the screen calls it at most every few seconds). */
    fun setTyping(familyId: String, chatId: String, uid: String) {
        if (DemoChats.active(familyId)) return
        chat(familyId, chatId).collection("typing").document(uid).set(mapOf("at" to FieldValue.serverTimestamp()))
    }

    fun clearTyping(familyId: String, chatId: String, uid: String) {
        if (DemoChats.active(familyId)) return
        chat(familyId, chatId).collection("typing").document(uid).delete()
    }

    /** When each person in the chat last typed (uid to millis). */
    fun observeTyping(familyId: String, chatId: String): Flow<Map<String, Long>> =
        if (DemoChats.active(familyId)) DemoChats.typing.map { it[chatId].orEmpty() } else callbackFlow {
        val reg = chat(familyId, chatId).collection("typing").addSnapshotListener { snap, e ->
            if (e == null && snap != null) trySend(snap.documents.associate { it.id to millis(it.get("at")) })
        }
        awaitClose { reg.remove() }
    }

    /** Sends again a message the server refused; the old copy is left as it was. */
    fun resend(familyId: String, chatId: String, uid: String, name: String, m: Message) =
        send(familyId, chatId, uid, name, m.text, pollOptions = m.pollOptions)

    /** Votes for answer [option] of a poll, or clears my vote when null. Only my own vote can change. */
    suspend fun vote(familyId: String, chatId: String, id: String, uid: String, option: Int?) {
        if (DemoChats.active(familyId)) {
            DemoChats.change(chatId, id) { m -> m.copy(votes = if (option == null) m.votes - uid else m.votes + (uid to option)) }
            return
        }
        messages(familyId, chatId).document(id)
            .update("votes.$uid", option ?: FieldValue.delete())
            .await()
    }

    suspend fun edit(familyId: String, chatId: String, id: String, text: String) {
        val body = text.trim().take(MAX_TEXT)
        require(body.isNotEmpty())
        if (DemoChats.active(familyId)) { DemoChats.change(chatId, id) { it.copy(text = body, editedAt = System.currentTimeMillis()) }; return }
        messages(familyId, chatId).document(id).update(mapOf("text" to body, "editedAt" to FieldValue.serverTimestamp())).await()
    }

    /** Delete for everyone: the text is removed and the message shows as deleted. */
    suspend fun deleteForEveryone(familyId: String, chatId: String, id: String, attachment: Attachment? = null) {
        if (DemoChats.active(familyId)) { DemoChats.change(chatId, id) { it.copy(text = "", deletedForAll = true) }; return }
        messages(familyId, chatId).document(id).update(mapOf("text" to "", "deletedForAll" to true, "editedAt" to FieldValue.serverTimestamp())).await()
        // The file's bytes are removed too (only the sender may).
        if (attachment != null) (0 until attachment.parts).forEach { n ->
            try { chat(familyId, chatId).collection("parts").document(ChatMedia.partId(attachment.id, n)).delete().await() } catch (e: Exception) { }
        }
    }

    /** Adds, changes or (with null) removes this person's reaction. */
    suspend fun react(familyId: String, chatId: String, id: String, uid: String, emoji: String?) {
        if (DemoChats.active(familyId)) {
            DemoChats.change(chatId, id) { m -> m.copy(reactions = if (emoji == null) m.reactions - uid else m.reactions + (uid to emoji)) }
            return
        }
        messages(familyId, chatId).document(id).update("reactions.$uid", emoji ?: FieldValue.delete()).await()
    }

    suspend fun markRead(familyId: String, chatId: String, uid: String) {
        if (DemoChats.active(familyId)) return
        try {
            chat(familyId, chatId).collection("reads").document(uid).set(mapOf("at" to FieldValue.serverTimestamp())).await()
        } catch (e: Exception) { }
    }

    suspend fun pin(familyId: String, chatId: String, messageId: String?) {
        if (DemoChats.active(familyId)) {
            DemoChats.chats.value = DemoChats.chats.value.map { if (it.id == chatId) it.copy(pinnedMessageId = messageId.orEmpty()) else it }
            return
        }
        chat(familyId, chatId).update("pinnedMessageId", messageId ?: FieldValue.delete()).await()
    }

    /**
     * Messages from others since this person last read the chat. Uses a count query, which Firestore bills as
     * one read per 1,000 messages counted, so it stays cheap.
     */
    suspend fun unreadCount(familyId: String, chatId: String, uid: String, lastAt: Long, lastSenderUid: String): Int {
        if (lastAt <= 0L || lastSenderUid == uid) return 0
        if (DemoChats.active(familyId)) {
            val readAt = DemoChats.reads.value[chatId]?.get(uid) ?: 0L
            return DemoChats.messages.value[chatId].orEmpty().count { it.senderUid != uid && it.at > readAt }
        }
        return try {
            val readAt = millis(chat(familyId, chatId).collection("reads").document(uid).get().await().get("at"))
            if (readAt >= lastAt) 0 else messages(familyId, chatId).whereGreaterThan("clientAt", readAt)
                .count().get(AggregateSource.SERVER).await().count.toInt()
        } catch (e: Exception) {
            0
        }
    }
}

/**
 * Made-up chats for the emulator test screen only (DemoActivity). Nothing here is read from or written to
 * Firestore; it lets the automatic check open and photograph the chat screens without an account.
 */
object DemoChats {
    private var familyId = ""
    val chats = MutableStateFlow<List<ChatRepository.Chat>>(emptyList())
    val messages = MutableStateFlow<Map<String, List<ChatRepository.Message>>>(emptyMap())
    val reads = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())
    val typing = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())
    val files = mutableMapOf<String, ByteArray>()

    fun active(id: String): Boolean = id.isNotBlank() && id == familyId

    fun add(chatId: String, m: ChatRepository.Message) {
        messages.value = messages.value + (chatId to (messages.value[chatId].orEmpty() + m))
        chats.value = chats.value.map { if (it.id == chatId) it.copy(lastText = m.text.take(160), lastSenderUid = m.senderUid, lastAt = m.at) else it }
    }

    fun change(chatId: String, id: String, edit: (ChatRepository.Message) -> ChatRepository.Message) {
        messages.value = messages.value + (chatId to messages.value[chatId].orEmpty().map { if (it.id == id) edit(it) else it })
    }

    private fun demoPhoto(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(640, 480, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint()
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, 480f, 0xFF4FC3F7.toInt(), 0xFFFFE0B2.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 640f, 480f, p)
        p.shader = null; p.color = 0xFFFFB74D.toInt(); c.drawCircle(470f, 150f, 70f, p)
        p.color = 0xFF0288D1.toInt(); c.drawRect(0f, 330f, 640f, 480f, p)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    /** A 1-second, quiet 440 Hz tone as a WAV file, so the player has something real to play. */
    private fun demoVoice(): ByteArray {
        val rate = 8000; val n = rate
        val data = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (kotlin.math.sin(2 * Math.PI * 440 * i / rate) * 3000).toInt()
            data[2 * i] = (v and 0xFF).toByte(); data[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(data.size)
        return h.array() + data
    }

    /** Fills a family chat and one direct chat with sample messages. [others] are (uid, name) pairs. */
    fun seed(family: String, me: String, myName: String, others: List<Pair<String, String>>) {
        familyId = family
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        val a = others.getOrNull(0) ?: ("asha" to "Asha")
        val b = others.getOrNull(1) ?: ("ravi" to "Ravi")
        fun m(id: String, who: Pair<String, String>, text: String, at: Long, reply: ChatRepository.Message? = null, reactions: Map<String, String> = emptyMap()) =
            ChatRepository.Message(id, who.first, who.second, text, at, false, null, false, reply?.id.orEmpty(), reply?.text.orEmpty(), reply?.senderName.orEmpty(), false, reactions)
        val self = me to myName
        val g1 = m("g1", a, "Good morning everyone! Dinner at our place on Sunday?", now - 26 * hour)
        val g2 = m("g2", b, "Count me in. Shall I bring dessert?", now - 25 * hour, reactions = mapOf(a.first to "👍"))
        val g3 = m("g3", self, "Yes please! I'll order the cake for Dadi's birthday too.", now - 24 * hour, reply = g1)
        val g4 = m("g4", a, "🎂🎉", now - 23 * hour)
        val g5 = m("g5", b, "Electricity bill is due on the 28th. I've added it to Finance.", now - 2 * hour, reactions = mapOf(me to "🙏", a.first to "❤️"))
        val g6 = m("g6", self, "Thanks! I'll pay it tonight.", now - 90 * 60_000L, reply = g5)
        val g7 = m("g7", a, "@Demo don't forget Dadi's medicines on the way back 💊", now - 12 * 60_000L)
        val dm = ChatRepository.directId(me, a.first)
        val d1 = m("d1", a, "Can you pick up the kids from school today?", now - 5 * hour)
        val d2 = m("d2", self, "Sure, I'll leave office by 3.", now - 4 * hour)
        val d3 = m("d3", a, "😊", now - 3 * hour)
        val poll = m("g8", b, "Sunday lunch: what should we cook?", now - 6 * 60_000L)
            .copy(pollOptions = listOf("Biryani", "Chole bhature", "Dosa"), votes = mapOf(a.first to 0, me to 0, b.first to 2))
        // Attachments for the emulator check: a made-up photo, a short voice clip and a PDF (all generated here).
        files["demophoto01"] = demoPhoto()
        files["demovoice01"] = demoVoice()
        files["demofile01"] = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF".toByteArray()
        val d4 = m("d4", a, ChatMedia.label("photo", "", 0), now - 150 * 60_000L)
            .copy(attachment = ChatRepository.Attachment("demophoto01", "photo", "beach.jpg", "image/jpeg", files["demophoto01"]!!.size.toLong(), 1, w = 640, h = 480))
        val d5 = m("d5", self, ChatMedia.label("voice", "", 7_000), now - 140 * 60_000L)
            .copy(attachment = ChatRepository.Attachment("demovoice01", "voice", "voice.wav", "audio/wav", files["demovoice01"]!!.size.toLong(), 1, 7_000,
                ChatMedia.wave((0 until 70).map { (kotlin.math.sin(it / 4.0) * 50 + 55).toInt() })))
        val g9 = m("g9", b, ChatMedia.label("file", "Car insurance 2026.pdf", 0), now - 3 * 60_000L)
            .copy(attachment = ChatRepository.Attachment("demofile01", "file", "Car insurance 2026.pdf", "application/pdf", 1_240_000, 3))
        typing.value = mapOf(dm to mapOf(a.first to now + 50_000L))
        messages.value = mapOf(ChatRepository.FAMILY_CHAT to listOf(g1, g2, g3, g4, g5, g6, g7, poll, g9), dm to listOf(d1, d2, d3, d4, d5))
        reads.value = mapOf(
            ChatRepository.FAMILY_CHAT to mapOf(me to now - 60 * 60_000L, b.first to now - 30 * 60_000L),
            dm to mapOf(me to now - 3 * hour, a.first to now - 3 * hour)
        )
        chats.value = listOf(
            ChatRepository.Chat(ChatRepository.FAMILY_CHAT, "group", emptyList(), "Family", "📊 " + poll.text, b.first, poll.at, "g5"),
            ChatRepository.Chat(dm, "direct", listOf(me, a.first).sorted(), "", d3.text, a.first, d3.at, "")
        )
    }
}
