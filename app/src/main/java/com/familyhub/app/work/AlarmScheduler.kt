package com.familyhub.app.work

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.familyhub.app.MainActivity
import com.familyhub.app.data.remote.PRIVATE_FLAG
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.health.StepService
import com.familyhub.app.health.StepTracker
import com.familyhub.app.ui.common.atTime
import com.familyhub.app.ui.common.dayKey
import com.familyhub.app.ui.common.parseTimes
import com.familyhub.app.ui.modules.DAY_MS
import com.familyhub.app.ui.modules.bool
import com.familyhub.app.ui.modules.lng
import com.familyhub.app.ui.modules.nextOccurrence
import com.familyhub.app.ui.modules.num
import com.familyhub.app.ui.modules.startOfDay
import com.familyhub.app.ui.modules.str
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

object Notifier {
    const val CH_TIMED = "timed_reminders"
    const val CH_GENERAL = "family_reminders"
    const val CH_STEPS = "step_counter"
    const val CH_CALLS = "calls"
    const val CH_SAFETY = "safety_alerts"
    const val CH_LOCATION = "location_sharing"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_TIMED, "Medicines and timed reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_GENERAL, "Family reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_STEPS, "Step counter", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CALLS, "Calls", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SAFETY, "Safety alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_LOCATION, "Location sharing", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Opens the app on a given screen (for example "medicines"). */
    fun openApp(ctx: Context, route: String?, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (route != null) putExtra(MainActivity.EXTRA_ROUTE, route)
        }
        return PendingIntent.getActivity(ctx, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/**
 * Schedules exact-time notifications for the next 36 hours: every dose of every active medicine
 * and every reminder that has a time. Run again whenever records change, daily, and after a restart.
 */
object AlarmScheduler {
    private const val PREFS = "family_hub_alarms"
    private const val KEY_CODES = "codes"

    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    const val EXTRA_KIND = "kind"
    const val EXTRA_PATH = "path"
    const val EXTRA_SLOT = "slot"
    const val EXTRA_CODE = "code"
    const val EXTRA_HAS_STOCK = "hasStock"

    data class Planned(
        val code: Int,
        val at: Long,
        val title: String,
        val text: String,
        val kind: String,
        val path: String,
        val slot: String,
        val hasStock: Boolean
    )

    private fun docPath(r: Rec, familyId: String, uid: String): String {
        val id = r.str("id")
        return if (r[PRIVATE_FLAG] == true) "users/$uid/items/$id" else "families/$familyId/items/$id"
    }

    fun plan(items: List<Rec>, familyId: String, uid: String, now: Long = System.currentTimeMillis()): List<Planned> {
        val horizon = now + 36 * 3_600_000L
        val out = ArrayList<Planned>()
        val today = startOfDay(now)

        for (m in items.filter { it.str("type") == "medicine" }) {
            val times = parseTimes(m.str("times"))
            if (times.isEmpty()) continue
            val start = m.lng("startMillis")?.let { startOfDay(it) }
            val end = m.lng("endMillis")?.let { startOfDay(it) }
            val taken = m["taken"] as? Map<*, *>
            val path = docPath(m, familyId, uid)
            for (dayOffset in 0..1) {
                val day = today + dayOffset * DAY_MS
                if (start != null && day < start) continue
                if (end != null && day > end) continue
                for ((h, min) in times) {
                    val at = atTime(day, h, min)
                    val slot = dayKey(day) + "_" + "%02d%02d".format(h, min)
                    if (taken?.get(slot) == true) continue
                    val text = listOf(
                        m.str("dose"),
                        m.str("food"),
                        m.str("memberName").takeIf { it.isNotBlank() }?.let { "for $it" }.orEmpty()
                    ).filter { it.isNotBlank() }.joinToString(" • ")
                    // A second, gentle check 30 minutes later if it still is not marked as taken.
                    val check = at + 30 * 60_000L
                    if (check > now && check <= horizon) {
                        out.add(
                            Planned(
                                code = (path + slot + "check").hashCode(),
                                at = check,
                                title = "Did you take " + m.str("title") + "?",
                                text = "It was due at " + "%02d:%02d".format(h, min) + ". Tap Taken if you've had it.",
                                kind = "medicine_check",
                                path = path,
                                slot = slot,
                                hasStock = m.num("stock") != null
                            )
                        )
                    }
                    if (at <= now || at > horizon) continue
                    out.add(
                        Planned(
                            code = (path + slot).hashCode(),
                            at = at,
                            title = "Time for " + m.str("title"),
                            text = text.ifBlank { "Tap Taken once you have had it." },
                            kind = "medicine",
                            path = path,
                            slot = slot,
                            hasStock = m.num("stock") != null
                        )
                    )
                }
            }
        }

        for (r in items.filter { it.str("type") == "reminder" && !it.bool("done") }) {
            val date = r.lng("dateMillis") ?: continue
            val (h, min) = parseTimes(r.str("time")).firstOrNull() ?: (9 to 0)
            val repeat = r.str("repeat").uppercase()
            val path = docPath(r, familyId, uid)
            var at = atTime(nextOccurrence(date, repeat, now), h, min)
            if (at <= now && repeat != "" && repeat != "NONE") {
                at = atTime(nextOccurrence(date, repeat, now + DAY_MS), h, min)
            }
            if (at <= now || at > horizon) continue
            val slot = dayKey(at) + "_" + "%02d%02d".format(h, min)
            out.add(
                Planned(
                    code = (path + slot).hashCode(),
                    at = at,
                    title = r.str("title"),
                    text = r.str("notes").ifBlank { "Reminder from Family Hub" },
                    kind = "reminder",
                    path = path,
                    slot = slot,
                    hasStock = false
                )
            )
        }
        return out.sortedBy { it.at }.take(80)
    }

    fun scheduleAll(ctx: Context, items: List<Rec>, familyId: String, uid: String) {
        if (uid.isBlank()) return
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val planned = plan(items, familyId, uid)
            val newCodes = planned.map { it.code }.toSet()
            // Cancel alarms that are no longer needed (medicine deleted, reminder done, time changed).
            val old = prefs.getStringSet(KEY_CODES, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }
            for (code in old) {
                if (code !in newCodes) am.cancel(pending(ctx, code, null))
            }
            for (p in planned) schedule(ctx, am, p)
            prefs.edit().putStringSet(KEY_CODES, newCodes.map { it.toString() }.toSet()).apply()
        } catch (e: Exception) {
            Log.w("AlarmScheduler", "Could not schedule alarms", e)
        }
    }

    fun schedule(ctx: Context, am: AlarmManager, p: Planned) {
        val pi = pending(ctx, p.code, p)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.at, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.at, pi)
        }
    }

    private fun pending(ctx: Context, code: Int, p: Planned?): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = "com.familyhub.app.ALARM_$code"
            if (p != null) {
                putExtra(EXTRA_TITLE, p.title)
                putExtra(EXTRA_TEXT, p.text)
                putExtra(EXTRA_KIND, p.kind)
                putExtra(EXTRA_PATH, p.path)
                putExtra(EXTRA_SLOT, p.slot)
                putExtra(EXTRA_CODE, code)
                putExtra(EXTRA_HAS_STOCK, p.hasStock)
            }
        }
        return PendingIntent.getBroadcast(ctx, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/** Shows the notification when a medicine dose or a reminder is due. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Notifier.canPost(ctx)) return
        val kind = intent.getStringExtra(AlarmScheduler.EXTRA_KIND).orEmpty()
        if (kind == "medicine_check") {
            // Only remind again if the dose still is not marked as taken.
            val path = intent.getStringExtra(AlarmScheduler.EXTRA_PATH).orEmpty()
            val slot = intent.getStringExtra(AlarmScheduler.EXTRA_SLOT).orEmpty()
            if (path.isBlank() || slot.isBlank()) return
            val pending = goAsync()
            try {
                FirebaseFirestore.getInstance().document(path).get().addOnCompleteListener { task ->
                    try {
                        val doc = if (task.isSuccessful) task.result else null
                        val taken = (doc?.get("taken") as? Map<*, *>)?.get(slot) == true
                        if (doc != null && doc.exists() && !taken) show(ctx, intent)
                    } finally {
                        pending.finish()
                    }
                }
            } catch (e: Exception) {
                pending.finish()
            }
            return
        }
        show(ctx, intent)
    }

    private fun show(ctx: Context, intent: Intent) {
        Notifier.ensureChannels(ctx)
        val code = intent.getIntExtra(AlarmScheduler.EXTRA_CODE, 0)
        val kind = intent.getStringExtra(AlarmScheduler.EXTRA_KIND).orEmpty()
        val isMedicine = kind == "medicine" || kind == "medicine_check"
        val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE).orEmpty()
        val text = intent.getStringExtra(AlarmScheduler.EXTRA_TEXT).orEmpty()

        val builder = NotificationCompat.Builder(ctx, Notifier.CH_TIMED)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (isMedicine) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(Notifier.openApp(ctx, if (isMedicine) "health_hub" else "reminders", code))
            .setAutoCancel(true)

        if (isMedicine) {
            builder.addAction(0, "Taken", actionIntent(ctx, intent, AlarmActionReceiver.ACTION_TAKEN, code + 1))
        }
        builder.addAction(0, "Snooze 10 min", actionIntent(ctx, intent, AlarmActionReceiver.ACTION_SNOOZE, code + 2))

        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(code, builder.build())
        } catch (e: SecurityException) {
            Log.w("AlarmReceiver", "Notification blocked", e)
        }
    }

    private fun actionIntent(ctx: Context, source: Intent, action: String, requestCode: Int): PendingIntent {
        val i = Intent(ctx, AlarmActionReceiver::class.java).apply {
            this.action = action
            source.extras?.let { putExtras(it) }
        }
        return PendingIntent.getBroadcast(ctx, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/** Handles the Taken and Snooze buttons on medicine and reminder notifications. */
class AlarmActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_TAKEN = "com.familyhub.app.TAKEN"
        const val ACTION_SNOOZE = "com.familyhub.app.SNOOZE"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val code = intent.getIntExtra(AlarmScheduler.EXTRA_CODE, 0)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(code)
        when (intent.action) {
            ACTION_TAKEN -> {
                val path = intent.getStringExtra(AlarmScheduler.EXTRA_PATH).orEmpty()
                val slot = intent.getStringExtra(AlarmScheduler.EXTRA_SLOT).orEmpty()
                if (path.isBlank() || slot.isBlank()) return
                val pending = goAsync()
                val updates = HashMap<String, Any>()
                updates["taken.$slot"] = true
                if (intent.getBooleanExtra(AlarmScheduler.EXTRA_HAS_STOCK, false)) {
                    updates["stock"] = FieldValue.increment(-1)
                }
                FirebaseFirestore.getInstance().document(path).update(updates)
                    .addOnCompleteListener { pending.finish() }
            }
            ACTION_SNOOZE -> {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val p = AlarmScheduler.Planned(
                    code = code xor 0x5A5A,
                    at = System.currentTimeMillis() + 10 * 60_000L,
                    title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE).orEmpty(),
                    text = intent.getStringExtra(AlarmScheduler.EXTRA_TEXT).orEmpty(),
                    kind = intent.getStringExtra(AlarmScheduler.EXTRA_KIND).orEmpty(),
                    path = intent.getStringExtra(AlarmScheduler.EXTRA_PATH).orEmpty(),
                    slot = intent.getStringExtra(AlarmScheduler.EXTRA_SLOT).orEmpty(),
                    hasStock = intent.getBooleanExtra(AlarmScheduler.EXTRA_HAS_STOCK, false)
                )
                try {
                    AlarmScheduler.schedule(ctx, am, p)
                } catch (e: Exception) {
                    Log.w("AlarmActionReceiver", "Snooze failed", e)
                }
            }
        }
    }
}

/** After the phone restarts: set the alarms again and resume step counting. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            WorkManager.getInstance(ctx).enqueue(
                OneTimeWorkRequestBuilder<ReminderWorker>()
                    .setInputData(workDataOf(ReminderWorker.KEY_REFRESH_ONLY to true))
                    .build()
            )
        } catch (e: Exception) {
            Log.w("BootReceiver", "Could not queue refresh", e)
        }
        if (StepTracker.isEnabled(ctx)) StepService.start(ctx)
        // A safe-arrival timer that was running keeps its alarm after a restart.
        try { com.familyhub.app.safety.ArrivalTimer.rearm(ctx) } catch (e: Exception) { Log.w("BootReceiver", "Arrival timer not restored", e) }
    }
}

fun hasPermission(ctx: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
