package com.familyhub.app.safety

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.familyhub.app.work.Notifier
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.ZoneId
import java.util.Date

/** A family member's shared location as one viewer sees it. */
data class SharedLocation(
    val uid: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val battery: Int,
    val updatedAt: Long,
    val expiresAt: Long,
    /** End time, or 0 for "until stopped". */
    val endAt: Long
)

/**
 * Live location in Firestore: one small document per person, families/{familyId}/locations/{uid}, overwritten
 * on each update and deleted when sharing stops, so no trail of old points is kept. Only the person sharing and
 * the family members they chose can read it, and only until it expires (Firestore rules; no admin override).
 */
object LocationShares {
    private fun ref(familyId: String, uid: String) =
        FirebaseFirestore.getInstance().collection("families").document(familyId).collection("locations").document(uid)

    fun write(s: ShareState, loc: SeenLocation, battery: Int, now: Long) {
        val data = hashMapOf<String, Any>(
            "uid" to s.uid,
            "name" to s.name.take(80),
            "lat" to loc.lat,
            "lng" to loc.lng,
            "accuracy" to loc.accuracyM.toDouble(),
            "battery" to battery.coerceIn(0, 100),
            "updatedAt" to FieldValue.serverTimestamp(),
            // When the phone measured this place; it can be older than the upload if no new fix came.
            "fixAt" to Timestamp(Date(loc.at)),
            "expiresAt" to Timestamp(Date(SafetyLogic.expiresAt(now, s.endAt))),
            "endAt" to (s.endAt ?: 0L),
            "viewerUids" to s.viewerUids.filter { it != s.uid }.distinct().take(20)
        )
        ref(s.familyId, s.uid).set(data).addOnFailureListener { Log.w("LocationShares", "Location not saved", it) }
    }

    fun delete(familyId: String, uid: String) {
        if (familyId.isBlank() || uid.isBlank()) return
        ref(familyId, uid).delete().addOnFailureListener { Log.w("LocationShares", "Location not removed", it) }
    }

    /**
     * Follows one member's shared location. Emits null when they are not sharing with me (or it expired).
     * Listens only while a screen is open.
     */
    fun observe(familyId: String, memberUid: String): Flow<SharedLocation?> = callbackFlow {
        val reg = ref(familyId, memberUid).addSnapshotListener { d, e ->
            if (e != null || d == null || !d.exists()) {
                trySend(null)
                return@addSnapshotListener
            }
            val lat = d.getDouble("lat")
            val lng = d.getDouble("lng")
            if (lat == null || lng == null) {
                trySend(null)
                return@addSnapshotListener
            }
            trySend(
                SharedLocation(
                    uid = memberUid,
                    name = d.getString("name").orEmpty(),
                    lat = lat,
                    lng = lng,
                    accuracyM = (d.getDouble("accuracy") ?: 0.0).toFloat(),
                    battery = (d.getLong("battery") ?: -1L).toInt(),
                    updatedAt = (d.getTimestamp("fixAt") ?: d.getTimestamp("updatedAt"))?.toDate()?.time ?: System.currentTimeMillis(),
                    expiresAt = d.getTimestamp("expiresAt")?.toDate()?.time ?: 0L,
                    endAt = d.getLong("endAt") ?: 0L
                )
            )
        }
        awaitClose { reg.remove() }
    }
}

/**
 * Shares this phone's location while it runs, with a notification that stays visible and a Stop button.
 * Started only from the app's screen after the person chose who sees it and for how long. Updates at most
 * once a minute (after moving 25 m) plus every 5 minutes, to save battery and Firestore writes.
 */
