package com.familyhub.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.familyhub.app.data.remote.ChatRepository
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Best-effort chat notifications without a server.
 *
 * Sending a push to a closed app needs a trusted server (FCM with a service key), which would need Cloud
 * Functions and the paid plan. Instead, Android runs this check about every 15 minutes while the phone is
 * online, and shows a notification for chats with new messages. Android may wait longer when the battery
 * is low, in Doze, or for apps it has restricted. Each check reads only a few chat summaries.
 */
class ChatWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        const val CHANNEL = "family_chat"
        private const val PREFS = "family_hub_chat"
        private const val KEY_SEEN = "notifiedUpTo"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<ChatWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("chat_check", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Called when the chats are open, so messages already seen are not notified later. */
        fun markSeen(ctx: Context) {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_SEEN, System.currentTimeMillis()).apply()
        }

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Family chat", NotificationManager.IMPORTANCE_DEFAULT))
            }
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.success()
        if (!AlarmScheduler.canPost(ctx)) return Result.success()
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getLong(KEY_SEEN, System.currentTimeMillis())
        if (!prefs.contains(KEY_SEEN)) { prefs.edit().putLong(KEY_SEEN, since).apply(); return Result.success() }
        return try {
            val db = FirebaseFirestore.getInstance()
            val familyId = db.collection("users").document(uid).get().await().getString("activeFamilyId").orEmpty()
            if (familyId.isBlank()) return Result.success()
            val muted = prefs.getStringSet("muted", emptySet()).orEmpty()
            val chats = db.collection("families").document(familyId).collection("chats")
            val docs = chats.whereArrayContains("memberUids", uid).get().await().documents +
                listOfNotNull(chats.document(ChatRepository.FAMILY_CHAT).get().await().takeIf { it.exists() })
            var newest = since
            ensureChannel(ctx)
            for (d in docs) {
                val at = (d.get("lastAt") as? Timestamp)?.toDate()?.time ?: 0L
                if (at <= since || d.getString("lastSenderUid") == uid || d.id in muted) continue
                newest = maxOf(newest, at)
                val title = if (d.id == ChatRepository.FAMILY_CHAT) "Family chat" else "New message"
                val n = NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.sym_action_chat)
                    .setContentTitle(title)
                    .setContentText(d.getString("lastText").orEmpty().take(120))
                    .setAutoCancel(true)
                    .setContentIntent(AlarmScheduler.openApp(ctx, "chats", d.id.hashCode()))
                    .build()
                try { NotificationManagerCompat.from(ctx).notify("chat", d.id.hashCode(), n) } catch (e: SecurityException) { }
            }
            prefs.edit().putLong(KEY_SEEN, maxOf(newest, since)).apply()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
