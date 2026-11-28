package com.familyhub.app.data.remote

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Memory photos, comments and reactions on the free Firebase plan (no Cloud Storage): photo bytes are kept in
 * Firestore in parts of at most ~600 KB, like chat attachments. A family memory's parts live in
 * families/{f}/memoryParts (family members with Memories access); an "Only me" memory's in users/{uid}/memoryParts.
 * Comments and reactions live in families/{f}/memoryNotes.
 */
object MemoryRepository {
    private val db get() = FirebaseFirestore.getInstance()

    data class Photo(val id: String, val parts: Int, val w: Int, val h: Int, val byUid: String)
    data class Note(val id: String, val memoryId: String, val uid: String, val name: String, val kind: String, val text: String, val at: Long)

    // Test screens keep everything on the phone.
    private val demoFiles = mutableMapOf<String, ByteArray>()
    private val demoNotes = MutableStateFlow<List<Note>>(emptyList())

    private fun partsCol(familyId: String, uid: String, private: Boolean) =
        if (private) db.collection("users").document(uid).collection("memoryParts")
        else db.collection("families").document(familyId).collection("memoryParts")

    private fun notesCol(familyId: String) = db.collection("families").document(familyId).collection("memoryNotes")

    fun photos(rec: Rec): List<Photo> = (rec["photos"] as? List<*>).orEmpty().mapNotNull { p ->
        val m = p as? Map<*, *> ?: return@mapNotNull null
        Photo(m["id"] as? String ?: return@mapNotNull null, (m["parts"] as? Number)?.toInt() ?: 1,
            (m["w"] as? Number)?.toInt() ?: 0, (m["h"] as? Number)?.toInt() ?: 0, m["by"] as? String ?: "")
    }

    /** Saves the photo's bytes and returns what to add to the memory's "photos" list. */
    suspend fun savePhoto(familyId: String, uid: String, memoryId: String, private: Boolean, bytes: ByteArray, w: Int, h: Int): Map<String, Any> {
        val pieces = ChatMedia.split(bytes)
        require(pieces.size <= ChatMedia.MAX_PARTS) { "This photo is too large." }
        val id = UUID.randomUUID().toString().replace("-", "").take(24)
        if (DemoChats.active(familyId)) {
            demoFiles[id] = bytes
        } else {
            val col = partsCol(familyId, uid, private)
            val batch = db.batch()
            pieces.forEachIndexed { n, data ->
                batch.set(col.document(ChatMedia.partId(id, n)),
                    mapOf("ownerUid" to uid, "memoryId" to memoryId, "data" to data, "at" to FieldValue.serverTimestamp()))
            }
            batch.commit().await()
        }
        return mapOf("id" to id, "parts" to pieces.size, "w" to w, "h" to h, "by" to uid)
    }

    suspend fun loadPhoto(familyId: String, uid: String, private: Boolean, p: Photo): ByteArray {
        if (DemoChats.active(familyId)) return demoFiles[p.id] ?: demoPhoto()
        val col = partsCol(familyId, uid, private)
        return ChatMedia.join((0 until p.parts).map { n -> col.document(ChatMedia.partId(p.id, n)).get().await().getString("data") })
    }

    /** The stored parts of a photo as they are (for backups). */
    suspend fun fetchParts(familyId: String, uid: String, private: Boolean, p: Photo): List<String> {
        if (DemoChats.active(familyId)) return emptyList()
        val col = partsCol(familyId, uid, private)
        return (0 until p.parts).map { n -> col.document(ChatMedia.partId(p.id, n)).get(com.google.firebase.firestore.Source.SERVER).await().getString("data").orEmpty() }
    }

    /** Writes a backed-up photo's parts if the server does not have them. Returns true when something was written. */
    suspend fun restoreParts(familyId: String, uid: String, private: Boolean, memoryId: String, photoId: String, parts: List<String>): Boolean {
        if (parts.isEmpty() || parts.any { it.isEmpty() }) return false
        val col = partsCol(familyId, uid, private)
        if (col.document(ChatMedia.partId(photoId, 0)).get(com.google.firebase.firestore.Source.SERVER).await().exists()) return false
        val batch = db.batch()
        parts.forEachIndexed { n, data ->
            batch.set(col.document(ChatMedia.partId(photoId, n)), mapOf("ownerUid" to uid, "memoryId" to memoryId, "data" to data, "at" to FieldValue.serverTimestamp()))
        }
        batch.commit().await()
        return true
    }

