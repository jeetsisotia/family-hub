package com.familyhub.app.safety

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One alert attempt, kept on this phone only (last 60). */
data class AlertLogEntry(
    val id: String,
    val at: Long,
    /** low_battery, sos, arrival or test */
    val kind: String,
    val name: String,
    val phone: String,
    /** Sending, Sent, Delivered, Failed: ..., Not sent: ..., Test only, not sent */
    val status: String
)

/** Live sharing in progress on this phone. */
data class ShareState(
    val familyId: String,
    val uid: String,
    val name: String,
    val viewerUids: List<String>,
    val viewerNames: List<String>,
    /** End time, or null for "until I stop". */
    val endAt: Long?,
    val startedAt: Long
)

/** A safe-arrival timer in progress. */
data class ArrivalState(
    val deadline: Long,
    val destination: String,
    val familyId: String,
    val uid: String,
    val name: String
)

/**
 * Safety settings, emergency contacts, the emergency card and the alert log. They are kept on this phone so
 * alerts work without a connection and when the app is closed. Contacts, the card and settings are also saved
 * to the owner's private Firestore record (users/{uid}/safety/settings, readable only by them) so they come
 * back on a new phone. The alert log and locations never leave the phone this way.
 */
object SafetyStore {
    private const val PREFS = "family_hub_safety"

    /** Changes whenever anything here changes, so screens can refresh. */
    val tick = mutableIntStateOf(0)

    private fun p(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun changed() {
        tick.intValue = tick.intValue + 1
    }

    // ---------- contacts ----------
    fun contacts(ctx: Context): List<EmergencyContact> {
        val arr = try { JSONArray(p(ctx).getString("contacts", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            EmergencyContact(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name"),
                phone = o.optString("phone"),
                relation = o.optString("relation"),
                lowBattery = o.optBoolean("lowBattery", true),
                sos = o.optBoolean("sos", true),
                arrival = o.optBoolean("arrival", true)
            )
        }
    }

    fun saveContacts(ctx: Context, list: List<EmergencyContact>) {
        val arr = JSONArray()
        list.take(10).forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id).put("name", c.name).put("phone", c.phone).put("relation", c.relation)
                    .put("lowBattery", c.lowBattery).put("sos", c.sos).put("arrival", c.arrival)
            )
        }
        p(ctx).edit().putString("contacts", arr.toString()).putLong("editedAt", System.currentTimeMillis()).apply()
        changed()
    }

    /**
     * The first time, offers the two emergency contacts already saved in My Profile. The profile itself is
     * not changed.
     */
    fun importFromProfile(ctx: Context, userDoc: Map<String, Any?>) {
        if (p(ctx).getBoolean("importedProfile", false)) return
        if (contacts(ctx).isEmpty()) {
            val found = (1..2).mapNotNull { i ->
                val phone = SafetyLogic.cleanPhone((userDoc["emergency${i}Phone"] as? String).orEmpty()) ?: return@mapNotNull null
                EmergencyContact(
                    id = UUID.randomUUID().toString(),
                    name = (userDoc["emergency${i}Name"] as? String).orEmpty().ifBlank { "Emergency contact $i" },
                    phone = phone,
                    relation = (userDoc["emergency${i}Relation"] as? String).orEmpty()
                )
            }
            if (found.isNotEmpty()) saveContacts(ctx, found)
        }
        p(ctx).edit().putBoolean("importedProfile", true).apply()
    }

    // ---------- settings ----------
    fun lowBatteryOn(ctx: Context) = p(ctx).getBoolean("lowBatteryOn", false)
    fun threshold(ctx: Context) = SafetyLogic.clampThreshold(p(ctx).getInt("threshold", SafetyLogic.DEFAULT_THRESHOLD))
    /** Location features (live sharing, location in alerts). Off until this person turns them on. */
    fun locationOn(ctx: Context) = p(ctx).getBoolean("locationOn", false)

    fun setLowBattery(ctx: Context, on: Boolean, threshold: Int = threshold(ctx)) {
        p(ctx).edit().putBoolean("lowBatteryOn", on).putInt("threshold", SafetyLogic.clampThreshold(threshold))
            .putLong("editedAt", System.currentTimeMillis()).apply()
        changed()
    }

    fun setLocationOn(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("locationOn", on).putLong("editedAt", System.currentTimeMillis()).apply()
        changed()
    }

    /** Who this phone belongs to, so alerts sent while the app is closed can say whose phone it is. */
    fun setIdentity(ctx: Context, uid: String, name: String, familyId: String) {
        val e = p(ctx)
        if (e.getString("uid", "") == uid && e.getString("name", "") == name && e.getString("familyId", "") == familyId) return
        e.edit().putString("uid", uid).putString("name", name).putString("familyId", familyId).apply()
    }

