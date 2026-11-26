package com.familyhub.app.safety

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.familyhub.app.data.remote.ChatRepository
import com.familyhub.app.work.Notifier
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Battery level and charging state, read directly (works with the app closed; no broadcast needed). */
object BatteryInfo {
    fun read(ctx: Context): Pair<Int, Boolean>? {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level !in 0..100) return null
        return level to bm.isCharging
    }
}

/**
 * Checks the battery about every 15 minutes, also with the app closed, using WorkManager (Android's own
 * scheduler; it survives restarts). A battery broadcast is not used because Android no longer wakes closed
 * apps for it. In Doze or battery saver Android may delay the check, so an alert can come late.
 */
class LowBatteryWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    companion object {
        private const val NAME = "safety_low_battery"

        fun schedule(ctx: Context) {
            try {
                WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    NAME, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<LowBatteryWorker>(15, TimeUnit.MINUTES).build()
                )
            } catch (e: Exception) {
                Log.w("LowBatteryWorker", "Could not schedule", e)
            }
        }

        fun cancel(ctx: Context) {
            try { WorkManager.getInstance(ctx).cancelUniqueWork(NAME) } catch (_: Exception) { }
        }

        /** One check, also called when the app opens. Returns what was decided. */
        fun runCheck(ctx: Context, now: Long = System.currentTimeMillis()): BatteryDecision {
            if (!SafetyStore.lowBatteryOn(ctx)) return BatteryDecision.NONE
            val (level, charging) = BatteryInfo.read(ctx) ?: return BatteryDecision.NONE
            val d = SafetyLogic.batteryDecision(
                level, charging, SafetyStore.threshold(ctx),
                SafetyStore.batteryAlerted(ctx), SafetyStore.batteryLastAlertAt(ctx), now
            )
            when (d) {
                BatteryDecision.SEND -> {
                    val to = SafetyStore.contacts(ctx).filter { it.lowBattery }
                    // The episode counts as handled even with nobody to tell, so it does not retry every 15 minutes.
                    SafetyStore.setBatteryAlerted(ctx, true, now)
                    if (to.isNotEmpty()) {
                        val text = SafetyLogic.lowBatteryText(
                            SafetyStore.myName(ctx), level, now, SafetySms.bestLocation(ctx), ZoneId.systemDefault()
                        )
                        SafetySms.send(ctx, "low_battery", to, text, automatic = true)
                        SafetyNotify.show(ctx, 4402, "Low battery alert sent",
                            "Battery at $level%. Your emergency contacts were sent an SMS. See Family safety for the result.", "safety")
                    }
                }
                BatteryDecision.RESET -> SafetyStore.setBatteryAlerted(ctx, false)
                else -> {}
            }
            return d
        }
    }

    override fun doWork(): Result {
        try { runCheck(applicationContext) } catch (e: Exception) { Log.w("LowBatteryWorker", "Check failed", e) }
        return Result.success()
    }
}

object SafetyNotify {
    fun show(ctx: Context, id: Int, title: String, text: String, route: String, actions: List<NotificationCompat.Action> = emptyList()) {
        if (!Notifier.canPost(ctx)) return
        Notifier.ensureChannels(ctx)
        val b = NotificationCompat.Builder(ctx, Notifier.CH_SAFETY)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(Notifier.openApp(ctx, route, id))
        actions.forEach { b.addAction(it) }
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, b.build())
        } catch (e: SecurityException) { }
    }

    fun cancel(ctx: Context, id: Int) {
        try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id) } catch (_: Exception) { }
    }
}

/** Messages to the family group chat (sent when online; Firestore keeps them on the phone until then). */
object SafetyChat {
    fun post(familyId: String, uid: String, name: String, text: String) {
        if (familyId.isBlank() || uid.isBlank()) return
        try {
            ChatRepository.send(familyId, ChatRepository.FAMILY_CHAT, uid, name.ifBlank { "Member" }, text)
        } catch (e: Exception) {
            Log.w("SafetyChat", "Chat message not sent", e)
        }
    }
}

/**
 * Safe-arrival timer: at the chosen time the phone asks "Did you arrive safely?". If there is no answer
 * within 5 minutes, the chosen contacts get an SMS and the family chat gets a message.
 */
object ArrivalTimer {
    const val ACTION_CHECK = "com.familyhub.app.safety.ARRIVAL_CHECK"
    const val ACTION_SAFE = "com.familyhub.app.safety.ARRIVAL_SAFE"
    private const val NOTE_ID = 4403

