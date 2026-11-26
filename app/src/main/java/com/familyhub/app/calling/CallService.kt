package com.familyhub.app.calling

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.familyhub.app.work.Notifier

/**
 * Keeps the microphone (and camera) allowed while a call is going on, with a visible "Call in progress"
 * notification (Android requires one). It runs only for the length of one call.
 */
class CallService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 4301
        private const val EXTRA_NAME = "name"

        fun start(ctx: Context, name: String) {
            try {
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, CallService::class.java).putExtra(EXTRA_NAME, name)
                )
            } catch (e: Exception) {
                Log.w("CallService", "Could not start the call service", e)
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, CallService::class.java)) } catch (_: Exception) { }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        val n = build(name)
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(
                    NOTIFICATION_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else startForeground(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            // Without the foreground service the call still works while the app is on screen.
            Log.w("CallService", "Foreground start refused", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun build(name: String): Notification {
        Notifier.ensureChannels(this)
        return NotificationCompat.Builder(this, Notifier.CH_CALLS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Call in progress")
            .setContentText(if (name.isBlank()) "Family Hub call" else "Call with $name")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(Notifier.openApp(this, "calls", 4302))
            .build()
    }
}