    fun myName(ctx: Context) = p(ctx).getString("name", "").orEmpty()
    fun myUid(ctx: Context) = p(ctx).getString("uid", "").orEmpty()
    fun familyId(ctx: Context) = p(ctx).getString("familyId", "").orEmpty()

    // ---------- emergency card ----------
    fun card(ctx: Context): EmergencyCard {
        val o = try { JSONObject(p(ctx).getString("card", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        return EmergencyCard(
            enabled = o.optBoolean("enabled", false),
            bloodGroup = o.optString("bloodGroup"),
            allergies = o.optString("allergies"),
            notes = o.optString("notes"),
            includeInSms = o.optBoolean("includeInSms", false)
        )
    }

    fun saveCard(ctx: Context, c: EmergencyCard) {
        val o = JSONObject().put("enabled", c.enabled).put("bloodGroup", c.bloodGroup.take(10))
            .put("allergies", c.allergies.take(200)).put("notes", c.notes.take(300)).put("includeInSms", c.includeInSms)
        p(ctx).edit().putString("card", o.toString()).putLong("editedAt", System.currentTimeMillis()).apply()
        changed()
    }

    // ---------- low-battery episode ----------
    fun batteryAlerted(ctx: Context) = p(ctx).getBoolean("batteryAlerted", false)
    fun batteryLastAlertAt(ctx: Context) = p(ctx).getLong("batteryLastAt", 0L)
    fun setBatteryAlerted(ctx: Context, alerted: Boolean, at: Long? = null) {
        val e = p(ctx).edit().putBoolean("batteryAlerted", alerted)
        if (at != null) e.putLong("batteryLastAt", at)
        e.apply()
        changed()
    }

    // ---------- last known location ----------
    fun lastLocation(ctx: Context): SeenLocation? {
        val s = p(ctx).getString("lastLoc", null) ?: return null
        return try {
            val o = JSONObject(s)
            SeenLocation(o.getDouble("lat"), o.getDouble("lng"), o.getDouble("acc").toFloat(), o.getLong("at"))
        } catch (e: Exception) { null }
    }

    fun saveLocation(ctx: Context, l: SeenLocation) {
        val old = lastLocation(ctx)
        if (old != null && old.at >= l.at) return
        p(ctx).edit().putString(
            "lastLoc", JSONObject().put("lat", l.lat).put("lng", l.lng).put("acc", l.accuracyM.toDouble()).put("at", l.at).toString()
        ).apply()
    }

    fun clearLocation(ctx: Context) {
        p(ctx).edit().remove("lastLoc").apply()
        changed()
    }

    // ---------- SMS log and limit ----------
    fun log(ctx: Context): List<AlertLogEntry> {
        val arr = try { JSONArray(p(ctx).getString("log", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            AlertLogEntry(o.optString("id"), o.optLong("at"), o.optString("kind"), o.optString("name"), o.optString("phone"), o.optString("status"))
        }
    }

    private fun writeLog(ctx: Context, list: List<AlertLogEntry>) {
        val arr = JSONArray()
        list.sortedByDescending { it.at }.take(60).forEach {
            arr.put(JSONObject().put("id", it.id).put("at", it.at).put("kind", it.kind).put("name", it.name).put("phone", it.phone).put("status", it.status))
        }
        p(ctx).edit().putString("log", arr.toString()).commit()
        changed()
    }

    @Synchronized
    fun addLog(ctx: Context, e: AlertLogEntry) = writeLog(ctx, listOf(e) + log(ctx))

    /** Updates a log entry's status. A failure or delivery is never overwritten by a later "Sent". */
    @Synchronized
    fun updateLog(ctx: Context, id: String, status: String) {
        val list = log(ctx)
        if (list.none { it.id == id }) return
        writeLog(ctx, list.map {
            if (it.id != id) it
            else if (status == "Sent" && (it.status.startsWith("Failed") || it.status == "Delivered")) it
            else it.copy(status = status)
        })
    }

    @Synchronized
    fun clearLog(ctx: Context) = writeLog(ctx, emptyList())

    /** When automatic SMS went out, for the hourly limit. */
    fun autoSmsTimes(ctx: Context): List<Long> {
        val arr = try { JSONArray(p(ctx).getString("smsTimes", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.optLong(it) }
    }

    fun addAutoSmsTimes(ctx: Context, times: List<Long>) {
        val now = System.currentTimeMillis()
        val keep = (autoSmsTimes(ctx) + times).filter { now - it < 2 * 60 * 60_000L }
        p(ctx).edit().putString("smsTimes", JSONArray(keep).toString()).apply()
    }

    // ---------- live sharing ----------
    fun share(ctx: Context): ShareState? {
        val s = p(ctx).getString("share", null) ?: return null
        return try {
            val o = JSONObject(s)
            val v = o.getJSONArray("viewers")
            val vn = o.optJSONArray("viewerNames") ?: JSONArray()
            ShareState(
                familyId = o.getString("familyId"),
                uid = o.getString("uid"),
                name = o.optString("name"),
                viewerUids = (0 until v.length()).map { v.getString(it) },
                viewerNames = (0 until vn.length()).map { vn.getString(it) },
                endAt = o.optLong("endAt", 0L).takeIf { it > 0L },
                startedAt = o.optLong("startedAt")
            )
        } catch (e: Exception) { null }
    }

    fun setShare(ctx: Context, s: ShareState?) {
        val e = p(ctx).edit()
        if (s == null) e.remove("share") else e.putString(
            "share",
            JSONObject().put("familyId", s.familyId).put("uid", s.uid).put("name", s.name)
                .put("viewers", JSONArray(s.viewerUids)).put("viewerNames", JSONArray(s.viewerNames))
                .put("endAt", s.endAt ?: 0L).put("startedAt", s.startedAt).toString()
        )
        e.commit()
        changed()
    }

    // ---------- safe arrival ----------
    fun arrival(ctx: Context): ArrivalState? {
        val s = p(ctx).getString("arrival", null) ?: return null
        return try {
            val o = JSONObject(s)
            ArrivalState(o.getLong("deadline"), o.optString("destination"), o.optString("familyId"), o.optString("uid"), o.optString("name"))
        } catch (e: Exception) { null }
    }

    fun setArrival(ctx: Context, a: ArrivalState?) {
        val e = p(ctx).edit()
        if (a == null) e.remove("arrival") else e.putString(
            "arrival",
            JSONObject().put("deadline", a.deadline).put("destination", a.destination).put("familyId", a.familyId)
                .put("uid", a.uid).put("name", a.name).toString()
        )
        e.commit()
        changed()
    }

    // ---------- private cloud copy ----------
    private fun cloudDoc(uid: String) =
        FirebaseFirestore.getInstance().collection("users").document(uid).collection("safety").document("settings")

    /** Saves contacts, the card and settings to the owner's private record. Errors are ignored (kept on the phone). */
    suspend fun saveToCloud(ctx: Context, uid: String) {
        if (uid.isBlank()) return
        val c = card(ctx)
        val data = hashMapOf<String, Any>(
            "contacts" to contacts(ctx).map {
                mapOf("id" to it.id, "name" to it.name, "phone" to it.phone, "relation" to it.relation,
                    "lowBattery" to it.lowBattery, "sos" to it.sos, "arrival" to it.arrival)
            },
            "card" to mapOf("enabled" to c.enabled, "bloodGroup" to c.bloodGroup, "allergies" to c.allergies,
                "notes" to c.notes, "includeInSms" to c.includeInSms),
            "lowBatteryOn" to lowBatteryOn(ctx),
            "threshold" to threshold(ctx),
            "locationOn" to locationOn(ctx),
            "editedAt" to p(ctx).getLong("editedAt", System.currentTimeMillis()),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try { cloudDoc(uid).set(data, SetOptions.merge()).await() } catch (e: Exception) { }
    }

    /** On a new phone (nothing saved here yet), brings back the private copy. */
    suspend fun restoreFromCloud(ctx: Context, uid: String) {
        if (uid.isBlank() || p(ctx).contains("editedAt")) return
        val d = try { cloudDoc(uid).get().await() } catch (e: Exception) { return }
        if (!d.exists()) return
        val list = (d.get("contacts") as? List<*>).orEmpty().mapNotNull { x ->
            val m = x as? Map<*, *> ?: return@mapNotNull null
            EmergencyContact(
                id = (m["id"] as? String).orEmpty().ifBlank { UUID.randomUUID().toString() },
                name = (m["name"] as? String).orEmpty(),
                phone = (m["phone"] as? String).orEmpty(),
                relation = (m["relation"] as? String).orEmpty(),
                lowBattery = m["lowBattery"] as? Boolean ?: true,
                sos = m["sos"] as? Boolean ?: true,
                arrival = m["arrival"] as? Boolean ?: true
            )
        }
        if (list.isNotEmpty()) saveContacts(ctx, list)
        (d.get("card") as? Map<*, *>)?.let { m ->
            saveCard(ctx, EmergencyCard(
                enabled = m["enabled"] as? Boolean ?: false,
                bloodGroup = (m["bloodGroup"] as? String).orEmpty(),
                allergies = (m["allergies"] as? String).orEmpty(),
                notes = (m["notes"] as? String).orEmpty(),
                includeInSms = m["includeInSms"] as? Boolean ?: false
            ))
        }
        // Alerts and location stay off on a new phone until the person turns them on there (permissions differ).
        p(ctx).edit().putBoolean("importedProfile", true).putInt("threshold",
            SafetyLogic.clampThreshold((d.getLong("threshold") ?: SafetyLogic.DEFAULT_THRESHOLD.toLong()).toInt())).apply()
        changed()
    }
}
