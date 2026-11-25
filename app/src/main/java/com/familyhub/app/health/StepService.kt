package com.familyhub.app.health

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.familyhub.app.ui.common.dayKey
import com.familyhub.app.work.Notifier
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.text.NumberFormat

/**
 * Keeps the step sensor registered while the phone is in use, so steps are counted all day.
 * Shows a quiet, low-priority notification with today's total (Android requires one).
 */
class StepService : Service(), SensorEventListener {

    companion object {
        private const val NOTIFICATION_ID = 4201
        private const val SYNC_EVERY_MS = 15 * 60_000L

        fun start(ctx: Context) {
            if (!StepTracker.isSupported(ctx) || !StepTracker.hasPermission(ctx)) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, StepService::class.java))
            } catch (e: Exception) {
                Log.w("StepService", "Could not start step counting", e)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, StepService::class.java))
        }

        /** Saves today's steps and water to the private activity log and, if shared, to the family cards. */
        fun sync(ctx: Context) {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val db = FirebaseFirestore.getInstance()
            val day = dayKey()
            val steps = StepTracker.today(ctx)
            val week = StepTracker.week(ctx)
            db.collection("users").document(uid).collection("activity").document(day).set(
                mapOf(
                    "day" to day,
                    "steps" to steps,
                    "water" to StepTracker.water(ctx),
                    "goal" to StepTracker.goal(ctx),
                    "updatedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            )
            if (StepTracker.shareWithFamily(ctx)) {
                for (f in StepTracker.families(ctx)) {
                    db.collection("families").document(f).collection("members").document(uid)
                        .update(mapOf("stepsToday" to steps, "stepsDate" to day, "stepsWeek" to week))
                        .addOnFailureListener { Log.w("StepService", "member card update failed", it) }
                }
            }
        }
    }

    private var sensorManager: SensorManager? = null
    private var lastSync = 0L
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        val n = buildNotification(StepTracker.today(this))
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            Log.w("StepService", "Foreground start refused", e)
            stopSelf()
            return
        }
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sm == null || sensor == null || !StepTracker.hasPermission(this)) {
            stopSelf()
            return
        }
        sensorManager = sm
        // Readings are batched for up to a minute to save battery.
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!StepTracker.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        StepTracker.record(this, event.values[0].toLong())
        val now = System.currentTimeMillis()
        if (now - lastNotified > 60_000L) {
            lastNotified = now
            try {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(StepTracker.today(this)))
            } catch (e: SecurityException) {
                // Notifications are off; counting continues.
            }
        }
        if (now - lastSync > SYNC_EVERY_MS) {
            lastSync = now
            sync(this)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        sync(this)
        super.onDestroy()
    }

    private fun buildNotification(steps: Long): Notification {
        val goal = StepTracker.goal(this)
        val fmt = NumberFormat.getIntegerInstance()
        return NotificationCompat.Builder(this, Notifier.CH_STEPS)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("${fmt.format(steps)} steps today")
            .setContentText("Goal ${fmt.format(goal)} • tap to open Health")
            .setProgress(goal, steps.toInt().coerceAtMost(goal), false)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(Notifier.openApp(this, "health_hub", NOTIFICATION_ID))
            .build()
    }
}