class LocationShareService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 4401
        private const val ACTION_STOP = "com.familyhub.app.safety.STOP_SHARING"

        fun hasPermission(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Starts sharing. Call only while the app is on screen (Android requires that for location). */
        fun start(ctx: Context, s: ShareState) {
            SafetyStore.setShare(ctx, s)
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, LocationShareService::class.java))
            } catch (e: Exception) {
                Log.w("LocationShare", "Could not start sharing", e)
                SafetyStore.setShare(ctx, null)
            }
        }

        fun stop(ctx: Context) {
            val s = SafetyStore.share(ctx)
            SafetyStore.setShare(ctx, null)
            if (s != null) LocationShares.delete(s.familyId, s.uid)
            try { ctx.stopService(Intent(ctx, LocationShareService::class.java)) } catch (_: Exception) { }
        }

        /** True while the service is sharing in this app process. */
        @Volatile
        var running = false
            private set

        /**
         * When the app opens: if Android stopped sharing early (phone restart, app closed from recents), it starts
         * again while time is left; if the time is over, the shared location is removed.
         */
        fun resume(ctx: Context) {
            val s = SafetyStore.share(ctx) ?: return
            if (running) return
            if ((s.endAt != null && s.endAt <= System.currentTimeMillis()) || !hasPermission(ctx) || !SafetyStore.locationOn(ctx)) {
                stop(ctx)
                return
            }
            start(ctx, s)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastUploaded: SeenLocation? = null
    private var lastUploadAt = 0L
    private var latest: SeenLocation? = null
    private var listening = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        val s = SafetyStore.share(this)
        // Enter the foreground first in every case: Android stops an app whose foreground service never does.
        try {
            val n = build(s ?: ShareState("", "", "", emptyList(), emptyList(), null, System.currentTimeMillis()))
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else startForeground(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            Log.w("LocationShare", "Foreground start refused", e)
            finish()
            return START_NOT_STICKY
        }
        if (s == null || !hasPermission(this)) {
            finish()
            return START_NOT_STICKY
        }
        running = true
        startUpdates()
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        if (listening) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        var any = false
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 30_000L, 10f, listener, Looper.getMainLooper())
                    any = true
                }
            } catch (e: Exception) {
                Log.w("LocationShare", "No updates from $p", e)
            }
        }
        listening = any
        // Start from the last known place so viewers see something straight away.
        SafetySms.bestLocation(this)?.let { latest = it }
    }

    private fun onFix(l: Location) {
        val s = SeenLocation(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 0f, System.currentTimeMillis())
        // A rough network fix does not replace a recent accurate one.
        val prev = latest
        if (prev != null && s.accuracyM > 200f && prev.accuracyM < 50f && s.at - prev.at < 2 * 60_000L) return
        latest = s
        SafetyStore.saveLocation(this, s)
        upload(false)
    }

    private fun tick() {
        val s = SafetyStore.share(this)
        if (s == null) {
            finish()
            return
        }
        if (s.endAt != null && System.currentTimeMillis() >= s.endAt) {
            finish()
            return
        }
        upload(true)
    }

    private fun upload(heartbeat: Boolean) {
        val s = SafetyStore.share(this) ?: return
        val loc = latest ?: return
        val now = System.currentTimeMillis()
        if (s.endAt != null && now >= s.endAt) {
            finish()
            return
        }
        val due = SafetyLogic.shouldUpload(lastUploaded, lastUploadAt, loc, now)
        if (!due && !(heartbeat && now - lastUploadAt >= SafetyLogic.UPLOAD_HEARTBEAT_MS)) return
        val battery = BatteryInfo.read(this)?.first ?: -1
        LocationShares.write(s, loc, battery, now)
        lastUploaded = loc
        lastUploadAt = now
    }

    private fun finish() {
        running = false
        handler.removeCallbacks(tickRunnable)
        try {
            (getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(listener)
        } catch (_: Exception) { }
        listening = false
        val s = SafetyStore.share(this)
        if (s != null) {
            LocationShares.delete(s.familyId, s.uid)
            SafetyStore.setShare(this, null)
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tickRunnable)
        try {
            (getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(listener)
        } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun build(s: ShareState): Notification {
        Notifier.ensureChannels(this)
        val stop = PendingIntent.getService(
            this, 4406, Intent(this, LocationShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val who = when {
            s.viewerNames.isEmpty() -> "${s.viewerUids.size} family members"
            s.viewerNames.size <= 2 -> s.viewerNames.joinToString(" and ")
            else -> "${s.viewerNames.size} family members"
        }
        val until = s.endAt?.let { " until " + SafetyLogic.timeText(it, ZoneId.systemDefault()).substringAfter(", ") } ?: " until you stop"
        return NotificationCompat.Builder(this, Notifier.CH_LOCATION)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Sharing your location")
            .setContentText("With $who$until")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(Notifier.openApp(this, "safety", 4407))
            .addAction(0, "Stop sharing", stop)
            .build()
    }
}
