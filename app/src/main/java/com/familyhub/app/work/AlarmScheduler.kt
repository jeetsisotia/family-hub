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
    const val CH_RING = "incoming_calls"
    const val CH_MISSED = "missed_calls"

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
        nm.createNotificationChannel(
            NotificationChannel(CH_RING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MISSED, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private const val RING_ID = 4310

    /** "Asha is calling": shown while a call rings and Family Hub is running (open or in the background). */
    fun ringing(ctx: Context, title: String, text: String, slot: Int = 0) {
        if (!canPost(ctx)) return
        ensureChannels(ctx)
        val n = androidx.core.app.NotificationCompat.Builder(ctx, CH_RING)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(title).setContentText(text)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true).setAutoCancel(true)
            .setContentIntent(openApp(ctx, "calls", 4311))
            .setFullScreenIntent(openApp(ctx, "calls", 4312), true)
            .build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).notify(RING_ID + slot, n) }
    }

    fun stopRinging(ctx: Context, slot: Int = 0) { runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).cancel(RING_ID + slot) } }

    /** A missed call (one-to-one or group), opening the Calls screen. */
    fun missedCall(ctx: Context, id: Int, title: String, text: String) {
        if (!canPost(ctx)) return
        ensureChannels(ctx)
        val n = androidx.core.app.NotificationCompat.Builder(ctx, CH_MISSED)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle(title).setContentText(text)
            .setCategory("missed_call")
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, "calls", 4320 + (id and 0xFF)))
            .build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).notify(4400 + (id and 0x3FF), n) }
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
    const val EXTRA_REPEATING = "repeating"

    data class Planned(
        val code: Int,
        val at: Long,
        val title: String,
        val text: String,
        val kind: String,
        val path: String,
        val slot: String,
        val hasStock: Boolean,
        /** A repeating reminder: "Done" skips this occurrence only. */
        val repeating: Boolean = false
    )

    private fun docPath(r: Rec, familyId: String, uid: String): String {
        val id = r.str("id")
        return if (r[PRIVATE_FLAG] == true) "users/$uid/items/$id" else "families/$familyId/items/$id"
    }

    fun plan(
        items: List<Rec>, familyId: String, uid: String, now: Long = System.currentTimeMillis(),
        events: List<Rec> = emptyList(), myName: String = ""
    ): List<Planned> {
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

        for (r in items.filter { it.str("type") == "reminder" && !it.bool("done") && it["isDeleted"] != true }) {
            val date = r.lng("dateMillis") ?: continue
            // A shared reminder for someone else rings only on their phone. Private ones and those for nobody
            // in particular ring here.
            val forName = r.str("memberName").trim()
            if (r[PRIVATE_FLAG] != true && forName.isNotEmpty() && myName.isNotBlank() && !forName.equals(myName.trim(), true)) continue
            val path = docPath(r, familyId, uid)
            val time = r.str("time")
            val early = ReminderRules.earlyMinutes(r.str("earlyAlert"))
            val occ = ReminderRules.occurrences(
                date, time, r.str("repeat"), (r.num("everyDays") ?: 0.0).toInt(), early, r.str("lastDoneSlot"), now, horizon
            )
            for (o in occ) {
                val whenText = ReminderRules.timeText(time).let { if (it == "All day") "Today" else it }
                out.add(
                    Planned(
                        code = (path + o.slot + if (o.early) "early" else "").hashCode(),
                        at = o.at,
                        title = if (o.early) ReminderRules.earlyText(early) + ": " + r.str("title") else r.str("title"),
                        text = r.str("notes").ifBlank { if (o.early) "Due at $whenText." else "Reminder from Family Hub" },
                        kind = if (o.early) "reminder_early" else "reminder",
                        path = path,
                        slot = o.slot,
                        hasStock = false,
                        repeating = !r.str("repeat").let { it.isBlank() || it.equals("None", true) }
                    )
                )
            }
        }

        // Calendar events with a time and an alert (events saved before had neither, so they stay silent).
        for (e in events.filter { it["isDeleted"] != true }) {
            val minutes = ReminderRules.eventAlertMinutes(e.str("alert")) ?: continue
            val time = e.str("time")
            if (ReminderRules.parseTime(time) == null && minutes < 24 * 60) continue
            val date = e.lng("startTimeMillis") ?: continue
            val path = "families/$familyId/events/" + e.str("id")
            val occ = ReminderRules.occurrences(date, time, e.str("recurrence"), 0, minutes, "", now, horizon)
                .filter { it.early || minutes == 0 }
            for (o in occ) {
                val whenText = ReminderRules.timeText(time)
                out.add(
                    Planned(
                        code = (path + o.slot + "event").hashCode(),
                        at = o.at,
                        title = if (minutes == 0) e.str("title") else ReminderRules.earlyText(minutes) + ": " + e.str("title"),
                        text = listOf(if (whenText == "All day") "All day" else "At $whenText", e.str("category"), e.str("notes"))
                            .filter { it.isNotBlank() }.joinToString(" • "),
                        kind = "event",
                        path = path,
                        slot = o.slot,
                        hasStock = false
                    )
                )
            }
        }
        return out.sortedBy { it.at }.take(80)
    }

    fun scheduleAll(
        ctx: Context, items: List<Rec>, familyId: String, uid: String,
        events: List<Rec> = emptyList(), myName: String = ""
    ) {
        if (uid.isBlank()) return
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val planned = plan(items, familyId, uid, System.currentTimeMillis(), events, myName)
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
                putExtra(EXTRA_REPEATING, p.repeating)
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
            .setContentIntent(Notifier.openApp(ctx, when {
                isMedicine -> "health_hub"
                kind == "event" -> "calendar"
                else -> "reminders"
            }, code))
            .setAutoCancel(true)

        if (isMedicine) {
            builder.addAction(0, "Taken", actionIntent(ctx, intent, AlarmActionReceiver.ACTION_TAKEN, code + 1))
        }
        if (kind == "reminder") {
            builder.addAction(0, "Done", actionIntent(ctx, intent, AlarmActionReceiver.ACTION_DONE, code + 3))
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
        const val ACTION_DONE = "com.familyhub.app.DONE"
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
            ACTION_DONE -> {
                // A one-time reminder is marked done; a repeating one skips only this occurrence.
                val path = intent.getStringExtra(AlarmScheduler.EXTRA_PATH).orEmpty()
                val slot = intent.getStringExtra(AlarmScheduler.EXTRA_SLOT).orEmpty()
                if (path.isBlank()) return
                val updates: Map<String, Any> =
                    if (intent.getBooleanExtra(AlarmScheduler.EXTRA_REPEATING, false)) mapOf("lastDoneSlot" to slot)
                    else mapOf("done" to true, "doneAtMillis" to System.currentTimeMillis())
                val pending = goAsync()
                try {
                    FirebaseFirestore.getInstance().document(path).update(updates)
                        .addOnCompleteListener { pending.finish() }
                } catch (e: Exception) {
                    pending.finish()
                }
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
                    hasStock = intent.getBooleanExtra(AlarmScheduler.EXTRA_HAS_STOCK, false),
                    repeating = intent.getBooleanExtra(AlarmScheduler.EXTRA_REPEATING, false)
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
        // Also after the clock or time zone changes, so alarms follow the new local times.
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_TIMEZONE_CHANGED && action != Intent.ACTION_TIME_CHANGED) return
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