    fun start(ctx: Context, minutes: Int, destination: String, familyId: String, uid: String, name: String) {
        val deadline = System.currentTimeMillis() + minutes * 60_000L
        SafetyStore.setArrival(ctx, ArrivalState(deadline, destination.trim().take(60), familyId, uid, name))
        arm(ctx, deadline)
    }

    /** "I arrived": stops the timer. */
    fun confirm(ctx: Context) {
        SafetyStore.setArrival(ctx, null)
        cancelAlarm(ctx)
        SafetyNotify.cancel(ctx, NOTE_ID)
    }

    /** After a restart or app update: sets the alarm again for a timer still running. */
    fun rearm(ctx: Context) {
        val a = SafetyStore.arrival(ctx) ?: return
        val next = when (SafetyLogic.arrivalStage(System.currentTimeMillis(), a.deadline)) {
            ArrivalStage.RUNNING -> a.deadline
            ArrivalStage.ASK -> a.deadline + SafetyLogic.ARRIVAL_GRACE_MS
            ArrivalStage.ALERT -> System.currentTimeMillis() + 5_000L
        }
        arm(ctx, next)
    }

    private fun pending(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 4404, Intent(ctx, SafetyAlarmReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun arm(ctx: Context, at: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pending(ctx)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancelAlarm(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pending(ctx))
    }

    fun onAlarm(ctx: Context) {
        val a = SafetyStore.arrival(ctx) ?: return
        val now = System.currentTimeMillis()
        when (SafetyLogic.arrivalStage(now, a.deadline)) {
            ArrivalStage.RUNNING -> arm(ctx, a.deadline)
            ArrivalStage.ASK -> {
                val safe = PendingIntent.getBroadcast(
                    ctx, 4405, Intent(ctx, SafetyAlarmReceiver::class.java).setAction(ACTION_SAFE),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                SafetyNotify.show(
                    ctx, NOTE_ID, "Did you arrive safely?",
                    "Tap \"I'm safe\" within 5 minutes, or your chosen contacts will get an SMS" +
                        (if (a.destination.isNotBlank()) " about ${a.destination}." else "."),
                    "safety",
                    listOf(NotificationCompat.Action(0, "I'm safe", safe))
                )
                arm(ctx, a.deadline + SafetyLogic.ARRIVAL_GRACE_MS)
            }
            ArrivalStage.ALERT -> {
                SafetyStore.setArrival(ctx, null)
                val to = SafetyStore.contacts(ctx).filter { it.arrival }
                val text = SafetyLogic.arrivalText(a.name, a.destination, a.deadline, SafetySms.bestLocation(ctx), ZoneId.systemDefault())
                if (to.isNotEmpty()) SafetySms.send(ctx, "arrival", to, text, automatic = true)
                SafetyChat.post(
                    a.familyId, a.uid, a.name,
                    "⚠️ Safe-arrival check: I have not confirmed arriving" +
                        (if (a.destination.isNotBlank()) " at ${a.destination}" else "") + ". Please check on me."
                )
                SafetyNotify.show(ctx, NOTE_ID, "Safe-arrival alert sent",
                    "You did not confirm arriving, so your contacts and family chat were told. Open Family safety to say you are safe.", "safety")
            }
        }
    }
}

class SafetyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ArrivalTimer.ACTION_CHECK -> ArrivalTimer.onAlarm(ctx)
            ArrivalTimer.ACTION_SAFE -> {
                val a = SafetyStore.arrival(ctx)
                ArrivalTimer.confirm(ctx)
                if (a != null) SafetyChat.post(a.familyId, a.uid, a.name, "✅ I arrived safely" +
                    (if (a.destination.isNotBlank()) " at ${a.destination}." else "."))
            }
        }
    }
}

/** Run when the app opens with a signed-in, approved member. Cheap: no network reads. */
object SafetyStartup {
    fun onAppOpen(ctx: Context, uid: String, name: String, familyId: String) {
        try {
            SafetyStore.setIdentity(ctx, uid, name, familyId)
            if (SafetyStore.lowBatteryOn(ctx)) {
                LowBatteryWorker.schedule(ctx)
                LowBatteryWorker.runCheck(ctx)
            }
            LocationShareService.resume(ctx)
            ArrivalTimer.rearm(ctx)
        } catch (e: Exception) {
            Log.w("SafetyStartup", "Safety start-up skipped", e)
        }
    }
}
