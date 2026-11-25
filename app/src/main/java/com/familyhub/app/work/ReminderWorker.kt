package com.familyhub.app.work

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.familyhub.app.data.remote.PRIVATE_FLAG
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.health.StepService
import com.familyhub.app.health.StepTracker
import com.familyhub.app.money.SmsReader
import com.familyhub.app.ui.modules.AlertItem
import com.familyhub.app.ui.modules.allAlerts
import com.familyhub.app.ui.modules.daysUntil
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Runs about once a day (and after a restart):
 * - posts one notification for anything due within three days or overdue by up to three days
 *   (bills, EMIs, renewals, expiries, events, tasks, period, card bills),
 * - sets the exact-time alarms for medicines and reminders,
 * - tells you about new bank SMS that are not in Finance yet.
 */
class ReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_REFRESH_ONLY = "refreshOnly"
        private const val NOTIFICATION_ID = 1001
        private const val SMS_NOTIFICATION_ID = 1002
        private const val PREFS = "family_hub_worker"
        private const val KEY_SMS_CHECKED = "smsCheckedAt"
        private val COLLECTIONS = listOf("items", "events", "expenses", "tasks", "trips")
    }

    override suspend fun doWork(): Result {
        val user = FirebaseAuth.getInstance().currentUser ?: return Result.success()
        val ctx = applicationContext
        return try {
            val db = FirebaseFirestore.getInstance()
            val profile = db.collection("users").document(user.uid).get().await()
            val familyId = profile.getString("activeFamilyId").orEmpty()

            val data = HashMap<String, List<Rec>>()
            for (c in COLLECTIONS) {
                val list = ArrayList<Rec>()
                if (familyId.isNotBlank()) {
                    db.collection("families").document(familyId).collection(c).get().await().documents.forEach { d ->
                        val m = HashMap<String, Any?>()
                        d.data?.let { m.putAll(it) }
                        m["id"] = d.id
                        list.add(m)
                    }
                }
                db.collection("users").document(user.uid).collection(c).get().await().documents.forEach { d ->
                    val m = HashMap<String, Any?>()
                    d.data?.let { m.putAll(it) }
                    m["id"] = d.id
                    m[PRIVATE_FLAG] = true
                    list.add(m)
                }
                data[c] = list
            }

            AlarmScheduler.scheduleAll(ctx, data["items"].orEmpty(), familyId, user.uid)
            if (StepTracker.isEnabled(ctx)) StepService.sync(ctx)

            if (!inputData.getBoolean(KEY_REFRESH_ONLY, false)) {
                val soon = allAlerts(data).filter { daysUntil(it.dateMillis) in -3..3 }
                if (soon.isNotEmpty()) notifyDue(soon)
                checkBankSms(data["expenses"].orEmpty())
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun notifyDue(alerts: List<AlertItem>) {
        val ctx = applicationContext
        if (!Notifier.canPost(ctx)) return
        Notifier.ensureChannels(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val first = alerts.first()
        val lines = alerts.take(6).joinToString("\n") { "${it.title}: ${it.label}" }
        val notification = NotificationCompat.Builder(ctx, Notifier.CH_GENERAL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Family Hub: ${alerts.size} reminder(s)")
            .setContentText("${first.title}: ${first.label}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setContentIntent(Notifier.openApp(ctx, "alerts", NOTIFICATION_ID))
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    /** Counts bank alerts received since the last check that are not in Finance yet. */
    private fun checkBankSms(expenses: List<Rec>) {
        val ctx = applicationContext
        if (!SmsReader.canRead(ctx) || !Notifier.canPost(ctx)) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val since = prefs.getLong(KEY_SMS_CHECKED, now - 2 * 86_400_000L)
        prefs.edit().putLong(KEY_SMS_CHECKED, now).apply()
        val known = expenses.mapNotNull { it["sourceKey"] as? String }.toSet()
        val fresh = SmsReader.read(ctx, since).filter { it.key !in known && !it.isTransfer }
        if (fresh.isEmpty()) return
        Notifier.ensureChannels(ctx)
        val spent = fresh.filter { !it.isCredit }.sumOf { it.amount }
        val text = if (spent > 0) "₹%,.0f spent in %d new transaction(s). Tap to review and add.".format(spent, fresh.size)
        else "${fresh.size} new transaction(s) found. Tap to review and add."
        val n = NotificationCompat.Builder(ctx, Notifier.CH_GENERAL)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("New bank transactions")
            .setContentText(text)
            .setContentIntent(Notifier.openApp(ctx, "import", SMS_NOTIFICATION_ID))
            .setAutoCancel(true)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(SMS_NOTIFICATION_ID, n)
    }
}
