package com.familyhub.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.ui.modules.AlertItem
import com.familyhub.app.ui.modules.buildAlerts
import com.familyhub.app.ui.modules.daysUntil
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Runs about once a day and posts a notification for anything due within three days
 * (or overdue by up to three days): bills, EMIs, renewals, expiries, events, tasks.
 */
class ReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val user = FirebaseAuth.getInstance().currentUser ?: return Result.success()
        return try {
            val db = FirebaseFirestore.getInstance()
            val profile = db.collection("users").document(user.uid).get().await()
            val familyId = profile.getString("activeFamilyId").orEmpty()
            if (familyId.isBlank()) return Result.success()

            val base = db.collection("families").document(familyId)
            val data = HashMap<String, List<Rec>>()
            for (c in listOf("items", "events", "expenses", "tasks", "trips")) {
                val snap = base.collection(c).get().await()
                data[c] = snap.documents.map { d ->
                    val m = HashMap<String, Any?>()
                    d.data?.let { m.putAll(it) }
                    m["id"] = d.id
                    m as Rec
                }
            }
            val soon = buildAlerts(data).filter { daysUntil(it.dateMillis) in -3..3 }
            if (soon.isNotEmpty()) notify(soon)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun notify(alerts: List<AlertItem>) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Family reminders", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent()
        val pending = PendingIntent.getActivity(
            ctx, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val first = alerts.first()
        val lines = alerts.take(6).joinToString("\n") { "${it.title}: ${it.label}" }
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Family Hub: ${alerts.size} reminder(s)")
            .setContentText("${first.title}: ${first.label}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "family_reminders"
        private const val NOTIFICATION_ID = 1001
    }
}