    /** Removes a photo's bytes (the person who added it, or an admin). */
    suspend fun deletePhoto(familyId: String, uid: String, private: Boolean, p: Photo) {
        if (DemoChats.active(familyId)) { demoFiles.remove(p.id); return }
        val col = partsCol(familyId, uid, private)
        (0 until p.parts).forEach { n -> col.document(ChatMedia.partId(p.id, n)).delete().await() }
    }

    fun observeNotes(familyId: String, memoryId: String): Flow<List<Note>> =
        if (DemoChats.active(familyId)) demoNotes.map { l -> l.filter { it.memoryId == memoryId } }
        else callbackFlow {
            val reg = notesCol(familyId).whereEqualTo("memoryId", memoryId).addSnapshotListener { snap, e ->
                if (e != null) { trySend(emptyList()); return@addSnapshotListener }
                trySend(snap?.documents.orEmpty().map { d ->
                    Note(d.id, d.getString("memoryId").orEmpty(), d.getString("uid").orEmpty(), d.getString("name").orEmpty(),
                        d.getString("kind").orEmpty(), d.getString("text").orEmpty(), (d.get("clientAt") as? Number)?.toLong() ?: 0L)
                }.sortedBy { it.at })
            }
            awaitClose { reg.remove() }
        }

    suspend fun addComment(familyId: String, memoryId: String, uid: String, name: String, text: String) {
        val now = System.currentTimeMillis()
        if (DemoChats.active(familyId)) { demoNotes.value = demoNotes.value + Note(UUID.randomUUID().toString(), memoryId, uid, name, "comment", text, now); return }
        notesCol(familyId).add(mapOf("memoryId" to memoryId, "uid" to uid, "name" to name, "kind" to "comment", "text" to text,
            "clientAt" to now, "at" to FieldValue.serverTimestamp())).await()
    }

    /** One reaction per person per memory; choosing the same emoji again removes it. */
    suspend fun react(familyId: String, memoryId: String, uid: String, name: String, emoji: String, current: String?) {
        val id = "${memoryId}_${uid}_r"
        if (DemoChats.active(familyId)) {
            val rest = demoNotes.value.filterNot { it.id == id }
            demoNotes.value = if (current == emoji) rest else rest + Note(id, memoryId, uid, name, "reaction", emoji, System.currentTimeMillis())
            return
        }
        val ref = notesCol(familyId).document(id)
        if (current == emoji) ref.delete().await()
        else ref.set(mapOf("memoryId" to memoryId, "uid" to uid, "name" to name, "kind" to "reaction", "text" to emoji,
            "clientAt" to System.currentTimeMillis(), "at" to FieldValue.serverTimestamp())).await()
    }

    suspend fun deleteNote(familyId: String, id: String) {
        if (DemoChats.active(familyId)) { demoNotes.value = demoNotes.value.filterNot { it.id == id }; return }
        notesCol(familyId).document(id).delete().await()
    }

    /** Test screens: seed a few comments and reactions. */
    fun seedDemo(memoryId: String) {
        val t = System.currentTimeMillis()
        demoNotes.value = listOf(
            Note("n1", memoryId, "asha", "Asha", "comment", "Best trip ever!", t - 7_200_000),
            Note("n2", memoryId, "ravi", "Ravi", "comment", "Let's go again next winter.", t - 3_600_000),
            Note("${memoryId}_asha_r", memoryId, "asha", "Asha", "reaction", "❤️", t),
            Note("${memoryId}_ravi_r", memoryId, "ravi", "Ravi", "reaction", "🎉", t)
        )
    }

    /** A small generated picture for test screens. */
    private fun demoPhoto(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(320, 200, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.rgb(255, 183, 77))
        val p = android.graphics.Paint().apply { color = android.graphics.Color.rgb(255, 241, 118) }
        c.drawCircle(240f, 60f, 36f, p)
        p.color = android.graphics.Color.rgb(121, 85, 72); c.drawRect(0f, 140f, 320f, 200f, p)
        val out = java.io.ByteArrayOutputStream(); bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }
}
